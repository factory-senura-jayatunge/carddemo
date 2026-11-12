"""make batch-window-close (scripts/batch_window_close.py) against the stub
batch-service: the request it sends for each holder, the answers of the
batch-service execution contract (sections 6 and 9) and the exit status."""

from __future__ import annotations

import os
import secrets
import shutil
import subprocess
import sys

import pytest

from conftest import AIRFLOW_DIR, StubBatchService

MODERNIZATION = os.path.dirname(AIRFLOW_DIR)
SCRIPT = os.path.join(MODERNIZATION, "scripts", "batch_window_close.py")
CLOSE = "/internal/batch-window/close"
TOKEN = secrets.token_urlsafe(24)


@pytest.fixture
def stub():
    s = StubBatchService()
    yield s
    s.close()


def env_for(stub, **holder) -> dict:
    env = {k: v for k, v in os.environ.items() if k not in ("CYCLE", "RUN_KEY")}
    env.update(CARDDEMO_BATCH_PORT=str(stub.server.server_address[1]), CARDDEMO_BATCH_INTERNAL_TOKEN=TOKEN,
               **holder)
    return env


def close(stub, *args, **holder):
    done = subprocess.run([sys.executable, SCRIPT, *args], env=env_for(stub, **holder), capture_output=True,
                          text=True, timeout=60)
    out = done.stdout + done.stderr
    assert TOKEN not in out
    return done.returncode, out


def cycle_body(status="OPEN"):
    return {"windowId": 3, "cycleId": 17, "windowRunKey": None, "closedAt": "2022-07-06T22:00:00.000Z",
            "cycle": {"cycleId": 17, "attempt": 1, "status": status}}


def test_cycle_close_never_completes_the_cycle(stub):
    stub.reply("POST", CLOSE, (200, cycle_body()))
    rc, out = close(stub, CYCLE="17")
    assert rc == 0
    assert "batch-window-close: OK (window 3 of cycle 17 closed at 2022-07-06T22:00:00.000Z" in out
    assert "cycle 17 (attempt 1) is OPEN" in out
    [request] = stub.requests
    assert request["json"] == {"cycleId": 17, "complete": False}
    assert request["headers"]["Authorization"] == f"Bearer {TOKEN}"
    assert request["headers"]["Content-Type"] == "application/json"


def test_run_key_close_sends_no_complete(stub):
    run_id = "manual__1"
    key = f"carddemo_data_load/{run_id}"
    stub.reply("POST", CLOSE, (200, {"windowId": 4, "cycleId": None, "windowRunKey": key,
                                     "closedAt": "2022-07-06T22:00:00.000Z", "cycle": None}))
    rc, out = close(stub, RUN_KEY=key)
    assert rc == 0 and f"window 4 of run key {key} closed" in out
    assert stub.posted(CLOSE) == [{"windowRunKey": key}]


def test_a_run_key_is_sent_as_given(stub):
    key = "d/run with 'quotes', \"double\", $HOME; `x` & spaces"
    stub.reply("POST", CLOSE, (200, {"windowId": 4, "windowRunKey": key, "cycle": None}))
    assert close(stub, RUN_KEY=key)[0] == 0
    assert stub.posted(CLOSE) == [{"windowRunKey": key}]


def test_already_closed_window_is_ok(stub):
    stub.reply("POST", CLOSE, (200, cycle_body()), (200, cycle_body()))
    assert close(stub, CYCLE="17")[0] == 0
    assert close(stub, CYCLE="17")[0] == 0


@pytest.mark.parametrize("holder", [{}, {"CYCLE": "17", "RUN_KEY": "d/r"}, {"CYCLE": " "},
                                    {"CYCLE": "0"}, {"CYCLE": "-1"}, {"CYCLE": "17x"}, {"CYCLE": "1.5"},
                                    {"RUN_KEY": "k" * 251}])
def test_bad_holder_is_refused_before_any_request(stub, holder):
    rc, out = close(stub, **holder)
    assert rc == 2 and "batch-window-close: FAILED" in out
    assert stub.requests == []


def test_extra_arguments_are_refused(stub):
    rc, out = close(stub, "--complete", CYCLE="17")
    assert rc == 2 and "takes no arguments" in out
    assert stub.requests == []


