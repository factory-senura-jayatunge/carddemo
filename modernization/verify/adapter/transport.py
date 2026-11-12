"""verify-batch-spec's transport: spec_jobs.session.Session on the verify-batch driver.

A SpecSession wraps one locked differential live.Session: the private database
carddemo_verify_batch[_suffix], its lock and its run directory. Each reset() is
one case: a fresh private CARDDEMO_VAR_DIR, make db-reset, the checked one-shot
sample load, snapshot isolation and the fixture login, then batch-service in the
case's mode on 127.0.0.1.

Two paths reach the database, both sqlcmd inside the mssql container with the
SQL on stdin and scripting variables off (-x):
  * login path, as <db>_<login>: the password goes from CARDDEMO_DB_<LOGIN>_PASSWORD
    (fixture: a random one made per session, never written anywhere) into the
    container's SQLCMDPASSWORD. query, execute, snapshot, hold_lock, metadata.
  * admin path, as sa through the compose secret (with_env.SA_SQLCMDPASSWORD):
    fixed templates only. Provisioning, the snapshot-digest reads, the
    section 9 conformance queries and the lock holder's session state.
No SQL text, result or sqlcmd message reaches an exception, a log or the
console: errors carry a fixed code and at most SQL Server message numbers.
Every wait has a bound that fails instead of hanging. While the load report
gap is open, every call raises before it touches anything.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Mapping

from modernization.verify.differential import live
from modernization.verify.spec_jobs.session import LOGINS, ArchiveBlockProof, Execution, FixtureLock

from . import batchmeta, sqltext

import with_env  # noqa: E402  (scripts/ is on sys.path once live is imported)

SQLCMD = "/opt/mssql-tools18/bin/sqlcmd"
SAMPLE = live.d.REPO / "app" / "data" / "ASCII"
MODES = ("legacy", "fixed")
# Server properties a case may set; nothing else reaches the server's command line.
PROPERTIES = {"carddemo.batch.pauth.commit-interval": re.compile(r"[1-9][0-9]{0,5}\Z")}
JOB_NAME = re.compile(r"[a-z][A-Za-z0-9]{0,99}\Z")
BUSINESS_DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}\Z")
RUN_KEY = re.compile(r"[ -~]{1,250}\Z")
REFUSAL_CODE = re.compile(r"[A-Z][A-Z0-9_]{0,63}\Z")
MESSAGE_NUMBER = re.compile(r"\bMsg ([0-9]{1,6}), Level [0-9]{1,2}")
# A line sqlcmd would take as a command or a batch separator: ":r", ":!!" and
# every other colon command, and the ones it also takes without the colon, in
# any case after leading blanks (GO, !!, ED, RESET, QUIT, EXIT). -x only turns
# off variable substitution; -X would also drop the environment that carries
# SQLCMDPASSWORD, so the text itself is refused instead.
SQLCMD_LINE = re.compile(r"^[ \t]*(:|GO\b|!!|(ED|RESET|QUIT|EXIT)\b)", re.IGNORECASE | re.MULTILINE)
# Everything else is refused before it reaches sqlcmd: a carriage return, form
# feed or other control character could open a line that sqlcmd reads as a
# command but SQLCMD_LINE does not see, and a non-ASCII character could be
# narrowed by a code page conversion on its way into a column. A Latin-1 value
# goes in as sqltext.latin1_literal, in hex.
PLAIN_TEXT = re.compile(r"[\t\n\x20-\x7e]*\Z")
PASSWORD = re.compile(r"[A-Za-z0-9_-]{43}\Z")
SID = re.compile(r"0x[0-9A-F]{32}\Z")
FIXTURE_USER = "fixture_svc"
QUERY_TIMEOUT = 90          # sqlcmd -t: the server cancels a longer batch
SQL_TIMEOUT = 120           # the docker compose exec process
LOCK_TIMEOUT = 60           # taking the held lock, and LOCK_TIMEOUT inside it
LOAD_TIMEOUT = live.JOB_TIMEOUT
POLL_INTERVAL = 0.5
# The holder's own session (random host name and its login): its ID, its open
# transaction, whether a request still runs, and the locks it holds: granted
# update or exclusive locks on the requested table (directly, or on its rows,
# pages or HoBTs through sys.partitions), and requests still waiting.
HOLDER_SQL = (
    "SELECT s.session_id AS session_id, s.open_transaction_count AS open_tx, "
    "CAST(CASE WHEN EXISTS (SELECT 1 FROM sys.dm_exec_requests r WHERE r.session_id = s.session_id) "
    "THEN 1 ELSE 0 END AS BIT) AS busy, "
    "(SELECT COUNT_BIG(*) FROM sys.dm_tran_locks l "
    "LEFT JOIN sys.partitions p ON l.resource_type IN ('KEY', 'PAGE', 'RID', 'HOBT') "
    "AND p.hobt_id = l.resource_associated_entity_id "
    "WHERE l.request_session_id = s.session_id AND l.resource_database_id = DB_ID() "
    "AND l.request_status = 'GRANT' AND l.request_mode IN ({modes}) "
    "AND COALESCE(CASE WHEN l.resource_type = 'OBJECT' THEN l.resource_associated_entity_id END, p.object_id) "
    "= OBJECT_ID(N'{table}')) AS held, "
    "(SELECT COUNT_BIG(*) FROM sys.dm_tran_locks l WHERE l.request_session_id = s.session_id "
    "AND l.request_status <> 'GRANT') AS waiting "
    "FROM sys.dm_exec_sessions s "
    "WHERE s.host_name = N'{token}' AND s.login_name = N'{login}' AND s.database_id = DB_ID()")
HELD_MODES = ("U", "X", "RangeS-U", "RangeX-X", "RangeI-U", "RangeI-X", "RangeX-S", "RangeX-U")
# The archive step's session tag (batch-execution-contract.md section 4.5):
# this prefix, then the job execution ID as 8 big-endian two's-complement
# bytes. A session carries the tag when its first 24 bytes are the tag and any
# further bytes are 0x00.
EXEC_TAG_PREFIX = b"carddemo-exec-v1"
# The tagged requests of the database. The tag, the wait, the wait resource
# and the blocker all come from one sys.dm_exec_requests row, so they describe
# the same request at the same moment; the KEY's object is resolved from that
# row's own wait_resource (KEY: <db id>:<hobt id> (<hash>)) through
# sys.partitions. No caller value is in the text.
ARCHIVE_SQL = (
    "SELECT r.session_id AS waiting, r.blocking_session_id AS blocking, r.wait_type AS wait_type, "
    "r.wait_resource AS wait_resource, CONVERT(VARCHAR(256), r.context_info, 2) AS tag, DB_ID() AS db, "
    "x.hobt AS hobt, (SELECT TOP (1) OBJECT_SCHEMA_NAME(p.object_id) + N'.' + OBJECT_NAME(p.object_id) "
    "FROM sys.partitions p WHERE p.hobt_id = x.hobt) AS object_name "
    "FROM sys.dm_exec_requests r "
    "CROSS APPLY (SELECT CASE WHEN r.wait_resource LIKE 'KEY: %:% (%)' THEN TRY_CAST(SUBSTRING("
    "r.wait_resource, CHARINDEX(':', r.wait_resource, 6) + 1, "
    "CHARINDEX(' (', r.wait_resource) - CHARINDEX(':', r.wait_resource, 6) - 1) AS BIGINT) END AS hobt) x "
    "WHERE r.database_id = DB_ID() AND SUBSTRING(r.context_info, 1, 16) = 0x" + EXEC_TAG_PREFIX.hex().upper())
ARCHIVE_WAITS = ("LCK_M_X", "LCK_M_U")
ARCHIVE_OBJECT = "core.transaction"
WAIT_RESOURCE_KEY = re.compile(r"KEY: ([0-9]{1,5}):([0-9]{1,20}) \([0-9a-f]{1,40}\)\Z")
TAG_HEX = re.compile(r"(?:[0-9A-F]{2}){1,128}\Z")
# hold_lock's statement, as a whole: SELECT TOP (n) <columns> FROM <schema>.<table>
# WITH (<hints>) [WHERE <column> <op> <literal> [AND ...]] [ORDER BY <columns>].
# No parenthesis outside TOP and WITH, so no subquery, and nothing may follow,
# so no second statement; literals hold no quote.
_IDENT = r"(?:\[[A-Za-z_][A-Za-z0-9_]{0,127}\]|[A-Za-z_][A-Za-z0-9_]{0,127})"
_LITERAL = r"(?:-?[0-9]{1,19}|N?'[A-Za-z0-9 _.:-]{0,64}')"
_CONDITION = rf"(?:{_IDENT}\s*(?:=|<>|<=|>=|<|>)\s*{_LITERAL}|{_IDENT}\s+IS\s+(?:NOT\s+)?NULL)"
_ORDER = rf"{_IDENT}(?:\s+(?:ASC|DESC))?"
LOCK_STATEMENT = re.compile(
    rf"SELECT\s+TOP\s*\(\s*[1-9][0-9]{{0,3}}\s*\)\s+{_IDENT}(?:\s*,\s*{_IDENT})*"
    r"\s+FROM\s+(?:\[(?P<s1>[A-Za-z_]+)\]|(?P<s2>[A-Za-z_]+))\.(?:\[(?P<t1>[A-Za-z_]+)\]|(?P<t2>[A-Za-z_]+))"
    r"\s+WITH\s*\(\s*(?P<hints>[A-Za-z]+(?:\s*,\s*[A-Za-z]+)*)\s*\)"
    rf"(?:\s+WHERE\s+{_CONDITION}(?:\s+AND\s+{_CONDITION})*)?"
    rf"(?:\s+ORDER\s+BY\s+{_ORDER}(?:\s*,\s*{_ORDER})*)?\Z",
    re.IGNORECASE)
LOCK_HINTS = frozenset({"UPDLOCK", "XLOCK", "HOLDLOCK", "ROWLOCK", "SERIALIZABLE"})
# The fixture login is created by the session that uses it; one that exists
# already (a killed run, or someone else's) is refused, never taken over.
FIXTURE_EXISTS = "50001"
# The login gets a random SID, chosen and written to the ownership record
# before CREATE LOGIN. A login is this session's only when it has that name and
# that SID, so an error or a timeout of the CREATE never makes a login that
# existed before the run look like this session's. The drop checks both in
# the batch that drops, and reports what is left: T-SQL has no conditional
# DROP LOGIN, so the check and the drop are adjacent statements of one batch.
FIXTURE_DROP_SQL = (
    "SET NOCOUNT ON;\n"
    "DECLARE @found VARBINARY(85) = (SELECT sid FROM sys.server_principals WHERE name = N'{login}');\n"
    "IF @found = {sid} AND DATALENGTH(@found) = 16 DROP LOGIN {quoted};\n"
    "SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'{login}') "
    "THEN CASE WHEN @found IS NULL THEN 'ABSENT' ELSE 'DROPPED' END "
    "WHEN EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'{login}' AND sid = {sid} "
    "AND DATALENGTH(sid) = 16) THEN 'OWNED' "
    "ELSE 'FOREIGN' END;")
FIXTURE_GONE = ("ABSENT", "DROPPED")
# Exactly what the fixture login may hold once provisioned: its default
# CONNECT grants, the two fixed database roles and nothing else.
FIXTURE_GRANTS_SQL = (
    "SELECT "
    "(SELECT COUNT_BIG(*) FROM sys.server_role_members m WHERE m.member_principal_id = SUSER_ID(N'{login}')) "
    "AS server_roles, "
    "(SELECT COUNT_BIG(*) FROM sys.server_permissions p WHERE p.grantee_principal_id = SUSER_ID(N'{login}') "
    "AND NOT (p.permission_name = 'CONNECT SQL' AND p.state = 'G')) AS server_permissions, "
    "(SELECT STRING_AGG(CONVERT(NVARCHAR(MAX), r.name), N',') WITHIN GROUP (ORDER BY r.name) "
    "FROM sys.database_role_members m JOIN sys.database_principals r ON r.principal_id = m.role_principal_id "
    "WHERE m.member_principal_id = USER_ID(N'{user}')) AS database_roles, "
    "(SELECT COUNT_BIG(*) FROM sys.database_permissions p WHERE p.grantee_principal_id = USER_ID(N'{user}') "
    "AND NOT (p.permission_name = 'CONNECT' AND p.state = 'G')) AS database_permissions, "
    "(SELECT COUNT_BIG(*) FROM sys.database_permissions p WHERE p.grantee_principal_id = 0 "
    "AND p.permission_name = 'UNMASK') AS public_unmask, "
    "(SELECT COUNT_BIG(*) FROM sys.databases d WHERE d.database_id = DB_ID() "
    "AND d.owner_sid = SUSER_SID(N'{login}')) AS owner, "
    "(SELECT COUNT_BIG(*) FROM sys.database_principals u WHERE u.sid = SUSER_SID(N'{login}') "
    "AND u.name = N'{user}') AS mapped_user")
FIXTURE_GRANTS = {"server_roles": 0, "server_permissions": 0, "database_roles": "db_datareader,db_datawriter",
                  "database_permissions": 0, "public_unmask": 0, "owner": 0, "mapped_user": 1}


class TransportError(RuntimeError):
    """A fixed code and at most SQL Server message numbers; never SQL text or values."""

    def __init__(self, code: str, numbers: tuple[str, ...] = ()):
        super().__init__(code + (f" (Msg {','.join(numbers)})" if numbers else ""))
        self.code, self.numbers = code, tuple(numbers)


def message_numbers(*texts: str) -> tuple[str, ...]:
    found = []
    for text in texts:
        for number in MESSAGE_NUMBER.findall(text or ""):
            if number not in found:
                found.append(number)
    return tuple(found[:5])


def run_command(argv: list[str], *, stdin: str | None = None, env: Mapping[str, str] | None = None,
                timeout: float, cwd: Path | None = None) -> tuple[int, str, str]:
    """(exit code, stdout, stderr) of one bounded process; a timeout kills it and fails."""
    try:
        out = subprocess.run(argv, input=stdin, stdin=subprocess.DEVNULL if stdin is None else None,
                             capture_output=True, text=True, encoding="utf-8", errors="replace",
                             env=None if env is None else dict(env), timeout=timeout, cwd=cwd)
    except subprocess.TimeoutExpired:
        raise TransportError("TIMEOUT") from None
    return out.returncode, out.stdout, out.stderr


def _gap() -> None:
    if live.d.LOAD_REPORT_GAP:
        raise live.d.PipelineError(live.d.LOAD_REPORT_GAP)


def _plain(sql: str) -> str:
    if not isinstance(sql, str) or not sql.strip() or not PLAIN_TEXT.match(sql) or SQLCMD_LINE.search(sql):
        raise TransportError("SQL_SHAPE")
    return sql


def _positive(value) -> bool:
    return type(value) is int and value >= 1


def execution_tag(execution_id) -> bytes:
    """The archive step's 24-byte session tag for one job execution ID.

    Contract section 4.5: an execution ID is an int (never a bool) from 0 to
    2^63 - 1, the domain of batch.BATCH_JOB_EXECUTION_SEQ, which starts at 0.
    For these IDs the two's-complement bytes equal the unsigned ones, so the
    tag is compared as bytes and never decoded back into an ID.
    """
    if not live.d.is_execution_id(execution_id):
        raise TransportError("ARCHIVE_EXECUTION")
    return EXEC_TAG_PREFIX + execution_id.to_bytes(8, "big", signed=True)


def _carries_tag(hex_text, tag: bytes) -> bool:
    if not isinstance(hex_text, str) or not TAG_HEX.match(hex_text):
        return False
    raw = bytes.fromhex(hex_text)
    return raw[:len(tag)] == tag and not raw[len(tag):].strip(b"\x00")


def _fsync_dir(folder: Path) -> None:
    fd = os.open(folder, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def private_copy(source: Path, target: Path) -> Path:
    """A regular, non-link 0600 copy; the original must be a regular file and stay unchanged."""
    source = Path(source)
    if source.is_symlink() or not source.is_file():
        raise TransportError("SOURCE_FILE")
    before = _sha256(source)
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
    with open(fd, "wb") as f:
        f.write(source.read_bytes())
    os.chmod(target, 0o600)
    if _sha256(source) != before or _sha256(target) != before:
        raise TransportError("SOURCE_CHANGED")
    return target


def aggregate_rows(table: str, output: str) -> list[dict]:
    """The one line of an aggregate template, "<row count> <digest>", as parse_aggregate's rows."""
    lines = [line for line in output.splitlines() if line.strip()]
    parts = lines[0].split() if len(lines) == 1 else []
    if len(parts) != 2 or not re.fullmatch(r"[0-9]{1,19}", parts[0]):
        raise sqltext.Refused("DIGEST_RESULT", table)
    return [{"row_count": int(parts[0]), "digest": parts[1]}]


