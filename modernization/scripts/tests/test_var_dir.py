"""CARDDEMO_VAR_DIR, the one output setting: python3 -m unittest discover -s modernization/scripts/tests

Its resolution (environment, then the shared .env, then the main checkout's
var/, always absolute), setup's handling of it and of the removed settings,
the harnesses' private folders, and the Makefile's RUN_VAR_DIR and
NEW_INSTANCE, run in a scratch copy of modernization/ whose mvnw, stack.py
and java are stubs that only record what they get."""
from __future__ import annotations

import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import types
import unittest
import unittest.mock

SCRIPTS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODERNIZATION = os.path.dirname(SCRIPTS)
REPO = os.path.dirname(MODERNIZATION)
sys.path.insert(0, SCRIPTS)

import verify_migrations  # noqa: E402
import with_env  # noqa: E402
from with_env import (LEGACY_OUTPUT_SETTINGS, VAR_DIR_INVALID, VAR_DIR_PRIVATE, PrivateVarDir,  # noqa: E402
                      VarDirError, default_var_dir, is_private_var_dir, legacy_output_settings, resolve_var_dir)

VAR = "CARDDEMO_VAR_DIR"
# Inherited settings that would point a child at the real stack.
DROP = ("CARDDEMO_ENV_FILE", "CARDDEMO_INFRA_DIR", VAR, "CARDDEMO_JWT_KEY_FILE", "CARDDEMO_MSSQL_SA_PASSWORD_FILE",
        "MAKEFLAGS", "MAKELEVEL", "MFLAGS", "JAVA_HOME", "DOCKER_HOST", "RUN_VAR_DIR", "NEW_INSTANCE", "KEEP_VAR",
        *LEGACY_OUTPUT_SETTINGS)


def clean_env(**extra) -> dict:
    env = {k: v for k, v in os.environ.items() if k not in DROP}
    env.update(extra)
    return env


class ResolveTest(unittest.TestCase):
    def test_the_environment_wins_over_the_env_file(self):
        self.assertEqual(resolve_var_dir({VAR: "/from/env"}, {VAR: "/from/file"}), "/from/env")

    def test_the_env_file_comes_next(self):
        self.assertEqual(resolve_var_dir({}, {VAR: "/from/file"}), "/from/file")
        self.assertEqual(resolve_var_dir({VAR: "  "}, {VAR: "/from/file"}), "/from/file")

    def test_the_main_checkout_var_is_the_default(self):
        self.assertEqual(resolve_var_dir({}, {}), default_var_dir())
        self.assertEqual(resolve_var_dir({VAR: ""}, {VAR: ""}), default_var_dir())
        self.assertTrue(os.path.isabs(default_var_dir()))
        self.assertEqual(os.path.basename(default_var_dir()), "var")

    def test_a_relative_value_is_refused_wherever_it_comes_from(self):
        for environ, values in (({VAR: "var"}, {}), ({}, {VAR: "./var"}), ({VAR: "../x"}, {VAR: "/abs"})):
            with self.assertRaises(VarDirError) as e:
                resolve_var_dir(environ, values)
            self.assertEqual(str(e.exception), VAR_DIR_INVALID)

    def test_the_env_file_named_by_the_environment_is_read(self):
        with tempfile.TemporaryDirectory() as d:
            env_file = os.path.join(d, ".env")
            with open(env_file, "w") as f:
                f.write(f"{VAR}=/from/named/file\n")
            self.assertEqual(resolve_var_dir({"CARDDEMO_ENV_FILE": env_file}), "/from/named/file")
            self.assertEqual(resolve_var_dir({"CARDDEMO_ENV_FILE": os.path.join(d, "missing")}), default_var_dir())

    def test_legacy_settings_are_named_only_when_set(self):
        self.assertEqual(legacy_output_settings({"CARDDEMO_REPORTS_DIR": "/x", "CARDDEMO_TRANREPT_DIR": ""},
                                                {"CARDDEMO_BATCH_FILES_INBOUNDDIR": "/y"}),
                         ["CARDDEMO_REPORTS_DIR", "CARDDEMO_BATCH_FILES_INBOUNDDIR"])
        self.assertEqual(legacy_output_settings({}, {}), [])
        self.assertEqual(set(LEGACY_OUTPUT_SETTINGS) >= {"CARDDEMO_REPORTS_DIR", "CARDDEMO_TRANREPT_DIR",
                                                         "CARDDEMO_INBOUND_DIR", "CARDDEMO_TRANSACTION_REPORT_ROOT",
                                                         "CARDDEMO_BATCH_FILES_OUTPUTDIR"}, True)


class WithEnvTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="var-dir-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.env_file = os.path.join(self.tmp, ".env")

    def run_with_env(self, file_text, **env):
        with open(self.env_file, "w") as f:
            f.write(file_text)
        return subprocess.run([sys.executable, os.path.join(SCRIPTS, "with_env.py"), "--env-file", self.env_file, "--",
                               sys.executable, "-c", f"import os; print(os.environ['{VAR}'])"],
                              capture_output=True, text=True, env=clean_env(**env), timeout=60)

    def test_the_command_always_gets_an_absolute_value(self):
        self.assertEqual(self.run_with_env(f"{VAR}=/shared/var\n").stdout.strip(), "/shared/var")
        self.assertEqual(self.run_with_env(f"{VAR}=/shared/var\n", **{VAR: "/own"}).stdout.strip(), "/own")
        self.assertEqual(self.run_with_env("").stdout.strip(), default_var_dir())

    def test_a_relative_value_stops_before_the_command(self):
        for text, env in ((f"{VAR}=var\n", {}), ("", {VAR: "rel/var"})):
            r = self.run_with_env(text, **env)
            self.assertEqual(r.returncode, 2)
            self.assertEqual(r.stdout, "")
            self.assertIn(VAR_DIR_INVALID, r.stderr)

    def test_heavy_refuses_a_relative_value_before_taking_a_lock(self):
        r = subprocess.run([sys.executable, os.path.join(SCRIPTS, "heavy.py"), "t", "--", "true"],
                           capture_output=True, text=True, timeout=60,
                           env=clean_env(CARDDEMO_ENV_FILE=self.env_file, **{VAR: "var"}))
        self.assertEqual(r.returncode, 2)
        self.assertIn(f"t: FAILED ({VAR_DIR_INVALID})", r.stderr)

    def test_heavy_takes_its_lock_under_the_resolved_folder(self):
        with open(self.env_file, "w") as f:
            f.write(f"{VAR}={self.tmp}/shared\n")
        r = subprocess.run([sys.executable, os.path.join(SCRIPTS, "heavy.py"), "t", "--", "true"],
                           capture_output=True, text=True, timeout=60, env=clean_env(CARDDEMO_ENV_FILE=self.env_file))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue(os.path.exists(os.path.join(self.tmp, "shared", "run", "heavy.lock")))


class PrivateVarDirTest(unittest.TestCase):
    def test_a_private_folder_is_0700_and_removed_with_what_it_holds(self):
        lines = []
        with PrivateVarDir("t", environ={}, out=lines.append) as path:
            self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o700)
            self.assertTrue(os.path.isabs(path))
            self.assertNotEqual(path, resolve_var_dir({}, {}))
            os.makedirs(os.path.join(path, "out", "abc", "def"), mode=0o700)
            with open(os.path.join(path, "out", "abc", "def", ".instance"), "w") as f:
                f.write("x")
        self.assertFalse(os.path.exists(path))
        self.assertEqual(lines, [])

    def test_two_folders_are_never_the_same(self):
        a, b = PrivateVarDir("t", environ={}), PrivateVarDir("t", environ={})
        try:
            self.assertNotEqual(a.open(), b.open())
        finally:
            a.close()
            b.close()

    def test_keep_var_keeps_it_and_prints_its_path(self):
        lines = []
        with PrivateVarDir("t", environ={"KEEP_VAR": "1"}, out=lines.append) as path:
            pass
        try:
            self.assertTrue(os.path.isdir(path))
            self.assertEqual(len(lines), 1)
            self.assertIn(f"kept {path} (KEEP_VAR=1)", lines[0])
        finally:
            shutil.rmtree(path, True)

    def test_close_without_open_does_nothing(self):
        PrivateVarDir("t", environ={}).close()


