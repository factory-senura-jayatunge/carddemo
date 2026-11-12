"""Run both independent validator suites without a database or adapter.

From the repository root:
    python3 -m modernization.verify.spec_jobs.offline_tests
"""
from __future__ import annotations

import sys
import unittest

from .layouts import ROOT


class EmptySuiteError(ValueError):
    pass


def load_suite() -> unittest.TestSuite:
    loader = unittest.TestLoader()
    suites = []
    for path in ("modernization/verify/spec_jobs/tests", "modernization/verify/data/oracle/tests"):
        suite = loader.discover(str(ROOT / path), top_level_dir=str(ROOT))
        if not suite.countTestCases():
            raise EmptySuiteError(f"No offline validator tests discovered in {path}.")
        suites.append(suite)
    return unittest.TestSuite(suites)


def main() -> int:
    try:
        suite = load_suite()
    except EmptySuiteError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    if not suite.countTestCases():
        print("No offline validator tests discovered.", file=sys.stderr)
        return 1
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() and not result.skipped else 1


if __name__ == "__main__":
    raise SystemExit(main())