class _Shared:
    """What every login of one SpecSession shares."""

    def __init__(self, live_session, runner, popen, new_driver, switches):
        self.live, self.runner, self.popen = live_session, runner, popen
        self.new_driver, self.switches = new_driver, switches
        if not sqltext.DB_PATTERN.match(live_session.db):
            raise TransportError("DATABASE_NOT_PRIVATE")
        self.db = live_session.db
        self.fixture_password = secrets.token_urlsafe(32)
        self.env: dict | None = None
        self.count = self.loads = 0
        self.work = self.var = self.driver = self.output_root = self.api = None
        self.switch_args: tuple[str, ...] = ()
        self.fixture_sid = "0x" + secrets.token_hex(16).upper()
        # None, "unclear" (CREATE LOGIN sent, outcome unknown) or "created".
        self.fixture_state: str | None = None
        self.conformant = False
        # The ownership record: the login's name and SID from before its
        # CREATE until close() has dropped it. It sits next to the database's
        # lock, outside the run directory (a temporary one by default), so a
        # failed or skipped drop leaves it for the operator and for the next
        # run, which refuses to start while it exists.
        self.manifest = Path(live.STATE_DIR) / f"{self.db}.fixture-login.json"


class _Refused:
    """A launch the API refused: its code is the status, as the Protocol asks."""

    def __init__(self, code: str):
        self.execution = Execution(code)

    def done(self) -> bool:
        return True

    def wait(self, timeout: float = 120) -> Execution:
        return self.execution