class StackVarGuardTest(unittest.TestCase):
    """stack.py runs only on the shared folder and passes no removed setting on."""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="var-dir-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.env_file = os.path.join(self.tmp, ".env")
        with open(self.env_file, "w") as f:
            f.write(f"{VAR}={self.tmp}/shared\n")

    def stack(self, *argv, **env):
        return subprocess.run([sys.executable, os.path.join(SCRIPTS, "stack.py"), *argv], capture_output=True,
                              text=True, timeout=60,
                              env=clean_env(CARDDEMO_ENV_FILE=self.env_file, CARDDEMO_INFRA_DIR=self.tmp, **env))

    def test_a_private_folder_is_recognised_only_where_the_harness_makes_it(self):
        with PrivateVarDir("t", environ={}) as path:
            self.assertTrue(is_private_var_dir(path))
            self.assertTrue(is_private_var_dir(path + "/"))
        for other in (self.tmp, "/shared/var", default_var_dir(),
                      os.path.join(self.tmp, "carddemo-t-var-x"),
                      os.path.join(tempfile.gettempdir(), "carddemo-var"),
                      os.path.join(tempfile.gettempdir(), "other-t-var-x")):
            self.assertFalse(is_private_var_dir(other), other)

    def test_every_command_refuses_a_private_folder(self):
        with PrivateVarDir("t", environ={}) as path:
            for cmd in ("status", "up", "sa-secret"):
                r = self.stack(cmd, **{VAR: path})
                self.assertEqual(r.returncode, 2, r.stdout + r.stderr)
                self.assertIn(f"{cmd}: FAILED ({VAR_DIR_PRIVATE})", r.stdout)
            self.assertEqual(os.listdir(path), [])

    def test_up_refuses_a_removed_setting_by_name_before_anything_else(self):
        r = self.stack("up", CARDDEMO_INBOUND_DIR="/secret-looking/inbound")
        self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
        self.assertIn("up: FAILED (CARDDEMO_INBOUND_DIR is no longer read", r.stdout)
        self.assertNotIn("/secret-looking", r.stdout + r.stderr)
        with open(self.env_file, "a") as f:
            f.write("CARDDEMO_REPORTS_DIR=/x\n")
        r = self.stack("up", CARDDEMO_TRANSACTION_REPORT_ROOT="/y")
        self.assertIn("up: FAILED (CARDDEMO_REPORTS_DIR, CARDDEMO_TRANSACTION_REPORT_ROOT are no longer read", r.stdout)
        self.assertFalse(os.path.exists(os.path.join(self.tmp, "shared", "run")))

    def test_no_child_gets_a_removed_setting(self):
        code = ("import json, sys; sys.path.insert(0, sys.argv[1]); import stack; "
                "print(json.dumps(sorted(k for k in stack.child_env() if k.startswith('CARDDEMO_'))))")
        legacy = {name: "/old" for name in LEGACY_OUTPUT_SETTINGS}
        r = subprocess.run([sys.executable, "-c", code, SCRIPTS], capture_output=True, text=True, timeout=60,
                           env=clean_env(CARDDEMO_ENV_FILE=self.env_file, CARDDEMO_INFRA_DIR=self.tmp, **legacy))
        self.assertEqual(r.returncode, 0, r.stderr)
        keys = set(json.loads(r.stdout))
        self.assertIn(VAR, keys)
        self.assertEqual(keys & set(LEGACY_OUTPUT_SETTINGS), set())


