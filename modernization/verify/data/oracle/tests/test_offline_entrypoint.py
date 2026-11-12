"""Guard the spec-job directory from outside that directory."""
from io import StringIO
import unittest
from unittest.mock import patch

from modernization.verify.spec_jobs import offline_tests


class OfflineDirectoryGuardTest(unittest.TestCase):
    def test_empty_spec_job_directory_is_refused_with_nonempty_oracle_suite(self):
        nonempty = unittest.TestSuite([unittest.FunctionTestCase(lambda: None)])
        with patch.object(offline_tests.unittest.TestLoader, "discover",
                          side_effect=[unittest.TestSuite(), nonempty]) as discover, \
                patch.object(offline_tests.unittest, "TextTestRunner") as runner, \
                patch("sys.stderr", new_callable=StringIO) as diagnostic:
            self.assertEqual(offline_tests.main(), 1)
            self.assertEqual(discover.call_count, 1)
            runner.assert_not_called()
            self.assertIn("modernization/verify/spec_jobs/tests", diagnostic.getvalue())
