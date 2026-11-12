"""Tests of the SA password's compose secret: python3 -m unittest discover -s modernization/scripts/tests

The mssql service has no MSSQL_SA_PASSWORD in its environment; init_env.py
--sa-secret copies it from .env to the secret file, infra/mssql/entrypoint.sh
passes it to SQL Server's first start only, and the health check and the
scripts read the file inside the container."""
from __future__ import annotations

import io
import os
import re
import stat
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

SCRIPTS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODERNIZATION = os.path.dirname(SCRIPTS)
sys.path.insert(0, SCRIPTS)

import init_env  # noqa: E402
import with_env  # noqa: E402

SECRET = "/run/secrets/mssql_sa_password"
FAKE = "Fake0pass0for0tests0only"


def mode(path: str) -> int:
    return stat.S_IMODE(os.stat(path).st_mode)


class SaSecretFileTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.env = os.path.join(self.dir, ".env")
        self.secret = os.path.join(self.dir, "var", "secrets", "mssql-sa-password")
        self.write_env(FAKE)

    def write_env(self, value: str) -> None:
        with open(self.env, "w", encoding="utf-8") as f:
            f.write(f"MSSQL_PID=Developer\nMSSQL_SA_PASSWORD={value}\nRABBITMQ_DEFAULT_PASS=other\n")

    def run_sync(self) -> tuple[int, str]:
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            rc = init_env.main(["init_env.py", "--sa-secret", self.env, self.secret])
        text = out.getvalue() + err.getvalue()
        self.assertNotIn(FAKE, text)
        self.assertNotIn("other", text)
        return rc, text

    def test_writes_the_value_alone_mode_644_in_a_700_directory(self):
        rc, text = self.run_sync()
        self.assertEqual(rc, 0)
        self.assertIn("written", text)
        with open(self.secret, "rb") as f:
            self.assertEqual(f.read(), FAKE.encode())
        self.assertEqual(mode(self.secret), 0o644)
        self.assertEqual(mode(os.path.dirname(self.secret)), 0o700)

    def test_a_rerun_keeps_the_file_and_a_new_value_rewrites_the_same_inode(self):
        self.run_sync()
        inode = os.stat(self.secret).st_ino
        rc, text = self.run_sync()
        self.assertEqual((rc, os.stat(self.secret).st_ino), (0, inode))
        self.assertIn("kept", text)
        self.write_env("Short1pw")
        rc, text = self.run_sync()
        self.assertEqual(rc, 0)
        self.assertIn("updated", text)
        # The same inode: a running container's bind mount sees the new value.
        self.assertEqual(os.stat(self.secret).st_ino, inode)
        with open(self.secret, "rb") as f:
            self.assertEqual(f.read(), b"Short1pw")

    def test_an_empty_value_fails_and_writes_nothing(self):
        self.write_env("")
        rc, text = self.run_sync()
        self.assertEqual(rc, 1)
        self.assertIn("MSSQL_SA_PASSWORD is empty", text)
        self.assertFalse(os.path.exists(self.secret))

    def test_a_symlink_is_not_followed(self):
        os.makedirs(os.path.dirname(self.secret), mode=0o700)
        target = os.path.join(self.dir, "elsewhere")
        open(target, "w").close()
        os.symlink(target, self.secret)
        rc, _text = self.run_sync()
        self.assertEqual(rc, 1)
        self.assertEqual(os.path.getsize(target), 0)

    def test_quiet_sync_prints_only_a_change(self):
        out = io.StringIO()
        with redirect_stdout(out):
            init_env.sa_secret(self.env, self.secret, verbose=False)
            first = out.getvalue()
            init_env.sa_secret(self.env, self.secret, verbose=False)
        self.assertIn("written", first)
        self.assertEqual(out.getvalue(), first)


