#!/usr/bin/env python3
"""Run the CardDemo test suite.

  * discovers tests/unit/test_*.py and tests/integration/test_*.py and fails
    on files or names that break the conventions in tests/test-config.toml
  * builds the -ftrace variant and the COBOL test drivers first
  * runs test methods in parallel threads (each test has its own temp dir),
    in shuffled order with a printed seed
  * retries a failing test; a test that fails and then passes is reported
    as FLAKY (not hidden), a test that fails every attempt fails the run
  * writes JUnit XML with per-test timings, a timing report, and paragraph
    coverage; fails if coverage is below the configured minimum

Outputs go to build/test-results/ (override with --results).
"""
from __future__ import annotations

import argparse
import concurrent.futures
import fnmatch
import json
import os
import random
import sys
import tempfile
import time
import traceback
import unittest
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tools"))

import carddemo  # noqa: E402
from tests import paracov, support  # noqa: E402


@dataclass
class Outcome:
    test_id: str
    suite: str
    status: str = "passed"          # passed | failed | error | skipped
    attempts: list[tuple[str, float, str]] = field(default_factory=list)  # (status, seconds, detail)

    @property
    def seconds(self) -> float:
        return sum(a[1] for a in self.attempts)

    @property
    def flaky(self) -> bool:
        return self.status == "passed" and len(self.attempts) > 1


def naming_violations(config: dict) -> list[str]:
    naming = config["naming"]
    problems = []
    for suite in naming["suites"]:
        for path in sorted((ROOT / "tests" / suite).glob("*.py")):
            if path.name in naming["helpers"]:
                continue
            if not fnmatch.fnmatch(path.name, naming["python_module_pattern"]):
                problems.append(f"{path.relative_to(ROOT)}: test modules must match {naming['python_module_pattern']}")
    for path in sorted((ROOT / "tests").rglob("*.cbl")):
        if not fnmatch.fnmatch(path.relative_to(ROOT / "tests").as_posix(), naming["cobol_driver_glob"]):
            problems.append(f"{path.relative_to(ROOT)}: COBOL test drivers must match tests/{naming['cobol_driver_glob']}")
    return problems


def iter_cases(suite: unittest.TestSuite):
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            yield from iter_cases(item)
        else:
            yield item


def discover(config: dict, suites: list[str]) -> tuple[list[tuple[str, unittest.TestCase]], list[str]]:
    naming = config["naming"]
    loader = unittest.TestLoader()
    loader.testMethodPrefix = naming["method_prefix"].rstrip("_")
    cases, problems = [], []
    for suite in suites:
        found = loader.discover(str(ROOT / "tests" / suite), pattern=naming["python_module_pattern"],
                                top_level_dir=str(ROOT))
        for case in iter_cases(found):
            if isinstance(case, unittest.loader._FailedTest):  # import error in a test module
                cases.append((suite, case))
                continue
            cls = type(case).__name__
            if not cls.endswith(naming["class_suffix"]):
                problems.append(f"{case.id()}: test classes must end with {naming['class_suffix']!r}")
            if not case._testMethodName.startswith(naming["method_prefix"]):
                problems.append(f"{case.id()}: test methods must start with {naming['method_prefix']!r}")
            cases.append((suite, case))
    return cases, problems


def run_once(case: unittest.TestCase) -> tuple[str, float, str]:
    fresh = type(case)(case._testMethodName)
    result = unittest.TestResult()
    start = time.monotonic()
    fresh.run(result)
    seconds = time.monotonic() - start
    # subTest failures each add an entry, so report all of them.
    if result.errors:
        return "error", seconds, "\n".join(f"{t}\n{tb}" for t, tb in result.errors + result.failures)
    if result.failures:
        return "failed", seconds, "\n".join(f"{t}\n{tb}" for t, tb in result.failures)
    if result.skipped:
        return "skipped", seconds, result.skipped[0][1]
    if result.unexpectedSuccesses:
        return "failed", seconds, "unexpected success"
    return "passed", seconds, ""


