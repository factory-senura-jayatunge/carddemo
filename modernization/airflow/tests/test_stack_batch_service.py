"""batch-service as a component of make up (scripts/stack.py): server
profile on loopback, the absolute sample paths it needs, Airflow pointed at
its port, and stop signalling the JVM's own pid."""

from __future__ import annotations

import importlib.util
import os
import signal
import subprocess
import sys
import time

import pytest

from conftest import AIRFLOW_DIR

MODERNIZATION = os.path.dirname(AIRFLOW_DIR)


def load_stack(monkeypatch, tmp_path, env_file=None, jar="batch-service-0.1.0-exec.jar"):
    path = os.path.join(MODERNIZATION, "scripts", "stack.py")
    spec = importlib.util.spec_from_file_location("stack_batch_under_test", path)
    stack = importlib.util.module_from_spec(spec)
    monkeypatch.setenv("CARDDEMO_VAR_DIR", str(tmp_path / "var"))
    monkeypatch.setenv("CARDDEMO_ENV_FILE", str(tmp_path / "missing.env"))
    for key in ("CARDDEMO_BATCH_PORT", "CARDDEMO_BATCH_URL", "CARDDEMO_LOAD_SOURCE_DIR",
                "CARDDEMO_PAUTH_SOURCE_FILE", "CARDDEMO_INBOUND_DIR", "CARDDEMO_OUTPUT_DIR",
                "CARDDEMO_BATCH_FILES_OUTPUTDIR", "CARDDEMO_BATCH_FILES_INBOUNDDIR", "CARDDEMO_TRANSACTION_REPORT_ROOT"):
        monkeypatch.delenv(key, raising=False)
    spec.loader.exec_module(stack)
    monkeypatch.setattr(stack, "read_env", lambda _path: dict(env_file or {}))
    monkeypatch.setattr(stack, "node_bin", lambda: "")
    monkeypatch.setattr(stack, "java_bin", lambda: "/jdk21/bin/java")
    monkeypatch.setattr(stack, "find_jar", lambda module, name: os.path.join(module, "target", jar)
                        if name == "batch-service" and jar else None)
    monkeypatch.setattr(stack, "jar_problem", lambda module, jar: None)
    return stack


needs_module = pytest.mark.skipif(
    not os.path.exists(os.path.join(MODERNIZATION, "batch", "batch-service", "pom.xml")),
    reason="batch-service is not in this checkout")


@needs_module
def test_up_starts_the_server_on_loopback_before_airflow(monkeypatch, tmp_path):
    stack = load_stack(monkeypatch, tmp_path)
    comps = stack.components()
    names = [c.name for c in comps]
    batch = comps[names.index("batch-service")]
    jar = os.path.join(MODERNIZATION, "batch", "batch-service", "target", "batch-service-0.1.0-exec.jar")
    assert batch.argv == ["/jdk21/bin/java", "-Xmx1g", "-XX:MaxMetaspaceSize=128m", "-Xss512k", "-jar", jar,
                          "--spring.profiles.active=server", "--server.port=28090", "--server.address=127.0.0.1"]
    assert batch.port == 28090
    assert batch.health_url == "http://127.0.0.1:28090/actuator/health"
    assert batch.module == "batch/batch-service" and batch.marker == jar and batch.problem is None
    airflow = [i for i, n in enumerate(names) if n.startswith("airflow-")]
    assert not airflow or names.index("batch-service") < min(airflow)


@needs_module
def test_port_comes_from_the_env_file_and_reaches_airflow(monkeypatch, tmp_path):
    stack = load_stack(monkeypatch, tmp_path, {"CARDDEMO_BATCH_PORT": "28190"})
    batch = next(c for c in stack.components() if c.name == "batch-service")
    assert "--server.port=28190" in batch.argv
    assert batch.health_url == "http://127.0.0.1:28190/actuator/health"
    assert stack.child_env()["CARDDEMO_BATCH_URL"] == "http://127.0.0.1:28190"