class VerifyMigrationsTest(unittest.TestCase):
    def test_every_migrate_call_gets_its_own_folder_and_removes_it(self):
        seen = []

        def run(argv, env, **kw):
            path = env[VAR]
            seen.append((path, os.path.isdir(path), stat.S_IMODE(os.stat(path).st_mode)))
            return types.SimpleNamespace(returncode=0, stdout="schema version 505\ndb-migrate: OK\n", stderr="")

        shared = "/shared/var"
        with unittest.mock.patch.dict(os.environ, {VAR: shared}), \
                unittest.mock.patch.object(verify_migrations.subprocess, "run", run), \
                unittest.mock.patch.dict(os.environ, {"KEEP_VAR": ""}):
            self.assertEqual(verify_migrations.migrate("x.jar", "carddemo_fresh", "carddemo_", "--reset"),
                             (True, "schema version 505"))
            self.assertEqual(verify_migrations.migrate("x.jar", "carddemo_upgrade", "carddemo_")[0], True)
        (first, made1, mode1), (second, made2, mode2) = seen
        self.assertTrue(made1 and made2)
        self.assertEqual((mode1, mode2), (0o700, 0o700))
        self.assertNotEqual(first, second)
        self.assertNotIn(shared, (first, second))
        self.assertFalse(os.path.exists(first) or os.path.exists(second))

    def test_the_folder_is_removed_when_db_migrate_fails(self):
        seen = []

        def run(argv, env, **kw):
            seen.append(env[VAR])
            return types.SimpleNamespace(returncode=1, stdout="ERROR: boom\n", stderr="")

        with unittest.mock.patch.object(verify_migrations.subprocess, "run", run):
            ok, detail = verify_migrations.migrate("x.jar", "carddemo_fresh", "carddemo_")
        self.assertFalse(ok)
        self.assertIn("ERROR: boom", detail)
        self.assertFalse(os.path.exists(seen[0]))


class SetupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="var-setup-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.example = os.path.join(self.tmp, "example.env")
        self.env_path = os.path.join(self.tmp, ".env")
        with open(self.example, "w") as f:
            f.write(f"# runtime folder\n{VAR}=\nCARDDEMO_DB_NAME=carddemo\n")

    def setup(self, env_text=None, **env):
        if env_text is not None:
            with open(self.env_path, "w") as f:
                f.write(env_text)
        r = subprocess.run([sys.executable, os.path.join(SCRIPTS, "init_env.py"), self.example, self.env_path],
                           capture_output=True, text=True, timeout=60, env=clean_env(**env))
        with open(self.env_path) as f:
            values = dict(re.findall(r"(?m)^([A-Z_0-9]+)=(.*)$", f.read()))
        return r.returncode, r.stdout + r.stderr, values

    def test_an_empty_value_gets_the_absolute_default_and_is_printed(self):
        rc, out, values = self.setup()
        self.assertEqual(rc, 0, out)
        self.assertEqual(values[VAR], default_var_dir())
        self.assertIn(f"ok    {VAR:16} {default_var_dir()} (written to .env)", out)

    def test_a_key_new_in_the_example_is_added_with_the_default(self):
        rc, out, values = self.setup("CARDDEMO_DB_NAME=carddemo\n")
        self.assertEqual(rc, 0, out)
        self.assertEqual(values[VAR], default_var_dir())

    def test_a_set_value_is_never_changed(self):
        rc, out, values = self.setup(f"{VAR}=/somewhere/else\n")
        self.assertEqual(rc, 0, out)
        self.assertEqual(values[VAR], "/somewhere/else")
        self.assertNotIn("written to .env", out)

    def test_a_relative_value_fails_in_the_env_file_or_the_environment(self):
        for env_text, env in ((f"{VAR}=var\n", {}), (None, {VAR: "var"})):
            rc, out, values = self.setup(env_text, **env)
            self.assertEqual(rc, 1, out)
            self.assertEqual(out.count(f"FAIL  {VAR} must be an absolute path"), 1, out)
            if env_text:
                self.assertEqual(values[VAR], "var")
            os.remove(self.env_path)

    def test_a_removed_setting_fails_by_name_without_its_value(self):
        rc, out, _values = self.setup("CARDDEMO_REPORTS_DIR=/secret-looking/reports\n",
                                      CARDDEMO_TRANREPT_DIR="/other/tranrept")
        self.assertEqual(rc, 1, out)
        self.assertIn("FAIL  CARDDEMO_REPORTS_DIR is no longer read", out)
        self.assertIn("FAIL  CARDDEMO_TRANREPT_DIR is no longer read", out)
        self.assertNotIn("/secret-looking/reports", out)
        self.assertNotIn("/other/tranrept", out)

    def test_the_example_names_the_setting_empty_and_no_removed_setting(self):
        with open(os.path.join(MODERNIZATION, ".env.example")) as f:
            example = f.read()
        self.assertRegex(example, rf"(?m)^{VAR}=$")
        for name in LEGACY_OUTPUT_SETTINGS:
            self.assertNotRegex(example, rf"(?m)^#?\s*{name}=")