def run_case(suite: str, case: unittest.TestCase, retries: int) -> Outcome:
    outcome = Outcome(case.id(), suite)
    for _ in range(retries + 1):
        try:
            status, seconds, detail = run_once(case)
        except Exception:  # a crash in the harness itself still counts as an error
            status, seconds, detail = "error", 0.0, traceback.format_exc()
        outcome.attempts.append((status, seconds, detail))
        if status in ("passed", "skipped"):
            break
    outcome.status = outcome.attempts[-1][0]
    return outcome


def write_junit(outcomes: list[Outcome], path: Path, wall: float) -> None:
    root = ET.Element("testsuites", name="carddemo", time=f"{wall:.3f}")
    by_module: dict[str, list[Outcome]] = {}
    for o in outcomes:
        by_module.setdefault(o.test_id.rsplit(".", 2)[0], []).append(o)
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    for module, items in sorted(by_module.items()):
        counts = {
            "tests": len(items),
            "failures": sum(o.status == "failed" for o in items),
            "errors": sum(o.status == "error" for o in items),
            "skipped": sum(o.status == "skipped" for o in items),
        }
        for k in totals:
            totals[k] += counts[k]
        ts = ET.SubElement(root, "testsuite", name=module, time=f"{sum(o.seconds for o in items):.3f}",
                           **{k: str(v) for k, v in counts.items()})
        for o in sorted(items, key=lambda x: x.test_id):
            cls, name = o.test_id.rsplit(".", 1)
            tc = ET.SubElement(ts, "testcase", classname=cls, name=name, time=f"{o.seconds:.3f}")
            props = ET.SubElement(tc, "properties")
            ET.SubElement(props, "property", name="attempts", value=str(len(o.attempts)))
            ET.SubElement(props, "property", name="flaky", value=str(o.flaky).lower())
            ET.SubElement(props, "property", name="suite", value=o.suite)
            last_detail = o.attempts[-1][2]
            if o.status == "failed":
                ET.SubElement(tc, "failure", message="failed").text = last_detail
            elif o.status == "error":
                ET.SubElement(tc, "error", message="error").text = last_detail
            elif o.status == "skipped":
                ET.SubElement(tc, "skipped", message=last_detail)
            if o.flaky:
                ET.SubElement(tc, "system-out").text = "FLAKY: failed then passed on retry\n" + "\n".join(
                    f"attempt {i + 1}: {s} ({t:.2f}s)\n{d}" for i, (s, t, d) in enumerate(o.attempts))
    for k, v in totals.items():
        root.set(k, str(v))
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)


def coverage(trace_dir: Path) -> dict:
    manifest = carddemo.load_manifest()
    programs = {p["name"]: ROOT / p["source"] for p in manifest["program"]}
    return paracov.report(paracov.inventory(programs), paracov.reached(sorted(trace_dir.glob("*.trace"))))


