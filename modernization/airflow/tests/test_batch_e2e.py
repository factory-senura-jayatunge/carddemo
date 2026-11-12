"""scripts/batch_e2e.py, the live harness of make test-batch-live, without a
server: the database guard, its own logins, the lock, the leftover server,
the arguments and the make target. The live cases need SQL Server and run
through make test-batch-live."""

from __future__ import annotations

import importlib.util
import json
import os
import re
import signal
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from conftest import AIRFLOW_DIR

MODERNIZATION = os.path.dirname(AIRFLOW_DIR)
SCRIPTS = os.path.join(MODERNIZATION, "scripts")


@pytest.fixture
def harness(monkeypatch, tmp_path):
    """batch_e2e with stack's var directory and env file in tmp_path."""
    monkeypatch.setenv("CARDDEMO_VAR_DIR", str(tmp_path / "var"))
    env_file = tmp_path / "test.env"
    env_file.write_text("CARDDEMO_DB_NAME=carddemo\n", encoding="utf-8")
    monkeypatch.setenv("CARDDEMO_ENV_FILE", str(env_file))
    for key in ("CARDDEMO_BATCH_E2E_DB_NAME", "CARDDEMO_DB_NAME", "CARDDEMO_TEST_MSSQL_DB",
                "CARDDEMO_E2E_DB_NAME", "CARDDEMO_DB_LOGIN_PREFIX"):
        monkeypatch.delenv(key, raising=False)
    monkeypatch.syspath_prepend(SCRIPTS)
    # A fresh stack, so RUN_DIR and ENV_FILE come from the variables above.
    monkeypatch.delitem(sys.modules, "stack", raising=False)
    spec = importlib.util.spec_from_file_location("batch_e2e_under_test", os.path.join(SCRIPTS, "batch_e2e.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    yield module
    sys.modules.pop("stack", None)


@pytest.mark.parametrize("name", ["carddemo_batch_e2e", "carddemo_batch_e2e_ci", "carddemo_batch_e2e_a1_b2"])
def test_harness_database_names(harness, name):
    assert harness.harness_db({"CARDDEMO_BATCH_E2E_DB_NAME": name}) == name


def test_default_database(harness):
    assert harness.harness_db({}) == "carddemo_batch_e2e"


@pytest.mark.parametrize("name", ["carddemo", "carddemo_e2e", "carddemo_test", "carddemo_batch_e2e-x",
                                  "carddemo_batch_e2eX", "carddemo_batch_e2e_", "x_carddemo_batch_e2e",
                                  "carddemo_batch_e2e_A", "carddemo_batch_e2e;drop"])
def test_other_names_are_refused(harness, name):
    with pytest.raises(harness.Failed, match="not a batch harness database name"):
        harness.harness_db({"CARDDEMO_BATCH_E2E_DB_NAME": name})


@pytest.mark.parametrize("key", ["CARDDEMO_DB_NAME", "CARDDEMO_TEST_MSSQL_DB", "CARDDEMO_E2E_DB_NAME"])
def test_a_name_another_database_uses_is_refused(harness, key):
    environ = {"CARDDEMO_BATCH_E2E_DB_NAME": "carddemo_batch_e2e_x", key: "carddemo_batch_e2e_x"}
    with pytest.raises(harness.Failed, match="also the demo, test or browser e2e database"):
        harness.harness_db(environ)


def test_a_name_the_env_file_gives_another_database_is_refused(harness, tmp_path):
    with open(os.environ["CARDDEMO_ENV_FILE"], "a", encoding="utf-8") as f:
        f.write("CARDDEMO_TEST_MSSQL_DB=carddemo_batch_e2e\n")
    with pytest.raises(harness.Failed, match="also the demo, test or browser e2e database"):
        harness.harness_db({})


def test_children_get_the_harness_database_and_its_own_logins(harness, monkeypatch):
    monkeypatch.setenv("CARDDEMO_DB_NAME", "carddemo")
    monkeypatch.setenv("CARDDEMO_DB_LOGIN_PREFIX", "carddemo_")
    monkeypatch.setenv("CARDDEMO_BATCH_E2E_DB_NAME", "carddemo_batch_e2e_t")
    env = harness.Harness("t", False, False).env()
    assert env["CARDDEMO_DB_NAME"] == "carddemo_batch_e2e_t"
    assert env["CARDDEMO_DB_LOGIN_PREFIX"] == "carddemo_batch_e2e_t_"


def test_state_is_outside_the_stack_pid_directory(harness):
    h = harness.Harness("t", False, False)
    assert os.path.dirname(h.pid_file) == os.path.join(harness.stack.RUN_DIR, "batch-e2e")
    h.lock()
    try:
        assert harness.stack.recorded() == []
    finally:
        h.close()


def test_a_second_run_on_the_same_database_is_refused(harness):
    first = harness.Harness("first-run", False, False)
    first.lock()
    try:
        with pytest.raises(harness.Failed, match="another run uses carddemo_batch_e2e: first-run pid"):
            harness.Harness("second-run", False, False).lock()
    finally:
        first.close()
    again = harness.Harness("third-run", False, False)
    again.lock()
    again.close()


def test_runs_on_different_databases_do_not_wait_for_each_other(harness, monkeypatch):
    first = harness.Harness("a", False, False)
    first.lock()
    monkeypatch.setenv("CARDDEMO_BATCH_E2E_DB_NAME", "carddemo_batch_e2e_other")
    other = harness.Harness("b", False, False)
    try:
        other.lock()
        other.close()
    finally:
        first.close()


SERVER = """
import os, signal, sys, time
signal.signal(signal.SIGTERM, lambda s, f: sys.exit(0))
open(sys.argv[1], "w").close()
while True:
    time.sleep(0.1)
"""


def spawn(tmp_path, name):
    script = tmp_path / f"{name}.py"
    script.write_text(SERVER, encoding="utf-8")
    ready = tmp_path / f"{name}.ready"
    proc = subprocess.Popen([sys.executable, str(script), str(ready)], start_new_session=True)
    deadline = time.time() + 20
    while not ready.exists() and time.time() < deadline:
        time.sleep(0.05)
    assert ready.exists()
    return proc, str(script)


def test_the_server_an_earlier_run_left_is_stopped(harness, tmp_path):
    proc, marker = spawn(tmp_path, "fake-batch-service")
    h = harness.Harness("t", False, False)
    h.lock()
    try:
        with open(h.pid_file, "w", encoding="utf-8") as f:
            f.write(f"{proc.pid} {marker}\n")
        h.stop_leftover()
        assert proc.wait(timeout=10) == 0
        assert not os.path.exists(h.pid_file)
    finally:
        h.close()
        if proc.poll() is None:
            proc.kill()


def test_a_reused_pid_is_never_stopped(harness, tmp_path):
    proc, _marker = spawn(tmp_path, "unrelated")
    h = harness.Harness("t", False, False)
    h.lock()
    try:
        with open(h.pid_file, "w", encoding="utf-8") as f:
            f.write(f"{proc.pid} /nowhere/batch-service-0.1.0-exec.jar\n")
        h.stop_leftover()
        assert proc.poll() is None
        assert not os.path.exists(h.pid_file)
    finally:
        h.close()
        proc.kill()
        proc.wait()


def test_close_stops_its_own_server_and_releases_the_lock(harness, tmp_path):
    h = harness.Harness("t", False, False)
    h.lock()
    h.proc, _marker = spawn(tmp_path, "own-server")
    with open(h.pid_file, "w", encoding="utf-8") as f:
        f.write(f"{h.proc.pid} x\n")
    h.close()
    assert h.proc.poll() is not None
    assert not os.path.exists(h.pid_file)
    harness.Harness("t2", False, False).lock()


def test_unknown_argument(harness, capsys):
    assert harness.main(["batch_e2e.py", "--fast"]) == 2
    assert "test-batch-live: FAILED (unknown argument --fast" in capsys.readouterr().out


@pytest.mark.skipif(sys.platform.startswith("linux"), reason="--sigstop runs on Linux")
def test_sigstop_is_refused_off_linux(harness, capsys):
    assert harness.main(["batch_e2e.py", "--name", "x", "--sigstop"]) == 2
    assert "x: FAILED (--sigstop runs on Linux only" in capsys.readouterr().out


def test_a_refused_database_fails_before_anything_runs(harness, monkeypatch, capsys):
    monkeypatch.setenv("CARDDEMO_BATCH_E2E_DB_NAME", "carddemo")
    monkeypatch.setattr(harness, "install_signal_exit", lambda: None)
    assert harness.main(["batch_e2e.py", "--no-build"]) == 1
    out = capsys.readouterr().out
    assert out.startswith("test-batch-live: FAILED (CARDDEMO_BATCH_E2E_DB_NAME=carddemo is not")
    assert not os.path.exists(os.path.join(harness.STATE_DIR, "carddemo.lock"))


class Echo(BaseHTTPRequestHandler):
    def do_POST(self):
        raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        body = {"auth": self.headers.get("Authorization"), "sent": json.loads(raw)}
        status = 503 if self.path.endswith("/open") else 200
        payload = json.dumps({"code": "WINDOW_BUSY", **body} if status == 503 else body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        if status == 503:
            self.send_header("Retry-After", "5")
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


def test_api_sends_the_bearer_token_and_reads_error_bodies(harness):
    server = ThreadingHTTPServer(("127.0.0.1", 0), Echo)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        api = harness.Api(server.server_address[1], "abc")
        status, body, headers = api.window("open", {"cycleId": 1})
        assert (status, body["code"], body["auth"], headers["Retry-After"]) == (503, "WINDOW_BUSY", "Bearer abc", "5")
        status, body, _ = api.launch("dataLoadJob", {"runKey": "k"})
        assert status == 200 and body["sent"] == {"params": {"runKey": "k"}}
        assert api.post("/internal/cycles", {}, token=False)[1]["auth"] is None
        assert api.post("/internal/cycles", {}, token="other")[1]["auth"] == "Bearer other"
    finally:
        server.shutdown()
        server.server_close()


def test_expect_names_every_difference(harness):
    with pytest.raises(harness.Failed, match=r"^open: HTTP 503, expected 200; code WINDOW_BUSY, expected X$"):
        harness.expect("open", (503, {"code": "WINDOW_BUSY"}, {}), 200, "X")
    with pytest.raises(harness.Failed, match=r"close: windowId 2, expected 1 \(busy\)$"):
        harness.expect("close", (200, {"windowId": 2, "message": "busy"}, {}), 200, windowId=1)
    assert harness.expect("ok", (200, {"a": 1}, {}), 200, a=1) == {"a": 1}
    cycle = {"status": "OPEN", "what": "x", "code": None}
    assert harness.expect("cycle", (201, cycle, {}), 201, status="OPEN", what="x") == cycle
    with pytest.raises(harness.Failed, match=r"^cycle: status 'OPEN', expected 'COMPLETED'$"):
        harness.expect("cycle", (201, cycle, {}), 201, status="COMPLETED")


def test_make_target_runs_the_harness_under_the_heavy_lock(tmp_path):
    env_file = tmp_path / "make.env"
    env_file.write_text("", encoding="utf-8")
    env = dict(os.environ, CARDDEMO_ENV_FILE=str(env_file), CARDDEMO_VAR_DIR=str(tmp_path / "var"))
    out = subprocess.run(["make", "-n", "--no-print-directory", "-C", MODERNIZATION, "test-batch-live",
                          "SIGSTOP=1"], env=env, capture_output=True, text=True, check=True).stdout
    line = next(l for l in out.splitlines() if "batch_e2e.py" in l)
    assert "with_env.py" in line and "heavy.py' test-batch-live" in line
    assert line.rstrip().endswith("scripts/batch_e2e.py --name test-batch-live --sigstop")
    plain = subprocess.run(["make", "-n", "--no-print-directory", "-C", MODERNIZATION, "test-batch-live"],
                           env=env, capture_output=True, text=True, check=True).stdout
    assert "--sigstop" not in plain

def cycle_service():
    """The cycle answers of batch-service (Cycles.register, BatchWindows) on the stub."""
    from conftest import StubBatchService

    stub = StubBatchService()
    state = {"status": "OPEN"}

    def body():
        return {"cycleId": 5, "businessDate": "2022-07-06", "attempt": 1, "interest": False, "interestParm": None,
                "status": state["status"], "openedAt": "2022-07-06T21:00:00.000Z", "closedAt": None}

    def register(request):
        sent = request["json"]
        if sent["businessDate"] != "2022-07-06":
            return 400, {"code": "INVALID_REQUEST", "field": "businessDate"}
        if state["status"] == "COMPLETED":
            return 409, {"code": "CYCLE_COMPLETED", **body()}
        if sent["interest"] == "always":
            return 409, {"code": "CYCLE_PARAMETERS_DIFFER", **body()}
        first = not state.get("registered")
        state["registered"] = True
        return (201 if first else 200), body()

    def open_window(request):
        if state["status"] == "COMPLETED":
            return 409, {"code": "CYCLE_COMPLETED", **body()}
        return 200, {"windowId": 9, "cycleId": 5, "windowRunKey": None, "mode": "WRITES_BLOCKED"}

    def close_window(request):
        if request["json"].get("complete"):
            state["status"] = "COMPLETED"
        return 200, {"windowId": 9, "cycleId": 5, "windowRunKey": None, "closedAt": "x", "cycle": body()}

    stub.handle("POST", "/internal/cycles", register)
    stub.handle("POST", "/internal/batch-window/open", open_window)
    stub.handle("POST", "/internal/batch-window/close", close_window)
    return stub


def test_the_cycle_cases_pass_on_batch_service_answers(harness):
    stub = cycle_service()
    try:
        h = harness.Harness("t", False, False)
        h.api = harness.Api(stub.server.server_address[1], "abc")
        h.cycles()
        assert h.cycle_id == 5
        h.cycle_completes()
        assert [r["json"].get("interest") for r in stub.requests[:4]] == ["never", "month_end", "always", "never"]
    finally:
        stub.close()


# --- the run's own CARDDEMO_VAR_DIR -------------------------------------------


def _drive_main(harness, monkeypatch, seen):
    monkeypatch.setattr(harness, "install_signal_exit", lambda: None)
    monkeypatch.setattr(harness.Harness, "stop_leftover", lambda self: None)
    monkeypatch.setattr(harness.Harness, "cases", lambda self: seen.setdefault("cases", self.var))

    def run(self, step, argv):
        seen.setdefault("runs", []).append((step, argv, os.path.isdir(self.var),
                                            oct(os.stat(self.var).st_mode & 0o777)))

    def start(self):
        seen["start"] = self.var
    monkeypatch.setattr(harness.Harness, "run", run)
    monkeypatch.setattr(harness.Harness, "start", start)
    return harness.main(["batch_e2e.py", "--no-build"])


def test_db_reset_gets_a_private_var_dir_that_is_removed(harness, monkeypatch, capsys):
    monkeypatch.delenv("KEEP_VAR", raising=False)
    seen = {}
    assert _drive_main(harness, monkeypatch, seen) == 0, capsys.readouterr().out
    (step, argv, existed, mode), = seen["runs"]
    private = seen["start"]
    assert step == "db-reset" and argv[-2:] == ["db-reset", f"RUN_VAR_DIR={private}"]
    assert existed and mode == "0o700"
    assert os.path.isabs(private) and private == os.path.realpath(private)
    shared = os.path.realpath(os.environ["CARDDEMO_VAR_DIR"])
    assert not private.startswith(shared + os.sep)
    assert seen["cases"] == private
    assert not os.path.exists(private)


def test_keep_var_keeps_the_folder_and_prints_it(harness, monkeypatch, capsys):
    monkeypatch.setenv("KEEP_VAR", "1")
    seen = {}
    assert _drive_main(harness, monkeypatch, seen) == 0
    private = seen["start"]
    try:
        assert os.path.isdir(private)
        assert re.search(rf"note\s+var\s+kept {re.escape(private)} \(KEEP_VAR=1\)", capsys.readouterr().out)
    finally:
        import shutil
        shutil.rmtree(private, ignore_errors=True)


def test_the_server_gets_the_private_var_dir(harness, monkeypatch, tmp_path):
    monkeypatch.setenv("CARDDEMO_BATCH_INTERNAL_TOKEN", "t")
    monkeypatch.setattr(harness.stack, "find_jar", lambda module, name: str(tmp_path / "batch-service-exec.jar"))
    monkeypatch.setattr(harness.stack, "java_bin", lambda: "/jdk/bin/java")
    monkeypatch.setattr(harness.stack, "node_bin", lambda: "")
    seen = {}

    def popen(argv, **kwargs):
        seen["env"] = kwargs["env"]
        raise harness.Failed("not started in this test")
    monkeypatch.setattr(harness.subprocess, "Popen", popen)
    h = harness.Harness("t", False, False)
    h.var = str(tmp_path / "private")
    with pytest.raises(harness.Failed, match="not started"):
        h.start()
    assert seen["env"]["CARDDEMO_VAR_DIR"] == h.var