JAR_MIGRATE = "db/db-migrate/target/db-migrate-0.0.1-exec.jar"
JAR_BATCH = "batch/batch-service/target/batch-service-0.0.1-exec.jar"
RECORD = f"""#!{sys.executable}
import json, os, sys
with open(os.environ["STUB_LOG"], "a") as f:
    f.write(json.dumps({{"tool": os.path.basename(sys.argv[0]), "argv": sys.argv[1:],
                         "var": os.environ.get("{VAR}"),
                         "pauth": os.environ.get("CARDDEMO_PAUTH_SOURCE_FILE")}}) + "\\n")
"""
STUBS = {
    "mvnw": RECORD + f"""for jar in ("{JAR_MIGRATE}", "{JAR_BATCH}"):
    os.makedirs(os.path.dirname(jar), exist_ok=True)
    open(jar, "w").close()
""",
    "scripts/stack.py": RECORD,
    "scripts/toolchain.sh": "#!/bin/sh\nexit 1\n",
    "java": RECORD,
}


class MakefileTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="var-make-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.here = os.path.join(self.tmp, "modernization")
        os.makedirs(os.path.join(self.here, "scripts"))
        os.makedirs(os.path.join(self.here, "batch", "batch-service"))
        shutil.copy(os.path.join(MODERNIZATION, "Makefile"), self.here)
        shutil.copy(os.path.join(SCRIPTS, "with_env.py"), os.path.join(self.here, "scripts"))
        open(os.path.join(self.here, "batch", "batch-service", "pom.xml"), "w").close()
        for name, body in STUBS.items():
            path = os.path.join(self.here, name)
            with open(path, "w") as f:
                f.write(body)
            os.chmod(path, 0o755)
        self.env_file = os.path.join(self.here, ".env")
        self.write_env("")
        self.log = os.path.join(self.tmp, "calls.jsonl")

    def write_env(self, text):
        with open(self.env_file, "w") as f:
            f.write("CARDDEMO_DB_NAME=carddemo\n" + text)

    def make(self, target, *args, **env):
        if os.path.exists(self.log):
            os.remove(self.log)
        r = subprocess.run(["make", "--no-print-directory", "-C", self.here, target, f"PYTHON={sys.executable}",
                            f"JAVA={os.path.join(self.here, 'java')}", *args],
                           capture_output=True, text=True, env=clean_env(STUB_LOG=self.log, **env), timeout=120)
        self.out = r.stdout + r.stderr
        return r.returncode

    def calls(self, tool=None):
        if not os.path.exists(self.log):
            return []
        with open(self.log) as f:
            rows = [json.loads(line) for line in f]
        return [r for r in rows if tool is None or r["tool"] == tool]

    def test_the_env_file_value_is_used_when_only_it_sets_one(self):
        self.write_env(f"{VAR}={self.tmp}/from-file\n")
        self.assertEqual(self.make("db-migrate"), 0, self.out)
        self.assertEqual({c["var"] for c in self.calls()}, {f"{self.tmp}/from-file"})

    def test_the_environment_wins_and_the_default_is_the_main_var(self):
        self.write_env(f"{VAR}={self.tmp}/from-file\n")
        self.assertEqual(self.make("db-migrate", **{VAR: f"{self.tmp}/from-env"}), 0, self.out)
        self.assertEqual({c["var"] for c in self.calls()}, {f"{self.tmp}/from-env"})
        self.write_env("")
        self.assertEqual(self.make("db-migrate"), 0, self.out)
        self.assertEqual({os.path.realpath(c["var"]) for c in self.calls()},
                         {os.path.realpath(os.path.join(self.here, "var"))})

    def test_run_var_dir_reaches_only_the_jvm(self):
        private = os.path.join(self.tmp, "private")
        self.write_env(f"{VAR}={self.tmp}/shared\n")
        for target, jar in (("db-reset", JAR_MIGRATE), ("db-migrate", JAR_MIGRATE), ("load", JAR_BATCH)):
            self.assertEqual(self.make(target, f"RUN_VAR_DIR={private}"), 0, self.out)
            java, = self.calls("java")
            self.assertEqual((java["argv"][:2], java["var"]), (["-jar", jar], private), target)
            for other in self.calls("stack.py") + self.calls("mvnw"):
                self.assertEqual(other["var"], f"{self.tmp}/shared", (target, other["tool"]))

    def test_a_relative_run_var_dir_stops_the_jvm(self):
        # make exits 2 for any failed recipe line.
        self.assertEqual(self.make("db-migrate", "RUN_VAR_DIR=private"), 2, self.out)
        self.assertEqual(self.calls("java"), [])
        self.assertIn(VAR_DIR_INVALID, self.out)

    def test_new_instance_adds_the_option_and_only_1_is_accepted(self):
        self.assertEqual(self.make("db-migrate", "NEW_INSTANCE=1"), 0, self.out)
        self.assertEqual(self.calls("java")[0]["argv"], ["-jar", JAR_MIGRATE, "--check-logins", "--new-instance"])
        self.assertEqual(self.make("db-migrate"), 0, self.out)
        self.assertEqual(self.calls("java")[0]["argv"], ["-jar", JAR_MIGRATE, "--check-logins"])
        for value in ("yes", "0", "true"):
            self.assertEqual(self.make("db-migrate", f"NEW_INSTANCE={value}"), 2, self.out)
            self.assertIn("db-migrate: FAILED (NEW_INSTANCE must be 1 or unset)", self.out)
            self.assertEqual(self.calls(), [])
        self.assertEqual(self.make("db-reset", "NEW_INSTANCE=1"), 0, self.out)
        self.assertNotIn("--new-instance", self.calls("java")[0]["argv"])


