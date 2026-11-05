"""tests/run_tests.py: which runs enforce the paragraph-coverage minimum."""
from __future__ import annotations

import unittest

from tests import run_tests, support

COV = {"percent": 11.6, "covered_units": 25, "total_units": 215, "programs_exercised": 1,
       "programs": dict.fromkeys(range(14))}


def args(*argv):
    return run_tests.build_parser(support.load_config()).parse_args(list(argv))


class CoverageGateTest(unittest.TestCase):
    def test_default_run_is_full(self):
        self.assertFalse(run_tests.is_filtered(args()))

    def test_k_pattern_and_suite_selection_are_filtered_runs(self):
        for argv in (["-k", "posttran"], ["--suite", "unit"], ["--suite", "integration", "-k", "abend"]):
            with self.subTest(argv=argv):
                self.assertTrue(run_tests.is_filtered(args(*argv)))
        self.assertFalse(run_tests.is_filtered(args("--suite", "all")))

    def test_full_run_below_minimum_fails(self):
        ok, lines = run_tests.coverage_verdict(COV, 85.0, filtered=False)
        self.assertFalse(ok)
        self.assertEqual(lines, ["COVERAGE: 25/215 program entries+paragraphs = 11.6% (minimum 85.0%) "
                                 "BELOW MINIMUM; 1/14 programs exercised"])

    def test_full_run_at_or_above_minimum_passes(self):
        for percent in (85.0, 87.0):
            with self.subTest(percent=percent):
                ok, lines = run_tests.coverage_verdict({**COV, "percent": percent}, 85.0, filtered=False)
                self.assertTrue(ok)
                self.assertIn("% (minimum 85.0%) OK;", lines[0])
                self.assertEqual(len(lines), 1)

    def test_filtered_run_reports_coverage_without_enforcing_it(self):
        ok, lines = run_tests.coverage_verdict(COV, 85.0, filtered=True)
        self.assertTrue(ok)
        self.assertIn("= 11.6% (minimum 85.0%) not enforced (filtered run);", lines[0])
        self.assertNotIn("BELOW MINIMUM", lines[0])
        self.assertEqual(lines[1], "NOTE: the coverage minimum applies only to full runs (no -k or --suite filter).")


if __name__ == "__main__":
    unittest.main()
