"""The live driver of make verify-batch: batch-service on a database of its own.

A Session holds what one run owns:

  1. The database CARDDEMO_VERIFY_BATCH_DB_NAME (default carddemo_verify_batch, or
     carddemo_verify_batch_<suffix>). It may not be the demo, test, browser e2e or batch
     e2e database. Each scenario resets it with make db-reset and its own logins (<db>_*).
  2. The lock $CARDDEMO_VAR_DIR/run/verify-batch/<db>.lock, held for the whole run, and
     the pid file of the run's server, both 0600 in a 0700 folder. A server that an
     earlier run left behind is stopped only while its command line still names the
     jar. The folder is not run/ itself, so make stop and make status never take it
     for a stack component.
  3. The run's own directory. Each case has a private CARDDEMO_VAR_DIR there (0700),
     never the stack's: the inbound daily file, the output root that db-reset makes
     (a case stops when it is missing), the load's execution-ID file (removed once
     read), the exports and the logs of the load, the load report, the server and the
     export. Only the JVMs get it: make db-reset runs with the stack's folder and
     passes the case's to its JVM as RUN_VAR_DIR, so stack.py and compose keep the
     shared run/, log/ and secrets/. No legacy output or inbound setting reaches a command.

A LiveDriver runs the steps of differential.execute() for one scenario and mode. The
server uses the server profile on 127.0.0.1 and a free port, never 28090. The database
and its logins are kept after the run for a look; the next run resets them.
"""
from __future__ import annotations

import base64
import fcntl
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
MODERNIZATION = HERE.parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(MODERNIZATION / "scripts"))

import batch_e2e  # noqa: E402
import differential as d  # noqa: E402
import stack  # noqa: E402

DB_ENV = "CARDDEMO_VERIFY_BATCH_DB_NAME"
DEFAULT_DB = "carddemo_verify_batch"
DB_PATTERN = re.compile(r"carddemo_verify_batch(_[a-z0-9]+)*\Z")
STATE_DIR = Path(stack.RUN_DIR) / "verify-batch"
BATCH_MODULE = MODERNIZATION / "batch" / "batch-service"
EXPORTER_MODULE = MODERNIZATION / "verify" / "differential"
PAUTH_FILE = (MODERNIZATION.parent / "app" / "app-authorization-ims-db2-mq" / "data" / "EBCDIC"
              / "AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat")
START_TIMEOUT, JOB_TIMEOUT = 180, 900
ID_FILE_PROPERTY = "carddemo.batch.load.execution-id-file"
# The output-root contract refuses these by name, the last three being the environment forms
# of the removed properties; the stack's CARDDEMO_VAR_DIR is shared.
LEGACY_SETTINGS = ("CARDDEMO_OUTPUT_DIR", "CARDDEMO_REPORTS_DIR", "CARDDEMO_TRANREPT_DIR", "CARDDEMO_INBOUND_DIR",
                   "CARDDEMO_BATCH_FILES_OUTPUTDIR", "CARDDEMO_BATCH_FILES_INBOUNDDIR", "CARDDEMO_TRANSACTION_REPORT_ROOT")
# The exporter's exit status for missing or invalid database settings (load report contract
# 1.3): nothing on stdout and one "verify-differential: FAILED" line on stderr.
EXPORTER_SETTINGS_EXIT = 2
SEQUENCE_LOGIN = "batch"
# verify-differential --load-report, schemaVersion 1: every key, in this order.
REPORT_KEYS = (("schemaVersion", int), ("loadExecutionId", int), ("jobName", str), ("status", str),
               ("exitCode", str), ("businessDate", str), ("groupIdMode", str), ("accountRecords", int),
               ("accountsAsRead", int), ("accountsMigrated", int), ("accountLayoutSource", str),
               ("accountLayoutWarnings", int), ("acctdataSha256", str), ("accountLayoutManifestSha256", str))
SHA_RE = re.compile(r"[0-9a-f]{64}")
# The next value NEXT VALUE FOR returns, without taking it. Before the first use (also
# after a RESTART) last_used_value is NULL and current_value is the value to come.
SEQUENCE_SQL = ("SET NOCOUNT ON; SELECT CASE WHEN last_used_value IS NULL THEN CAST(current_value AS BIGINT) "
                "ELSE CAST(last_used_value AS BIGINT) + CAST(increment AS BIGINT) END "
                "FROM sys.sequences WHERE object_id = OBJECT_ID(N'core.tran_id_seq')")
