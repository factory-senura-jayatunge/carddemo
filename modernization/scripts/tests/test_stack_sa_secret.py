"""Tests of when stack.py writes the SA secret file: python3 -m unittest discover -s modernization/scripts/tests

stack.py sa-secret and ensure-infra run as their own processes against a
scratch .env, compose file and var directory, with a fake docker first on
PATH that records its arguments, so the shared stack is never asked."""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPTS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODERNIZATION = os.path.dirname(SCRIPTS)
FAKE = "Fake0pass0for0tests0only"

FAKE_DOCKER = f"""#!{sys.executable}
import json, os, sys
args = sys.argv[1:]
with open(os.environ["FAKE_LOG"], "a") as f:
    f.write(json.dumps(args) + "\\n")
if os.environ.get("FAKE_ENV_LOG"):
    with open(os.environ["FAKE_ENV_LOG"], "a") as f:
        f.write(json.dumps([args, os.environ.get("CARDDEMO_MSSQL_SA_PASSWORD_FILE")]) + "\\n")
mode = os.environ["FAKE_MSSQL"]
if args[:1] == ["compose"] and "ps" in args and "-q" in args:
    if mode == "fail":
        sys.exit(1)
    print("" if mode == "absent" else "0123abcd")
elif args[:1] == ["inspect"]:
    print("/var/opt/mssql mssql-data")
    if mode == "mounted":
        print("/run/secrets/mssql_sa_password " + os.environ["FAKE_MOUNT_SOURCE"])
    print("/opt/carddemo/mssql-entrypoint.sh /x/entrypoint.sh" if mode == "mounted" else "")
elif args[:1] == ["compose"]:
    pass
else:
    sys.exit(2)
"""
OLD_COMPOSE = "services:\n  mssql:\n    image: mssql\nvolumes:\n  mssql-data:\n"
NEW_COMPOSE = OLD_COMPOSE + ("\nsecrets:\n  # copied by make up\n  mssql_sa_password:\n"
                             "    file: ${CARDDEMO_MSSQL_SA_PASSWORD_FILE:-./var/secrets/mssql-sa-password}\n")


class StackSaSecretTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.bin = os.path.join(self.dir, "bin")
        os.makedirs(self.bin)
        with open(os.path.join(self.bin, "docker"), "w", encoding="utf-8") as f:
            f.write(FAKE_DOCKER)
        os.chmod(os.path.join(self.bin, "docker"), 0o755)
        self.infra = os.path.join(self.dir, "infra")
        os.makedirs(self.infra)
        self.write_compose(NEW_COMPOSE)
        self.env = os.path.join(self.dir, ".env")
        self.var = os.path.join(self.dir, "var")
        # The stack's own folder comes from .env, as on a machine set up by make setup.
        with open(self.env, "w", encoding="utf-8") as f:
            f.write(f"MSSQL_SA_PASSWORD={FAKE}\nCARDDEMO_VAR_DIR={self.var}\n")
        self.default = os.path.join(self.var, "secrets", "mssql-sa-password")
        self.log = os.path.join(self.dir, "docker.log")

    def write_compose(self, text: str) -> None:
        with open(os.path.join(self.infra, "docker-compose.yml"), "w", encoding="utf-8") as f:
            f.write(text)

    def stack(self, *args, mssql="not mounted", path=None, source=None, **env):
        e = {k: v for k, v in os.environ.items() if not k.startswith("CARDDEMO_") and k != "RECREATE"}
        e.update(CARDDEMO_ENV_FILE=self.env, CARDDEMO_INFRA_DIR=self.infra, CARDDEMO_VAR_DIR=self.var,
                 FAKE_LOG=self.log, FAKE_MSSQL=mssql, FAKE_MOUNT_SOURCE=source or self.default,
                 PATH=path or self.bin + os.pathsep + os.environ["PATH"])
        e.update(env)
        r = subprocess.run([sys.executable, os.path.join(SCRIPTS, "stack.py"), *args], capture_output=True,
                           text=True, env=e, timeout=60)
        self.out = r.stdout + r.stderr
        self.assertNotIn(FAKE, self.out)
        return r.returncode

    def calls(self) -> list:
        if not os.path.exists(self.log):
            return []
        with open(self.log, encoding="utf-8") as f:
            return [json.loads(line) for line in f]

    def assert_written(self, path: str) -> None:
        with open(path, "rb") as f:
            self.assertEqual(f.read(), FAKE.encode())
        self.assertEqual(os.stat(path).st_mode & 0o777, 0o644)

    def assert_inspect_reads_mounts_only(self) -> None:
        inspects = [c for c in self.calls() if c[:1] == ["inspect"]]
        self.assertEqual(len(inspects), 1)
        self.assertEqual(inspects[0], ["inspect", "--format",
                                       "{{range .Mounts}}{{println .Destination .Source}}{{end}}", "0123abcd"])
        self.assertFalse([c for c in self.calls() if any("Env" in a for a in c)])

    def test_a_container_without_the_secret_mount_gets_no_file_and_a_note(self):
        self.assertEqual(self.stack("sa-secret", mssql="not mounted"), 0, self.out)
        self.assertFalse(os.path.exists(self.default))
        self.assertFalse(os.path.exists(os.path.dirname(self.default)))
        notes = [line for line in self.out.splitlines() if line.startswith("note")]
        self.assertEqual(len(notes), 1, self.out)
        self.assertIn("not written (the mssql container does not mount the secret)", notes[0])
        self.assert_inspect_reads_mounts_only()

    def test_a_container_that_mounts_the_secret_gets_the_file(self):
        self.assertEqual(self.stack("sa-secret", mssql="mounted"), 0, self.out)
        self.assert_written(self.default)
        self.assertNotIn("note", self.out)
        self.assert_inspect_reads_mounts_only()

    def test_an_explicit_path_is_written_without_asking_docker(self):
        own = os.path.join(self.dir, "own", "sa")
        self.assertEqual(self.stack("sa-secret", mssql="not mounted", CARDDEMO_MSSQL_SA_PASSWORD_FILE=own), 0, self.out)
        self.assert_written(own)
        self.assertFalse(os.path.exists(self.default))
        self.assertEqual(self.calls(), [])

    def test_no_container_writes_only_when_the_compose_file_has_the_secret(self):
        self.assertEqual(self.stack("sa-secret", mssql="absent"), 0, self.out)
        self.assert_written(self.default)
        os.unlink(self.default)
        self.write_compose(OLD_COMPOSE)
        self.assertEqual(self.stack("sa-secret", mssql="absent"), 0, self.out)
        self.assertFalse(os.path.exists(self.default))
        self.assertIn("no mssql container, and the compose file has no SA secret", self.out)

    def test_when_docker_cannot_say_nothing_is_written(self):
        self.assertEqual(self.stack("sa-secret", mssql="fail"), 0, self.out)
        self.assertFalse(os.path.exists(self.default))
        self.assertIn("docker cannot say", self.out)
        self.assertEqual(self.stack("sa-secret", path=os.path.join(self.dir, "nothing-here")), 0, self.out)
        self.assertFalse(os.path.exists(self.default))
        self.assertIn("docker cannot say", self.out)

    def test_ensure_infra_follows_the_same_rule(self):
        self.assertEqual(self.stack("ensure-infra", "db-reset", mssql="not mounted"), 0, self.out)
        self.assertFalse(os.path.exists(self.default))
        self.assertIn("note  sa-secret", self.out)
        self.assertTrue([c for c in self.calls() if "--wait" in c], self.calls())
        self.assertEqual(self.stack("ensure-infra", "db-reset", mssql="mounted"), 0, self.out)
        self.assert_written(self.default)

    def test_docker_desktop_s_host_prefix_is_the_same_file(self):
        self.assertEqual(self.stack("sa-secret", mssql="mounted",
                                    source="/host_mnt" + os.path.realpath(self.default)), 0, self.out)
        self.assert_written(self.default)

    def test_a_mount_from_another_file_is_refused(self):
        other = os.path.join(self.dir, "other", "mssql-sa-password")
        self.assertEqual(self.stack("sa-secret", mssql="mounted", source=other), 1, self.out)
        self.assertIn(f"the mssql container mounts the secret from {other}", self.out)
        self.assertFalse(os.path.exists(self.default) or os.path.exists(other))
        self.assertEqual(self.stack("ensure-infra", "db-reset", mssql="mounted", source=other), 1, self.out)
        self.assertFalse([c for c in self.calls() if "--wait" in c])

    def test_a_harness_child_of_an_imported_stack_is_not_explicit(self):
        """batch_e2e, e2e_stack and test_billpay import stack and start make with a copy of
        os.environ: the default path must not look like a path the operator asked for."""
        parent = ("import os, subprocess, sys\n"
                  "sys.path.insert(0, sys.argv[1])\n"
                  "import stack, batch_e2e, e2e_stack\n"
                  "print('parent', os.environ.get('CARDDEMO_MSSQL_SA_PASSWORD_FILE', 'unset'))\n"
                  "sys.exit(subprocess.run([sys.executable, os.path.join(sys.argv[1], 'stack.py'), 'sa-secret'],"
                  " env=dict(os.environ)).returncode)\n")
        e = {k: v for k, v in os.environ.items() if not k.startswith("CARDDEMO_") and not k.startswith("MAKE")}
        e.update(CARDDEMO_ENV_FILE=self.env, CARDDEMO_INFRA_DIR=self.infra, FAKE_LOG=self.log,
                 FAKE_MSSQL="not mounted", FAKE_MOUNT_SOURCE=self.default, PATH=self.bin + os.pathsep + os.environ["PATH"])
        r = subprocess.run([sys.executable, "-c", parent, SCRIPTS], capture_output=True, text=True, env=e, timeout=120)
        out = r.stdout + r.stderr
        self.assertNotIn(FAKE, out)
        self.assertEqual(r.returncode, 0, out)
        self.assertIn("parent unset", out)
        self.assertIn("not written (the mssql container does not mount the secret)", out)
        self.assertFalse(os.path.exists(self.default))
        self.assertFalse(os.path.exists(os.path.dirname(self.default)))

    def test_compose_gets_the_secret_path_without_it_in_the_environment(self):
        self.assertEqual(self.stack("ensure-infra", "db-reset", mssql="not mounted",
                                    FAKE_ENV_LOG=os.path.join(self.dir, "env.log")), 0, self.out)
        with open(os.path.join(self.dir, "env.log"), encoding="utf-8") as f:
            seen = [json.loads(line) for line in f]
        ups = [path for args, path in seen if "--wait" in args]
        self.assertEqual(ups, [self.default])
    def test_a_harness_s_private_folder_never_gets_the_secret(self):
        private = os.path.join(self.dir, "private-var")
        for mssql in ("mounted", "absent"):
            self.assertEqual(self.stack("sa-secret", mssql=mssql, CARDDEMO_VAR_DIR=private), 1, self.out)
            self.assertIn("is not the stack's secret file", self.out)
            self.assertFalse(os.path.exists(os.path.join(private, "secrets")))
            self.assertFalse(os.path.exists(self.default))
        self.assertEqual([c for c in self.calls() if c[:1] == ["inspect"]], [])

    def test_a_relative_folder_fails_before_anything(self):
        self.assertEqual(self.stack("sa-secret", mssql="mounted", CARDDEMO_VAR_DIR="var"), 2, self.out)
        self.assertIn("OUTPUT_ROOT_INVALID", self.out)
        self.assertEqual(self.calls(), [])

    def test_a_failed_copy_fails_ensure_infra(self):
        with open(self.env, "w", encoding="utf-8") as f:
            f.write(f"MSSQL_SA_PASSWORD=\nCARDDEMO_VAR_DIR={self.var}\n")
        self.assertEqual(self.stack("ensure-infra", "db-reset", mssql="mounted"), 1, self.out)
        self.assertIn("db-reset: FAILED (the SA password secret file", self.out)
        self.assertFalse([c for c in self.calls() if "--wait" in c])


