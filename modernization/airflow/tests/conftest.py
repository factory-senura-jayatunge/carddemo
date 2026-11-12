"""Test setup: Airflow reads its configuration on first import, so the
environment is set here, before any test module imports airflow. No
running Airflow and no metadata database are needed."""

from __future__ import annotations

import json
import os
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

AIRFLOW_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DAGS_DIR = os.path.join(AIRFLOW_DIR, "dags")

os.environ["AIRFLOW_HOME"] = tempfile.mkdtemp(prefix="carddemo-airflow-test-")
os.environ["AIRFLOW__CORE__LOAD_EXAMPLES"] = "False"
os.environ["AIRFLOW__CORE__UNIT_TEST_MODE"] = "True"
os.environ["AIRFLOW__CORE__DAGS_FOLDER"] = DAGS_DIR
sys.path.insert(0, AIRFLOW_DIR)

import pytest  # noqa: E402

TOKEN = "test-token-7f3a9c"


@pytest.fixture(scope="session")
def dagbag():
    from airflow.dag_processing.dagbag import DagBag

    return DagBag(dag_folder=DAGS_DIR)


class StubBatchService:
    """A stand-in for batch-service's /internal API on a free local port.

    reply(method, path, *answers) queues answers (status, body[, headers]);
    the last one repeats. handle(method, path, fn) answers with fn(request)
    instead. Every request is recorded in .requests."""

    def __init__(self):
        self.routes: dict = {}
        self.requests: list = []
        self.lock = threading.RLock()
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def _serve(self):
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length) if length else b""
                request = {"method": self.command, "path": self.path, "headers": dict(self.headers),
                           "json": json.loads(raw) if raw else None}
                with stub.lock:
                    stub.requests.append(request)
                    answers = stub.routes.get((self.command, self.path))
                    if callable(answers):
                        answers = [answers(request)]
                if not answers:
                    status, body, headers = 404, {"code": "NOT_FOUND", "message": "no stub route"}, {}
                else:
                    status, body, *rest = answers[0] if len(answers) == 1 else answers.pop(0)
                    headers = rest[0] if rest else {}
                payload = json.dumps(body).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                for k, v in headers.items():
                    self.send_header(k, v)
                self.end_headers()
                self.wfile.write(payload)

            do_GET = do_POST = _serve

            def log_message(self, *args):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def reply(self, method: str, path: str, *answers):
        self.routes[(method, path)] = list(answers)

    def handle(self, method: str, path: str, fn):
        self.routes[(method, path)] = fn

    def posted(self, path: str) -> list:
        return [r["json"] for r in self.requests if r["method"] == "POST" and r["path"] == path]

    def close(self):
        self.server.shutdown()
        self.server.server_close()


# Contract section 6, written out here rather than imported, so that the
# tests check the operators' tables against the contract.
WINDOW_JOBS = {"archiveMasterJob", "ingestDailyFileJob", "postDailyTransactionsJob", "calculateInterestJob",
               "statementJob", "applyRetentionJob", "dataLoadJob", "disclosureGroupReloadJob",
               "transactionTypeMaintenanceJob", "importMigrationJob"}


