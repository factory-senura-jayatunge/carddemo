#!/usr/bin/env python3
"""Markdown summary of a test run (build/test-results) for the CI job page.

    python3 tools/ci_summary.py [RESULTS_DIR] >> "$GITHUB_STEP_SUMMARY"

Shows the test counts, paragraph coverage per program, build and test
wall time, the slowest tests and any flaky or failed tests. Exits 1 when
the results are missing, so a run that never produced them is visible.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def render(results: Path, slowest: int = 10) -> str:
    summary = json.loads((results / "summary.json").read_text())
    coverage = json.loads((results / "coverage.json").read_text())
    timings = json.loads((results / "timings.json").read_text())
    coverage_ok = (not summary.get("coverage_enforced", True)
                   or summary["coverage_percent"] >= summary["coverage_min"])
    ok = summary["failed"] == 0 and coverage_ok
    out = [f"## Tests: {'passed' if ok else 'FAILED'}", "",
           "| run | passed | failed | skipped | flaky | coverage | minimum | build | tests |",
           "|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
           f"| {summary['tests']} | {summary['passed']} | {summary['failed']} | {summary['skipped']} "
           f"| {summary['flaky']} | {summary['coverage_percent']:.1f}% ({summary['coverage_units']}) "
           f"| {summary['coverage_min']:.1f}% | {timings['build_seconds']:.1f}s "
           f"| {timings['wall_seconds']:.1f}s |", "",
           f"Seed {timings['seed']}, {timings['workers']} workers.", "",
           "### Paragraph coverage", "", "| program | covered | percent |", "|---|---:|---:|"]
    for name, p in coverage["programs"].items():
        out.append(f"| {name} | {p['covered']}/{p['units']} | {p['percent']:.1f}% |")
    tests = timings["tests"]
    bad = [(t, v) for t, v in tests.items() if v["status"] not in ("passed", "skipped") or v["flaky"]]
    if bad:
        out += ["", "### Failed or flaky", "", "| test | status | attempts |", "|---|---|---:|"]
        for t, v in sorted(bad):
            out.append(f"| `{t}` | {'flaky' if v['flaky'] else v['status']} | {v['attempts']} |")
    out += ["", f"### Slowest {min(slowest, len(tests))} tests", "", "| test | seconds |", "|---|---:|"]
    for t, v in sorted(tests.items(), key=lambda kv: -kv[1]["seconds"])[:slowest]:
        out.append(f"| `{t}` | {v['seconds']:.2f} |")
    return "\n".join(out) + "\n"


def main(argv: list[str]) -> int:
    results = Path(argv[0]) if argv else ROOT / "build" / "test-results"
    missing = [n for n in ("summary.json", "coverage.json", "timings.json") if not (results / n).exists()]
    if missing:
        print(f"## Tests: no results\n\n{results} has no {', '.join(missing)}; the test step did not finish.")
        return 1
    print(render(results), end="")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