class MakefileExportTest(unittest.TestCase):
    """The Makefile exports CARDDEMO_MSSQL_SA_PASSWORD_FILE only when it was given."""

    def probe(self, *args, **env) -> str:
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        mk = os.path.join(d, "probe.mk")
        with open(mk, "w", encoding="utf-8") as f:
            f.write(f"include {os.path.join(MODERNIZATION, 'Makefile')}\n"
                    "probe:\n\t@echo \"export=$${CARDDEMO_MSSQL_SA_PASSWORD_FILE-unset} var=$(CARDDEMO_MSSQL_SA_PASSWORD_FILE)\"\n")
        e = {k: v for k, v in os.environ.items() if k != "CARDDEMO_MSSQL_SA_PASSWORD_FILE" and not k.startswith("MAKE")}
        e.update(env)
        r = subprocess.run(["make", "--no-print-directory", "-f", mk, "probe", *args], capture_output=True, text=True,
                           env=e, timeout=60)
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout.strip().splitlines()[-1]

    def test_the_default_is_not_exported(self):
        out = self.probe()
        self.assertTrue(out.startswith("export=unset var=/"), out)
        self.assertTrue(out.endswith("/secrets/mssql-sa-password"), out)

    def test_a_value_from_the_environment_or_the_command_line_is_exported(self):
        self.assertEqual(self.probe(CARDDEMO_MSSQL_SA_PASSWORD_FILE="/x/env"), "export=/x/env var=/x/env")
        self.assertEqual(self.probe("CARDDEMO_MSSQL_SA_PASSWORD_FILE=/x/cmd"), "export=/x/cmd var=/x/cmd")


if __name__ == "__main__":
    unittest.main()
