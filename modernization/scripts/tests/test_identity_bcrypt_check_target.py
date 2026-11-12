"""Tests of make identity-bcrypt-check: python3 -m unittest discover -s modernization/scripts/tests

The real Makefile runs in a scratch copy of modernization/ whose
scripts/with_env.py and scripts/identity_bcrypt_check.py are stubs that
record how they were called, so no database is asked."""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

MAKEFILE = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "Makefile")
SCRIPT = "scripts/identity_bcrypt_check.py"
SQL = "services/identity-service/src/main/resources/checks/bcrypt-outside-seed.sql"

RECORD = f"""#!{sys.executable}
import json, os, sys
with open(os.environ["STUB_LOG"], "a") as f:
    f.write(json.dumps({{"tool": os.path.basename(sys.argv[0]), "argv": sys.argv[1:],
                         "env_file": os.environ.get("CARDDEMO_ENV_FILE"),
                         "db": os.environ.get("CARDDEMO_DB_NAME")}}) + "\\n")
"""
STUBS = {
    # Runs the command it is given, as the real one does after loading .env.
    "scripts/with_env.py": RECORD + "os.execv(sys.argv[1], sys.argv[1:])\n",
    SCRIPT: RECORD + "print('identity-bcrypt-check: ' + ('OK' if not os.environ.get('STUB_RC') else 'FAILED (stub)'))\n"
                     "sys.exit(int(os.environ.get('STUB_RC', '0')))\n",
    "scripts/toolchain.sh": "#!/bin/sh\nexit 1\n",
}
DROP = ("CARDDEMO_ENV_FILE", "CARDDEMO_INFRA_DIR", "CARDDEMO_VAR_DIR", "CARDDEMO_JWT_KEY_FILE",
        "MAKEFLAGS", "MAKELEVEL", "MFLAGS", "JAVA_HOME", "DOCKER_HOST", "STUB_RC", "CARDDEMO_DB_NAME")
WITH_ENV = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "with_env.py")


class IdentityBcryptCheckTargetTest(unittest.TestCase):
    def setUp(self):
        self.tmp = os.path.realpath(tempfile.mkdtemp(prefix="bcrypt-target-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.here = os.path.join(self.tmp, "modernization")
        os.makedirs(os.path.join(self.here, "scripts"))
        shutil.copy(MAKEFILE, os.path.join(self.here, "Makefile"))
        for name, body in STUBS.items():
            path = os.path.join(self.here, name)
            with open(path, "w") as f:
                f.write(body)
            os.chmod(path, 0o755)
        os.makedirs(os.path.dirname(os.path.join(self.here, SQL)))
        open(os.path.join(self.here, SQL), "w").close()
        self.env_file = os.path.join(self.here, ".env")
        with open(self.env_file, "w") as f:
            f.write("CARDDEMO_DB_NAME=carddemo\n")
        self.log = os.path.join(self.tmp, "calls.jsonl")

    def make(self, target="identity-bcrypt-check", **env):
        e = {k: v for k, v in os.environ.items() if k not in DROP}
        e.update(STUB_LOG=self.log, **env)
        r = subprocess.run(["make", "--no-print-directory", "-C", self.here, target, f"PYTHON={sys.executable}"],
                           capture_output=True, text=True, env=e, timeout=60)
        self.out = r.stdout + r.stderr
        return r.returncode

    def calls(self):
        if not os.path.exists(self.log):
            return []
        with open(self.log) as f:
            return [json.loads(line) for line in f]

    def test_it_runs_the_check_through_with_env_with_the_shared_env_file(self):
        self.assertEqual(self.make(), 0, self.out)
        with_env, check = self.calls()
        self.assertEqual(with_env["tool"], "with_env.py")
        self.assertEqual(with_env["argv"], [sys.executable, os.path.join(self.here, SCRIPT)])
        self.assertEqual(check["tool"], "identity_bcrypt_check.py")
        self.assertEqual(check["argv"], [])
        self.assertEqual(check["env_file"], self.env_file)
        self.assertTrue(self.out.rstrip().endswith("identity-bcrypt-check: OK"), self.out)

    def test_the_environment_selects_another_database_over_the_env_file(self):
        shutil.copy(WITH_ENV, os.path.join(self.here, "scripts", "with_env.py"))
        self.assertEqual(self.make(), 0, self.out)
        self.assertEqual([c["db"] for c in self.calls()], ["carddemo"])
        os.unlink(self.log)
        self.assertEqual(self.make(CARDDEMO_DB_NAME="carddemo_test_identity_service"), 0, self.out)
        self.assertEqual([c["db"] for c in self.calls()], ["carddemo_test_identity_service"])

    def test_a_listed_user_fails_the_target(self):
        self.assertNotEqual(self.make(STUB_RC="1"), 0, self.out)
        self.assertIn("identity-bcrypt-check: FAILED", self.out)
        self.assertRegex(self.out, r"\] Error 1\b")

    def test_without_env_nothing_runs(self):
        os.unlink(self.env_file)
        self.assertNotEqual(self.make(), 0, self.out)
        self.assertIn(".env is missing; run make setup", self.out)
        self.assertEqual(self.calls(), [])

    def test_without_the_check_files_it_fails_and_says_what_is_missing(self):
        for missing in (SCRIPT, SQL):
            with self.subTest(missing=missing):
                path = os.path.join(self.here, missing)
                saved = path + ".saved"
                os.rename(path, saved)
                try:
                    self.assertNotEqual(self.make(), 0, self.out)
                    self.assertIn(f"identity-bcrypt-check: FAILED (no modernization/{missing} in this checkout; "
                                  "arrives with the identity-service bcrypt check)", self.out)
                    self.assertEqual(self.calls(), [])
                finally:
                    os.rename(saved, path)

    def test_it_never_starts_the_containers(self):
        self.assertEqual(self.make(), 0, self.out)
        self.assertFalse([c for c in self.calls() if c["tool"] == "stack.py"])
        recipe = subprocess.run(["make", "--no-print-directory", "-n", "-C", self.here, "identity-bcrypt-check",
                                 f"PYTHON={sys.executable}"], capture_output=True, text=True,
                                env={k: v for k, v in os.environ.items() if k not in DROP}, timeout=60).stdout
        self.assertNotIn("stack.py", recipe)
        self.assertNotIn("docker", recipe)

    def test_help_lists_the_target(self):
        self.make("help")
        self.assertRegex(self.out, r"(?m)^  identity-bcrypt-check +Read-only: ")


if __name__ == "__main__":
    unittest.main()