def test_missing_token_is_refused_before_any_request(stub):
    env = env_for(stub, CYCLE="17")
    env["CARDDEMO_BATCH_INTERNAL_TOKEN"] = ""
    done = subprocess.run([sys.executable, SCRIPT], env=env, capture_output=True, text=True, timeout=60)
    assert done.returncode == 2 and "run make setup" in done.stderr
    assert stub.requests == []


@pytest.mark.parametrize("status, body, headers, expected", [
    (400, {"code": "INVALID_REQUEST", "message": "The request is invalid.", "field": "complete"}, {},
     "HTTP 400 INVALID_REQUEST: The request is invalid. (field complete)"),
    (401, {"code": "UNAUTHENTICATED", "message": "Authentication required."}, {},
     "HTTP 401 UNAUTHENTICATED: Authentication required."),
    (404, {"code": "WINDOW_NOT_FOUND", "message": "No batch window was opened for this holder."}, {},
     "HTTP 404 WINDOW_NOT_FOUND: No batch window was opened for this holder."),
    (409, {"code": "EXECUTIONS_RUNNING", "message": "Executions are running.", "executionIds": [41, 42]}, {},
     "HTTP 409 EXECUTIONS_RUNNING: Executions are running.; running executions 41, 42"),
    (503, {"code": "LAUNCH_IN_PROGRESS",
           "message": "A job launch for this holder is in progress. Try again later."}, {"Retry-After": "30"},
     "HTTP 503 LAUNCH_IN_PROGRESS: A job launch for this holder is in progress. Try again later.; "
     "try again in 30 s"),
    (500, {}, {}, "HTTP 500"),
])
def test_every_other_answer_fails_with_its_code(stub, status, body, headers, expected):
    stub.reply("POST", CLOSE, (status, body, headers))
    rc, out = close(stub, CYCLE="17")
    assert rc == 1
    assert f"batch-window-close: FAILED ({expected})" in out


def test_unreachable_batch_service(stub):
    env = env_for(stub, CYCLE="17")
    stub.close()
    done = subprocess.run([sys.executable, SCRIPT], env=env, capture_output=True, text=True, timeout=60)
    assert done.returncode == 1 and "not reachable" in done.stderr


@pytest.mark.skipif(shutil.which("make") is None, reason="needs GNU make")
def test_make_target_passes_the_holder_and_the_env_file(stub, tmp_path):
    """Through the real make target and with_env.py: the token and port come
    from the env file, the holder from the make variable, even with shell
    characters in it."""
    env_file = tmp_path / ".env"
    env_file.write_text(f"CARDDEMO_BATCH_INTERNAL_TOKEN={TOKEN}\n"
                        f"CARDDEMO_BATCH_PORT={stub.server.server_address[1]}\n", encoding="utf-8")
    stub.reply("POST", CLOSE, (200, {"windowId": 5, "windowRunKey": "x", "cycle": None}))
    env = {k: v for k, v in os.environ.items()
           if k not in ("CYCLE", "RUN_KEY", "CARDDEMO_BATCH_INTERNAL_TOKEN", "CARDDEMO_BATCH_PORT")}
    env.update(CARDDEMO_ENV_FILE=str(env_file), CARDDEMO_VAR_DIR=str(tmp_path / "var"))
    key = "carddemo_import_migration/manual__1 'a' \"b\" `id` ;c & d"
    done = subprocess.run(["make", "-s", "-C", MODERNIZATION, "batch-window-close", f"RUN_KEY={key}"], env=env,
                          capture_output=True, text=True, timeout=120)
    assert TOKEN not in done.stdout + done.stderr
    assert done.returncode == 0, done.stderr
    assert stub.posted(CLOSE) == [{"windowRunKey": key}]

    both = subprocess.run(["make", "-s", "-C", MODERNIZATION, "batch-window-close", "CYCLE=17", "RUN_KEY=d/r"],
                          env=env, capture_output=True, text=True, timeout=120)
    assert both.returncode != 0 and "exactly one of CYCLE" in both.stderr
    assert len(stub.requests) == 1
