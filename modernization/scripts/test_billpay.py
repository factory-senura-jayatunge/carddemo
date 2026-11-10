#!/usr/bin/env python3
"""make test-billpay: every Bill Pay test, from the service to the browser.

Steps, in order; the first failure ends the run:
  0. manifest  build/test-billpay/manifest.json: the run's start time and the
               report files already on disk (verify/trace/tracelib/manifest.py)
  1. maven     ./mvnw verify for platform/ledger-core and billpay-service
  2. vitest    the Bill Pay page and number format tests
               (src/pages/BillPayPage.test.tsx, src/lib/format.test.ts)
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
web/test-results/e2e. A Chromium-only run writes
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
        run("maven", ["./mvnw", "-B", "verify", "-pl", "platform/ledger-core,services/billpay-service", "-am"])
        run("vitest", ["npm", "ci"], cwd=WEB)
        run("vitest", ["npx", "vitest", "run", *VITEST_FILES, "--reporter=default", "--reporter=junit",
                       "--outputFile.junit=" + os.path.join(RESULTS, "vitest-billpay-junit.xml")], cwd=WEB)
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
