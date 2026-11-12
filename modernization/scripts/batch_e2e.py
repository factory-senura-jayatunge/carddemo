#!/usr/bin/env python3
"""Drive a real batch-service server through its internal API on a database of
its own, and report what is covered and what is not built yet.

This is the minimal live harness of the batch-service execution contract
(architecture batch-execution-contract.md). It runs only what batch-service
registers today; it never fakes a cycle.

  1. Checks: the database is CARDDEMO_BATCH_E2E_DB_NAME (default
     carddemo_batch_e2e, or carddemo_batch_e2e_<suffix>), and it is neither the
     demo database (CARDDEMO_DB_NAME), the test database
     (CARDDEMO_TEST_MSSQL_DB) nor the browser e2e database
     (CARDDEMO_E2E_DB_NAME).
  2. Takes the lock of that database ($CARDDEMO_VAR_DIR/run/batch-e2e/<db>.lock),
     so two runs never reset it under each other, and stops a server that an
     earlier run on it left behind (its pid file there, whose command line
     must still name the jar; nothing else is touched). The directory is not
     run/ itself, so make stop and make status never take it for a stack
     component.
  3. make db-reset on that database with its own logins (<db>_*), builds
     batch-service, and starts its own server: the server
     profile on 127.0.0.1 and a free port, never 28090, so the stack of make
     up keeps running as it is. db-reset's JVM (RUN_VAR_DIR) and the server
     get a CARDDEMO_VAR_DIR of the run's own (mkdtemp, 0700), so no output
     root lands in the shared folder; it is removed at the end, KEEP_VAR=1
     keeps it and prints its path.
  4. The cases: anonymous health, the token, cycle registration, the batch
     window of a cycle and of a run key, dataLoadJob through the API (202,
     409 attach and complete, the close refused while it runs), a failed
     instance restarted with its stored values, a cycle completed by its
     close, and a probe of every contract job (400 means registered, 404
     JOB_NOT_FOUND a phased gap; the probe sends no parameters, so nothing
     runs).
  5. Always, also after a failure or SIGINT, SIGTERM or SIGHUP: stops its
     server and releases the lock. The database is kept for a look; the
     next run resets it.

--sigstop (Linux only, as in CI) also pauses the server's own JVM with
SIGSTOP for less than lease TTL + grace during the data load (lease 0.5 s /
2 s / 2 s), then SIGCONT: the execution must complete without a recovery.

Run it under heavy.py, as make test-batch-live does. Ends with
"<name>: OK (...)" or "<name>: FAILED (...)".

Usage: batch_e2e.py [--name NAME] [--sigstop] [--no-build]
"""

import fcntl
import json
import os
import re
import secrets
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stack  # noqa: E402
from with_env import VAR_DIR_KEY, PrivateVarDir  # noqa: E402

HERE = stack.HERE
DB_PATTERN = re.compile(r"carddemo_batch_e2e(_[a-z0-9]+)*\Z")
DAY = "2022-07-06"
START_TIMEOUT = 180
JOB_TIMEOUT = 900
# Contract section 2.1: every job, by kind. A probe with no parameters gets
# 400 from a registered job and 404 JOB_NOT_FOUND from a missing one.
CONTRACT_JOBS = (
    "archiveMasterJob", "ingestDailyFileJob", "postDailyTransactionsJob", "calculateInterestJob", "statementJob",
    "applyRetentionJob", "purgePendingAuthorizationsJob", "transactionReportJob", "categoryBalanceListingJob",
    "dataLoadJob", "disclosureGroupReloadJob", "transactionTypeMaintenanceJob", "exportMastersJob",
    "importMigrationJob")
# Not driven by this harness; each needs work that is not there yet.
PHASED_GAPS = (
    "full daily cycle through the DAG (verify-cycle stays a stub)",
    "cycle-rerun", "the cycle's file handling (inbound daily file, archive move, output folders)",
    "the lease takeover by a second server (SIGSTOP longer than TTL + grace with a launch meanwhile)",
)
TERMINAL = {"COMPLETED", "FAILED", "STOPPED", "ABANDONED"}
STATE_DIR = os.path.join(stack.RUN_DIR, "batch-e2e")


class Failed(Exception):
    pass


