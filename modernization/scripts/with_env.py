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

Usage: with_env.py [--env-file FILE] [--] COMMAND [ARG...]
"""

import os
import re
import subprocess
import sys

KEY_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)=(.*)$")
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def default_env_file() -> str:
    if os.environ.get("CARDDEMO_ENV_FILE"):
        return os.environ["CARDDEMO_ENV_FILE"]
    try:
        common = subprocess.run(
            ["git", "-C", HERE, "rev-parse", "--path-format=absolute", "--git-common-dir"],
            check=True, capture_output=True, text=True).stdout.strip()
        main = os.path.join(os.path.dirname(common.rstrip("/")), "modernization")
        if os.path.exists(os.path.join(main, "docker-compose.yml")):
            return os.path.join(main, ".env")
    except (OSError, subprocess.CalledProcessError):
        pass
    return os.path.join(HERE, ".env")


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
