#!/usr/bin/env python3
"""Run a command with the settings of the shared modernization/.env.

Every KEY=VALUE of the env file is added to the command's environment unless
the environment already has that key (even empty), so a caller can override
any value, e.g. CARDDEMO_TEST_MSSQL_DB=carddemo_test_backend. Nothing is
printed; the values reach only the child process.

When CARDDEMO_TEST_MSSQL_USER is sa and CARDDEMO_TEST_MSSQL_PASSWORD is unset
or empty, the test password defaults to MSSQL_SA_PASSWORD.

The env file is $CARDDEMO_ENV_FILE, else modernization/.env of the main
checkout (the worktree that owns .git), so it works from linked worktrees.

CARDDEMO_VAR_DIR always reaches the command, absolute: the environment's
value, else the env file's, else <main checkout>/modernization/var. A
relative value fails with OUTPUT_ROOT_INVALID before the command starts.

Usage: with_env.py [--env-file FILE] [--] COMMAND [ARG...]
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile

KEY_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)=(.*)$")
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# Shell, inside the mssql container: SQLCMDPASSWORD from the compose secret,
# or from the environment of a container created before the secret existed.
SA_SQLCMDPASSWORD = ('SQLCMDPASSWORD="$(cat /run/secrets/mssql_sa_password 2>/dev/null'
                     ' || printf %s "$MSSQL_SA_PASSWORD")"')


def main_modernization() -> str:
    """modernization/ of the main checkout (the worktree that owns .git),
    whose .env, compose file and var/ every checkout shares; this checkout's
    own directory when there is no such checkout. The Makefile does the same."""
    try:
        common = subprocess.run(
            ["git", "-C", HERE, "rev-parse", "--path-format=absolute", "--git-common-dir"],
            check=True, capture_output=True, text=True).stdout.strip()
        main = os.path.join(os.path.dirname(common.rstrip("/")), "modernization")
        if os.path.exists(os.path.join(main, "docker-compose.yml")):
            return main
    except (OSError, subprocess.CalledProcessError):
        pass
    return HERE


def default_env_file() -> str:
    return os.environ.get("CARDDEMO_ENV_FILE") or os.path.join(main_modernization(), ".env")


VAR_DIR_KEY = "CARDDEMO_VAR_DIR"
# Output settings that CARDDEMO_VAR_DIR replaced. The last three are the
# environment forms (Spring's relaxed binding) of the removed properties
# carddemo.batch.files.output-dir, carddemo.batch.files.inbound-dir and
# carddemo.transaction.report.root.
LEGACY_OUTPUT_SETTINGS = ("CARDDEMO_OUTPUT_DIR", "CARDDEMO_REPORTS_DIR", "CARDDEMO_TRANREPT_DIR",
                          "CARDDEMO_INBOUND_DIR", "CARDDEMO_BATCH_FILES_OUTPUTDIR",
                          "CARDDEMO_BATCH_FILES_INBOUNDDIR", "CARDDEMO_TRANSACTION_REPORT_ROOT")
VAR_DIR_INVALID = "OUTPUT_ROOT_INVALID: CARDDEMO_VAR_DIR must be an absolute path"
# PrivateVarDir's folders: <temp folder>/carddemo-<name>-var-<random>.
PRIVATE_VAR_PREFIX, PRIVATE_VAR_MARK = "carddemo-", "-var-"
VAR_DIR_PRIVATE = ("OUTPUT_ROOT_PRIVATE: CARDDEMO_VAR_DIR names a test harness's private folder; the stack runs "
                   "only on the shared one (a harness gives its folder to a make target as RUN_VAR_DIR)")


class VarDirError(ValueError):
    pass


def default_var_dir() -> str:
    return os.path.join(main_modernization(), "var")


def resolve_var_dir(environ=None, file_values=None) -> str:
    """CARDDEMO_VAR_DIR from the environment, else the shared .env, else
    <main checkout>/modernization/var. An empty value counts as unset; a
    relative one raises VarDirError, so the working directory never matters."""
    environ = os.environ if environ is None else environ
    if file_values is None:
        try:
            file_values = read_env(environ.get("CARDDEMO_ENV_FILE") or default_env_file())
        except OSError:
            file_values = {}
    value = (environ.get(VAR_DIR_KEY) or "").strip() or (file_values.get(VAR_DIR_KEY) or "").strip()
    if not value:
        return default_var_dir()
    if not os.path.isabs(value):
        raise VarDirError(VAR_DIR_INVALID)
    return value


def legacy_output_settings(environ=None, file_values=None) -> list:
    """The removed output settings that are still set (names only)."""
    environ = os.environ if environ is None else environ
    file_values = file_values or {}
    return [k for k in LEGACY_OUTPUT_SETTINGS if (environ.get(k) or "").strip() or (file_values.get(k) or "").strip()]


def is_private_var_dir(path: str) -> bool:
    """Whether path is a folder PrivateVarDir made, directly in the temp folder."""
    real = os.path.realpath(path)
    name = os.path.basename(real)
    return (os.path.dirname(real) == os.path.realpath(tempfile.gettempdir())
            and name.startswith(PRIVATE_VAR_PREFIX) and PRIVATE_VAR_MARK in name[len(PRIVATE_VAR_PREFIX):])


class PrivateVarDir:
    """A CARDDEMO_VAR_DIR of a harness's own, made with mkdtemp (0700) and
    removed on close; KEEP_VAR=1 keeps it and prints its path. Only the JVMs
    the harness starts get it (make gets it as RUN_VAR_DIR): stack.py,
    compose and Airflow keep the shared folder, whose run/, log/ and secrets/
    other checkouts rely on, so a test run leaves no root under its out/."""

    def __init__(self, name: str, environ=None, out=print):
        self.name, self.out, self.path = name, out, None
        self.keep = ((os.environ if environ is None else environ).get("KEEP_VAR") or "") == "1"

    def open(self) -> str:
        self.path = os.path.realpath(tempfile.mkdtemp(prefix=f"{PRIVATE_VAR_PREFIX}{self.name}{PRIVATE_VAR_MARK}"))
        return self.path

    def close(self) -> None:
        path, self.path = self.path, None
        if not path:
            return
        if self.keep:
            self.out(f"note  var              kept {path} (KEEP_VAR=1)")
            return
        try:
            shutil.rmtree(path)
        except OSError as e:
            self.out(f"warn  var              could not remove {path}: {e.strerror or e}")

    def __enter__(self) -> str:
        return self.open()

    def __exit__(self, *exc) -> bool:
        self.close()
        return False


def read_env(path: str) -> dict:
    values = {}
    with open(path, encoding="utf-8") as f:
        for line in f.read().splitlines():
            m = KEY_RE.match(line)
            if m:
                values[m.group(1)] = m.group(2)
    return values


def main(argv: list) -> int:
    args = argv[1:]
    env_file = None
    if args[:1] == ["--env-file"] and len(args) >= 2:
        env_file, args = args[1], args[2:]
    if args[:1] == ["--"]:
        args = args[1:]
    if not args:
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    env_file = env_file or default_env_file()
    try:
        values = read_env(env_file)
    except OSError:
        print(f"with_env: {env_file} is missing; run make -C modernization setup", file=sys.stderr)
        return 2

    env = dict(os.environ)
    for key, value in values.items():
        env.setdefault(key, value)
    env.setdefault("CARDDEMO_ENV_FILE", os.path.abspath(env_file))
    try:
        env[VAR_DIR_KEY] = resolve_var_dir(env, values)
    except VarDirError as e:
        print(f"with_env: {e}", file=sys.stderr)
        return 2
    if (env.get("CARDDEMO_TEST_MSSQL_USER", "sa") == "sa"
            and not env.get("CARDDEMO_TEST_MSSQL_PASSWORD")
            and env.get("MSSQL_SA_PASSWORD")):
        env["CARDDEMO_TEST_MSSQL_PASSWORD"] = env["MSSQL_SA_PASSWORD"]
    try:
        os.execvpe(args[0], args, env)
    except OSError as e:
        print(f"with_env: cannot run {args[0]}: {e.strerror}", file=sys.stderr)
        return 127


if __name__ == "__main__":
    sys.exit(main(sys.argv))