class EntrypointTest(unittest.TestCase):
    """infra/mssql/entrypoint.sh with its fixed paths moved into a scratch directory."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.secret = os.path.join(self.dir, "secret")
        self.master = os.path.join(self.dir, "data", "master.mdf")
        self.launch = os.path.join(self.dir, "launch_sqlservr.sh")
        self.log = os.path.join(self.dir, "launch.log")
        with open(self.launch, "w", encoding="utf-8") as f:
            f.write("#!/bin/sh\n"
                    f'if [ -n "${{MSSQL_SA_PASSWORD+x}}" ]; then echo set >> "{self.log}"; '
                    f'[ "$MSSQL_SA_PASSWORD" = "$(cat "{self.secret}")" ] && echo match >> "{self.log}"; '
                    f'else echo unset >> "{self.log}"; fi\n'
                    f'[ -n "${{SA_PASSWORD+x}}" ] && echo legacy >> "{self.log}"\n'
                    f'echo "args $*" >> "{self.log}"\n')
        os.chmod(self.launch, 0o755)
        with open(os.path.join(MODERNIZATION, "infra", "mssql", "entrypoint.sh"), encoding="utf-8") as f:
            script = f.read()
        for real, fake in ((SECRET, self.secret), ("/var/opt/mssql/data/master.mdf", self.master),
                           ("/opt/mssql/bin/launch_sqlservr.sh", self.launch)):
            self.assertIn(real, script)
            script = script.replace(real, fake)
        self.entrypoint = os.path.join(self.dir, "entrypoint.sh")
        with open(self.entrypoint, "w", encoding="utf-8") as f:
            f.write(script)

    def start(self, **env) -> tuple[int, list[str]]:
        environ = {"PATH": os.environ["PATH"], **env}
        rc = subprocess.run(["/bin/sh", self.entrypoint, "/opt/mssql/bin/sqlservr"], env=environ,
                            capture_output=True, text=True).returncode
        try:
            with open(self.log, encoding="utf-8") as f:
                return rc, f.read().split("\n")[:-1]
        except FileNotFoundError:
            return rc, []

    def write_secret(self):
        with open(self.secret, "w", encoding="utf-8") as f:
            f.write(FAKE)

    def test_the_first_start_gets_the_secret_as_the_sa_password(self):
        self.write_secret()
        self.assertEqual(self.start(), (0, ["set", "match", "args /opt/mssql/bin/sqlservr"]))

    def test_later_starts_get_no_password_even_from_the_environment(self):
        self.write_secret()
        os.makedirs(os.path.dirname(self.master))
        open(self.master, "w").close()
        rc, log = self.start(MSSQL_SA_PASSWORD="from-env", SA_PASSWORD="legacy-env")
        self.assertEqual((rc, log), (0, ["unset", "args /opt/mssql/bin/sqlservr"]))

    def test_a_first_start_without_the_secret_fails_before_sql_server(self):
        self.assertEqual(self.start(), (1, []))
        open(self.secret, "w").close()
        self.assertEqual(self.start(), (1, []))


def service_block(compose: str, name: str) -> str:
    m = re.search(rf"^  {name}:\n(.*?)(?=^  \S|^\S)", compose, re.M | re.S)
    assert m, name
    return m.group(1)


class ComposeTest(unittest.TestCase):
    def setUp(self):
        with open(os.path.join(MODERNIZATION, "docker-compose.yml"), encoding="utf-8") as f:
            self.compose = f.read()
        self.mssql = service_block(self.compose, "mssql")

    def test_the_mssql_service_has_no_sa_password_in_its_environment(self):
        settings = [line for line in self.mssql.splitlines() if not line.strip().startswith("#")]
        self.assertFalse([line for line in settings if "MSSQL_SA_PASSWORD" in line or "SA_PASSWORD" in line])

    def test_it_gets_the_secret_through_the_entrypoint(self):
        self.assertIn("    secrets:\n      - mssql_sa_password\n", self.mssql)
        self.assertIn('entrypoint: ["/bin/sh", "/opt/carddemo/mssql-entrypoint.sh"]', self.mssql)
        self.assertIn('command: ["/opt/mssql/bin/sqlservr"]', self.mssql)
        self.assertIn("- ./infra/mssql/entrypoint.sh:/opt/carddemo/mssql-entrypoint.sh:ro", self.mssql)
        self.assertRegex(self.compose, r"\nsecrets:\n(?:  #.*\n)*  mssql_sa_password:\n    file: "
                                       r"\$\{CARDDEMO_MSSQL_SA_PASSWORD_FILE:-\./var/secrets/mssql-sa-password\}\n")

    def test_the_health_check_reads_the_secret_file(self):
        (test,) = [line for line in self.mssql.splitlines() if line.strip().startswith("test:")]
        self.assertIn(f'SQLCMDPASSWORD=\\"$$(cat {SECRET})\\"', test)
        self.assertNotIn("$$MSSQL_SA_PASSWORD", test)


class ScriptsTest(unittest.TestCase):
    SCRIPTS = ("smoke.py", "billpay_e2e.py", "verify_migrations.py")

    def test_the_scripts_read_the_password_through_the_shared_snippet(self):
        for name in self.SCRIPTS:
            with open(os.path.join(SCRIPTS, name), encoding="utf-8") as f:
                source = f.read()
            self.assertIn("SA_SQLCMDPASSWORD + ' /opt/mssql-tools18/bin/sqlcmd", source, name)
            self.assertNotIn('SQLCMDPASSWORD="$MSSQL_SA_PASSWORD"', source, name)

    def sqlcmd_password(self, secret: str | None, env_value: str | None) -> str:
        d = tempfile.mkdtemp()
        path = os.path.join(d, "secret")
        if secret is not None:
            with open(path, "w", encoding="utf-8") as f:
                f.write(secret)
        snippet = with_env.SA_SQLCMDPASSWORD.replace(SECRET, path)
        self.assertNotEqual(snippet, with_env.SA_SQLCMDPASSWORD)
        env = {"PATH": os.environ["PATH"]}
        if env_value is not None:
            env["MSSQL_SA_PASSWORD"] = env_value
        out = subprocess.run(["/bin/sh", "-c", snippet + ' sh -c \'printf %s "$SQLCMDPASSWORD"\''], env=env,
                             capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        return out.stdout

    def test_the_snippet_prefers_the_secret_file(self):
        self.assertEqual(self.sqlcmd_password("from-file", "from-env"), "from-file")

    def test_the_snippet_falls_back_to_an_older_containers_environment(self):
        self.assertEqual(self.sqlcmd_password(None, "from-env"), "from-env")


if __name__ == "__main__":
    unittest.main()
