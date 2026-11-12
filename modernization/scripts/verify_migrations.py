#!/usr/bin/env python3
"""Check that this checkout's migrations work both ways on SQL Server.

Flyway runs out of order (each owner numbers migrations in its own range,
so a new V004 can arrive after V202 is applied). This check proves that:

1. upgrade: a scratch database migrated with the base commit's db-migrate,
   then migrated again with this checkout's db-migrate, passes and its
   service logins keep exactly their grants;
2. fresh: a second scratch database migrated with this checkout's
   db-migrate from empty (version order) passes the same checks;
3. both end in the same schema: tables, columns (with their masks),
   defaults, keys, indexes, checks, foreign keys, views and routines,
   sequences, permissions, role members, the database's collation and row
   versioning, migration-seeded row counts and the applied migrations
   (version, checksum), ignoring the order and time they ran in.

The scratch databases are <CARDDEMO_DB_NAME>_upgrade and <..>_fresh. They
use the same service logins as CARDDEMO_DB_NAME (login prefix
<CARDDEMO_DB_NAME>_ unless CARDDEMO_DB_LOGIN_PREFIX is set), so no new
logins are left behind, and they are dropped at the end unless --keep.
Each db-migrate run gets a CARDDEMO_VAR_DIR of its own (mkdtemp, removed
when it ends; KEEP_VAR=1 keeps it), so their output roots never land in
the shared folder.

The base is --base REF; by default the merge base of HEAD and
origin/modernization, or HEAD^ when HEAD is that commit. It is built in a
detached worktree under build/migrate-check/ that is removed afterwards.

Run it through with_env.py (make -C modernization verify-migrations).
--require-base (CI) fails when the base has no db-migrate instead of
running the fresh half only; a BASE that is not a commit in the clone
always fails.

Usage: verify_migrations.py [--base REF] [--keep] [--require-base]
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from with_env import SA_SQLCMDPASSWORD, VAR_DIR_KEY, PrivateVarDir, main_modernization  # noqa: E402

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(HERE)
NAME = "verify-migrations"
DB_NAME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]{0,90}$")
JAR_GLOB = os.path.join("db", "db-migrate", "target", "db-migrate-*-exec.jar")

# The fields of a "col|<schema>.<table or view>.<column>|..." line after its
# name, in order; CONCAT shows NULL as an empty field. The mask counts: a
# migration that adds, changes or drops one must leave the upgraded and the
# fresh database alike.
COLUMN_FIELDS = (
    ("type", "ty.name"),
    ("max_length", "c.max_length"),
    ("precision", "c.precision"),
    ("scale", "c.scale"),
    ("is_nullable", "c.is_nullable"),
    ("collation", "c.collation_name"),
    ("is_identity", "c.is_identity"),
    ("default", "dc.definition"),
    ("computed", "cc.definition"),
    ("column_id", "c.column_id"),
    ("is_masked", "c.is_masked"),
    ("masking_function", "mc.masking_function"),
)

# One row per line, fields joined by "|". System-generated constraint names
# carry a random suffix, so they are compared as "<system>".
FINGERPRINT_SQL = r"""
SET NOCOUNT ON;
DECLARE @r TABLE (line NVARCHAR(MAX));
INSERT @r SELECT CONCAT('col|', s.name, '.', o.name, '.', c.name, '|', """ + ", '|', ".join(
    expr for _label, expr in COLUMN_FIELDS) + r""")
  FROM sys.columns c JOIN sys.objects o ON o.object_id = c.object_id AND o.type IN ('U', 'V')
  JOIN sys.schemas s ON s.schema_id = o.schema_id JOIN sys.types ty ON ty.user_type_id = c.user_type_id
  LEFT JOIN sys.default_constraints dc ON dc.object_id = c.default_object_id
  LEFT JOIN sys.computed_columns cc ON cc.object_id = c.object_id AND cc.column_id = c.column_id
  LEFT JOIN sys.masked_columns mc ON mc.object_id = c.object_id AND mc.column_id = c.column_id
  WHERE o.is_ms_shipped = 0;
