"""Tests of make cycle-rerun: python3 -m unittest discover -s modernization/scripts/tests

The real Makefile runs in a scratch copy of modernization/ whose mvnw,
scripts/stack.py, scripts/with_env.py and java are stubs that only record
their arguments, so nothing is built, started or rerun."""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

MAKEFILE = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "Makefile")
JAR = "batch/batch-service/target/batch-service-0.0.1-exec.jar"

RECORD = f"""#!{sys.executable}
import json, os, sys
with open(os.environ["STUB_LOG"], "a") as f:
    f.write(json.dumps({{"tool": os.path.basename(sys.argv[0]), "argv": sys.argv[1:], "CYCLE": os.environ.get("CYCLE")}}) + "\\n")
"""
STUBS = {
    "mvnw": RECORD + f"""os.makedirs("batch/batch-service/target", exist_ok=True)
if not os.environ.get("STUB_NO_JAR"):
    open("{JAR}", "w").close()
""",
    "scripts/stack.py": RECORD,
    "scripts/with_env.py": f"#!{sys.executable}\nimport os, sys\nos.execv(sys.argv[1], sys.argv[1:])\n",
    "scripts/toolchain.sh": "#!/bin/sh\nexit 1\n",
    "java": RECORD + "sys.exit(int(os.environ.get('STUB_JAVA_RC', '0')))\n",
}
# Inherited settings that would point the copy at the real stack.
DROP = ("CARDDEMO_ENV_FILE", "CARDDEMO_INFRA_DIR", "CARDDEMO_VAR_DIR", "CARDDEMO_JWT_KEY_FILE", "CYCLE",
        "MAKEFLAGS", "MAKELEVEL", "MFLAGS", "JAVA_HOME", "DOCKER_HOST", "STUB_JAVA_RC", "STUB_NO_JAR")


class CycleRerunTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="cycle-rerun-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.here = os.path.join(self.tmp, "modernization")
        os.makedirs(os.path.join(self.here, "scripts"))
        os.makedirs(os.path.join(self.here, "batch", "batch-service"))
        shutil.copy(MAKEFILE, os.path.join(self.here, "Makefile"))
        open(os.path.join(self.here, "batch", "batch-service", "pom.xml"), "w").close()
        for name, body in STUBS.items():
            path = os.path.join(self.here, name)
            with open(path, "w") as f:
                f.write(body)
            os.chmod(path, 0o755)
        self.env_file = os.path.join(self.here, ".env")
        with open(self.env_file, "w") as f:
            f.write("CARDDEMO_DB_NAME=carddemo\n")
        self.log = os.path.join(self.tmp, "calls.jsonl")

    def make(self, *args, **env):
        e = {k: v for k, v in os.environ.items() if k not in DROP}
        e.update(STUB_LOG=self.log, **env)
        r = subprocess.run(["make", "--no-print-directory", "-C", self.here, "cycle-rerun", f"PYTHON={sys.executable}",
                            f"JAVA={os.path.join(self.here, 'java')}", *args],
                           capture_output=True, text=True, env=e, timeout=60)
        self.out = r.stdout + r.stderr
        return r.returncode

    def calls(self):
        if not os.path.exists(self.log):
            return []
        with open(self.log) as f:
            return [json.loads(line) for line in f]

    def assert_ran(self, cycle):
        stack, mvnw, java = self.calls()
        self.assertEqual(stack["argv"], ["ensure-infra", "cycle-rerun"])
        self.assertEqual(mvnw["argv"], ["-q", "-B", "-pl", "batch/batch-service", "-am", "package", "-DskipTests"])
        self.assertEqual(java["tool"], "java")
        self.assertEqual(java["argv"], ["-jar", JAR, "--carddemo.batch.command=cycle-rerun",
                                        f"--carddemo.batch.rerun.cycle-id={cycle}"])
        self.assertFalse(any("profiles" in a for a in java["argv"]), java["argv"])

    def test_a_cycle_id_runs_the_jar_with_the_rerun_command_and_no_demo_profile(self):
        self.assertEqual(self.make("CYCLE=17"), 0, self.out)
        self.assert_ran("17")
        self.assertIn("cycle-rerun: OK", self.out)

    def test_the_cycle_id_may_come_from_the_environment(self):
        self.assertEqual(self.make(CYCLE="4"), 0, self.out)
        self.assert_ran("4")

    def test_the_largest_int_is_a_cycle_id(self):
        self.assertEqual(self.make("CYCLE=2147483647"), 0, self.out)
        self.assert_ran("2147483647")

    def test_anything_but_a_cycle_id_exits_2_before_anything_runs(self):
        # make drops the leading blanks of a command-line value itself, so " 17"
        # is checked through the environment only.
        bad = [("cmd", v) for v in ["", "0", "-1", "+1", "007", "1.5", "1e3", "abc", "17 ", "1 2", "١٧",
                                    "1;touch pwned", "$(touch pwned)", "2147483648", "9999999999", "9223372036854775807",
                                    "1" * 20]]
        bad += [("env", v) for v in ["", " 17", "17\n", "0x11", "1;touch pwned", "2147483648", "9999999999"]]
        for where, value in bad:
            with self.subTest(where=where, value=value):
                if os.path.exists(self.log):
                    os.unlink(self.log)
                rc = self.make(f"CYCLE={value}") if where == "cmd" else self.make(CYCLE=value)
                self.assertNotEqual(rc, 0, self.out)
                self.assertIn("cycle-rerun: FAILED (CYCLE must be a cycle ID, a whole number from 1 to 2147483647",
                              self.out)
                self.assertRegex(self.out, r"\] Error 2\b")
                self.assertEqual(self.calls(), [])
                self.assertFalse(os.path.exists(os.path.join(self.here, "pwned")))
                self.assertFalse(os.path.exists(os.path.join(self.here, JAR)))

    def test_no_cycle_at_all_exits_2_before_anything_runs(self):
        self.assertNotEqual(self.make(), 0, self.out)
        self.assertIn("CYCLE must be a cycle ID", self.out)
        self.assertRegex(self.out, r"\] Error 2\b")
        self.assertEqual(self.calls(), [])

    def test_the_cycle_is_checked_before_the_env_file(self):
        os.unlink(self.env_file)
        self.assertNotEqual(self.make("CYCLE=x"), 0, self.out)
        self.assertIn("CYCLE must be a cycle ID", self.out)
        self.assertNotIn(".env is missing", self.out)
        self.assertNotEqual(self.make("CYCLE=3"), 0, self.out)
        self.assertIn(".env is missing; run make setup", self.out)
        self.assertEqual(self.calls(), [])

    def test_a_failing_jvm_fails_the_target(self):
        self.assertNotEqual(self.make("CYCLE=17", STUB_JAVA_RC="1"), 0, self.out)
        self.assertIn("cycle-rerun: FAILED (cycle-rerun of cycle 17, see above)", self.out)
        self.assertRegex(self.out, r"\] Error 1\b")
        self.assertNotIn("cycle-rerun: OK", self.out)

    def test_no_jar_after_the_build_fails_before_the_jvm(self):
        self.assertNotEqual(self.make("CYCLE=17", STUB_NO_JAR="1"), 0, self.out)
        self.assertIn("no batch/batch-service/target/batch-service-*-exec.jar after the build", self.out)
        self.assertEqual([c["tool"] for c in self.calls()], ["stack.py", "mvnw"])

    def test_help_lists_the_target(self):
        r = subprocess.run(["make", "--no-print-directory", "-C", self.here, "help"], capture_output=True, text=True,
                           env={k: v for k, v in os.environ.items() if k not in DROP}, timeout=60)
        self.assertRegex(r.stdout, r"(?m)^  cycle-rerun +Deliberate rerun of the cycle CYCLE=<id>")


if __name__ == "__main__":
    unittest.main()
