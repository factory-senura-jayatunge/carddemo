#!/usr/bin/env python3
"""Run the browser tests against a slice on its own database, then put the stack back.

The stack scenarios change data (BILL pays the start balances), so every
browser run needs freshly loaded data, and the demo database of make up
(CARDDEMO_DB_NAME) must never be reset under someone else's feet. This runner
starts identity-service, billpay-service and web from this checkout on the e2e
database instead, CARDDEMO_E2E_DB_NAME (default carddemo_e2e), and afterwards
restores what ran before.

  1. Checks: the e2e database is named carddemo_e2e or carddemo_e2e_<suffix>
     and is not the demo database (CARDDEMO_DB_NAME in the environment or
     .env) nor the test database (CARDDEMO_TEST_MSSQL_DB).
  2. Takes the slice lock (~/.cache/carddemo/slice.lock, machine-wide, see
     stack.py): until the run ends, make up, stop and down fail in every
     checkout and clone with this stack.py, and a make up or stop already
     under way is waited for. Older stack.py copies and processes started by
     hand do not know the lock; step 3 catches what they left on the ports
     before the run, nothing guards against what they start during it.
  3. Notes which slice components run, from which checkout and on which
     database, and fails before changing anything when a slice port is held
     by a process that stack.py did not record or whose checkout is unknown.
     Then it stops the recorded slice processes.
  4. For each browser: stops the two services, runs db-reset and load on the
     e2e database (unless --no-reload), starts the slice on it (stack.py up
     --only, which also builds stale jars), and runs Playwright with
     CARDDEMO_E2E_TARGET=stack.
  5. Always, also after a failure or SIGINT, SIGTERM or SIGHUP: stops the
     processes it started and starts again, each from its own checkout and on
     its own database, the components that ran before. A component that was
     not running stays stopped. The e2e database is kept for a look at what
     the tests left; the next run resets it.

Run it under heavy.py, as make test-e2e does; scripts/test_billpay.py uses
Session for its stack step. Ends with "<name>: OK" or "<name>: FAILED (...)".

Usage: e2e_stack.py [--name NAME] [--browsers "chromium firefox"] [--no-reload] [--] [PLAYWRIGHT ARG...]
"""

import fcntl
import os
import re
import secrets
import signal
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stack  # noqa: E402

HERE = stack.HERE
SERVICES = ("identity-service", "billpay-service")
SLICE = SERVICES + ("web",)
PORTS = {name: port for name, _rel, port, _heap in stack.JVM_SERVICES if name in SERVICES} | {"web": stack.WEB_PORT}
DB_PATTERN = re.compile(r"carddemo_e2e(_[a-z0-9]+)*\Z")
LOCK_WAIT = 900


class Failed(Exception):
    pass


def e2e_db(environ=None) -> str:
    environ = os.environ if environ is None else environ
    name = environ.get("CARDDEMO_E2E_DB_NAME") or "carddemo_e2e"
    if not DB_PATTERN.match(name):
        raise Failed(f"CARDDEMO_E2E_DB_NAME={name} is not an e2e database name (carddemo_e2e or "
                     f"carddemo_e2e_<suffix>); the run drops and reloads it")
    file_env = stack.read_env(stack.ENV_FILE)
    taken = {environ.get("CARDDEMO_DB_NAME"), file_env.get("CARDDEMO_DB_NAME") or "carddemo",
             environ.get("CARDDEMO_TEST_MSSQL_DB"), file_env.get("CARDDEMO_TEST_MSSQL_DB")}
    if name in taken:
        raise Failed(f"CARDDEMO_E2E_DB_NAME={name} is also the demo or test database; choose another")
    return name


def checkout_of(name, pid, marker):
    """modernization/ of the checkout a running slice process comes from, or None."""
    if name == "web":
        out = subprocess.run(["lsof", "-a", "-p", str(pid), "-d", "cwd", "-Fn"],
                             capture_output=True, text=True).stdout
        cwd = next((line[1:] for line in out.splitlines() if line.startswith("n")), "")
        found = os.path.dirname(cwd) if os.path.basename(cwd) == "web" else None
    else:
        i = marker.find(f"/services/{name}/")
        found = marker[:i] if i > 0 else None
    return found if found and os.path.exists(os.path.join(found, "scripts", "stack.py")) else None