-- db-migrate sets these only when it creates the database.
INSERT @r SELECT CONCAT('db|', d.collation_name, '|', d.is_read_committed_snapshot_on, '|',
    d.snapshot_isolation_state_desc)
  FROM sys.databases d WHERE d.database_id = DB_ID();
INSERT @r SELECT CONCAT('dfname|', s.name, '.', OBJECT_NAME(d.parent_object_id), '|',
    IIF(d.is_system_named = 1, '<system>', d.name))
  FROM sys.default_constraints d JOIN sys.schemas s ON s.schema_id = d.schema_id;
INSERT @r SELECT CONCAT('idx|', s.name, '.', o.name, '|',
    IIF(k.is_system_named = 1, '<system>', i.name), '|', i.type_desc, '|', i.is_unique, '|',
    i.is_primary_key, '|', i.is_unique_constraint, '|', i.filter_definition, '|',
    (SELECT STRING_AGG(CONCAT(c.name, IIF(ic.is_descending_key = 1, ' DESC', ''),
            IIF(ic.is_included_column = 1, ' INCLUDE', '')), ',')
            WITHIN GROUP (ORDER BY ic.is_included_column, ic.key_ordinal, c.name)
       FROM sys.index_columns ic JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
      WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id))
  FROM sys.indexes i JOIN sys.objects o ON o.object_id = i.object_id AND o.is_ms_shipped = 0
  JOIN sys.schemas s ON s.schema_id = o.schema_id
  LEFT JOIN sys.key_constraints k ON k.parent_object_id = i.object_id AND k.unique_index_id = i.index_id
  WHERE i.type > 0;
INSERT @r SELECT CONCAT('chk|', s.name, '.', OBJECT_NAME(k.parent_object_id), '|',
    IIF(k.is_system_named = 1, '<system>', k.name), '|', k.definition, '|', k.is_disabled)
  FROM sys.check_constraints k JOIN sys.schemas s ON s.schema_id = k.schema_id;
INSERT @r SELECT CONCAT('fk|', s.name, '.', OBJECT_NAME(f.parent_object_id), '|',
    IIF(f.is_system_named = 1, '<system>', f.name), '|', OBJECT_SCHEMA_NAME(f.referenced_object_id), '.',
    OBJECT_NAME(f.referenced_object_id), '|', f.delete_referential_action_desc, '|',
    f.update_referential_action_desc, '|', f.is_disabled, '|', f.is_not_trusted, '|',
    (SELECT STRING_AGG(CONCAT(COL_NAME(fc.parent_object_id, fc.parent_column_id), '>',
            COL_NAME(fc.referenced_object_id, fc.referenced_column_id)), ',')
            WITHIN GROUP (ORDER BY fc.constraint_column_id)
       FROM sys.foreign_key_columns fc WHERE fc.constraint_object_id = f.object_id))
  FROM sys.foreign_keys f JOIN sys.schemas s ON s.schema_id = f.schema_id;
INSERT @r SELECT CONCAT('obj|', s.name, '.', o.name, '|', o.type_desc, '|',
    CONVERT(VARCHAR(64), HASHBYTES('SHA2_256', m.definition), 2))
  FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id
  LEFT JOIN sys.sql_modules m ON m.object_id = o.object_id
  WHERE o.is_ms_shipped = 0 AND o.type IN ('U', 'V', 'P', 'FN', 'IF', 'TF', 'TR', 'SO', 'SN', 'TT');
INSERT @r SELECT CONCAT('seq|', s.name, '.', q.name, '|', TYPE_NAME(q.user_type_id), '|',
    CONVERT(NVARCHAR(40), q.start_value), '|', CONVERT(NVARCHAR(40), q.increment), '|',
    CONVERT(NVARCHAR(40), q.minimum_value), '|', CONVERT(NVARCHAR(40), q.maximum_value), '|', q.is_cycling, '|',
    CONVERT(NVARCHAR(40), q.current_value))
  FROM sys.sequences q JOIN sys.schemas s ON s.schema_id = q.schema_id;