@needs_module
def test_unbuilt_server_is_reported_not_started(monkeypatch, tmp_path):
    stack = load_stack(monkeypatch, tmp_path, jar=None)
    batch = next(c for c in stack.components() if c.name == "batch-service")
    assert batch.argv is None and "run make build" in batch.problem


def test_sample_paths_are_absolute_and_exist(monkeypatch, tmp_path):
    stack = load_stack(monkeypatch, tmp_path)
    env = stack.child_env()
    source, pauth = env["CARDDEMO_LOAD_SOURCE_DIR"], env["CARDDEMO_PAUTH_SOURCE_FILE"]
    assert os.path.isabs(source) and os.path.isdir(source)
    assert source == os.path.normpath(os.path.join(MODERNIZATION, "..", "app", "data", "ASCII"))
    assert os.path.isabs(pauth) and os.path.isfile(pauth)
    assert env["CARDDEMO_VAR_DIR"] == str(tmp_path / "var")
    assert not {"CARDDEMO_INBOUND_DIR", "CARDDEMO_OUTPUT_DIR", "CARDDEMO_BATCH_FILES_OUTPUTDIR",
                "CARDDEMO_BATCH_FILES_INBOUNDDIR", "CARDDEMO_TRANSACTION_REPORT_ROOT"} & set(env)
    assert env["CARDDEMO_BATCH_URL"] == "http://127.0.0.1:28090"


def test_relative_sample_paths_are_made_absolute(monkeypatch, tmp_path):
    stack = load_stack(monkeypatch, tmp_path, {"CARDDEMO_LOAD_SOURCE_DIR": "../app/data/ASCII"})
    monkeypatch.setenv("CARDDEMO_PAUTH_SOURCE_FILE", str(tmp_path / "pauth.dat"))
    env = stack.child_env()
    assert env["CARDDEMO_LOAD_SOURCE_DIR"] == os.path.normpath(os.path.join(MODERNIZATION, "..", "app", "data",
                                                                            "ASCII"))
    assert env["CARDDEMO_PAUTH_SOURCE_FILE"] == str(tmp_path / "pauth.dat")


# A stand-in for the JVM: records the signal it gets, so the test sees that
# stop reached the process that up recorded, not a shell around it.
FAKE_SERVER = """
import os, signal, sys, time
out = sys.argv[1]
def term(signum, frame):
    with open(os.path.join(out, "signal"), "w") as f:
        f.write(signal.Signals(signum).name)
    sys.exit(0)
signal.signal(signal.SIGTERM, term)
with open(os.path.join(out, "ready"), "w") as f:
    f.write(str(os.getpid()))
while True:
    time.sleep(0.1)
"""


def test_stop_signals_the_recorded_process_itself(monkeypatch, tmp_path):
    stack = load_stack(monkeypatch, tmp_path)
    script = tmp_path / "fake_batch_server.py"
    script.write_text(FAKE_SERVER, encoding="utf-8")
    comp = stack.Component("batch-service", None, str(tmp_path), [sys.executable, str(script), str(tmp_path)],
                           None, str(script))
    assert stack.start(comp, dict(os.environ)) == "started"
    pid, marker = stack.read_pid_marker("batch-service")
    try:
        deadline = time.time() + 20
        while not (tmp_path / "ready").exists() and time.time() < deadline:
            time.sleep(0.05)
        assert (tmp_path / "ready").read_text() == str(pid)
        command = subprocess.run(["ps", "-o", "command=", "-p", str(pid)], capture_output=True, text=True).stdout
        assert os.path.basename(command.split()[0]) not in ("sh", "bash", "zsh", "dash")
        assert str(script) in command and " -c " not in command
        # Its own session: the group that stop signals is led by this pid.
        assert os.getpgid(pid) == pid
        assert stack.stop("batch-service", pid, marker)
        assert (tmp_path / "signal").read_text() == "SIGTERM"
        assert not os.path.exists(comp.pid_file)
    finally:
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
