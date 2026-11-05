"""tools/ci_summary.py: the CI job summary built from build/test-results."""
from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "ci_summary", Path(__file__).resolve().parents[2] / "tools" / "ci_summary.py")
cs = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = cs
_spec.loader.exec_module(cs)


class CiSummaryTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(prefix="ci-summary-"))
        self.addCleanup(shutil.rmtree, self.dir)

    def write(self, failed=0, flaky=False, coverage=87.0, **extra):
        (self.dir / "summary.json").write_text(json.dumps({
            "tests": 3, "passed": 3 - failed, "failed": failed, "skipped": 0, "flaky": int(flaky),
            "coverage_percent": coverage, "coverage_units": "187/215", "coverage_min": 85.0, **extra}))
        (self.dir / "coverage.json").write_text(json.dumps({"programs": {
            "CBTRN02C": {"covered": 27, "units": 27, "percent": 100.0},
            "CBSTM03A": {"covered": 0, "units": 26, "percent": 0.0}}}))
        (self.dir / "timings.json").write_text(json.dumps({
            "seed": 7, "workers": 2, "build_seconds": 1.25, "wall_seconds": 3.5, "tests": {
                "t.fast": {"seconds": 0.1, "status": "passed", "attempts": 1, "flaky": False},
                "t.slow": {"seconds": 2.0, "status": "failed" if failed else "passed",
                           "attempts": 3 if failed else 1, "flaky": False},
                "t.retry": {"seconds": 0.5, "status": "passed", "attempts": 2, "flaky": flaky}}}))

    def test_passing_run(self):
        self.write()
        text = cs.render(self.dir)
        self.assertTrue(text.startswith("## Tests: passed\n"))
        self.assertIn("| 3 | 3 | 0 | 0 | 0 | 87.0% (187/215) | 85.0% | 1.2s | 3.5s |", text)
        self.assertIn("| CBSTM03A | 0/26 | 0.0% |", text)
        self.assertNotIn("Failed or flaky", text)
        slow = text[text.index("### Slowest"):]
        self.assertLess(slow.index("t.slow"), slow.index("t.retry"))
        self.assertLess(slow.index("t.retry"), slow.index("t.fast"))

    def test_failed_and_flaky_tests_are_listed(self):
        self.write(failed=1, flaky=True)
        text = cs.render(self.dir)
        self.assertTrue(text.startswith("## Tests: FAILED\n"))
        self.assertIn("| `t.slow` | failed | 3 |", text)
        self.assertIn("| `t.retry` | flaky | 2 |", text)

    def test_low_coverage_fails_only_when_enforced(self):
        for extra, verdict in (({}, "FAILED"), ({"coverage_enforced": True}, "FAILED"),
                               ({"coverage_enforced": False}, "passed")):
            with self.subTest(extra=extra):
                self.write(coverage=11.6, **extra)
                self.assertTrue(cs.render(self.dir).startswith(f"## Tests: {verdict}\n"))

    def test_missing_results_exit_1(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            rc = cs.main([str(self.dir)])
        self.assertEqual(rc, 1)
        self.assertIn("no results", buf.getvalue())


if __name__ == "__main__":
    unittest.main()