class Session:
    """The slice on the e2e database, from begin() until close()."""

    def __init__(self, name: str):
        self.name = name
        self.db = e2e_db()
        self.token = secrets.token_hex(8)
        self.fd = None
        self.prior = []        # (name, pid, checkout, database or None) of what ran before
        self.started = {}      # name -> pid of the slice processes this session started

    def env(self, **extra) -> dict:
        env = dict(os.environ)
        env.setdefault("CARDDEMO_ENV_FILE", stack.ENV_FILE)
        env.update(CARDDEMO_DB_NAME=self.db, CARDDEMO_E2E_SESSION=self.token)
        env.update(extra)
        node = stack.node_bin()
        if node:
            env["PATH"] = node + os.pathsep + env.get("PATH", "")
        return env

    def say(self, status, what, detail):
        stack.say(status, what, detail)

    # --- lock -------------------------------------------------------------

    def lock(self):
        os.makedirs(os.path.dirname(stack.SLICE_LOCK), exist_ok=True)
        fd = os.open(stack.SLICE_LOCK, os.O_RDWR | os.O_CREAT, 0o644)
        deadline = time.time() + LOCK_WAIT
        waited = False
        while True:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                holder = stack.lock_holder(stack.SLICE_LOCK)
                if holder:
                    os.close(fd)
                    raise Failed(f"another isolated run owns the slice: {holder}")
                if time.time() > deadline:
                    os.close(fd)
                    raise Failed(f"a make up, stop or down still runs after {LOCK_WAIT} s")
                if not waited:
                    self.say("..", "slice", "waiting for a make up, stop or down to finish")
                    waited = True
                time.sleep(2)
        os.ftruncate(fd, 0)
        os.pwrite(fd, (f"{self.name} pid {os.getpid()} since {time.strftime('%H:%M:%S')} on {self.db} "
                       f"in {HERE}\ntoken {self.token}\n").encode(), 0)
        self.fd = fd

    def unlock(self):
        if self.fd is not None:
            os.ftruncate(self.fd, 0)
            os.close(self.fd)
            self.fd = None

    # --- begin and close --------------------------------------------------

    def begin(self):
        self.lock()
        try:
            self.note_prior()
            for name in SLICE:
                self.stop(name)
        except BaseException:
            self.close()
            raise

    def note_prior(self):
        for name in SLICE:
            pid, marker = stack.read_pid_marker(name)
            if stack.alive(pid, marker):
                checkout = checkout_of(name, pid, marker)
                if not checkout:
                    raise Failed(f"{name} runs (pid {pid}) from a checkout this run cannot find, so it could "
                                 f"not be started again afterwards; stop it first (make stop)")
                db = stack.recorded_db(name) if name in SERVICES else None
                if db == self.db:
                    self.say("warn", name, f"left on {self.db} by an earlier run; stopped, not restored")
                    continue
                self.prior.append((name, pid, checkout, db))
                self.say("..", name, f"runs from {checkout}" + (f" on {db}" if db else "")
                         + "; stopped now, started again at the end")
            elif stack.port_busy(PORTS[name]):
                raise Failed(f"port {PORTS[name]} ({name}) is held by a process stack.py did not start; "
                             f"stop it first")

    def stop(self, name):
        pid, marker = stack.read_pid_marker(name)
        if not stack.alive(pid, marker):
            stack.stop(name, pid, marker)  # clears a stale pid file
            return
        if not stack.stop(name, pid, marker):
            raise Failed(f"{name} (pid {pid}) did not stop")
        self.say("ok", name, "stopped")
        self.started.pop(name, None)

    def close(self) -> list:
        """Stops what this session started and restarts what ran before; returns the problems.
        A signal that arrives meanwhile is held until the restore is done."""
        if self.fd is None:
            return []
        held = []
        sigs = (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)
        # Custom handlers, unlike a blocked mask, are not inherited by the
        # services the restore starts.
        saved = {s: signal.signal(s, lambda n, _f: held.append(n)) for s in sigs}
        try:
            problems = self._close()
        finally:
            for s, h in saved.items():
                signal.signal(s, h)
        if held:
            self.say("warn", "signal", f"signal {held[0]} held until the restore was done")
            if held[0] == signal.SIGINT:
                raise KeyboardInterrupt
            sys.exit(128 + held[0])
        return problems

    def _close(self) -> list:
        problems = []
        untouched = set()
        for name in reversed(SLICE):
            pid, marker = stack.read_pid_marker(name)
            if not stack.alive(pid, marker):
                continue
            if any(p[0] == name and p[1] == pid for p in self.prior):
                untouched.add(name)  # begin() failed before it stopped this one
            elif self.started.get(name) == pid or (name in SERVICES and stack.recorded_db(name) == self.db):
                try:
                    self.stop(name)
                except Failed as e:
                    problems.append(str(e))
            else:
                problems.append(f"{name} (pid {pid}) was started by someone else during the run; left running")
        groups = {}
        for name, _pid, checkout, db in self.prior:
            if name in untouched:
                continue
            groups.setdefault((checkout, db), []).append(name)
        for (checkout, db), names in groups.items():
            env = dict(os.environ)
            env["CARDDEMO_E2E_SESSION"] = self.token
            env.pop("CARDDEMO_DB_NAME", None)
            if db:
                env["CARDDEMO_DB_NAME"] = db
            self.say("..", "restore", f"{','.join(names)} from {checkout}" + (f" on {db}" if db else ""))
            rc = subprocess.run([sys.executable, os.path.join(checkout, "scripts", "stack.py"), "up", "--only",
                                 ",".join(names)], cwd=checkout, env=env).returncode
            if rc != 0:
                problems.append(f"restoring {','.join(names)} from {checkout} failed; run make up ONLY="
                                f"{','.join(names)} there")
        self.unlock()
        return problems

    def __enter__(self):
        self.begin()
        return self

    def __exit__(self, *exc):
        problems = self.close()
        for p in problems:
            self.say("FAIL", "restore", p)
        if problems and exc[0] is None:
            raise Failed("restore: " + "; ".join(problems))
        return False

    # --- one fresh slice --------------------------------------------------

    def run(self, step, argv, cwd=HERE, env=None):
        print(f"==    {step:10} {' '.join(argv)}", flush=True)
        rc = subprocess.run(argv, cwd=cwd, env=env or self.env()).returncode
        if rc != 0:
            raise Failed(f"{step}: exited {rc}, see above")

    def fresh(self, reload=True):
        """The slice up on the e2e database, after db-reset and load unless reload is False."""
        for name in SERVICES:
            self.stop(name)
        if reload:
            make = os.environ.get("MAKE", "make")
            self.run("reload", [make, "--no-print-directory", "-C", HERE, "db-reset", "load"])
        else:
            self.say("warn", "reload", f"skipped: {self.db} keeps what earlier runs left")
        self.run("slice", [sys.executable, os.path.join(HERE, "scripts", "stack.py"), "up", "--only", ",".join(SLICE)])
        for name in SLICE:
            pid, marker = stack.read_pid_marker(name)
            if stack.alive(pid, marker):
                self.started[name] = pid