class _Running:
    def __init__(self, session: "SpecSession", execution_id: int):
        self.session, self.execution_id = session, execution_id

    def _poll(self) -> str:
        status, body, _ = self.session._api().poll(self.execution_id)
        if status != 200 or body.get("executionId") != self.execution_id:
            raise TransportError("POLL")
        state = body.get("executionStatus")
        if not isinstance(state, str):
            raise TransportError("POLL")
        return state

    def done(self) -> bool:
        return self._poll() in live.batch_e2e.TERMINAL

    def wait(self, timeout: float = 120) -> Execution:
        deadline = time.monotonic() + timeout
        while True:
            state = self._poll()
            if state in live.batch_e2e.TERMINAL:
                return self.session._execution(self.execution_id, state)
            if time.monotonic() > deadline:
                raise TimeoutError("JOB_TIMEOUT")
            time.sleep(POLL_INTERVAL)


class SpecSession:
    """spec_jobs.session.Session for one locked live.Session; close() when done."""

    def __init__(self, live_session, *, runner=run_command, popen=subprocess.Popen, new_driver=None,
                 switches: list[str] | None = None):
        self._s = _Shared(live_session, runner, popen, new_driver or live.LiveDriver, switches)
        self.login = "batch"

    # --- handles -----------------------------------------------------------

    def as_login(self, login_id: str) -> "SpecSession":
        if login_id not in LOGINS:
            raise TransportError("UNKNOWN_LOGIN")
        sibling = object.__new__(SpecSession)
        sibling._s, sibling.login = self._s, login_id
        return sibling

    @property
    def var_dir(self) -> Path:
        if self._s.var is None:
            raise TransportError("NO_CASE")
        return self._s.var

    def _api(self):
        if self._s.api is None:
            raise TransportError("NO_SERVER")
        return self._s.api

    # --- sqlcmd ------------------------------------------------------------

    def _compose(self) -> list[str]:
        infra = os.environ.get("CARDDEMO_INFRA_DIR") or live.stack.main_modernization()
        return ["docker", "compose", "--project-directory", infra, "-f", os.path.join(infra, "docker-compose.yml"),
                "--env-file", live.stack.ENV_FILE, "exec", "-T"]

    def _password(self) -> str:
        s = self._s
        if self.login == "fixture":
            return s.fixture_password
        if s.env is None:
            s.env = s.live.env(None)
        password = s.env.get(f"CARDDEMO_DB_{self.login.upper()}_PASSWORD") or ""
        if not password:
            raise TransportError("LOGIN_PASSWORD_MISSING")
        return password

    def _login_argv(self, *, host: str | None = None, script: bool = True) -> list[str]:
        argv = [*self._compose(), "-e", "SQLCMDPASSWORD", "mssql", SQLCMD, "-S", "localhost",
                "-U", f"{self._s.db}_{self.login}", "-C", "-b", "-x", "-h", "-1", "-y", "0", "-f", "65001",
                "-t", str(QUERY_TIMEOUT), "-d", self._s.db]
        if host is not None:
            argv += ["-H", host]
        return argv + (["-i", "/dev/stdin"] if script else [])

    def _login_sql(self, sql: str) -> str:
        env = dict(os.environ, SQLCMDPASSWORD=self._password())
        rc, out, err = self._s.runner(self._login_argv(), stdin=_plain(sql) + "\n", env=env, timeout=SQL_TIMEOUT)
        if rc != 0:
            raise TransportError("SQL_FAILED", message_numbers(out, err))
        return out

    def _admin(self, sql: str, database: str | None = None) -> str:
        database = database or self._s.db
        if database != "master" and not sqltext.DB_PATTERN.match(database):
            raise TransportError("DATABASE_NOT_PRIVATE")
        script = (with_env.SA_SQLCMDPASSWORD + f" {SQLCMD} -S localhost -U sa -C -b -x -h -1 -y 0 -f 65001 "
                  f'-t {QUERY_TIMEOUT} -d "$1" -i /dev/stdin')
        env = {k: v for k, v in os.environ.items() if k != "SQLCMDPASSWORD"}
        rc, out, err = self._s.runner([*self._compose(), "mssql", "sh", "-c", script, "sh", database],
                                      stdin=_plain(sql) + "\n", env=env, timeout=SQL_TIMEOUT)
        if rc != 0:
            raise TransportError("ADMIN_SQL_FAILED", message_numbers(out, err))
        return out

    # --- cases -------------------------------------------------------------

    def reset(self, *, mode: str, properties: Mapping[str, str] | None = None) -> None:
        _gap()
        if mode not in MODES:
            raise TransportError("MODE")
        extra = []
        for key, value in (properties or {}).items():
            if key not in PROPERTIES or not isinstance(value, str) or not PROPERTIES[key].match(value):
                raise TransportError("PROPERTY")
            extra.append(f"--{key}={value}")
        s = self._s
        switches = list(s.switches) if s.switches is not None else live.d.batch_switches()
        fidelity = (live.d.Mode("legacy", frozenset()) if mode == "legacy"
                    else live.d.Mode("all fixed", frozenset(switches)))
        switch_args, server_env = live.d.spring_settings(fidelity, switches, live.d.interest_parm(SAMPLE))
        s.live.stop_server()
        s.api = s.work = s.var = s.driver = s.output_root = None
        s.count += 1
        work = s.live.root / f"spec-{s.count:03d}-{mode}"
        driver = s.new_driver(s.live, work, fidelity)
        driver.reset()
        s.work, s.driver, s.output_root, s.switch_args = work, driver, driver.output_root, tuple(switch_args)
        # The checked sample load: a completed dataLoadJob whose report matches.
        driver.load(SAMPLE, list(switch_args))
        self._provision()
        s.api = s.live.start_server([*switch_args, *extra], driver.env() | server_env, work / "server.log")
        s.var = driver.var

    def _provision(self) -> None:
        s = self._s
        login, password = f"{s.db}_fixture", s.fixture_password
        if not PASSWORD.match(password):
            raise TransportError("FIXTURE_PASSWORD")
        q_login, q_user = sqltext.quote(login), sqltext.quote(FIXTURE_USER)
        if not SID.match(s.fixture_sid):
            raise TransportError("FIXTURE_SID")
        if s.fixture_state == "unclear":
            raise TransportError("FIXTURE_UNCLEAR")
        if s.fixture_state is None:
            # On disk before the login exists, removed only after its drop.
            try:
                self._write_record(self._record(), replace=False)
            except FileExistsError:
                raise TransportError("FIXTURE_RECORD_LEFT") from None
            except OSError:
                raise TransportError("FIXTURE_MANIFEST") from None
            create = (f"IF SUSER_ID(N'{login}') IS NOT NULL THROW {FIXTURE_EXISTS}, 'FIXTURE_LOGIN_EXISTS', 1;\n"
                      f"CREATE LOGIN {q_login} WITH PASSWORD = N'{password}', SID = {s.fixture_sid}, "
                      "CHECK_POLICY = OFF, CHECK_EXPIRATION = OFF;")
            try:
                self._admin(create)
            except TransportError as e:
                # 15025: created by someone else between the check and CREATE.
                if FIXTURE_EXISTS in e.numbers or "15025" in e.numbers:
                    s.manifest.unlink()
                    raise TransportError("FIXTURE_LOGIN_EXISTS") from None
                # Not proof either way: close() drops a login of this name
                # only if it carries this session's SID.
                s.fixture_state = "unclear"
                raise TransportError("PROVISION", e.numbers) from None
            s.fixture_state = "created"
        # db-reset made a new database, so the user and its roles are new in each case.
        sql = (sqltext.allow_snapshot_sql(s.db) + "\n"
               f"CREATE USER {q_user} FOR LOGIN {q_login};\n"
               f"ALTER ROLE db_datareader ADD MEMBER {q_user};\n"
               f"ALTER ROLE db_datawriter ADD MEMBER {q_user};")
        try:
            self._admin(sql)
        except TransportError as e:
            raise TransportError("PROVISION", e.numbers) from None
        grants = sqltext.parse_json_output(self._admin(sqltext.json_query(
            FIXTURE_GRANTS_SQL.format(login=login, user=FIXTURE_USER))))
        if len(grants) != 1 or grants[0] != FIXTURE_GRANTS:
            raise TransportError("FIXTURE_GRANTS")
        if not s.conformant:
            answers = []
            for query, _expected in sqltext.conformance_queries():
                try:
                    answers.append(aggregate_rows("conformance", self._admin(query)))
                except sqltext.Refused:
                    answers.append([])
            if not sqltext.check_conformance(answers):
                raise TransportError("CONFORMANCE")
            s.conformant = True

    def _record(self, cleanup: str | None = None) -> dict:
        s = self._s
        login = f"{s.db}_fixture"
        record = {"login": login, "database": s.db, "sid": s.fixture_sid,
                  "drop": FIXTURE_DROP_SQL.format(login=login, sid=s.fixture_sid, quoted=sqltext.quote(login))}
        if cleanup:
            record["cleanup"] = cleanup
        return record

    def _write_record(self, record: dict, *, replace: bool) -> None:
        """Puts the whole ownership record in place, durably, or raises OSError.

        The record goes to a fresh temporary file in the same folder, which
        is flushed to disk, then becomes the record in one step: a hard link
        that fails with FileExistsError when a record is already there, or
        os.replace when replace is set. The folder is flushed last, so the
        name is on disk before the caller goes on (before CREATE LOGIN). A
        kill at any moment leaves no record, the old one or the new one,
        never an empty or partial file.
        """
        folder = self._s.manifest.parent
        temp = folder / f".{self._s.manifest.name}.{secrets.token_hex(8)}.tmp"
        fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0), 0o600)
        try:
            with open(fd, "w", encoding="utf-8") as f:
                json.dump(record, f)
                f.flush()
                os.fsync(f.fileno())
            if replace:
                os.replace(temp, self._s.manifest)
            else:
                os.link(temp, self._s.manifest)
        finally:
            temp.unlink(missing_ok=True)
        _fsync_dir(folder)

    @property
    def fixture_record(self) -> Path | None:
        """The ownership record while it exists: the login was not dropped yet."""
        return self._s.manifest if self._s.manifest.exists() else None

    def close(self) -> None:
        """Stops the server and drops the fixture login: exact name and this session's SID."""
        s = self._s
        try:
            s.live.stop_server()
        finally:
            s.api = None
            if s.fixture_state is not None:
                self._drop_fixture()

    def _drop_fixture(self) -> None:
        s = self._s
        record = self._record()
        try:
            out = self._admin(record["drop"], "master")
            outcome = out.strip() if out.strip() in (*FIXTURE_GONE, "OWNED", "FOREIGN") else "UNREADABLE"
            numbers = ()
        except TransportError as e:
            outcome, numbers = e.code, e.numbers
        if outcome in FIXTURE_GONE:
            s.fixture_state = None
            s.manifest.unlink(missing_ok=True)
            return
        # The record stays, with what the drop found, for the operator.
        try:
            self._write_record(self._record(outcome), replace=True)
        except OSError:
            pass
        # Another login of that name is preserved, never dropped.
        raise TransportError("FIXTURE_LOGIN_FOREIGN" if outcome == "FOREIGN" else "FIXTURE_DROP", numbers)

    def load(self, source_dir: Path, *, manifest: Path | None = None,
             authorization_file: Path | None = None) -> Execution:
        _gap()
        s = self._s
        if s.driver is None:
            raise TransportError("NO_CASE")
        s.loads += 1
        base = s.work / f"spec-load-{s.loads:02d}"
        base.mkdir(mode=0o700)
        source = base / "source"
        source.mkdir(mode=0o700)
        for path in sorted(Path(source_dir).iterdir()):
            if path.is_file() and not path.is_symlink():
                private_copy(path, source / path.name)
        pauth = private_copy(Path(authorization_file or live.PAUTH_FILE), base / "pauth.dat")
        args = list(s.switch_args)
        if manifest is not None:
            args.append(f"--{live.d.LAYOUT_MANIFEST_PROPERTY}={private_copy(Path(manifest), base / 'manifest.json')}")
        id_dir = base / "load-id"
        id_dir.mkdir(mode=0o700)
        id_file = id_dir / "execution-id"
        batch, _ = s.live.jars()
        env = s.driver.env() | {"CARDDEMO_LOAD_SOURCE_DIR": str(source), "CARDDEMO_PAUTH_SOURCE_FILE": str(pauth)}
        argv = [live.stack.java_bin(), "-jar", batch, "--spring.profiles.active=demo", *args,
                f"--carddemo.batch.pauth.source-file={pauth}", f"--{live.ID_FILE_PROPERTY}={id_file}"]
        try:
            rc, out, err = s.runner(argv, env=env, timeout=LOAD_TIMEOUT, cwd=live.BATCH_MODULE)
            log = base / "load.log"
            log.write_text(out + err, encoding="utf-8")
            os.chmod(log, 0o600)
            try:
                execution_id = live.read_execution_id(id_file)
            except live.d.PipelineError:
                raise TransportError("LOAD_EXECUTION_ID") from None
        finally:
            shutil.rmtree(id_dir, ignore_errors=True)
        # An empty file names no execution: nothing is bound to it, never the newest dataLoadJob.
        if execution_id is None:
            raise TransportError("LOAD_EXECUTION_UNKNOWN")
        execution = self._execution(execution_id, None)
        if (rc == 0) != (execution.status == "COMPLETED"):
            raise TransportError("LOAD_EXIT_STATUS")
        return execution

    # --- SQL ---------------------------------------------------------------

    def query(self, sql: str) -> list[dict[str, Any]]:
        _gap()
        return sqltext.parse_json_output(self._login_sql(sqltext.json_query(_plain(sql))))

    def execute(self, sql: str) -> None:
        _gap()
        self._login_sql(sqltext.execute_batch(_plain(sql)))

    def fixture_execute(self, sql: str) -> None:
        self.as_login("fixture").execute(sql)

    def fixture_lock(self, sql: str):
        return self.as_login("fixture").hold_lock(sql)

    @contextmanager
    def hold_lock(self, sql: str):
        """A separate connection: BEGIN TRAN + sql, ROLLBACK on exit.

        sql is exactly LOCK_STATEMENT: one SELECT TOP (n) on one allowlisted
        table WITH (UPDLOCK or XLOCK, ...), no subquery and nothing after it.
        It must return a row: a holder that locked nothing fails instead of
        proving nothing. The holder is sqlcmd reading batches from a pipe, and
        nothing of its output is read. The lock counts as held only when the
        admin path sees exactly one session with the holder's random host name
        and login, idle, with an open transaction, no waiting lock request,
        and a granted update or exclusive lock on that table or its rows. A
        failed statement or an empty result ends sqlcmd (-b, XACT_ABORT),
        which fails the call. It yields FixtureLock with the session ID of
        the row that proved the lock held. When the body ends normally the
        same proof runs once more before the ROLLBACK and must show that same
        session; a holder that died, lost its lock or is another session
        meanwhile raises LOCK_LOST after the clean-up.
        """
        _gap()
        body = _plain(sql).strip().rstrip(";").strip()
        shape = LOCK_STATEMENT.match(body)
        hints = {h.strip().upper() for h in shape["hints"].split(",")} if shape else set()
        if not hints or not hints <= LOCK_HINTS or not hints & {"UPDLOCK", "XLOCK"}:
            raise TransportError("LOCK_SHAPE")
        table = f"{(shape['s1'] or shape['s2']).lower()}.{(shape['t1'] or shape['t2']).lower()}"
        if table not in sqltext.ALLOWED_TABLES:
            raise TransportError("LOCK_TARGET")
        token = "spec" + secrets.token_hex(8)
        check = HOLDER_SQL.format(token=token, login=f"{self._s.db}_{self.login}", table=table,
                                  modes=", ".join(f"'{m}'" for m in HELD_MODES))
        env = dict(os.environ, SQLCMDPASSWORD=self._password())
        proc = self._s.popen(self._login_argv(host=token, script=False), stdin=subprocess.PIPE,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env, text=True,
                             start_new_session=True)
        try:
            proc.stdin.write(f"SET XACT_ABORT ON; SET NOCOUNT ON; SET LOCK_TIMEOUT {LOCK_TIMEOUT * 1000}; "
                             f"BEGIN TRANSACTION; {body}; "
                             "IF @@ROWCOUNT = 0 THROW 50000, 'HOLD_LOCK_NO_ROW', 1;\nGO\n")
            proc.stdin.flush()
            holder = FixtureLock(self._await_holder(proc, check))
        except BaseException:
            proc.kill()
            proc.wait(timeout=SQL_TIMEOUT)
            raise
        try:
            yield holder
        except BaseException:
            # The case's own failure stays the one reported.
            self._release(proc)
            raise
        # Whatever the body observed counts as "while held" only if the
        # holder still holds the same lock now, before its ROLLBACK.
        try:
            held = proc.poll() is None and self._holder_session(self._holder_rows(check)) == holder.session_id
        except Exception:
            held = False
        self._release(proc)
        if not held:
            raise TransportError("LOCK_LOST")

    def _release(self, proc) -> None:
        try:
            proc.stdin.write("IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\nGO\n")
            proc.stdin.close()
        except OSError:
            pass
        try:
            proc.wait(timeout=SQL_TIMEOUT)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=SQL_TIMEOUT)

    def _holder_rows(self, check: str) -> list[dict]:
        rows = sqltext.parse_json_output(self._admin(sqltext.json_query(check)))
        if len(rows) > 1:
            raise TransportError("LOCK_HOLDER_AMBIGUOUS")
        return rows

    @staticmethod
    def _holder_session(rows: list[dict]) -> int | None:
        """The holder's session ID when its row proves the lock held, else None."""
        row = rows[0] if rows else {}
        counts = [row.get(k) for k in ("open_tx", "held", "waiting")]
        ready = (all(type(c) is int for c in counts) and counts[0] > 0 and counts[1] > 0 and counts[2] == 0
                 and row.get("busy") is False and _positive(row.get("session_id")))
        return row["session_id"] if ready else None

    def _await_holder(self, proc, check: str) -> int:
        deadline = time.monotonic() + LOCK_TIMEOUT + 10
        while True:
            if proc.poll() is not None:
                raise TransportError("LOCK_FAILED")
            session_id = self._holder_session(self._holder_rows(check))
            if session_id is not None:
                return session_id
            if time.monotonic() > deadline:
                raise TransportError("LOCK_TIMEOUT")
            time.sleep(0.2)

    def wait_for_archive_blocked(self, running, holder, *, timeout: float = 30.0) -> ArchiveBlockProof:
        """The proof that this archive execution's request waits on holder's key lock.

        The request is the one whose session carries the execution's tag
        (contract section 4.5); exactly one may. It is ready only when that
        same sys.dm_exec_requests row is blocked by holder.session_id with
        LCK_M_X or LCK_M_U on a KEY of core.transaction in this database.
        Polls the fixed admin template until then; an execution that ends
        first, a second tagged request or the deadline fails. observed_at_ns
        is this process's monotonic clock when the row was received.
        """
        _gap()
        if not isinstance(holder, FixtureLock) or not _positive(holder.session_id):
            raise TransportError("LOCK_SESSION_REQUIRED")
        if type(timeout) not in (int, float) or not 0 < timeout <= LOCK_TIMEOUT:
            raise TransportError("ARCHIVE_TIMEOUT_VALUE")
        # The execution ID may be 0 (section 4.5); the session IDs stay >= 1.
        # A refused ID is the caller's usage error, raised before any SQL.
        execution_id = getattr(running, "execution_id", None)
        tag = execution_tag(execution_id)
        deadline = time.monotonic() + timeout
        while True:
            rows = sqltext.parse_json_output(self._admin(sqltext.json_query(ARCHIVE_SQL)))
            observed = time.monotonic_ns()
            tagged = [row for row in rows if _carries_tag(row.get("tag"), tag)]
            if len(tagged) > 1:
                raise TransportError("ARCHIVE_TAG_AMBIGUOUS")
            proof = self._archive_proof(tagged[0], execution_id, holder, observed) if tagged else None
            if proof is not None:
                return proof
            if running.done():
                raise TransportError("ARCHIVE_NOT_BLOCKED")
            if time.monotonic() > deadline:
                raise TransportError("ARCHIVE_BLOCK_TIMEOUT")
            time.sleep(0.2)

    @staticmethod
    def _archive_proof(row: dict, execution_id: int, holder: FixtureLock, observed: int) -> ArchiveBlockProof | None:
        waiting, blocking, wait_type = row.get("waiting"), row.get("blocking"), row.get("wait_type")
        key = WAIT_RESOURCE_KEY.match(row.get("wait_resource") or "")
        if not (_positive(waiting) and _positive(blocking) and blocking == holder.session_id and waiting != blocking
                and wait_type in ARCHIVE_WAITS and key and _positive(row.get("db")) and _positive(row.get("hobt"))
                and int(key[1]) == row["db"] and int(key[2]) == row["hobt"]
                and row.get("object_name") == ARCHIVE_OBJECT):
            return None
        return ArchiveBlockProof(True, execution_id, waiting, blocking, ARCHIVE_OBJECT, "KEY", wait_type, observed)

    def snapshot(self, table: str, keys: tuple[str, ...], *, exclude: tuple[str, ...] = ()) -> dict:
        _gap()
        meta = sqltext.parse_columns(table, self.query(sqltext.columns_sql(table)))
        sql, _ = sqltext.per_key_sql(table, meta, keys, exclude, login_path=True)
        rows = sqltext.parse_json_output(self._login_sql(sqltext.json_query(sql, snapshot=True)))
        return sqltext.parse_per_key(table, keys, rows)

    def snapshot_digest(self, table: str, keys: tuple[str, ...] = (), *, exclude: tuple[str, ...] = (),
                        order_by: tuple[str, ...] = (), columns: tuple[str, ...] = (),
                        aggregate: bool = False) -> dict:
        _gap()
        sqltext.split_table(table)
        if aggregate and (keys or exclude):
            raise sqltext.Refused("AGGREGATE_SHAPE", table)
        if not aggregate and (columns or order_by):
            raise sqltext.Refused("PER_KEY_SHAPE", table)
        meta = sqltext.parse_columns(table, sqltext.parse_json_output(
            self._admin(sqltext.json_query(sqltext.columns_sql(table)))))
        if aggregate:
            sql = sqltext.snapshot_read(sqltext.table_aggregate_sql(table, meta, columns, order_by))
            return sqltext.parse_aggregate(table, aggregate_rows(table, self._admin(sql)))
        sql, _ = sqltext.per_key_sql(table, meta, keys, exclude)
        rows = sqltext.parse_json_output(self._admin(sqltext.json_query(sql, snapshot=True)))
        return sqltext.parse_per_key(table, keys, rows)

    # --- batch-service -----------------------------------------------------

    def cycle(self, business_date: str, *, completed: bool = False) -> int:
        _gap()
        if not isinstance(business_date, str) or not BUSINESS_DATE.match(business_date):
            raise TransportError("BUSINESS_DATE")
        status, body, _ = self._api().post("/internal/cycles", {"businessDate": business_date, "interest": "never"})
        if status not in (200, 201) or not _positive(body.get("cycleId")) or body.get("businessDate") != business_date:
            raise TransportError("CYCLE_REGISTER")
        cycle_id = body["cycleId"]
        if completed:
            self._window_open({"cycleId": cycle_id}, "cycleId", cycle_id)
            status, closed, _ = self._api().window("close", {"cycleId": cycle_id, "complete": True})
            if status != 200 or (closed.get("cycle") or {}).get("status") != "COMPLETED":
                raise TransportError("CYCLE_COMPLETE")
        return cycle_id

    def _window_open(self, body: dict, member: str, value) -> None:
        status, answer, _ = self._api().window("open", body)
        if status != 200 or answer.get(member) != value:
            raise TransportError("WINDOW_OPEN")

    @contextmanager
    def window(self, *, cycle_id: int | None = None, run_key: str | None = None):
        _gap()
        if (cycle_id is None) == (run_key is None):
            raise TransportError("WINDOW_HOLDER")
        if cycle_id is not None:
            if not _positive(cycle_id):
                raise TransportError("WINDOW_HOLDER")
            opener, member, value = {"cycleId": cycle_id}, "cycleId", cycle_id
            closer = {"cycleId": cycle_id, "complete": False}
        else:
            if not isinstance(run_key, str) or not RUN_KEY.match(run_key):
                raise TransportError("WINDOW_HOLDER")
            opener, member, value = {"windowRunKey": run_key}, "windowRunKey", run_key
            closer = dict(opener)
        self._window_open(opener, member, value)
        try:
            yield
        except BaseException:
            # The case's own failure stays the one reported.
            try:
                self._api().window("close", closer)
            except Exception:
                pass
            raise
        status, _body, _ = self._api().window("close", closer)
        if status != 200:
            raise TransportError("WINDOW_CLOSE")

    def launch(self, job: str, parameters: Mapping[str, Any]):
        _gap()
        if not isinstance(job, str) or not JOB_NAME.match(job):
            raise TransportError("JOB_NAME")
        status, body, _ = self._api().launch(job, json.loads(json.dumps(dict(parameters))))
        if status == 202:
            if not live.d.is_execution_id(body.get("executionId")):
                raise TransportError("LAUNCH")
            return _Running(self, body["executionId"])
        code = body.get("code")
        if (400 <= status < 500 and status not in (401, 403) or status == 503) and isinstance(code, str) \
                and REFUSAL_CODE.match(code):
            return _Refused(code)
        raise TransportError("LAUNCH")

    def _execution(self, execution_id: int, api_status: str | None) -> Execution:
        reader = self.as_login("batch")
        job = batchmeta.job(reader.query(batchmeta.job_sql(execution_id)), execution_id)
        counts, statuses, contexts = batchmeta.steps(reader.query(batchmeta.steps_sql(execution_id)))
        if api_status is not None and job["status"] != api_status:
            raise TransportError("STATUS_MISMATCH")
        return Execution(job["status"], execution_id=execution_id, job_instance_id=job["job_instance_id"],
                         exit_description=job["exit_description"], contexts=contexts,
                         instance_root=self._s.output_root, step_counts=counts, step_statuses=statuses,
                         job_context=job["job_context"])

    def log_bytes(self) -> bytes:
        if self._s.work is None:
            return b""
        try:
            return (self._s.work / "server.log").read_bytes()
        except FileNotFoundError:
            return b""