RESERVED_IDS = (9000000000000000, 9999999999999999)
UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")


def verify_db(environ=None) -> str:
    environ = os.environ if environ is None else environ
    name = environ.get(DB_ENV) or DEFAULT_DB
    if not DB_PATTERN.match(name):
        raise d.Unsupported(f"{DB_ENV}={name} is not a verify-batch database name ({DEFAULT_DB} or "
                            f"{DEFAULT_DB}_<suffix>); the run drops and recreates it")
    try:
        file_env = stack.read_env(stack.ENV_FILE)
    except OSError:
        raise d.Unsupported(f"{stack.ENV_FILE} is missing; run make setup") from None
    taken = {environ.get("CARDDEMO_DB_NAME"), file_env.get("CARDDEMO_DB_NAME") or "carddemo",
             environ.get("CARDDEMO_TEST_MSSQL_DB"), file_env.get("CARDDEMO_TEST_MSSQL_DB"),
             environ.get("CARDDEMO_E2E_DB_NAME"), file_env.get("CARDDEMO_E2E_DB_NAME") or "carddemo_e2e",
             environ.get("CARDDEMO_BATCH_E2E_DB_NAME"),
             file_env.get("CARDDEMO_BATCH_E2E_DB_NAME") or "carddemo_batch_e2e"}
    if name in taken:
        raise d.Unsupported(f"{DB_ENV}={name} is also the demo, test, browser e2e or batch e2e database")
    return name


def read_execution_id(path: Path) -> int | None:
    """The ID the one-shot load wrote before it waited for the job; None when it launched none."""
    try:
        data = path.read_bytes()
    except FileNotFoundError:
        raise d.PipelineError("the load did not create its execution-ID file") from None
    if not data:
        return None
    # The batch sequences start at 0 (V401), so a fresh database's first execution is 0.
    # At most 19 digits before int(), then the 64-bit bound (load report contract 1.4).
    if not re.fullmatch(rb"(0|[1-9][0-9]{0,18})\n", data) or not d.is_execution_id(int(data)):
        raise d.PipelineError("the load's execution-ID file holds no execution ID")
    return int(data)


def parse_load_report(stdout: str, execution: int) -> dict:
    """The --load-report JSON of exactly this execution, checked key by key."""
    try:
        pairs = json.loads(stdout, object_pairs_hook=d.JsonPairs)
    except ValueError:
        raise d.PipelineError(f"the load report of execution {execution} is not one JSON object") from None
    if not isinstance(pairs, d.JsonPairs) or not stdout.endswith("\n"):
        raise d.PipelineError(f"the load report of execution {execution} is not one JSON object")
    if not pairs or pairs[0] != ("schemaVersion", 1):
        raise d.PipelineError(f"the load report of execution {execution} is not schemaVersion 1")
    if [k for k, _v in pairs] != [k for k, _t in REPORT_KEYS]:
        raise d.PipelineError(f"the load report has the keys {[k for k, _v in pairs]}")
    report = dict(pairs)
    for key, kind in REPORT_KEYS:
        value = report[key]
        if type(value) is not kind or (kind is int and value < 0):
            raise d.PipelineError(f"the load report's {key} is not a {kind.__name__}")
    if not d.is_execution_id(report["loadExecutionId"]):
        raise d.PipelineError("the load report's loadExecutionId is not a 64-bit execution ID")
    source, mode = report["accountLayoutSource"], report["groupIdMode"]
    wrong = [what for what, bad in (
        ("loadExecutionId", report["loadExecutionId"] != execution),
        ("jobName", report["jobName"] != "dataLoadJob"),
        ("status", (report["status"], report["exitCode"]) != ("COMPLETED", "COMPLETED")),
        ("businessDate", report["businessDate"] != d.BUSINESS_DATE),
        ("accountRecords", report["accountsAsRead"] + report["accountsMigrated"] != report["accountRecords"]),
        ("accountLayoutSource", source not in ("supplied", "builtin", "none", "ignored")),
        ("groupIdMode", mode not in ("legacy", "fixed") or (mode == "legacy") != (source == "ignored")),
        ("legacy counts", source == "ignored" and (report["accountsMigrated"], report["accountLayoutWarnings"]) != (0, 0)),
        ("acctdataSha256", not SHA_RE.fullmatch(report["acctdataSha256"])),
        ("accountLayoutManifestSha256", not (SHA_RE.fullmatch(report["accountLayoutManifestSha256"])
                                             if source == "supplied" else report["accountLayoutManifestSha256"] == "")),
    ) if bad]
    if wrong:
        raise d.PipelineError(f"the load report of execution {execution} is wrong in {', '.join(wrong)}")
    return report


