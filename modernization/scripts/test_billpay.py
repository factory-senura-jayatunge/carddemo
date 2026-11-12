#!/usr/bin/env python3
"""make test-billpay: every Bill Pay test, from the service to the browser.

Steps, in order; the first failure ends the run:
  0. manifest  build/test-billpay/manifest.json: the run's start time and the
               report files already on disk (verify/trace/tracelib/manifest.py)
  1. maven     ./mvnw verify for platform/ledger-core and billpay-service
  2. vitest    the Bill Pay page and number format tests
               (src/pages/BillPayPage.test.tsx, src/lib/format.test.ts), after
               npm ci and gen (node scripts/gen-validation.mjs: web/src/api/generated,
               which git ignores and the pages' imports need; e2e-mock's dev server
               serves the same files)
 2b. tc-wait   until the Testcontainers containers and networks that appeared on
               the Docker engine since step 1 began (Ryuk's own included) have
               stopped or gone; it only watches, never stops or removes anything
  3. e2e-mock  Playwright e2e/bill.spec.ts on the mocked API (Vite on 25174,
               so it never reuses the stack's dev server on 25173)
  4. stack     the slice on its own database (scripts/e2e_stack.py): db-reset
               and load of CARDDEMO_E2E_DB_NAME (carddemo_e2e), identity-service,
               billpay-service and web up on it
  5. e2e-stack Playwright e2e/bill.spec.ts against that slice; afterwards the
               slice is stopped and what ran before is started again
  6. trace     the traceability gate for the BILL unit, on the reports this run
               wrote only (trace_matrix.py --manifest), into build/test-billpay/trace

The demo database (CARDDEMO_DB_NAME) is never reset. Vitest writes its JUnit
report to build/test-results/billpay/; Playwright keeps its own output in
web/e2e-output/results. A Chromium-only run writes
web/playwright-report/e2e-<target>-<mode>.json; each Playwright step checks
that it wrote it. Run under heavy.py and with_env.py, as make test-billpay does.

Ends with "test-billpay: OK" or "test-billpay: FAILED (<step>: <reason>)".
"""

import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import e2e_stack  # noqa: E402
import stack  # noqa: E402

HERE = stack.HERE
sys.path.insert(0, os.path.join(HERE, "verify", "trace"))
from tracelib import manifest  # noqa: E402

WEB = os.path.join(HERE, "web")
RESULTS = os.path.join(HERE, "build", "test-results", "billpay")
OUT = os.path.join(HERE, "build", "test-billpay")
MANIFEST = os.path.join(OUT, "manifest.json")
TRACE = os.path.join(HERE, "verify", "trace", "trace_matrix.py")
MOCK_PORT = "25174"
VITEST_FILES = ["src/pages/BillPayPage.test.tsx", "src/lib/format.test.ts"]
# The mock answers in fixed mode; the stack in CARDDEMO_E2E_MODE (fixed by default).
STACK_MODE = "legacy" if os.environ.get("CARDDEMO_E2E_MODE") == "legacy" else "fixed"
# What the BILL gate reads, relative to modernization/: (path, required).
REPORTS = [
    ("platform/ledger-core/target/spec-items", True),
    ("services/billpay-service/target/spec-items", True),
    ("build/test-results/billpay/vitest-billpay-junit.xml", True),
    ("web/playwright-report/e2e-mock-fixed.json", True),
    (f"web/playwright-report/e2e-stack-{STACK_MODE}.json", True),
    ("platform/test-support/target/spec-items", False),
]


class StepFailed(Exception):
    pass


# Testcontainers labels every container and network it creates, Ryuk's own included.
TC_LABEL = "org.testcontainers=true"
# Ryuk (testcontainers/ryuk 0.14.0 with Testcontainers 2.0.5) removes a JVM's containers and
# networks RYUK_RECONNECTION_TIMEOUT (10 s) after that JVM's connection closes, then removes
# itself. A removal is retried every RYUK_CHANGES_RETRY_INTERVAL (1 s), up to RYUK_REMOVE_RETRIES
# (10) times, each Docker request bounded by RYUK_REQUEST_TIMEOUT (10 s): about 30 s at worst.
TC_GONE_LIMIT = 60.0
TC_POLL = 0.5
DOCKER_TIMEOUT = 20
# Container states that hold no network interface any more.
TC_DOWN_STATES = {"exited", "dead", "created"}


