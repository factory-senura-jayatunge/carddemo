"""scripts/e2e_stack.py's own CARDDEMO_VAR_DIR: python3 -m unittest discover -s modernization/scripts/tests

db-reset and load of the e2e database get RUN_VAR_DIR, a mkdtemp folder (0700)
that exists from begin() to close() and is then removed (KEEP_VAR=1 keeps it);
the slice keeps the shared folder. Each case runs in a child Python, so the
module-level settings of stack.py come from that case's environment."""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest

SCRIPTS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DRIVER = textwrap.dedent("""
    import json, os, sys
    sys.path.insert(0, sys.argv[1])
    import e2e_stack
    runs = []
    def run(self, step, argv, cwd=None, env=None):
        runs.append({"step": step, "argv": argv, "var": self.var, "exists": os.path.isdir(self.var),
                     "mode": oct(os.stat(self.var).st_mode & 0o777), "env_var": (env or self.env()).get("CARDDEMO_VAR_DIR")})
    e2e_stack.Session.run = run
    s = e2e_stack.Session("t")
    with s:
        private = s.var
        s.fresh(True)
    print(json.dumps({"runs": runs, "private": private, "after": s.var, "exists_after": os.path.exists(private)}))
""")


class E2eStackVarTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.shared = os.path.realpath(os.path.join(self.tmp.name, "var"))
        os.makedirs(self.shared)
        env_file = os.path.join(self.tmp.name, "test.env")
        with open(env_file, "w", encoding="utf-8") as f:
            f.write("CARDDEMO_DB_NAME=carddemo\n")
        self.env = {k: v for k, v in os.environ.items()
                    if k not in ("KEEP_VAR", "CARDDEMO_E2E_DB_NAME", "CARDDEMO_DB_NAME", "CARDDEMO_TEST_MSSQL_DB",
                                 "RUN_VAR_DIR", "MAKEFLAGS", "MAKELEVEL")}
        self.env.update(CARDDEMO_VAR_DIR=self.shared, CARDDEMO_ENV_FILE=env_file,
                        CARDDEMO_SLICE_LOCK=os.path.join(self.tmp.name, "slice.lock"), MAKE="make")

    def drive(self, **extra):
        p = subprocess.run([sys.executable, "-c", DRIVER, SCRIPTS], env={**self.env, **extra},
                           capture_output=True, text=True, timeout=60)
        self.assertEqual(p.returncode, 0, p.stdout + p.stderr)
        return json.loads(p.stdout.strip().splitlines()[-1]), p.stdout

    def test_reload_gets_a_private_folder_and_the_slice_the_shared_one(self):
        got, _out = self.drive()
        reload, slice_ = got["runs"]
        private = got["private"]
        self.assertEqual(reload["step"], "reload")
        self.assertEqual(reload["argv"][-3:], ["db-reset", "load", f"RUN_VAR_DIR={private}"])
        self.assertTrue(reload["exists"])
        self.assertEqual(reload["mode"], "0o700")
        self.assertTrue(os.path.isabs(private))
        self.assertFalse(private.startswith(self.shared + os.sep))
        self.assertEqual(slice_["step"], "slice")
        self.assertFalse(any(a.startswith("RUN_VAR_DIR=") for a in slice_["argv"]))
        self.assertEqual(slice_["env_var"], self.shared)
        self.assertIsNone(got["after"])
        self.assertFalse(got["exists_after"])

    def test_keep_var_keeps_the_folder(self):
        got, out = self.drive(KEEP_VAR="1")
        private = got["private"]
        self.addCleanup(lambda: __import__("shutil").rmtree(private, ignore_errors=True))
        self.assertTrue(got["exists_after"])
        self.assertRegex(out, rf"note\s+var\s+kept {re.escape(private)} \(KEEP_VAR=1\)")


if __name__ == "__main__":
    unittest.main()