def instance_root(var_dir: Path, db: str) -> Path:
    """The one output root that db-reset made for db under a fresh CARDDEMO_VAR_DIR:
    <var>/out/<sha256 of the name>/<instance id>/ with its exact .instance marker. The
    exporter compares the reject file under it with the one it rebuilds."""
    component = var_dir / "out" / hashlib.sha256(db.encode("utf-8")).hexdigest()
    try:
        roots = sorted(p for p in component.iterdir() if not p.name.startswith("."))
    except FileNotFoundError:
        raise d.PipelineError(f"db-reset made no output root under {component}; batch-service and db-migrate "
                              "must have the output root (CARDDEMO_VAR_DIR/out/)") from None
    if len(roots) != 1 or not UUID_RE.fullmatch(roots[0].name) or roots[0].is_symlink() or not roots[0].is_dir():
        raise d.PipelineError(f"{component} holds {[p.name for p in roots]}, not one instance root")
    root = roots[0]
    database = base64.urlsafe_b64encode(db.encode("utf-8")).rstrip(b"=")
    marker = b"carddemo-output-root 1\ndatabase=" + database + b"\ninstance=" + root.name.encode() + b"\n"
    path = root / ".instance"
    if path.is_symlink() or not path.is_file() or path.read_bytes() != marker:
        raise d.PipelineError(f"{root} has no marker of {db}")
    return root


def _failed_line(text: str) -> str:
    failed = [line for line in text.splitlines() if line.startswith("verify-differential: FAILED")]
    return f": {failed[0][:300]}" if failed else ""


def parse_sequence(output: str) -> int:
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if len(lines) != 1 or not re.fullmatch(r"[0-9]{16}", lines[0]):
        raise d.PipelineError(f"core.tran_id_seq: {len(lines)} lines, not one 16-digit next value")
    value = int(lines[0])
    if not RESERVED_IDS[0] <= value <= RESERVED_IDS[1]:
        raise d.PipelineError(f"core.tran_id_seq's next value {value} is outside the reserved range")
    return value


def _expect(what, answer, status, **members) -> dict:
    try:
        return batch_e2e.expect(what, answer, status, **members)
    except batch_e2e.Failed as e:
        raise d.PipelineError(str(e)) from None