INSERT @r SELECT CONCAT('schema|', s.name, '|', USER_NAME(s.principal_id))
  FROM sys.schemas s WHERE s.schema_id BETWEEN 5 AND 16383;
INSERT @r SELECT CONCAT('principal|', p.name, '|', p.type_desc, '|', p.default_schema_name)
  FROM sys.database_principals p WHERE p.principal_id BETWEEN 5 AND 16383 AND p.is_fixed_role = 0;
INSERT @r SELECT CONCAT('perm|', USER_NAME(p.grantee_principal_id), '|', p.state_desc, '|', p.permission_name, '|',
    p.class_desc, '|', CASE p.class WHEN 1 THEN CONCAT(OBJECT_SCHEMA_NAME(p.major_id), '.', OBJECT_NAME(p.major_id),
    IIF(p.minor_id > 0, CONCAT('.', COL_NAME(p.major_id, p.minor_id)), ''))
    WHEN 3 THEN SCHEMA_NAME(p.major_id) ELSE '' END)
  FROM sys.database_permissions p WHERE p.grantee_principal_id BETWEEN 5 AND 16383
    AND NOT (p.class = 0 AND p.permission_name = 'CONNECT');
INSERT @r SELECT CONCAT('member|', USER_NAME(m.role_principal_id), '|', USER_NAME(m.member_principal_id))
  FROM sys.database_role_members m WHERE m.member_principal_id BETWEEN 5 AND 16383;
INSERT @r SELECT CONCAT('rows|', s.name, '.', t.name, '|', SUM(p.rows))
  FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id
  JOIN sys.partitions p ON p.object_id = t.object_id AND p.index_id IN (0, 1)
  WHERE t.is_ms_shipped = 0 AND t.name <> 'flyway_schema_history' GROUP BY s.name, t.name;
IF OBJECT_ID('dbo.flyway_schema_history') IS NOT NULL
  INSERT @r SELECT CONCAT('flyway|', ISNULL(h.version, 'R'), '|', h.description, '|', h.type, '|',
      h.checksum, '|', h.success)
    FROM dbo.flyway_schema_history h
    WHERE h.version IS NOT NULL OR h.installed_rank = (SELECT MAX(h2.installed_rank)
      FROM dbo.flyway_schema_history h2 WHERE h2.version IS NULL AND h2.script = h.script);
-- sqlcmd shows at most 8000 characters of a value; a longer line is
-- compared by its hash.
SELECT IIF(LEN(line) > 3900, CONCAT(LEFT(line, 200), '#', CONVERT(VARCHAR(64), HASHBYTES('SHA2_256', line), 2)),
    line) FROM @r;