class DockerError(Exception):
    pass


def docker_lines(argv, env, runner=subprocess.run) -> list:
    try:
        p = runner(["docker", *argv], env=env, capture_output=True, text=True, timeout=DOCKER_TIMEOUT)
    except (OSError, subprocess.TimeoutExpired) as e:
        raise DockerError(f"docker {' '.join(argv[:2])}: {type(e).__name__}")
    if p.returncode != 0:
        first = (p.stderr or "").strip().splitlines()
        raise DockerError(f"docker {' '.join(argv[:2])} exited {p.returncode}: {first[0][:200] if first else 'no message'}")
    return [line for line in p.stdout.splitlines() if line.strip()]


def tc_resources(env, runner=subprocess.run) -> dict:
    """Testcontainers' containers and networks on the engine: 'container <id>' or 'network <id>' -> (state, name)."""
    found = {}
    for line in docker_lines(["ps", "-a", "--no-trunc", "--filter", "label=" + TC_LABEL,
                              "--format", "{{.ID}}\t{{.State}}\t{{.Names}}"], env, runner):
        ident, state, name = (line.split("\t") + ["", ""])[:3]
        found["container " + ident] = (state, name)
    for line in docker_lines(["network", "ls", "--no-trunc", "--filter", "label=" + TC_LABEL,
                              "--format", "{{.ID}}\t{{.Name}}"], env, runner):
        ident, name = (line.split("\t") + [""])[:2]
        found["network " + ident] = ("present", name)
    return found


class TestcontainersBarrier:
    """Waits until what Testcontainers started during Maven has stopped or gone.

    Ryuk removes a JVM's SQL Server container, and then itself, about 10 s after Maven ends,
    which can be while Playwright loads its first pages. When Docker deletes their interfaces,
    a Chromium on the same host aborts the requests in flight with net::ERR_NETWORK_CHANGED;
    Vite's module graph breaks and the page stays blank.

    Docker gives no way to tell which Testcontainers session a Maven run used, so what counts
    is what appeared on the engine (the docker CLI with Maven's environment) since before()
    ran. Another run on the same engine at the same time is waited for too, and reported by
    ID when it outlives TC_GONE_LIMIT.
    """

    def __init__(self, env, runner=subprocess.run, clock=time.monotonic, sleep=time.sleep, limit=TC_GONE_LIMIT):
        self.env, self.runner, self.clock, self.sleep, self.limit = env, runner, clock, sleep, limit
        self.known = None
        self.before_error = None

    def before(self) -> None:
        try:
            self.known = set(tc_resources(self.env, self.runner))
        except DockerError as e:
            self.known, self.before_error = None, str(e)

    def wait(self) -> str:
        start = self.clock()
        seen = set()
        while True:
            try:
                now = tc_resources(self.env, self.runner)
            except DockerError as e:
                if self.before_error is not None:
                    return f"Docker not reachable before or after maven ({e}), so no Testcontainers to wait for"
                raise StepFailed(f"tc-wait: cannot list Testcontainers containers and networks: {e}")
            new = {key: value for key, value in now.items() if self.known is None or key not in self.known}
            up = {key: (state, name) for key, (state, name) in new.items()
                  if key.startswith("network ") or state not in TC_DOWN_STATES}
            seen |= set(up)
            elapsed = self.clock() - start
            if not up:
                scope = "on the engine" if self.known is None else "new since maven began"
                return (f"{len(seen)} Testcontainers container(s) and network(s) {scope} were up; "
                        f"all stopped or gone after {elapsed:.1f} s")
            if elapsed >= self.limit:
                listed = ", ".join(f"{key.split()[0]} {key.split()[1][:12]} {name or '?'} ({state})"
                                   for key, (state, name) in sorted(up.items()))
                raise StepFailed(f"tc-wait: still up after {self.limit:.0f} s: {listed}; "
                                 "nothing was stopped (another run on this Docker engine can also own them)")
            self.sleep(TC_POLL)