class Session:
    """One run's database, lock, server and directory; use it as a context manager."""

    def __init__(self, root: Path, name: str, environ=None):
        self.name = name
        self.db = verify_db(environ)
        self.root = root.resolve() / "live"
        self.pid_file = STATE_DIR / f"{self.db}.pid"
        self.lock_fd = None
        self.proc = None
        self.count = 0

    def __enter__(self) -> "Session":
        self.lock()
        self.stop_leftover()
        self.root.mkdir(parents=True, mode=0o700)
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def lock(self) -> None:
        STATE_DIR.mkdir(parents=True, mode=0o700, exist_ok=True)
        os.chmod(STATE_DIR, 0o700)
        fd = os.open(STATE_DIR / f"{self.db}.lock", os.O_RDWR | os.O_CREAT, 0o600)
        os.fchmod(fd, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            holder = os.pread(fd, 512, 0).decode("utf-8", "replace").strip() or "unknown"
            os.close(fd)
            raise d.Unsupported(f"another run uses {self.db}: {holder}") from None
        os.ftruncate(fd, 0)
        os.pwrite(fd, f"{self.name} pid {os.getpid()} since {time.strftime('%H:%M:%S')} in {MODERNIZATION}\n".encode(), 0)
        self.lock_fd = fd

    def stop_leftover(self) -> None:
        try:
            pid_text, marker = self.pid_file.read_text(encoding="utf-8").strip().split(" ", 1)
            pid = int(pid_text)
        except (OSError, ValueError):
            pid, marker = None, ""
        if pid and marker and stack.alive(pid, marker):
            stack.say("..", "leftover", f"server pid {pid} of an earlier run on {self.db}; stopping it")
            self._stop_group(pid, lambda: stack.alive(pid, marker))
        self.pid_file.unlink(missing_ok=True)

    @staticmethod
    def _stop_group(pid: int, running) -> None:
        for sig in (signal.SIGCONT, signal.SIGTERM):
            try:
                os.killpg(pid, sig)
            except ProcessLookupError:
                return
        deadline = time.time() + stack.STOP_TIMEOUT
        while time.time() < deadline and running():
            time.sleep(0.5)
        if running():
            try:
                os.killpg(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass

    def close(self) -> None:
        self.stop_server()
        if self.lock_fd is not None:
            os.ftruncate(self.lock_fd, 0)
            os.close(self.lock_fd)
            self.lock_fd = None

    def env(self, var_dir: Path | None) -> dict:
        """Every command's environment: the run's database and logins, and CARDDEMO_VAR_DIR,
        the case's own for a JVM or the stack's (var_dir None) for make, with no legacy
        output or inbound setting from the shell or .env."""
        env = stack.child_env()
        for name in LEGACY_SETTINGS:
            env.pop(name, None)
        # Own logins: a CARDDEMO_DB_LOGIN_PREFIX from .env would make db-reset recreate the
        # logins of the demo database's services.
        env.update(CARDDEMO_DB_NAME=self.db, CARDDEMO_DB_LOGIN_PREFIX=f"{self.db}_",
                   CARDDEMO_VAR_DIR=str(var_dir) if var_dir is not None else stack.VAR_DIR)
        return env

    def jars(self) -> tuple[str, str]:
        batch = stack.find_jar(str(BATCH_MODULE), "batch-service")
        exporter = stack.find_jar(str(EXPORTER_MODULE), "verify-differential")
        missing = [m for m, jar in (("batch/batch-service", batch), ("verify/differential", exporter)) if not jar]
        if missing:
            raise d.Unsupported(f"no exec jar in {' and '.join(missing)}/target; run make build")
        return batch, exporter

    def step(self, what: str, argv: list[str], env: dict, cwd: Path, log: Path | None = None,
             exporter: bool = False) -> str:
        """Runs one command; its output goes to log (owner-only) when given, else to the console.
        An exporter run that exits for its database settings stops the whole run."""
        print(f"==    {what:10} {' '.join(argv)}", flush=True)
        if log is None:
            rc, text = subprocess.run(argv, cwd=cwd, env=env).returncode, ""
        else:
            out = subprocess.run(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True)
            rc, text = out.returncode, out.stdout + out.stderr
            log.write_text(text, encoding="utf-8")
            os.chmod(log, 0o600)
        if exporter and rc == EXPORTER_SETTINGS_EXIT:
            raise d.Unsupported(f"{what}: exited {rc}{_failed_line(text)}, see {log}")
        if rc != 0:
            raise d.PipelineError(f"{what}: exited {rc}" + (f", see {log}" if log else ", see above"))
        return text

    def report(self, what: str, argv: list[str], env: dict, cwd: Path, log: Path) -> str:
        """Runs a command whose stdout is its answer; stderr goes to log (owner-only)."""
        print(f"==    {what:10} {' '.join(argv)}", flush=True)
        out = subprocess.run(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True)
        log.write_text(out.stderr, encoding="utf-8")
        os.chmod(log, 0o600)
        if out.returncode == EXPORTER_SETTINGS_EXIT and not out.stdout:
            raise d.Unsupported(f"{what}: exited {out.returncode}{_failed_line(out.stderr)}, see {log}")
        if out.returncode != 0:
            raise d.PipelineError(f"{what}: exited {out.returncode}{_failed_line(out.stderr)}, see {log}")
        return out.stdout

    def sql(self, query: str, env: dict) -> str:
        """A read as the run's own batch login (<db>_batch), not sa: its grants on
        core.tran_id_seq make the sequence visible in sys.sequences. The password goes
        from env into the container's SQLCMDPASSWORD, never onto a command line."""
        password = env.get(f"CARDDEMO_DB_{SEQUENCE_LOGIN.upper()}_PASSWORD") or ""
        if not password:
            raise d.Unsupported(f"CARDDEMO_DB_{SEQUENCE_LOGIN.upper()}_PASSWORD is not set; run make setup")
        infra = os.environ.get("CARDDEMO_INFRA_DIR") or stack.main_modernization()
        cmd = ["docker", "compose", "--project-directory", infra, "-f", os.path.join(infra, "docker-compose.yml"),
               "--env-file", stack.ENV_FILE, "exec", "-T", "-e", "SQLCMDPASSWORD", "mssql",
               "/opt/mssql-tools18/bin/sqlcmd", "-S", "localhost", "-U", f"{self.db}_{SEQUENCE_LOGIN}", "-C", "-b",
               "-h", "-1", "-W", "-d", self.db, "-Q", query]
        out = subprocess.run(cmd, capture_output=True, text=True, env=dict(os.environ, SQLCMDPASSWORD=password))
        if out.returncode != 0:
            raise d.PipelineError(f"sqlcmd on {self.db}: {(out.stdout.strip() or out.stderr.strip())[:300]}")
        return out.stdout

    def start_server(self, args: list[str], env: dict, log_file: Path):
        self.stop_server()
        batch, _ = self.jars()
        token = env.get("CARDDEMO_BATCH_INTERNAL_TOKEN") or ""
        if not token:
            raise d.Unsupported("CARDDEMO_BATCH_INTERNAL_TOKEN is not set; run make setup")
        port = batch_e2e.free_port()
        argv = [stack.java_bin(), "-Xmx1g", "-XX:MaxMetaspaceSize=128m", "-Xss512k", "-jar", batch,
                "--spring.profiles.active=server", f"--server.port={port}", "--server.address=127.0.0.1", *args]
        with open(os.open(log_file, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600), "ab") as log:
            log.write(f"=== {time.strftime('%Y-%m-%d %H:%M:%S')} {self.name} on {self.db}\n".encode())
            log.flush()
            self.proc = subprocess.Popen(argv, cwd=BATCH_MODULE, env=env, stdin=subprocess.DEVNULL, stdout=log,
                                         stderr=subprocess.STDOUT, start_new_session=True)
        fd = os.open(self.pid_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        # The mode of open() applies only to a new file; one left by an older run keeps its own.
        os.fchmod(fd, 0o600)
        with open(fd, "w", encoding="utf-8") as f:
            f.write(f"{self.proc.pid} {batch}\n")
        api = batch_e2e.Api(port, token)
        deadline = time.time() + START_TIMEOUT
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise d.PipelineError(f"the server exited {self.proc.returncode}; see {log_file}")
            if stack.healthy(f"{api.base}/actuator/health"):
                return api
            time.sleep(1)
        raise d.PipelineError(f"the server was not healthy within {START_TIMEOUT} s; see {log_file}")

    def stop_server(self) -> None:
        if self.proc is not None and self.proc.poll() is None:
            proc = self.proc
            self._stop_group(proc.pid, lambda: proc.poll() is None)
            proc.wait(timeout=10)
        self.proc = None
        self.pid_file.unlink(missing_ok=True)

    def driver(self, scenario: d.Scenario, mode: d.Mode) -> "LiveDriver":
        self.count += 1
        return LiveDriver(self, self.root / f"{self.count:03d}-{scenario.job}-{scenario.case.replace('/', '_')}", mode)


class LiveDriver(d.Driver):
    def __init__(self, session: Session, work: Path, mode: d.Mode):
        self.s, self.work, self.mode = session, work, mode
        self.var = work / "var"
        self.output_root = None
        self.api = None
        self.load_execution = None

    def reset(self) -> None:
        if d.LOAD_REPORT_GAP:
            raise d.PipelineError(d.LOAD_REPORT_GAP)
        self.s.stop_server()
        self.work.mkdir(parents=True, mode=0o700)
        self.var.mkdir(mode=0o700)
        make = os.environ.get("MAKE", "make")
        self.s.step("db-reset", [make, "--no-print-directory", "-C", str(MODERNIZATION), "db-reset",
                                 f"RUN_VAR_DIR={self.var}"], self.s.env(None), MODERNIZATION)
        self.output_root = instance_root(self.var, self.s.db)

    def env(self) -> dict:
        return self.s.env(self.var)

    def load(self, source_dir: Path, args: list[str]) -> dict:
        batch, _ = self.s.jars()
        env = self.env() | {"CARDDEMO_LOAD_SOURCE_DIR": str(source_dir), "CARDDEMO_PAUTH_SOURCE_FILE": str(PAUTH_FILE)}
        # The load creates the file itself (CREATE_NEW) and names its execution before it
        # waits, so the ID is known even when the job fails. The report is bound to that
        # ID, never to the newest dataLoadJob, and never read from the log.
        id_dir = self.work / "load-id"
        id_dir.mkdir(mode=0o700)
        id_file = id_dir / "execution-id"
        failed = id_error = None
        try:
            self.s.step("load", [stack.java_bin(), "-jar", batch, "--spring.profiles.active=demo", *args,
                                 f"--{ID_FILE_PROPERTY}={id_file}"], env, BATCH_MODULE, self.work / "load.log")
        except d.PipelineError as e:
            failed = e
        try:
            if failed is None or id_file.exists():
                self.load_execution = read_execution_id(id_file)
        except d.PipelineError as e:
            id_error = e
        finally:
            shutil.rmtree(id_dir, ignore_errors=True)
        # A failed load with an unreadable ID file reports both; neither hides the other. A
        # failed load whose file names no execution (empty, missing or unreadable) launched an
        # unknown one: nothing is bound to it, never the newest dataLoadJob.
        if failed is not None:
            named = (self.load_execution if self.load_execution is not None else
                     "unknown, not bound" + (f": {id_error}" if id_error is not None else ""))
            raise d.PipelineError(f"{failed} (load execution {named})")
        if id_error is not None:
            raise id_error
        if self.load_execution is None:
            raise d.PipelineError("the load exited 0 but named no execution")
        return self.load_report(self.load_execution)

    def load_report(self, execution: int) -> dict:
        if d.LOAD_REPORT_GAP:
            raise d.PipelineError(d.LOAD_REPORT_GAP)
        _, exporter = self.s.jars()
        stdout = self.s.report("load report", [stack.java_bin(), "-jar", exporter, "--load-report", str(execution)],
                               self.env(), MODERNIZATION, self.work / "load-report.log")
        return parse_load_report(stdout, execution)

    def start(self, args: list[str], env: dict[str, str], daily_file: Path | None, business_date: str) -> None:
        owned = sorted(set(env) & {*LEGACY_SETTINGS, "CARDDEMO_VAR_DIR", "CARDDEMO_DB_NAME", "CARDDEMO_DB_LOGIN_PREFIX"})
        if owned:
            raise d.PipelineError(f"the server settings may not set {', '.join(owned)}")
        # Inbound is derived from CARDDEMO_VAR_DIR, never set on its own.
        inbound = self.var / "inbound"
        inbound.mkdir(mode=0o700)
        if daily_file is not None:
            staged = inbound / "dailytran" / f"{business_date}.txt"
            staged.parent.mkdir(mode=0o700)
            shutil.copyfile(daily_file, staged)
            os.chmod(staged, 0o600)
            if d.sha256(staged) != d.sha256(daily_file):
                raise d.PipelineError("the staged daily file differs from the case's")
        self.api = self.s.start_server(args, self.env() | env, self.work / "server.log")

    def register(self, body: dict) -> dict:
        return _expect("register the cycle", self.api.post("/internal/cycles", body), 201, attempt=1)

    def window(self, action: str, body: dict) -> dict:
        return _expect(f"window {action}", self.api.window(action, body), 200)

    def interest_sequence_next(self) -> int:
        return parse_sequence(self.s.sql(SEQUENCE_SQL, self.env()))

    def launch(self, job: str, params: dict) -> dict:
        started = _expect(f"launch {job}", self.api.launch(job, params), 202, restart=False)
        execution = started.get("executionId")
        deadline = time.time() + JOB_TIMEOUT
        while time.time() < deadline:
            body = _expect(f"poll {job}", self.api.poll(execution), 200, executionId=execution)
            if body.get("executionStatus") in batch_e2e.TERMINAL:
                return body
            time.sleep(1)
        raise d.PipelineError(f"{job}: execution {execution} not terminal after {JOB_TIMEOUT} s")

    def export(self, job: str, cycle_id: int, load_execution: int) -> Path:
        if load_execution != self.load_execution:
            raise d.PipelineError(f"the export names load {load_execution}, this case loaded {self.load_execution}")
        _, exporter = self.s.jars()
        export = self.work / "export"
        cmd = d.exporter_command(Path(exporter), export, job, cycle_id, load_execution, self.output_root)
        self.s.step("export", [stack.java_bin(), *cmd[1:]], self.env(), MODERNIZATION, self.work / "export.log",
                    exporter=True)
        return export