RUNBOOK = os.path.join(REPO, "docs", "runbooks", "modernized-database-restore.md")


class RestoreRunbookTest(unittest.TestCase):
    def setUp(self):
        with open(RUNBOOK) as f:
            self.text = f.read()
        with open(os.path.join(MODERNIZATION, "Makefile")) as f:
            self.targets = set(re.findall(r"(?m)^([a-z][a-z0-9-]*):", f.read()))

    def test_every_make_command_names_a_target(self):
        commands = re.findall(r"make -C modernization ([a-z][a-z0-9-]*)", self.text)
        self.assertGreaterEqual(set(commands), {"stop", "db-migrate", "up", "cycle-rerun"})
        for target in commands:
            self.assertIn(target, self.targets)

    def test_the_steps_are_in_order(self):
        steps = [self.text.index(s) for s in ("make -C modernization stop", "RESTORE DATABASE",
                                               "make -C modernization db-migrate NEW_INSTANCE=1",
                                               "make -C modernization up")]
        self.assertEqual(steps, sorted(steps))
        for code in ("OUTPUT_ROOT_INCOMPLETE", "OUTPUT_ROOT_MISMATCH"):
            self.assertIn(f"## {code}", self.text)

    def test_no_password_on_a_command_line(self):
        self.assertNotRegex(self.text, r"-P\s")
        self.assertIn('SQLCMDPASSWORD="$(cat /run/secrets/mssql_sa_password', self.text)

    def test_agents_md_links_it(self):
        # docs/runbooks/README.md is a cited source of the job specs (by line
        # and hash), so the link lives in AGENTS.md and the modernization README.
        with open(os.path.join(REPO, "AGENTS.md"), encoding="utf-8") as f:
            self.assertIn("(docs/runbooks/modernized-database-restore.md)", f.read())
        with open(os.path.join(REPO, "modernization", "README.md"), encoding="utf-8") as f:
            self.assertIn("(../docs/runbooks/modernized-database-restore.md)", f.read())


if __name__ == "__main__":
    unittest.main()
