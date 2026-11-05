"""tools/check.py: the `make check` summary and exit status."""
from __future__ import annotations

import importlib.util
import io
import sys
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "check_tool", Path(__file__).resolve().parents[2] / "tools" / "check.py")
check = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = check  # dataclasses look the module up while building Step
_spec.loader.exec_module(check)


def step(target, rc, output=""):
    return check.Step(target, rc, 1.0, output)


class CheckSummaryTest(unittest.TestCase):
    def test_all_passing_steps_exit_zero(self):
        text, rc = check.summary([step("build", 0), step("test", 0, "TESTS: 3 run\nCOVERAGE: 90%\n")])
        self.assertEqual(rc, 0)
        self.assertIn("make check: OK", text)
        self.assertRegex(text, r"test +ok .*TESTS: 3 run; COVERAGE: 90%")

    def test_missing_tool_is_not_run_and_fails_the_check(self):
        missing = step("lint", 2, "NOT PRESENT: lint tool not present (scripts/lint.sh)\n")
        self.assertEqual(missing.status, "not run")
        self.assertEqual(missing.detail, "lint tool not present (scripts/lint.sh)")
        text, rc = check.summary([missing, step("build", 0)])
        self.assertEqual(rc, 1)
        self.assertIn("make check: FAILED (not run: lint)", text)

    def test_failed_and_not_run_are_both_listed(self):
        text, rc = check.summary([step("lint", 2, "NOT PRESENT: x\n"), step("build", 2), step("test", 0)])
        self.assertEqual(rc, 1)
        self.assertIn("make check: FAILED (failed: build; not run: lint)", text)
        self.assertRegex(text, r"build +FAILED .*exit 2")

    def test_runs_every_target_even_after_a_failure(self):
        ran = []

        def fake(make, target):
            ran.append(target)
            return step(target, 2 if target == "lint" else 0)

        original = check.run_step
        check.run_step = fake
        try:
            rc = check.main(["lint", "build", "test"], out=io.StringIO())
        finally:
            check.run_step = original
        self.assertEqual((ran, rc), (["lint", "build", "test"], 1))

    def test_no_targets_is_a_usage_error(self):
        self.assertEqual(check.main([], err=io.StringIO()), 2)


if __name__ == "__main__":
    unittest.main()
