from io import StringIO
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from modernization.verify.spec_jobs import offline_tests


class OfflineEntryPointTest(unittest.TestCase):
    def test_discovers_both_owned_suites(self):
        def names(suite):
            for child in suite:
                if isinstance(child, unittest.TestSuite):
                    yield from names(child)
                else:
                    yield child.id()
        ids = list(names(offline_tests.load_suite()))
        self.assertTrue(any(name.startswith("modernization.verify.spec_jobs.tests.") for name in ids))
        self.assertTrue(any(name.startswith("modernization.verify.data.oracle.tests.") for name in ids))

    def test_empty_suite_is_not_a_pass(self):
        with patch.object(offline_tests, "load_suite", return_value=unittest.TestSuite()), \
                patch("sys.stderr", new_callable=StringIO):
            self.assertEqual(offline_tests.main(), 1)

    def test_empty_oracle_directory_is_refused_from_outside_that_directory(self):
        nonempty = unittest.TestSuite([unittest.FunctionTestCase(lambda: None)])
        with patch.object(offline_tests.unittest.TestLoader, "discover",
                          side_effect=[nonempty, unittest.TestSuite()]) as discover, \
                patch.object(offline_tests.unittest, "TextTestRunner") as runner, \
                patch("sys.stderr", new_callable=StringIO) as diagnostic:
            self.assertEqual(offline_tests.main(), 1)
            self.assertEqual(discover.call_count, 2)
            runner.assert_not_called()
            self.assertIn("modernization/verify/data/oracle/tests", diagnostic.getvalue())

    def test_failure_or_skip_is_not_a_pass(self):
        suite = unittest.TestSuite([unittest.FunctionTestCase(lambda: None)])
        for successful, skipped, wanted in ((True, [], 0), (False, [], 1), (True, ["skip"], 1)):
            with patch.object(offline_tests, "load_suite", return_value=suite), \
                    patch.object(offline_tests.unittest, "TextTestRunner") as runner:
                runner.return_value.run.return_value = SimpleNamespace(
                    wasSuccessful=lambda: successful, skipped=skipped)
                self.assertEqual(offline_tests.main(), wanted)
