"""Tests of identity_bcrypt_check.py: python3 -m unittest discover -s modernization/scripts/tests

The database name comes from CARDDEMO_DB_NAME. It must be a plain SQL
identifier before any command is built, and it reaches sqlcmd as its own
argument, never inside the shell string."""
from __future__ import annotations

import io
import os
import subprocess
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest import mock

SCRIPTS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, SCRIPTS)

import identity_bcrypt_check as check  # noqa: E402

KNOWN = ["carddemo", "carddemo_test_identity_identity_service", "CardDemo2", "_x", "a" * 128]
BAD = ["", "a" * 129, "carddemo;DROP", 'card"demo', "card'demo", "$(id)", "`id`", "card demo", "card-demo",
       "carddémo", "card\ndemo", "card]demo", "[carddemo]", "card.demo", "card\\demo", "carddemo\x00"]


def done(stdout: str = "", returncode: int = 0) -> subprocess.CompletedProcess:
    return subprocess.CompletedProcess([], returncode, stdout=stdout, stderr="")


class DatabaseNameTest(unittest.TestCase):
    def test_accepts_plain_identifiers_up_to_128_characters(self):
        for name in KNOWN:
            with self.subTest(name=name):
                self.assertTrue(check.valid_db_name(name))

    def test_refuses_everything_else(self):
        for name in BAD:
            with self.subTest(name=repr(name)):
                self.assertFalse(check.valid_db_name(name))

    def run_main(self, db: str | None, result: subprocess.CompletedProcess | None = None):
        env = {"CARDDEMO_ENV_FILE": "/nonexistent/.env", "CARDDEMO_INFRA_DIR": "/nonexistent/infra"}
        if db is not None:
            env["CARDDEMO_DB_NAME"] = db
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, env, clear=False), \
                mock.patch.object(check.subprocess, "run", return_value=result or done()) as run, \
                redirect_stdout(out), redirect_stderr(err):
            if db is None:
                os.environ.pop("CARDDEMO_DB_NAME", None)
            rc = check.main()
        return rc, out.getvalue() + err.getvalue(), run

    def test_an_invalid_name_runs_nothing_and_is_not_echoed(self):
        # An environment value cannot hold NUL, so that one is only checked by the validator.
        for name in [n for n in BAD if n and "\x00" not in n]:
            with self.subTest(name=repr(name)):
                rc, text, run = self.run_main(name)
                self.assertEqual(rc, 2)
                run.assert_not_called()
                self.assertIn("identity-bcrypt-check: FAILED (CARDDEMO_DB_NAME is not a plain database name", text)
                self.assertNotIn(name, text)

    def test_the_name_is_an_argument_not_part_of_the_shell_string(self):
        rc, text, run = self.run_main("carddemo_test_identity_identity_service")
        self.assertEqual(rc, 0)
        self.assertIn("identity-bcrypt-check: OK", text)
        argv = run.call_args.args[0]
        script = argv[argv.index("-c") + 1]
        self.assertNotIn("carddemo_test_identity_identity_service", script)
        self.assertIn('-d "$2"', script)
        self.assertIn('-Q "$1"', script)
        self.assertEqual(argv[-1], "carddemo_test_identity_identity_service")
        self.assertEqual(argv[-3], "sh")

    def test_an_unset_or_empty_name_means_carddemo(self):
        for db in (None, ""):
            with self.subTest(db=db):
                rc, _, run = self.run_main(db)
                self.assertEqual(rc, 0)
                self.assertEqual(run.call_args.args[0][-1], "carddemo")

    def test_listed_users_fail_the_check_by_id_only(self):
        rc, text, _ = self.run_main("carddemo", done("MIGR0001\nMIGR0002\n"))
        self.assertEqual(rc, 1)
        self.assertIn("fail  MIGR0001", text)
        self.assertIn("FAILED (2 user(s) in carddemo", text)
        self.assertNotIn("$2", text)

    def test_a_query_failure_fails_the_check(self):
        rc, text, _ = self.run_main("carddemo", done("Login failed", returncode=1))
        self.assertEqual(rc, 1)
        self.assertIn("identity-bcrypt-check: FAILED (cannot query carddemo", text)


if __name__ == "__main__":
    unittest.main()
