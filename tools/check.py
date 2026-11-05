#!/usr/bin/env python3
"""Run several make targets in order and summarise them (the `make check` aggregate).

Every step runs even when an earlier one fails, so one run shows the whole
picture. A step whose output contains a line starting with NOT PRESENT: is
reported as "not run": its tool is missing, which is a failure, not a pass.
Exit status: 0 when every step passed, 1 otherwise.
"""
from __future__ import annotations

import os
import subprocess
import sys
import time
from dataclasses import dataclass

NOT_PRESENT = "NOT PRESENT:"
# Lines from tests/run_tests.py that the summary repeats.
TEST_LINES = ("TESTS:", "COVERAGE:")


@dataclass
class Step:
    target: str
    rc: int
    seconds: float
    output: str

    @property
    def status(self) -> str:
        if any(line.startswith(NOT_PRESENT) for line in self.output.splitlines()):
            return "not run"
        return "ok" if self.rc == 0 else "FAILED"

    @property
    def detail(self) -> str:
        lines = self.output.splitlines()
        for line in lines:
            if line.startswith(NOT_PRESENT):
                return line[len(NOT_PRESENT):].strip()
        picked = [line.strip() for line in lines if line.startswith(TEST_LINES)]
        if picked:
            return "; ".join(picked)
        return f"exit {self.rc}" if self.rc else ""


def run_step(make: str, target: str) -> Step:
    print(f"==> make {target}", flush=True)
    start = time.monotonic()
    proc = subprocess.Popen([make, "--no-print-directory", target], stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, bufsize=1)
    captured = []
    assert proc.stdout is not None
    for line in proc.stdout:
        sys.stdout.write(line)
        captured.append(line)
    rc = proc.wait()
    return Step(target, rc, time.monotonic() - start, "".join(captured))


def summary(steps: list[Step]) -> tuple[str, int]:
    width = max(len(s.target) for s in steps)
    out = ["", "check summary:"]
    for s in steps:
        out.append(f"  {s.target:<{width}}  {s.status:<7}  {s.seconds:6.1f}s  {s.detail}".rstrip())
    failed = [s.target for s in steps if s.status == "FAILED"]
    not_run = [s.target for s in steps if s.status == "not run"]
    if not failed and not not_run:
        out.append("make check: OK")
        return "\n".join(out), 0
    parts = []
    if failed:
        parts.append("failed: " + ", ".join(failed))
    if not_run:
        parts.append("not run: " + ", ".join(not_run))
    out.append("make check: FAILED (" + "; ".join(parts) + ")")
    return "\n".join(out), 1


def main(argv: list[str]) -> int:
    if not argv:
        print("usage: check.py TARGET...", file=sys.stderr)
        return 2
    make = os.environ.get("MAKE", "make")
    steps = [run_step(make, t) for t in argv]
    text, rc = summary(steps)
    print(text)
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
