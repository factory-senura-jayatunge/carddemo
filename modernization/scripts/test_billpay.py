#!/usr/bin/env python3
"""make test-billpay: every Bill Pay test, from the service to the browser.

Steps, in order; the first failure ends the run:
  1. maven     ./mvnw verify for platform/ledger-core and billpay-service
  2. vitest    the Bill Pay page tests (src/pages/BillPayPage.test.tsx)
  3. e2e-mock  Playwright e2e/bill.spec.ts on the mocked API (Vite on 25174,
               so it never reuses the stack's dev server on 25173)
  4. stack     make db-reset load, build the services, start identity-service,
               billpay-service and web (up --only)
  5. e2e-stack Playwright e2e/bill.spec.ts against the running slice
  6. trace     the traceability gate for the BILL unit

Playwright empties web/test-results on every run, so its JUnit report is
copied to build/test-results/billpay/ after each Playwright step. Run under
heavy.py and with_env.py, as make test-billpay does. The slice stays up.

Ends with "test-billpay: OK" or "test-billpay: FAILED (<step>: <reason>)".
"""

import json
import os
import shutil
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import stack  # noqa: E402

HERE = stack.HERE
WEB = os.path.join(HERE, "web")
RESULTS = os.path.join(HERE, "build", "test-results", "billpay")
PLAYWRIGHT_JUNIT = os.path.join(WEB, "test-results", "e2e-junit.xml")
TRACE = os.path.join(HERE, "verify", "trace", "trace_matrix.py")
MOCK_PORT = "25174"
SLICE = "identity-service,billpay-service,web"


class StepFailed(Exception):
    pass


def env_with_node(extra=None) -> dict:
    env = dict(os.environ)
    node = stack.node_bin()
    if node:
        env["PATH"] = node + os.pathsep + env.get("PATH", "")
    env.update(extra or {})
    return env


def run(step, argv, cwd=HERE, extra=None) -> None:
    print(f"==    {step:10} {' '.join(argv)}", flush=True)
    rc = subprocess.run(argv, cwd=cwd, env=env_with_node(extra)).returncode
    if rc != 0:
        raise StepFailed(f"{step}: exited {rc}, see above")


def keep_playwright_junit(name) -> None:
    if not os.path.exists(PLAYWRIGHT_JUNIT):
        raise StepFailed(f"{name}: Playwright wrote no {os.path.relpath(PLAYWRIGHT_JUNIT, HERE)}")
    os.makedirs(RESULTS, exist_ok=True)
    shutil.copyfile(PLAYWRIGHT_JUNIT, os.path.join(RESULTS, f"{name}-junit.xml"))


def trace_supports_unit() -> bool:
    out = subprocess.run([sys.executable, TRACE, "--help"], capture_output=True, text=True)
    return "--unit" in out.stdout


def trace_bill() -> None:
    print(f"==    {'trace':10} {os.path.relpath(TRACE, HERE)}", flush=True)
    if trace_supports_unit():
        if subprocess.run([sys.executable, TRACE, "--unit", "BILL"], cwd=HERE).returncode != 0:
            raise StepFailed("trace: BILL gaps, see above")
        return
    # Until trace_matrix.py has --unit, its gate covers every unit in scope;
    # only the BILL items count here.
    subprocess.run([sys.executable, TRACE], cwd=HERE, capture_output=True, text=True)
    matrix = os.path.join(HERE, "build", "trace", "matrix.json")
    try:
        with open(matrix, encoding="utf-8") as f:
            gaps = json.load(f)["gate"]["gaps"]
    except (OSError, ValueError, KeyError) as e:
        raise StepFailed(f"trace: cannot read {matrix}: {e}")
    bill = [g for g in gaps if "-BILL-" in g["id"]]
    for g in bill[:50]:
        print(f"  {g['id']}: {g['reason']}")
    if bill:
        raise StepFailed(f"trace: {len(bill)} BILL gap(s)")
    print("trace: OK (no BILL gaps)")


def main() -> int:
    start = time.monotonic()
    make = os.environ.get("MAKE", "make")
    try:
        run("maven", ["./mvnw", "-B", "verify", "-pl", "platform/ledger-core,services/billpay-service", "-am"])
        run("vitest", ["npm", "ci"], cwd=WEB)
        run("vitest", ["npx", "vitest", "run", "src/pages/BillPayPage.test.tsx", "--reporter=default",
                       "--reporter=junit", "--outputFile.junit=test-results/vitest-billpay-junit.xml"], cwd=WEB)
        run("e2e-mock", ["npx", "playwright", "test", "e2e/bill.spec.ts", "--project=chromium"], cwd=WEB,
            extra={"CARDDEMO_WEB_PORT": MOCK_PORT})
        keep_playwright_junit("e2e-mock")
        run("stack", [make, "--no-print-directory", "db-reset", "load"])
        run("stack", ["./mvnw", "-q", "-B", "-pl", "services/identity-service,services/billpay-service", "-am",
                      "package", "-DskipTests"])
        run("stack", [sys.executable, os.path.join(HERE, "scripts", "stack.py"), "up", "--only", SLICE])
        run("e2e-stack", ["npx", "playwright", "test", "e2e/bill.spec.ts", "--project=chromium"], cwd=WEB,
            extra={"CARDDEMO_E2E_TARGET": "stack"})
        keep_playwright_junit("e2e-stack")
        trace_bill()
    except StepFailed as e:
        print(f"test-billpay: FAILED ({e})")
        return 1
    print(f"test-billpay: OK ({time.monotonic() - start:.0f} s; reports in {os.path.relpath(RESULTS, HERE)}; the slice stays up)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