"""


def say(status: str, what: str, detail: str = "") -> None:
    print(f"{status:5} {what:16} {detail}".rstrip(), flush=True)


def fail(reason: str) -> int:
    print(f"{NAME}: FAILED ({reason})", flush=True)
    return 1


def git(*args, check=True) -> str:
    out = subprocess.run(["git", "-C", REPO, *args], capture_output=True, text=True)
    if check and out.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {out.stderr.strip()[:300]}")
    return out.stdout.strip() if out.returncode == 0 else ""


def resolve_base(ref: str | None) -> str:
    head = git("rev-parse", "HEAD")
    if ref:
        resolved = git("rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}", check=False)
        if not resolved:
            raise RuntimeError(f"BASE {ref} is not a commit in this clone; fetch it first (CI needs fetch-depth 0)")
        return resolved
    upstream = os.environ.get("VERIFY_MIGRATIONS_UPSTREAM", "origin/modernization")
    base = git("merge-base", "HEAD", upstream, check=False)
    if not base:
        raise RuntimeError(f"no merge base of HEAD and {upstream}; fetch it or pass BASE=<ref>")
    return git("rev-parse", "HEAD^") if base == head else base


def java() -> str:
    home = os.environ.get("JAVA_HOME")
    return os.path.join(home, "bin", "java") if home else "java"


def build(modernization_dir: str, log_path: str) -> str | None:
    """Builds db-migrate in modernization_dir; returns the exec jar."""
    with open(log_path, "ab") as log:
        rc = subprocess.run(["./mvnw", "-q", "-B", "-pl", "db/db-migrate", "-am", "package", "-DskipTests"],
                            cwd=modernization_dir, stdin=subprocess.DEVNULL, stdout=log,
                            stderr=subprocess.STDOUT).returncode
    jars = sorted(glob.glob(os.path.join(modernization_dir, JAR_GLOB)))
    return jars[-1] if rc == 0 and jars else None


def migrate(jar: str, db: str, prefix: str, *options) -> tuple[bool, str]:
    # db-migrate creates the scratch database's output root under
    # CARDDEMO_VAR_DIR; the shared folder would keep one per run.
    with PrivateVarDir(NAME, out=lambda line: print(line, flush=True)) as var:
        env = dict(os.environ, CARDDEMO_DB_NAME=db, CARDDEMO_DB_LOGIN_PREFIX=prefix, **{VAR_DIR_KEY: var})
        out = subprocess.run([java(), "-jar", jar, *options], env=env, stdin=subprocess.DEVNULL,
                             capture_output=True, text=True)
    text = out.stdout + out.stderr
    version = re.findall(r"schema version (\S+)", text)
    ok = out.returncode == 0 and "db-migrate: OK" in text
    if ok:
        return True, f"schema version {version[-1] if version else '?'}"
    lines = [l for l in text.splitlines() if re.search(r"ERROR|FAILED|Exception|Validate failed|Detected", l)]
    return False, " / ".join(l.strip()[:240] for l in lines[:4]) or f"exit {out.returncode}"


def sqlcmd(db: str, sql: str) -> list[str]:
    infra = os.environ.get("CARDDEMO_INFRA_DIR") or main_modernization()
    cmd = ["docker", "compose", "--project-directory", infra, "-f", os.path.join(infra, "docker-compose.yml"),
           "--env-file", os.environ["CARDDEMO_ENV_FILE"], "exec", "-T", "mssql", "sh", "-c",
           SA_SQLCMDPASSWORD + ' /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C -b '
           '-h -1 -y 8000 -d "$1" -i /dev/stdin', "sh", db]
    out = subprocess.run(cmd, input=sql, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"sqlcmd on {db}: {(out.stdout.strip() or out.stderr.strip())[:300]}")
    return [line.rstrip() for line in out.stdout.splitlines() if line.strip()]


def drop(db: str) -> None:
    sqlcmd("master", f"IF DB_ID(N'{db}') IS NOT NULL BEGIN ALTER DATABASE [{db}] SET SINGLE_USER WITH ROLLBACK "
                     f"IMMEDIATE; DROP DATABASE [{db}]; END")


def compare(fresh: list[str], upgraded: list[str]) -> list[str]:
    a, b = set(fresh), set(upgraded)
    return [f"only fresh:    {line}" for line in sorted(a - b)] + \
           [f"only upgraded: {line}" for line in sorted(b - a)]


def main(argv) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--base", help="commit to upgrade from (default: merge base with origin/modernization)")
    ap.add_argument("--keep", action="store_true", help="keep the scratch databases")
    ap.add_argument("--require-base", action="store_true",
                    help="fail instead of skipping the upgrade when the base has no db-migrate")
    args = ap.parse_args(argv)

    db = os.environ.get("CARDDEMO_DB_NAME") or "carddemo"
    if not DB_NAME_RE.match(db):
        return fail(f"CARDDEMO_DB_NAME '{db}' is not a plain name")
    if not os.environ.get("CARDDEMO_ENV_FILE"):
        return fail("run it through scripts/with_env.py")
    prefix = os.environ.get("CARDDEMO_DB_LOGIN_PREFIX") or f"{db}_"
    upgrade_db, fresh_db = f"{db}_upgrade", f"{db}_fresh"
    work = os.path.join(HERE, "build", "migrate-check")
    os.makedirs(work, exist_ok=True)
    log_path = os.path.join(work, "build.log")

    try:
        base = resolve_base(args.base)
    except RuntimeError as e:
        return fail(str(e))
    try:
        sqlcmd("master", "SELECT 1")
    except RuntimeError as e:
        return fail(f"SQL Server is not reachable: {e}")
    head = git("rev-parse", "HEAD")
    say("ok", "base", f"{base[:12]} -> HEAD {head[:12]} (scratch {upgrade_db}, {fresh_db})")
    if not git("ls-tree", "--name-only", base, "modernization/db/db-migrate/pom.xml", check=False):
        if args.require_base:
            return fail(f"the base {base[:12]} has no db-migrate, so there is nothing to upgrade from")
        say("skip", "upgrade", f"{base[:12]} has no db-migrate; nothing to upgrade from")
        base = None
    else:
        if subprocess.run(["git", "-C", REPO, "merge-base", "--is-ancestor", base, head]).returncode != 0:
            say("warn", "base", f"{base[:12]} is not an ancestor of HEAD")
        if not git("diff", "--name-only", base, head, "--", "modernization/db/", check=False):
            say("note", "base", "no change under modernization/db/ since the base; the upgrade repeats the same migrations")

    tree = os.path.join(work, f"base-{base[:12]}") if base else None
    try:
        jar = build(HERE, log_path)
        if not jar:
            return fail(f"building db-migrate in this checkout; see {log_path}")
        if tree:
            if os.path.isdir(tree):
                git("worktree", "remove", "--force", tree, check=False)
                shutil.rmtree(tree, ignore_errors=True)
            git("worktree", "add", "--detach", "--force", tree, base)
            base_jar = build(os.path.join(tree, "modernization"), log_path)
            if not base_jar:
                return fail(f"building db-migrate at {base[:12]}; see {log_path}")
            ok, detail = migrate(base_jar, upgrade_db, prefix, "--reset")
            say("ok" if ok else "FAIL", "base migrate", detail)
            if not ok:
                return fail(f"the base's own migrations fail on an empty {upgrade_db}")
        else:
            ok, detail = migrate(jar, upgrade_db, prefix, "--reset")
            say("ok" if ok else "FAIL", "base migrate", f"(none) {detail}")
        ok, detail = migrate(jar, upgrade_db, prefix, "--check-logins")
        say("ok" if ok else "FAIL", "upgrade", detail)
        if not ok:
            return fail(f"HEAD's db-migrate on a database at the base's schema: {detail}")
        ok, detail = migrate(jar, fresh_db, prefix, "--reset", "--check-logins")
        say("ok" if ok else "FAIL", "fresh", detail)
        if not ok:
            return fail(f"HEAD's db-migrate on an empty database: {detail}")
        try:
            fresh, upgraded = sqlcmd(fresh_db, FINGERPRINT_SQL), sqlcmd(upgrade_db, FINGERPRINT_SQL)
        except RuntimeError as e:
            return fail(str(e))
        diff = compare(fresh, upgraded)
        if diff:
            for line in diff[:40]:
                print(f"      {line[:300]}")
            say("FAIL", "same schema", f"{len(diff)} difference(s), {len(fresh)} fresh / {len(upgraded)} upgraded items")
            return fail("the upgraded and the fresh database differ")
        say("ok", "same schema", f"{len(fresh)} items equal")
    finally:
        if tree and os.path.isdir(tree):
            git("worktree", "remove", "--force", tree, check=False)
            shutil.rmtree(tree, ignore_errors=True)
            git("worktree", "prune", check=False)
        if not args.keep:
            for name in (upgrade_db, fresh_db):
                try:
                    drop(name)
                except RuntimeError as e:
                    say("warn", "drop", str(e))
    print(f"{NAME}: OK" + ("" if base else " (fresh only: no base with db-migrate)"), flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