class WindowModel:
    """The one global batch window of the contract (section 6) on the stub:
    an open is idempotent for its holder and answers 503 WINDOW_BUSY with
    Retry-After to any other holder; a close by the holder ends it; a
    window job is refused with 409 BATCH_WINDOW_REQUIRED unless its cycleId
    or windowRunKey holds the window. A close must send complete for a
    cycle and may not send complete: true for a run key (400 INVALID_REQUEST,
    field complete; section 9). held_for(holder, busy_answers) makes
    another holder keep the window until it has refused that many opens."""

    def __init__(self, stub: StubBatchService, retry_after: int = 1):
        self.holder: dict | None = None
        self.window_id = 0
        self.retry_after = retry_after
        self.busy_left: int | None = None
        self.busy_answers = 0
        stub.handle("POST", "/internal/batch-window/open", self.open)
        stub.handle("POST", "/internal/batch-window/close", self.close)

    @staticmethod
    def holder_of(body: dict) -> dict:
        return {k: body[k] for k in ("cycleId", "windowRunKey") if body.get(k) is not None}

    def held_for(self, holder: dict, busy_answers: int):
        self.holder, self.busy_left = holder, busy_answers
        self.window_id += 1

    def _window(self) -> dict:
        return {"windowId": self.window_id, "cycleId": (self.holder or {}).get("cycleId"),
                "windowRunKey": (self.holder or {}).get("windowRunKey")}

    def open(self, request):
        holder = self.holder_of(request["json"])
        if self.holder is not None and self.holder != holder:
            self.busy_answers += 1
            body = {"code": "WINDOW_BUSY", "message": "another holder has the batch window open",
                    **self._window(), "openedAt": "2022-07-06T21:00:00.000Z"}
            if self.busy_left is not None:
                self.busy_left -= 1
                if self.busy_left <= 0:
                    self.holder, self.busy_left = None, None
            return 503, body, {"Retry-After": str(self.retry_after)}
        if self.holder is None:
            self.window_id += 1
            self.holder = holder
        return 200, {**self._window(), "mode": "WRITES_BLOCKED", "openedAt": "2022-07-06T21:00:00.000Z"}

    def close(self, request):
        holder = self.holder_of(request["json"])
        complete = request["json"].get("complete")
        if ("cycleId" in holder and not isinstance(complete, bool)) or ("windowRunKey" in holder and complete):
            return 400, {"code": "INVALID_REQUEST", "message": "complete is missing or not allowed",
                         "field": "complete"}
        body = {**self._window(), "closedAt": "2022-07-06T22:00:00.000Z", "cycle": None}
        if self.holder == holder:
            self.holder = None
        return 200, body

    def admits(self, job: str, params: dict) -> bool:
        if job not in WINDOW_JOBS:
            return True
        if self.holder is None:
            return False
        if "cycleId" in self.holder:
            return params.get("cycleId") == self.holder["cycleId"]
        return params.get("windowRunKey") == self.holder["windowRunKey"]

@pytest.fixture(autouse=True)
def var_dir(monkeypatch, tmp_path):
    """stack.py gives every Airflow process an absolute CARDDEMO_VAR_DIR, and
    the DAGs fail without one; each test gets an empty one of its own."""
    path = tmp_path / "var"
    path.mkdir()
    monkeypatch.setenv("CARDDEMO_VAR_DIR", str(path))
    return path


@pytest.fixture
def batch_service(monkeypatch):
    stub = StubBatchService()
    monkeypatch.setenv("CARDDEMO_BATCH_URL", stub.url)
    monkeypatch.setenv("CARDDEMO_BATCH_INTERNAL_TOKEN", TOKEN)
    yield stub
    stub.close()


class FakeTaskInstance:
    def __init__(self, xcom: dict | None = None, dag_id: str = "carddemo_test", run_id: str = "manual__1",
                 task_id: str = "task"):
        self.xcom = dict(xcom or {})
        self.dag_id, self.run_id, self.task_id = dag_id, run_id, task_id

    def xcom_pull(self, task_ids=None, key="return_value"):
        return self.xcom.get((task_ids, key))

    def xcom_push(self, key, value):
        self.xcom[(None, key)] = value


@pytest.fixture
def make_context():
    def make(params=None, ds="2022-07-06", cycle=None, dag_id="carddemo_test", run_id="manual__1",
             task_id="task"):
        xcom = {}
        if cycle is not None:
            xcom[("register_cycle", "return_value")] = cycle
        defaults = {"businessDate": None, "interest": "always", "statements": "every_cycle",
                    "purge_position": "after_cycle"}
        return {"params": {**defaults, **(params or {})}, "ds": ds,
                "ti": FakeTaskInstance(xcom, dag_id, run_id, task_id)}

    return make
