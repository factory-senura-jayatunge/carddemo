#!/usr/bin/env python3
"""Verify the natural-language spec: format, coverage and equivalence checks.

Runs check_spec.py on the whole spec, builds the GnuCOBOL programs, then runs
every checks/*_reimpl/run_check.py on the sample data and on its stress data.
Prints one line per step and a summary; exits 1 if any step fails.

Usage: python3 docs/modernization/spec/tools/verify_spec.py [--require-all] [--skip-build]
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

SPEC = Path(__file__).resolve().parent.parent
REPO = SPEC.parents[2]
STEP_TIMEOUT = 600


def run(name: str, cmd: list[str], results: list[tuple[str, bool, float, str]]) -> bool:
    print(f"== {name}: {' '.join(cmd)}", flush=True)
    start = time.monotonic()
    try:
        proc = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True, timeout=STEP_TIMEOUT)
        out, ok = proc.stdout + proc.stderr, proc.returncode == 0
        detail = f"exit {proc.returncode}"
    except subprocess.TimeoutExpired as exc:
        parts = [p.decode(errors="replace") if isinstance(p, bytes) else p for p in (exc.stdout, exc.stderr) if p]
        out, ok = "".join(parts), False
        detail = f"timed out after {STEP_TIMEOUT}s"
    seconds = time.monotonic() - start
    lines = out.rstrip().splitlines()
    # A passing step prints its last lines only; a failing one prints everything.
    for line in (lines if not ok else lines[-3:]):
        print(f"   {line}")
    prefixes = ("COVERAGE", "OVERALL", "build[") if ok else ("check_spec:", "OVERALL", "build[")
    summary = next((l for l in reversed(lines) if l.startswith(prefixes)), detail)
    results.append((name, ok, seconds, summary if ok else f"{detail}; {summary}"))
    return ok


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--require-all", action="store_true",
                    help="also fail when an in-scope program or job has no spec")
    ap.add_argument("--skip-build", action="store_true", help="do not run make build first")
    args = ap.parse_args()
    py = sys.executable
    results: list[tuple[str, bool, float, str]] = []

    check = [py, str(SPEC / "tools" / "check_spec.py")]
    if args.require_all:
        check.append("--require-all")
    run("check_spec" + (" --require-all" if args.require_all else ""), check, results)

    checks = sorted((SPEC / "checks").glob("*_reimpl/run_check.py"))
    built = True
    if checks and not args.skip_build:
        built = run("build GnuCOBOL", ["make", "-C", str(REPO), "build"], results)
    for script in checks:
        name = script.parent.name
        for label, extra in (("sample", []), ("stress", ["--stress"])):
            if not built:
                results.append((f"{name} {label}", False, 0.0, "not run: build failed"))
                continue
            run(f"{name} {label}", [py, str(script), *extra], results)
    if not checks:
        results.append(("equivalence checks", False, 0.0, f"none found under {SPEC / 'checks'}"))

    print("\nverify-spec summary:")
    for name, ok, seconds, detail in results:
        print(f"  {name:<28} {'ok' if ok else 'FAILED':<7} {seconds:6.1f}s  {detail}")
    failed = [r for r in results if not r[1]]
    print(f"verify-spec: {'OK' if not failed else f'FAILED ({len(failed)} of {len(results)} steps)'}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
