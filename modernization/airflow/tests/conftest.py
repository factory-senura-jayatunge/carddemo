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
    the last one repeats. Every request is recorded in .requests."""

    def __init__(self):
        self.routes: dict = {}
        self.requests: list = []
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def _serve(self):
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length) if length else b""
                stub.requests.append({"method": self.command, "path": self.path,
                                      "headers": dict(self.headers),
                                      "json": json.loads(raw) if raw else None})
                answers = stub.routes.get((self.command, self.path))
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

    def close(self):
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture
def batch_service(monkeypatch):
    stub = StubBatchService()
    monkeypatch.setenv("CARDDEMO_BATCH_URL", stub.url)
    monkeypatch.setenv("CARDDEMO_BATCH_INTERNAL_TOKEN", TOKEN)
    yield stub
    stub.close()


class FakeTaskInstance:
    def __init__(self, xcom: dict | None = None):
        self.xcom = dict(xcom or {})

    def xcom_pull(self, task_ids=None, key="return_value"):
        return self.xcom.get((task_ids, key))

    def xcom_push(self, key, value):
        self.xcom[(None, key)] = value


@pytest.fixture
def make_context():
    def make(params=None, ds="2022-07-06", cycle=None):
        xcom = {}
        if cycle is not None:
            xcom[("register_cycle", "return_value")] = cycle
        defaults = {"businessDate": None, "interest": "always", "statements": "every_cycle",
                    "purge_position": "after_cycle"}
        return {"params": {**defaults, **(params or {})}, "ds": ds, "ti": FakeTaskInstance(xcom)}

    return make