def env_with_node(extra=None) -> dict:
    env = dict(os.environ)
    node = stack.node_bin()
    if node:
        env["PATH"] = node + os.pathsep + env.get("PATH", "")
    env.update(extra or {})
    return env


def run(step, argv, cwd=HERE, env=None) -> None:
    print(f"==    {step:10} {' '.join(argv)}", flush=True)
    rc = subprocess.run(argv, cwd=cwd, env=env or env_with_node()).returncode
    if rc != 0:
        raise StepFailed(f"{step}: exited {rc}, see above")


def playwright(step, target, env) -> None:
    since = time.time_ns()
    run(step, ["npx", "playwright", "test", "e2e/bill.spec.ts", "--project=chromium"], cwd=WEB, env=env)
    mode = STACK_MODE if target == "stack" else "fixed"
    report = os.path.join(WEB, "playwright-report", f"e2e-{target}-{mode}.json")
    if not os.path.exists(report) or os.stat(report).st_mtime_ns < since:
        raise StepFailed(f"{step}: Playwright wrote no {os.path.relpath(report, HERE)}")


def trace_bill() -> None:
    argv = [sys.executable, TRACE, "--unit", "BILL", "--manifest", MANIFEST, "--out", os.path.join(OUT, "trace")]
    print(f"==    {'trace':10} {' '.join(os.path.relpath(a, HERE) if a.startswith(HERE) else a for a in argv[1:])}",
          flush=True)
    if subprocess.run(argv, cwd=HERE).returncode != 0:
        raise StepFailed("trace: BILL gaps, see above")


def main() -> int:
    e2e_stack.install_signal_exit()
    start = time.monotonic()
    try:
        # Before any step writes a report, so that only this run's reports count.
        manifest.begin(Path(MANIFEST), Path(HERE).parent, "test-billpay", REPORTS)
        barrier = TestcontainersBarrier(env_with_node())
        barrier.before()
        run("maven", ["./mvnw", "-B", "verify", "-pl", "platform/ledger-core,services/billpay-service", "-am"])
        run("vitest", ["npm", "ci"], cwd=WEB)
        run("gen", ["node", stack.GEN_VALIDATION], cwd=WEB)
        run("vitest", ["npx", "vitest", "run", *VITEST_FILES, "--reporter=default", "--reporter=junit",
                       "--outputFile.junit=" + os.path.join(RESULTS, "vitest-billpay-junit.xml")], cwd=WEB)
        print(f"==    {'tc-wait':10} Testcontainers from maven, on the engine maven's environment names", flush=True)
        print(f"      {'tc-wait':10} {barrier.wait()}", flush=True)
        playwright("e2e-mock", "mock", env_with_node({"CARDDEMO_WEB_PORT": MOCK_PORT}))
        try:
            with e2e_stack.Session("test-billpay") as s:
                print(f"==    {'stack':10} the slice on {s.db}", flush=True)
                s.fresh()
                playwright("e2e-stack", "stack", s.env(CARDDEMO_E2E_TARGET="stack"))
        except e2e_stack.Failed as e:
            raise StepFailed(f"stack: {e}")
        trace_bill()
    except StepFailed as e:
        print(f"test-billpay: FAILED ({e})")
        return 1
    except (KeyboardInterrupt, SystemExit):
        print("test-billpay: FAILED (interrupted)")
        raise
    print(f"test-billpay: OK ({time.monotonic() - start:.0f} s; reports in {os.path.relpath(RESULTS, HERE)}, "
          f"gate in {os.path.relpath(OUT, HERE)}/trace)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
