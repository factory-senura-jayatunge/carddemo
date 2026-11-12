#!/usr/bin/env python3
"""Lists active users outside the demo seed whose password is still a {bcrypt} hash.

Runs services/identity-service/src/main/resources/checks/bcrypt-outside-seed.sql
against CARDDEMO_DB_NAME (default carddemo; 1 to 128 of A-Z, a-z, 0-9 and _,
anything else exits 2 without running a command) in the running mssql
container, as sa. Sign-on replaces a {bcrypt} hash with Argon2id after its first successful
check, and a forced password change writes Argon2id, so a listed user has not
signed on since the change: a migrated or imported user to follow up before
bcrypt verification is removed. The named demo seed users (ADMIN001-005,
USER0001-005) are exempt. Prints user IDs only, never hashes. Read-only.

Ends with "identity-bcrypt-check: OK" or "identity-bcrypt-check: FAILED (...)".
Usage: identity_bcrypt_check.py   (run through with_env.py)
"""

import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from with_env import SA_SQLCMDPASSWORD, main_modernization  # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHECK = os.path.join(HERE, "services", "identity-service", "src", "main", "resources",
                     "checks", "bcrypt-outside-seed.sql")
NAME = "identity-bcrypt-check"
# Every database the stack and the tests create has such a name; anything else is refused before a
# command is built, so the name can never become shell or sqlcmd syntax.
DB_NAME = re.compile(r"[A-Za-z0-9_]{1,128}")


def valid_db_name(db: str) -> bool:
    return DB_NAME.fullmatch(db) is not None


def listed(db: str, sql: str) -> list[str]:
    infra = os.environ.get("CARDDEMO_INFRA_DIR") or main_modernization()
    cmd = ["docker", "compose", "--project-directory", infra,
           "-f", os.path.join(infra, "docker-compose.yml"),
           "--env-file", os.environ["CARDDEMO_ENV_FILE"],
           "exec", "-T", "mssql", "sh", "-c",
           SA_SQLCMDPASSWORD + ' /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C -b '
           '-h -1 -W -d "$2" -Q "$1"', "sh", sql, db]
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"cannot query {db}: {out.stdout.strip() or out.stderr.strip()}"[:300])
    return [line.strip() for line in out.stdout.splitlines() if line.strip()]


def main() -> int:
    db = os.environ.get("CARDDEMO_DB_NAME") or "carddemo"
    if not valid_db_name(db):
        print(f"{NAME}: FAILED (CARDDEMO_DB_NAME is not a plain database name: "
              "1 to 128 of A-Z, a-z, 0-9 and _)", file=sys.stderr)
        return 2
    with open(CHECK, encoding="utf-8") as f:
        sql = f.read()
    try:
        users = listed(db, sql)
    except RuntimeError as e:
        print(f"{NAME}: FAILED ({e})", file=sys.stderr)
        return 1
    if users:
        for user in users:
            print(f"fail  {user}  active, {{bcrypt}} hash, not a demo seed user, no forced change")
        print(f"{NAME}: FAILED ({len(users)} user(s) in {db} still have a {{bcrypt}} hash)", file=sys.stderr)
        return 1
    print(f"ok    no active user outside the demo seed has a {{bcrypt}} hash in {db}")
    print(f"{NAME}: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