def main(argv: list[str] | None = None) -> int:
    config = support.load_config()
    parser = argparse.ArgumentParser(description="Run the CardDemo test suite")
    parser.add_argument("--suite", choices=["unit", "integration", "all"], default="all")
    parser.add_argument("-j", "--jobs", type=int, default=int(os.environ.get("CARDDEMO_TEST_JOBS", 0)) or os.cpu_count() or 2)
    parser.add_argument("--retries", type=int, default=int(os.environ.get("CARDDEMO_TEST_RETRIES", config["run"]["retries"])))
    parser.add_argument("--seed", type=int, default=int(os.environ.get("CARDDEMO_TEST_SEED", 0)) or None)
    parser.add_argument("--coverage-min", type=float,
                        default=float(os.environ.get("CARDDEMO_COVERAGE_MIN", config["coverage"]["min_percent"])))
    parser.add_argument("--fail-on-flaky", action="store_true", default=os.environ.get("CARDDEMO_FAIL_ON_FLAKY") == "1")
    parser.add_argument("-k", dest="pattern", help="only run tests whose id contains this text")
    parser.add_argument("--results", type=Path, default=carddemo.build_root() / "test-results")
    args = parser.parse_args(argv)

    suites = config["naming"]["suites"] if args.suite == "all" else [args.suite]
    results_dir = args.results
    results_dir.mkdir(parents=True, exist_ok=True)

    problems = naming_violations(config)
    t0 = time.monotonic()
    try:
        carddemo.build("trace", verbose=True)
        support.build_drivers("trace")
    except carddemo.CardDemoError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    build_seconds = time.monotonic() - t0
    cases, discover_problems = discover(config, suites)
    problems += discover_problems
    if args.pattern:
        cases = [(s, c) for s, c in cases if args.pattern in c.id()]
    seed = args.seed if args.seed is not None else random.randrange(1, 10**6)
    if config["run"]["shuffle"]:
        random.Random(seed).shuffle(cases)

    with tempfile.TemporaryDirectory(prefix="carddemo-trace-") as trace_tmp:
        os.environ["CARDDEMO_TRACE_DIR"] = trace_tmp
        os.environ["CARDDEMO_TEST_VARIANT"] = "trace"
        support._built.add("trace")
        print(f"running {len(cases)} tests ({', '.join(suites)}) with {args.jobs} workers, "
              f"seed {seed}, retries {args.retries}")
        started = time.monotonic()
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
            futures = [pool.submit(run_case, s, c, args.retries) for s, c in cases]
            outcomes = [f.result() for f in futures]
        wall = time.monotonic() - started
        cov = coverage(Path(trace_tmp))

    write_junit(outcomes, results_dir / "junit.xml", wall)
    passed = [o for o in outcomes if o.status == "passed"]
    failed = [o for o in outcomes if o.status in ("failed", "error")]
    skipped = [o for o in outcomes if o.status == "skipped"]
    flaky = [o for o in outcomes if o.flaky]

    for o in failed:
        print(f"\nFAIL {o.test_id} ({len(o.attempts)} attempts)\n{o.attempts[-1][2]}")
    for o in flaky:
        print(f"\nFLAKY {o.test_id}: failed {len(o.attempts) - 1}x, then passed\n{o.attempts[0][2]}")

    slowest = sorted(outcomes, key=lambda o: -o.seconds)[:8]
    print("\nslowest tests:")
    for o in slowest:
        print(f"  {o.seconds:6.2f}s {o.test_id}")
    (results_dir / "timings.json").write_text(json.dumps({
        "seed": seed, "workers": args.jobs, "build_seconds": round(build_seconds, 3),
        "wall_seconds": round(wall, 3),
        "tests": {o.test_id: {"seconds": round(o.seconds, 3), "status": o.status,
                              "attempts": len(o.attempts), "flaky": o.flaky} for o in outcomes},
    }, indent=2) + "\n")
    (results_dir / "flaky.json").write_text(json.dumps(
        [{"test": o.test_id, "attempts": [a[0] for a in o.attempts]} for o in flaky], indent=2) + "\n")
    (results_dir / "coverage.json").write_text(json.dumps(cov, indent=2) + "\n")

    print("\nparagraph coverage by program:")
    for name, p in cov["programs"].items():
        print(f"  {name:<9} {p['covered']:>3}/{p['units']:<3} {p['percent']:5.1f}%")
    if cov["unknown_trace_names"]:
        problems.append(f"trace names missing from the paragraph inventory: {cov['unknown_trace_names']}")

    coverage_ok = cov["percent"] >= args.coverage_min
    for p in problems:
        print(f"CONVENTION/INVENTORY ERROR: {p}")
    summary = {
        "tests": len(outcomes), "passed": len(passed), "failed": len(failed),
        "skipped": len(skipped), "flaky": len(flaky),
        "coverage_percent": cov["percent"], "coverage_units": f"{cov['covered_units']}/{cov['total_units']}",
        "coverage_min": args.coverage_min, "wall_seconds": round(wall, 2),
    }
    (results_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(f"\nTESTS: {len(outcomes)} run, {len(passed)} passed, {len(failed)} failed, "
          f"{len(skipped)} skipped, {len(flaky)} flaky in {wall:.1f}s (seed {seed})")
    print(f"COVERAGE: {cov['covered_units']}/{cov['total_units']} program entries+paragraphs = "
          f"{cov['percent']:.1f}% (minimum {args.coverage_min:.1f}%) "
          f"{'OK' if coverage_ok else 'BELOW MINIMUM'}; "
          f"{cov['programs_exercised']}/{len(cov['programs'])} programs exercised")
    print(f"JUnit XML: {results_dir / 'junit.xml'}")
    ok = not failed and not problems and coverage_ok and outcomes and not (flaky and args.fail_on_flaky)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