def harness_db(environ=None) -> str:
    environ = os.environ if environ is None else environ
    name = environ.get("CARDDEMO_BATCH_E2E_DB_NAME") or "carddemo_batch_e2e"
    if not DB_PATTERN.match(name):
        raise Failed(f"CARDDEMO_BATCH_E2E_DB_NAME={name} is not a batch harness database name "
                     f"(carddemo_batch_e2e or carddemo_batch_e2e_<suffix>); the run drops and recreates it")
    file_env = stack.read_env(stack.ENV_FILE)
    taken = {environ.get("CARDDEMO_DB_NAME"), file_env.get("CARDDEMO_DB_NAME") or "carddemo",
             environ.get("CARDDEMO_TEST_MSSQL_DB"), file_env.get("CARDDEMO_TEST_MSSQL_DB"),
             environ.get("CARDDEMO_E2E_DB_NAME"), file_env.get("CARDDEMO_E2E_DB_NAME") or "carddemo_e2e"}
    if name in taken:
        raise Failed(f"CARDDEMO_BATCH_E2E_DB_NAME={name} is also the demo, test or browser e2e database")
    return name


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Api:
    """The internal API of one server; never prints the token."""

    def __init__(self, port: int, token: str):
        self.base = f"http://127.0.0.1:{port}"
        self.token = token
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(self, method, path, body=None, token=True):
        headers = {"Accept": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {self.token if token is True else token}"
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with self.opener.open(request, timeout=120) as r:
                status, raw, h = r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            status, raw, h = e.code, e.read(), e.headers
        try:
            parsed = json.loads(raw) if raw else {}
        except ValueError:
            parsed = {}
        return status, parsed if isinstance(parsed, dict) else {}, h

    def post(self, path, body, **kw):
        return self.call("POST", path, body, **kw)

    def launch(self, job, params):
        return self.post(f"/internal/jobs/{job}/executions", {"params": params})

    def window(self, action, body):
        return self.post(f"/internal/batch-window/{action}", body)

    def poll(self, execution_id):
        return self.call("GET", f"/internal/jobs/executions/{execution_id}")


def expect(what, answer, http_status, code=None, /, **members):
    """members may name any body member, status included (a cycle's)."""
    got_status, body, _ = answer
    problems = []
    if got_status != http_status:
        problems.append(f"HTTP {got_status}, expected {http_status}")
    if code is not None and body.get("code") != code:
        problems.append(f"code {body.get('code')}, expected {code}")
    for key, value in members.items():
        if body.get(key) != value:
            problems.append(f"{key} {body.get(key)!r}, expected {value!r}")
    if problems:
        detail = body.get("message") or ""
        raise Failed(f"{what}: {'; '.join(problems)}" + (f" ({detail})" if detail else ""))
    return body


class Harness:
    def __init__(self, name, sigstop, build):
        self.name, self.sigstop, self.build = name, sigstop, build
        self.db = harness_db()
        self.lock_fd = None
        self.proc = None
        self.port = None
        self.api = None
        self.passed = []
        self.notes = []
        self.registered, self.gaps = [], []
        self.var = None  # the run's own CARDDEMO_VAR_DIR, set by main()
        self.pid_file = os.path.join(STATE_DIR, f"{self.db}.pid")
        self.log_file = os.path.join(stack.LOG_DIR, f"{self.db}.log")

    # --- lock, prior state and cleanup -----------------------------------

    def lock(self):
        os.makedirs(STATE_DIR, exist_ok=True)
        fd = os.open(os.path.join(STATE_DIR, f"{self.db}.lock"), os.O_RDWR | os.O_CREAT, 0o644)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            holder = os.pread(fd, 512, 0).decode("utf-8", "replace").strip() or "unknown"
            os.close(fd)
            raise Failed(f"another run uses {self.db}: {holder}")
        os.ftruncate(fd, 0)
        os.pwrite(fd, f"{self.name} pid {os.getpid()} since {time.strftime('%H:%M:%S')} in {HERE}\n".encode(), 0)
        self.lock_fd = fd

    def stop_leftover(self):
        """A server an earlier run on this database left behind: its pid file
        names it, and its command line must still name its jar."""
        try:
            with open(self.pid_file, encoding="utf-8") as f:
                pid_text, marker = f.read().strip().split(" ", 1)
            pid = int(pid_text)
        except (OSError, ValueError):
            pid, marker = None, ""
        if pid and marker and stack.alive(pid, marker):
            stack.say("..", "leftover", f"server pid {pid} of an earlier run on {self.db}; stopping it")
            # Started in its own session, so the group is led by pid.
            for sig in (signal.SIGCONT, signal.SIGTERM):
                try:
                    os.killpg(pid, sig)
                except ProcessLookupError:
                    pass
            deadline = time.time() + stack.STOP_TIMEOUT
            while time.time() < deadline and stack.alive(pid, marker):
                time.sleep(0.5)
            if stack.alive(pid, marker):
                try:
                    os.killpg(pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                time.sleep(1)
            if stack.alive(pid, marker):
                raise Failed(f"the earlier server pid {pid} did not stop")
        if os.path.exists(self.pid_file):
            os.remove(self.pid_file)

    def close(self):
        if self.proc is not None and self.proc.poll() is None:
            try:
                os.killpg(self.proc.pid, signal.SIGCONT)
                os.killpg(self.proc.pid, signal.SIGTERM)
                self.proc.wait(timeout=stack.STOP_TIMEOUT)
            except subprocess.TimeoutExpired:
                os.killpg(self.proc.pid, signal.SIGKILL)
                self.proc.wait(timeout=10)
            except ProcessLookupError:
                pass
            stack.say("ok", "server", f"stopped (pid {self.proc.pid})")
        if os.path.exists(self.pid_file):
            os.remove(self.pid_file)
        if self.lock_fd is not None:
            os.ftruncate(self.lock_fd, 0)
            os.close(self.lock_fd)
            self.lock_fd = None

    # --- database and server ---------------------------------------------

    def env(self) -> dict:
        env = stack.child_env()
        # Own logins too: a CARDDEMO_DB_LOGIN_PREFIX from .env would make
        # db-reset recreate the logins the demo database's services use.
        env.update(CARDDEMO_DB_NAME=self.db, CARDDEMO_DB_LOGIN_PREFIX=f"{self.db}_")
        return env

    def run(self, step, argv):
        print(f"==    {step:10} {' '.join(argv)}", flush=True)
        rc = subprocess.run(argv, cwd=HERE, env=self.env()).returncode
        if rc != 0:
            raise Failed(f"{step}: exited {rc}, see above")

    def reset(self):
        make = os.environ.get("MAKE", "make")
        self.run("db-reset", [make, "--no-print-directory", "-C", HERE, "db-reset", f"RUN_VAR_DIR={self.var}"])
        if self.build:
            self.run("build", [os.path.join(HERE, "mvnw"), "-q", "-B", "-pl", "batch/batch-service", "-am",
                               "package", "-DskipTests"])

    def start(self):
        module = os.path.join(HERE, "batch", "batch-service")
        jar = stack.find_jar(module, "batch-service")
        if not jar:
            raise Failed("no batch/batch-service/target/batch-service-*-exec.jar; run make build")
        self.port = free_port()
        argv = [stack.java_bin(), "-Xmx1g", "-XX:MaxMetaspaceSize=128m", "-Xss512k", "-jar", jar,
                "--spring.profiles.active=server", f"--server.port={self.port}", "--server.address=127.0.0.1"]
        if self.sigstop:
            argv += ["--carddemo.batch.lease.renewal-interval=500ms", "--carddemo.batch.lease.ttl=2s",
                     "--carddemo.batch.lease.grace=2s"]
        env = self.env()
        env[VAR_DIR_KEY] = self.var
        token = env.get("CARDDEMO_BATCH_INTERNAL_TOKEN") or ""
        if not token:
            raise Failed("CARDDEMO_BATCH_INTERNAL_TOKEN is not set; run make setup")
        os.makedirs(stack.LOG_DIR, exist_ok=True)
        with open(self.log_file, "ab") as log:
            log.write(f"\n=== {time.strftime('%Y-%m-%d %H:%M:%S')} {self.name} on {self.db}\n".encode())
            log.flush()
            # java itself, in its own session: SIGSTOP and stop reach the JVM.
            self.proc = subprocess.Popen(argv, cwd=module, env=env, stdin=subprocess.DEVNULL, stdout=log,
                                         stderr=subprocess.STDOUT, start_new_session=True)
        with open(self.pid_file, "w", encoding="utf-8") as f:
            f.write(f"{self.proc.pid} {jar}\n")
        self.api = Api(self.port, token)
        deadline = time.time() + START_TIMEOUT
        while time.time() < deadline:
            if self.proc.poll() is not None:
                raise Failed(f"the server exited {self.proc.returncode}; see {self.log_file}")
            if stack.healthy(f"{self.api.base}/actuator/health"):
                stack.say("ok", "server", f"pid {self.proc.pid} on 127.0.0.1:{self.port}, database {self.db}")
                return
            time.sleep(1)
        raise Failed(f"the server was not healthy within {START_TIMEOUT} s; see {self.log_file}")

    # --- cases ------------------------------------------------------------

    def case(self, name, fn):
        fn()
        self.passed.append(name)
        stack.say("ok", "case", name)

    def wait_terminal(self, execution_id, timeout=JOB_TIMEOUT):
        deadline = time.time() + timeout
        while time.time() < deadline:
            body = expect(f"poll {execution_id}", self.api.poll(execution_id), 200)
            if body.get("executionStatus") in TERMINAL:
                return body
            time.sleep(1)
        raise Failed(f"execution {execution_id} not terminal after {timeout} s")

    def health(self):
        status, body, _ = self.api.call("GET", "/actuator/health", token=False)
        if status != 200 or body.get("status") != "UP":
            raise Failed(f"anonymous health: HTTP {status} {body}")
        if set(body) - {"status", "groups"}:
            raise Failed(f"anonymous health shows details: {sorted(body)}")

    def token(self):
        expect("no token", self.api.post("/internal/cycles", {"businessDate": DAY, "interest": "never"},
                                         token=False), 401, "UNAUTHENTICATED")
        expect("wrong token", self.api.post("/internal/cycles", {"businessDate": DAY, "interest": "never"},
                                            token=secrets.token_urlsafe(24)), 401, "UNAUTHENTICATED")

    def cycles(self):
        first = expect("register", self.api.post("/internal/cycles", {"businessDate": DAY, "interest": "never"}),
                       201, status="OPEN", interest=False, attempt=1, businessDate=DAY)
        self.cycle_id = first["cycleId"]
        # month_end resolves to no interest on 2022-07-06, the stored value.
        expect("register again", self.api.post("/internal/cycles", {"businessDate": DAY, "interest": "month_end"}),
               200, cycleId=self.cycle_id)
        expect("other interest", self.api.post("/internal/cycles", {"businessDate": DAY, "interest": "always"}),
               409, "CYCLE_PARAMETERS_DIFFER", cycleId=self.cycle_id)
        expect("bad date", self.api.post("/internal/cycles", {"businessDate": "2022-13-01", "interest": "never"}),
               400, "INVALID_REQUEST")

    def cycle_window(self):
        holder = {"cycleId": self.cycle_id}
        opened = expect("open", self.api.window("open", holder), 200, cycleId=self.cycle_id, mode="WRITES_BLOCKED")
        expect("open again", self.api.window("open", holder), 200, windowId=opened["windowId"])
        status, body, headers = self.api.window("open", {"windowRunKey": "harness/busy"})
        expect("other holder", (status, body, headers), 503, "WINDOW_BUSY", cycleId=self.cycle_id)
        if not (headers.get("Retry-After") or "").isdigit():
            raise Failed(f"WINDOW_BUSY without a whole-second Retry-After: {headers.get('Retry-After')!r}")
        expect("close without complete", self.api.window("close", holder), 400, "INVALID_REQUEST", field="complete")
        closed = expect("close, cycle stays open", self.api.window("close", {**holder, "complete": False}), 200,
                        windowId=opened["windowId"])
        if (closed.get("cycle") or {}).get("status") != "OPEN":
            raise Failed(f"complete false left the cycle {closed.get('cycle')}")
        again = expect("close again", self.api.window("close", {**holder, "complete": False}), 200,
                       windowId=opened["windowId"])
        if again.get("closedAt") != closed.get("closedAt"):
            raise Failed("closing a closed window changed its body")
        expect("never opened", self.api.window("close", {"windowRunKey": f"harness/none-{secrets.token_hex(4)}"}),
               404, "WINDOW_NOT_FOUND")

    def data_load(self):
        window = f"harness/{secrets.token_hex(4)}"
        params = {"runKey": f"{window}/load", "businessDate": DAY, "windowRunKey": window}
        expect("no window", self.api.launch("dataLoadJob", params), 409, "BATCH_WINDOW_REQUIRED")
        expect("open", self.api.window("open", {"windowRunKey": window}), 200, windowRunKey=window)
        started = expect("launch", self.api.launch("dataLoadJob", params), 202, restart=False)
        execution = started["executionId"]
        status, body, _ = self.api.launch("dataLoadJob", params)
        if body.get("code") == "JOB_EXECUTION_RUNNING":
            expect("attach", (status, body, None), 409, executionId=execution)
            self.notes.append("409 JOB_EXECUTION_RUNNING attached to the running load")
        else:
            expect("second launch", (status, body, None), 409, "JOB_INSTANCE_COMPLETE", executionId=execution)
            self.notes.append("the load ended before the second launch: 409 JOB_INSTANCE_COMPLETE, not the attach")
        polled = expect("poll", self.api.poll(execution), 200)
        if polled.get("executionStatus") not in TERMINAL:
            expect("lease", (200, polled.get("lease") or {}, None), 200, state="LIVE")
            status, body, _ = self.api.window("close", {"windowRunKey": window})
            if status == 409:
                expect("close while running", (status, body, None), 409, "EXECUTIONS_RUNNING")
                if execution not in (body.get("executionIds") or []):
                    raise Failed(f"EXECUTIONS_RUNNING without {execution}: {body.get('executionIds')}")
                self.notes.append("409 EXECUTIONS_RUNNING refused the close while the load ran")
            elif self.api.poll(execution)[1].get("executionStatus") not in TERMINAL:
                raise Failed(f"the window closed while execution {execution} ran: HTTP {status}")
            else:
                self.notes.append(f"the load ended during the close check (HTTP {status}), not the 409")
            if self.sigstop:
                self.pause(execution)
        else:
            self.notes.append("the load ended before the close check")
            if self.sigstop:
                raise Failed("the load ended before the SIGSTOP pause could run")
        done = self.wait_terminal(execution)
        if (done.get("executionStatus"), done.get("exitStatus")) != ("COMPLETED", "COMPLETED"):
            raise Failed(f"dataLoadJob ended {done.get('executionStatus')} / {done.get('exitStatus')}; "
                         f"see {self.log_file}")
        expect("relaunch", self.api.launch("dataLoadJob", params), 409, "JOB_INSTANCE_COMPLETE",
               executionId=execution, exitStatus="COMPLETED")
        expect("close with complete", self.api.window("close", {"windowRunKey": window, "complete": True}), 400,
               "INVALID_REQUEST", field="complete")
        expect("close", self.api.window("close", {"windowRunKey": window}), 200, windowRunKey=window, cycle=None)

    def pause(self, execution):
        """SIGSTOP the server's own JVM for less than TTL + grace (4 s)."""
        pid = self.proc.pid
        command = subprocess.run(["ps", "-o", "command=", "-p", str(pid)], capture_output=True, text=True).stdout
        if "batch-service" not in command or self.proc.poll() is not None:
            raise Failed(f"pid {pid} is not this run's server; not stopping it")
        os.kill(pid, signal.SIGSTOP)
        try:
            time.sleep(1.5)
        finally:
            os.kill(pid, signal.SIGCONT)
        polled = expect("poll after the pause", self.api.poll(execution), 200, executionId=execution)
        lease = polled.get("lease") or {}
        if lease.get("state") == "REVOKED":
            raise Failed(f"a pause shorter than TTL + grace revoked the lease: {lease}")
        self.notes.append("SIGSTOP 1.5 s < TTL + grace 4 s: no recovery")

    def restart_keeps_values(self):
        window = f"harness/{secrets.token_hex(4)}"
        params = {"runKey": f"{window}/load", "businessDate": DAY, "windowRunKey": window}
        expect("open", self.api.window("open", {"windowRunKey": window}), 200)
        execution = expect("launch", self.api.launch("dataLoadJob", params), 202, restart=False)["executionId"]
        failed = self.wait_terminal(execution)
        if failed.get("executionStatus") != "FAILED":
            raise Failed(f"a second load over loaded tables ended {failed.get('executionStatus')}; the server "
                         f"profile must refuse the reload (carddemo.data-load.reload-allowed=false)")
        expect("changed businessDate", self.api.launch("dataLoadJob", {**params, "businessDate": "2022-07-07"}),
               400, "INVALID_JOB_PARAMETERS", field="businessDate")
        restarted = expect("restart", self.api.launch("dataLoadJob", params), 202, restart=True)
        if restarted["executionId"] == execution:
            raise Failed("the restart reused the failed execution")
        self.wait_terminal(restarted["executionId"])
        expect("close", self.api.window("close", {"windowRunKey": window}), 200)

    def cycle_completes(self):
        holder = {"cycleId": self.cycle_id}
        expect("open", self.api.window("open", holder), 200)
        closed = expect("complete", self.api.window("close", {**holder, "complete": True}), 200)
        if (closed.get("cycle") or {}).get("status") != "COMPLETED":
            raise Failed(f"complete true left the cycle {closed.get('cycle')}")
        expect("open completed", self.api.window("open", holder), 409, "CYCLE_COMPLETED", cycleId=self.cycle_id)
        expect("register completed", self.api.post("/internal/cycles", {"businessDate": DAY, "interest": "never"}),
               409, "CYCLE_COMPLETED", cycleId=self.cycle_id)

    def unknown_execution(self):
        expect("poll", self.api.poll(9_000_000_000_000), 404, "EXECUTION_NOT_FOUND")

    def probe_jobs(self):
        for job in CONTRACT_JOBS:
            status, body, _ = self.api.launch(job, {})
            if status == 404 and body.get("code") == "JOB_NOT_FOUND":
                self.gaps.append(job)
            elif status == 400:
                self.registered.append(job)
            else:
                raise Failed(f"probe {job} with no parameters: HTTP {status} {body.get('code')}; expected 400 or "
                             f"404 JOB_NOT_FOUND")

    def cases(self):
        self.case("health is anonymous and shows no details", self.health)
        self.case("the internal API refuses a missing or wrong token (401)", self.token)
        self.case("cycle registration: 201, 200 resume, 409 other interest, 400", self.cycles)
        self.case("cycle window: open, 503 busy, 400 without complete, close false, 404 never opened",
                  self.cycle_window)
        self.case("dataLoadJob in its run-key window: 409 no window, 202, attach, 409 complete, close",
                  self.data_load)
        self.case("failed instance: 400 changed businessDate, 202 restart with stored values",
                  self.restart_keeps_values)
        self.case("cycle completed by its close: 409 CYCLE_COMPLETED on open and register", self.cycle_completes)
        self.case("unknown execution: 404 EXECUTION_NOT_FOUND", self.unknown_execution)
        self.case("job probe", self.probe_jobs)


def install_signal_exit():
    for sig in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, lambda s, _f: sys.exit(128 + s))


def main(argv) -> int:
    args = argv[1:]
    name, sigstop, build = "test-batch-live", False, True
    while args:
        if args[0] == "--name" and len(args) > 1:
            name, args = args[1], args[2:]
        elif args[0] == "--sigstop":
            sigstop, args = True, args[1:]
        elif args[0] == "--no-build":
            build, args = False, args[1:]
        else:
            print(f"{name}: FAILED (unknown argument {args[0]}; {__doc__.strip().splitlines()[-1]})")
            return 2
    if sigstop and not sys.platform.startswith("linux"):
        print(f"{name}: FAILED (--sigstop runs on Linux only; on macOS a pause test needs its own window)")
        return 2
    install_signal_exit()
    begun = time.monotonic()
    try:
        h = Harness(name, sigstop, build)
        h.lock()
        # The server writes into the folder, so it stops (close) before the
        # folder is removed.
        with PrivateVarDir(name, out=lambda line: print(line, flush=True)) as h.var:
            try:
                stack.say("ok", "var", f"own CARDDEMO_VAR_DIR {h.var}")
                h.stop_leftover()
                h.reset()
                h.start()
                h.cases()
            finally:
                h.close()
    except Failed as e:
        print(f"{name}: FAILED ({e})")
        return 1
    except Exception as e:  # a harness bug still ends with the FAILED line, after the cleanup above
        print(f"{name}: FAILED (harness error {type(e).__name__}: {e})")
        raise
    except (KeyboardInterrupt, SystemExit):
        print(f"{name}: FAILED (interrupted; the server of this run is stopped)")
        raise
    for note in h.notes:
        stack.say("note", "", note)
    stack.say("info", "registered", ", ".join(h.registered) or "none")
    stack.say("gap", "not built", ", ".join(h.gaps) or "none")
    for gap in PHASED_GAPS:
        stack.say("gap", "not driven", gap)
    print(f"{name}: OK ({len(h.passed)} cases on {h.db}, {time.monotonic() - begun:.0f} s; "
          f"{len(h.registered)} of {len(CONTRACT_JOBS)} contract jobs registered, the rest are phased gaps)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