def install_signal_exit():
    """SIGTERM and SIGHUP end the run like SIGINT, so close() still runs."""
    for sig in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, lambda s, _f: sys.exit(128 + s))


def main(argv) -> int:
    args = argv[1:]
    name, browsers, reload = "test-e2e", ["chromium"], True
    while args:
        if args[0] == "--name" and len(args) > 1:
            name, args = args[1], args[2:]
        elif args[0] == "--browsers" and len(args) > 1:
            browsers, args = args[1].replace(",", " ").split() or ["chromium"], args[2:]
        elif args[0] == "--no-reload":
            reload, args = False, args[1:]
        elif args[0] == "--":
            args = args[1:]
            break
        else:
            break
    if not reload and (not args or len(browsers) != 1):
        print(f"{name}: FAILED (--no-reload, E2E_RELOAD=0, is a diagnostic run of one spec on one browser: "
              f"set E2E_SPEC and a single E2E_BROWSERS; the full target always reloads)")
        return 2
    install_signal_exit()
    start = time.monotonic()
    try:
        with Session(name) as s:
            npx = os.path.join(stack.node_bin(), "npx") if stack.node_bin() else "npx"
            for b in browsers:
                s.fresh(reload)
                s.run(f"e2e-{b}", [npx, "playwright", "test", f"--project={b}", *args],
                      cwd=os.path.join(HERE, "web"), env=s.env(CARDDEMO_E2E_TARGET="stack"))
    except Failed as e:
        print(f"{name}: FAILED ({e})")
        return 1
    except (KeyboardInterrupt, SystemExit):
        print(f"{name}: FAILED (interrupted; see the restore lines above)")
        raise
    data = f"a fresh {s.db} each" if reload else f"{s.db} as it was"
    print(f"{name}: OK ({' '.join(browsers)} on {data}, {time.monotonic() - start:.0f} s; what ran before is back)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
