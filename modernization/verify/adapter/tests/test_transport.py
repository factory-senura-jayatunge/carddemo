"""The transport without docker, a database, a JVM or a server: sqlcmd, the
driver, the live session and the API are stand-ins that record what they get."""
from __future__ import annotations

import json
import os
import re
import stat
import tempfile
import unittest
from dataclasses import dataclass
from pathlib import Path
from unittest import mock

from modernization.verify.adapter import sqltext, transport
from modernization.verify.differential import live
from modernization.verify.spec_jobs import fixtures
from modernization.verify.spec_jobs.session import ArchiveBlockProof, Execution, FixtureLock

DB = "carddemo_verify_batch_t"
SWITCHES = ["DEF-ACUP-002", "DEF-INTC-010"]
SECRET = "batch-password-sentinel"
SENTINEL = "4111222233334444"
HEX = "ab" * 32


def framed(rows) -> str:
    text = rows if isinstance(rows, str) else json.dumps(rows)
    return f"{len(text.encode('utf-16-le')) // 2}:{text}\n"


@dataclass
class Call:
    kind: str
    argv: list
    stdin: str | None
    env: dict
    cwd: object


class Runner:
    def __init__(self):
        self.calls: list[Call] = []
        self.routes = []
        self.load = None

    def route(self, kind, marker, result):
        self.routes.insert(0, (kind, marker, result))

    def __call__(self, argv, *, stdin=None, env=None, timeout, cwd=None):
        kind = "java" if argv[0] == "java" else "admin" if "-c" in argv else "login"
        self.calls.append(Call(kind, list(argv), stdin, dict(env or {}), cwd))
        assert timeout > 0
        if kind == "java":
            return self.load(argv)
        for route_kind, marker, result in self.routes:
            if route_kind == kind and marker in stdin:
                return result(stdin) if callable(result) else result
        for sql, want in sqltext.conformance_queries():
            if kind == "admin" and stdin.strip() == sql.strip():
                return 0, f"{want['rows']:>20} {want['digest']}\n", ""
        if kind == "admin" and "DROP LOGIN" in stdin:
            return 0, "DROPPED\n", ""
        if kind == "admin" and ("CREATE LOGIN" in stdin or "CREATE USER" in stdin):
            return 0, "", ""
        if kind == "admin" and "sys.server_role_members" in stdin:
            return 0, framed([transport.FIXTURE_GRANTS]), ""
        raise AssertionError(f"unrouted {kind} SQL")

    def of(self, kind):
        return [c for c in self.calls if c.kind == kind]


class FakeApi:
    def __init__(self):
        self.calls = []
        self.answers = {}

    def answer(self, key, *results):
        self.answers[key] = list(results)

    def _next(self, key, default):
        queue = self.answers.get(key)
        if queue:
            return queue.pop(0) if len(queue) > 1 else queue[0]
        return default

    def post(self, path, body):
        self.calls.append(("post", path, body))
        return self._next(("post", path), (201, {"cycleId": 5, "businessDate": body["businessDate"]}, {}))

    def window(self, action, body):
        self.calls.append(("window", action, body))
        echo = {k: v for k, v in body.items() if k != "complete"}
        return self._next(("window", action), (200, {**echo, "cycle": {"status": "OPEN"}}, {}))

    def launch(self, job, params):
        self.calls.append(("launch", job, params))
        return self._next(("launch", job), (202, {"executionId": 11, "restart": False}, {}))

    def poll(self, execution_id):
        self.calls.append(("poll", execution_id))
        return self._next(("poll",), (200, {"executionId": execution_id, "executionStatus": "COMPLETED"}, {}))


class FakeLive:
    def __init__(self, root: Path, api: FakeApi):
        self.db, self.root, self.api = DB, root, api
        self.calls = []
        self.passwords = {"CARDDEMO_DB_BATCH_PASSWORD": SECRET, "CARDDEMO_DB_ACCOUNT_PASSWORD": "account-pw"}

    def env(self, var):
        self.calls.append(("env", var))
        return dict(self.passwords)

    def jars(self):
        return "/jars/batch.jar", "/jars/exporter.jar"

    def start_server(self, args, env, log_file):
        self.calls.append(("start", list(args), dict(env)))
        log_file.write_bytes(b"server log\n")
        return self.api

    def stop_server(self):
        self.calls.append(("stop",))


class FakeDriver:
    def __init__(self, session, work, mode):
        self.s, self.work, self.mode = session, work, mode
        self.var = work / "var"
        self.output_root = None
        session.calls.append(("driver", mode.label))

    def reset(self):
        self.work.mkdir(parents=True, mode=0o700)
        self.var.mkdir(mode=0o700)
        self.output_root = self.var / "out" / "root"
        self.s.calls.append(("db-reset",))

    def load(self, source, args):
        self.s.calls.append(("sample-load", source, list(args)))
        return {}

    def env(self):
        return {"CARDDEMO_VAR_DIR": str(self.var)}


class Pipe:
    def __init__(self):
        self.text, self.closed = "", False

    def write(self, text):
        if self.closed:
            raise OSError("closed")
        self.text += text

    def flush(self):
        pass

    def close(self):
        self.closed = True


class FakeProc:
    made = []

    def __init__(self, argv, **kw):
        self.argv, self.kw = argv, kw
        self.stdin, self.code, self.killed = Pipe(), None, False
        FakeProc.made.append(self)

    def poll(self):
        return self.code

    def kill(self):
        self.killed, self.code = True, -9

    def wait(self, timeout=None):
        if self.code is None:
            self.code = 0
        return self.code


def job_row(execution_id, status="COMPLETED", message=""):
    return [{"JOB_EXECUTION_ID": execution_id, "JOB_INSTANCE_ID": 4, "STATUS": status, "EXIT_CODE": status,
             "EXIT_MESSAGE": message, "SHORT_CONTEXT": None, "SERIALIZED_CONTEXT": None}]


def step_rows(status="COMPLETED"):
    row = {"STEP_EXECUTION_ID": 21, "STEP_NAME": "list", "STATUS": status, "SHORT_CONTEXT": None,
           "SERIALIZED_CONTEXT": None}
    row.update({c: 0 for c in transport.batchmeta.COUNT_COLUMNS})
    row["WRITE_COUNT"] = 50
    return [row]


class Base(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        env_file = self.tmp / "env-file"
        env_file.write_text("", encoding="utf-8")
        for target, name, value in ((live.d, "LOAD_REPORT_GAP", ""), (live.stack, "ENV_FILE", str(env_file)),
                                    (live.stack, "main_modernization", lambda: "/infra"),
                                    (live.stack, "java_bin", lambda: "java"),
                                    (transport.time, "sleep", lambda s: None),
                                    (live, "STATE_DIR", self.tmp / "state")):
            patcher = mock.patch.object(target, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        (self.tmp / "state").mkdir(mode=0o700)
        self.record = self.tmp / "state" / f"{DB}.fixture-login.json"
        env = mock.patch.dict(os.environ, {"CARDDEMO_INFRA_DIR": ""})
        env.start()
        self.addCleanup(env.stop)
        FakeProc.made = []
        self.api = FakeApi()
        self.live = FakeLive(self.tmp / "live", self.api)
        self.live.root.mkdir(mode=0o700)
        self.runner = Runner()
        self.session = transport.SpecSession(self.live, runner=self.runner, popen=FakeProc,
                                             new_driver=FakeDriver, switches=SWITCHES)
        self.runner.route("login", "batch.BATCH_JOB_EXECUTION e", lambda sql: (0, framed(job_row(11)), ""))
        self.runner.route("login", "batch.BATCH_STEP_EXECUTION s", lambda sql: (0, framed(step_rows()), ""))

    def reset(self, mode="legacy", **kw):
        self.session.reset(mode=mode, **kw)
        self.runner.calls.clear()
        self.live.calls.clear()


class GapTest(Base):
    def test_every_call_raises_before_touching_anything(self):
        with mock.patch.object(live.d, "LOAD_REPORT_GAP", "the gap"):
            calls = [lambda: self.session.reset(mode="legacy"), lambda: self.session.query("SELECT 1 a"),
                     lambda: self.session.execute("DELETE FROM core.tcat_bal"),
                     lambda: self.session.fixture_execute("DELETE FROM core.tcat_bal"),
                     lambda: self.session.load(self.tmp),
                     lambda: self.session.cycle("2022-07-06"), lambda: self.session.launch("archiveMasterJob", {}),
                     lambda: self.session.window(cycle_id=1).__enter__(),
                     lambda: self.session.hold_lock("SELECT 1").__enter__(),
                     lambda: self.session.fixture_lock("SELECT 1").__enter__(),
                     lambda: self.session.snapshot("core.tcat_bal", ("acct_id",)),
                     lambda: self.session.snapshot_digest("core.transaction", ("tran_pk",))]
            for call in calls:
                with self.assertRaises(live.d.PipelineError):
                    call()
        self.assertEqual(self.runner.calls, [])
        self.assertEqual(self.live.calls, [])
        self.assertEqual(FakeProc.made, [])


class ResetTest(Base):
    def test_a_case_resets_loads_provisions_then_starts_the_server(self):
        self.session.reset(mode="legacy")
        kinds = [c[0] for c in self.live.calls if c[0] != "env"]
        self.assertEqual(kinds, ["stop", "driver", "db-reset", "sample-load", "start"])
        self.assertEqual(self.live.calls[1], ("driver", "legacy"))
        load = self.live.calls[3]
        self.assertEqual(load[1], transport.SAMPLE)
        self.assertIn("--carddemo.fidelity.DEF-ACUP-002=legacy", load[2])
        self.assertIn("--carddemo.fidelity.DEF-INTC-010=legacy", load[2])
        start = self.live.calls[-1]
        self.assertEqual([a for a in start[1] if a.startswith("--carddemo.fidelity.")], load[2])
        self.assertIn(live.d.INTEREST_PARM_ENV, start[2])
        self.assertEqual(self.session.var_dir, self.live.root / "spec-001-legacy" / "var")
        create, provision, grants, *conformance = self.runner.of("admin")
        sid = self.session._s.fixture_sid
        self.assertRegex(sid, r"\A0x[0-9A-F]{32}\Z")
        self.assertTrue(create.stdin.startswith(
            f"IF SUSER_ID(N'{DB}_fixture') IS NOT NULL THROW 50001, 'FIXTURE_LOGIN_EXISTS', 1;\n"
            f"CREATE LOGIN [{DB}_fixture] WITH PASSWORD = N'"))
        self.assertIn(f"', SID = {sid}, CHECK_POLICY = OFF", create.stdin)
        self.assertNotIn("ALTER LOGIN", create.stdin)
        self.assertIn(f"ALTER DATABASE [{DB}] SET ALLOW_SNAPSHOT_ISOLATION ON;", provision.stdin)
        self.assertTrue(provision.stdin.endswith(
            f"CREATE USER [fixture_svc] FOR LOGIN [{DB}_fixture];\n"
            "ALTER ROLE db_datareader ADD MEMBER [fixture_svc];\n"
            "ALTER ROLE db_datawriter ADD MEMBER [fixture_svc];\n"))
        self.assertNotIn("db_owner", provision.stdin)
        self.assertIn("sys.server_role_members m WHERE m.member_principal_id = SUSER_ID(N'"
                      f"{DB}_fixture')", grants.stdin)
        self.assertIn("USER_ID(N'fixture_svc')", grants.stdin)
        for call in (create, provision, grants):
            self.assertEqual(call.argv[-1], DB)
        self.assertEqual(len(conformance), 3)
        password = self.session._s.fixture_password
        self.assertIn(password, create.stdin)
        manifest = self.record
        self.assertEqual(json.loads(manifest.read_text()), {
            "login": f"{DB}_fixture", "database": DB, "sid": sid,
            "drop": transport.FIXTURE_DROP_SQL.format(login=f"{DB}_fixture", sid=sid, quoted=f"[{DB}_fixture]")})
        self.assertFalse((self.live.root / "fixture-login.json").exists())
        self.assertEqual(stat.S_IMODE(manifest.stat().st_mode), 0o600)
        self.assertNotIn(password, manifest.read_text())
        for call in self.runner.calls:
            self.assertFalse(any(password in a for a in call.argv))
            self.assertNotIn(password, json.dumps(call.env))
            if call is not create:
                self.assertNotIn(password, call.stdin)

    def test_fixed_mode_sets_every_switch_and_only_allowed_properties(self):
        self.session.reset(mode="fixed", properties={"carddemo.batch.pauth.commit-interval": "10"})
        start = [c for c in self.live.calls if c[0] == "start"][0]
        self.assertIn("--carddemo.fidelity.DEF-ACUP-002=fixed", start[1])
        self.assertIn("--carddemo.fidelity.DEF-INTC-010=fixed", start[1])
        self.assertEqual(start[1][-1], "--carddemo.batch.pauth.commit-interval=10")

    def test_refusals_come_before_anything_runs(self):
        for mode, properties in (("other", None), ("legacy", {"spring.datasource.url": "x"}),
                                 ("legacy", {"carddemo.batch.pauth.commit-interval": "0"}),
                                 ("legacy", {"carddemo.batch.pauth.commit-interval": 10}),
                                 ("legacy", {"carddemo.batch.pauth.commit-interval": "1 --x=y"})):
            with self.assertRaises(transport.TransportError):
                self.session.reset(mode=mode, properties=properties)
        self.assertEqual(self.live.calls, [])
        self.assertEqual(self.runner.calls, [])

    def test_conformance_runs_once_per_session(self):
        self.session.reset(mode="legacy")
        self.session.reset(mode="fixed")
        conformance = [c for c in self.runner.of("admin") if "AS t(card_num" in c.stdin]
        self.assertEqual(len(conformance), 3)
        self.assertEqual(self.session.var_dir.parent.name, "spec-002-fixed")

    def test_a_conformance_mismatch_blocks_the_server(self):
        self.runner.route("admin", "AS t(card_num", (0, f"2 {HEX}\n", ""))
        with self.assertRaises(transport.TransportError) as e:
            self.session.reset(mode="legacy")
        self.assertEqual(e.exception.code, "CONFORMANCE")
        self.assertNotIn("start", [c[0] for c in self.live.calls])

    def test_a_provisioning_error_names_message_numbers_only(self):
        self.runner.route("admin", "CREATE LOGIN", (1, f"Msg 15247, Level 16, State 1 '{SENTINEL}'\n", ""))
        with self.assertRaises(transport.TransportError) as e:
            self.session.reset(mode="legacy")
        self.assertEqual(str(e.exception), "PROVISION (Msg 15247)")
        # A login of that name is dropped only if it carries this session's SID.
        self.runner.calls.clear()
        self.session.close()
        drop = self.runner.of("admin")[-1].stdin
        self.assertIn(f"IF @found = {self.session._s.fixture_sid} AND DATALENGTH(@found) = 16 DROP LOGIN", drop)
        self.assertFalse(self.record.exists())

    def test_a_fixture_login_that_exists_already_is_refused_never_taken_over(self):
        for answer in ("Msg 50001, Level 16, State 1\nFIXTURE_LOGIN_EXISTS\n",
                       "Msg 15025, Level 16, State 1\n"):
            self.runner.route("admin", "CREATE LOGIN", (1, answer, ""))
            with self.assertRaises(transport.TransportError) as e:
                self.session.reset(mode="legacy")
            self.assertEqual(e.exception.code, "FIXTURE_LOGIN_EXISTS")
            self.assertFalse(any("CREATE USER" in c.stdin for c in self.runner.of("admin")))
            self.assertNotIn("start", [c[0] for c in self.live.calls])
            self.assertFalse(self.record.exists())
            self.runner.calls.clear()
            self.session.close()
            self.assertEqual(self.runner.calls, [])

    def test_a_leftover_manifest_is_refused_before_any_login(self):
        self.record.write_text("{}")
        with self.assertRaises(transport.TransportError) as e:
            self.session.reset(mode="legacy")
        self.assertEqual(e.exception.code, "FIXTURE_RECORD_LEFT")
        self.assertFalse(any("CREATE LOGIN" in c.stdin for c in self.runner.of("admin")))
        self.assertEqual(self.record.read_text(), "{}")
        self.record.unlink()
        self.record.symlink_to(self.tmp / "elsewhere")
        with self.assertRaises(transport.TransportError) as e:
            self.session.reset(mode="legacy")
        self.assertIn(e.exception.code, ("FIXTURE_RECORD_LEFT", "FIXTURE_MANIFEST"))
        self.assertFalse((self.tmp / "elsewhere").exists())
        self.assertFalse(any("CREATE LOGIN" in c.stdin for c in self.runner.of("admin")))

    def test_the_login_is_created_once_and_its_user_in_every_case(self):
        self.session.reset(mode="legacy")
        self.session.reset(mode="fixed")
        stdins = [c.stdin for c in self.runner.of("admin")]
        self.assertEqual(sum("CREATE LOGIN" in s for s in stdins), 1)
        self.assertEqual(sum("CREATE USER" in s for s in stdins), 2)
        self.assertEqual(sum("sys.server_role_members" in s for s in stdins), 2)

    def test_any_grant_beyond_the_two_roles_blocks_the_server(self):
        wrong = [{"server_roles": 1}, {"server_permissions": 1}, {"database_roles": "db_datareader"},
                 {"database_roles": "db_datareader,db_datawriter,db_ddladmin"}, {"database_permissions": 1},
                 {"public_unmask": 1}, {"owner": 1}, {"mapped_user": 0}, {"server_roles": "0"}]
        for change in wrong + [None]:
            row = dict(transport.FIXTURE_GRANTS, **(change or {}))
            if change is None:
                del row["database_roles"]
            self.runner.route("admin", "sys.server_role_members", (0, framed([row]), ""))
            self.live.calls.clear()
            with self.assertRaises(transport.TransportError) as e:
                self.session.reset(mode="legacy")
            self.assertEqual(e.exception.code, "FIXTURE_GRANTS")
            self.assertNotIn("start", [c[0] for c in self.live.calls])
        for rows in ([], [transport.FIXTURE_GRANTS] * 2):
            self.runner.route("admin", "sys.server_role_members", (0, framed(rows), ""))
            with self.assertRaises(transport.TransportError) as e:
                self.session.reset(mode="legacy")
            self.assertEqual(e.exception.code, "FIXTURE_GRANTS")


class SqlTest(Base):
    def test_query_runs_as_the_named_login_with_the_sql_on_stdin(self):
        self.runner.route("login", "SELECT 1 AS a", (0, framed([{"a": 1}]), ""))
        self.assertEqual(self.session.as_login("account").query("SELECT 1 AS a"), [{"a": 1}])
        call = self.runner.calls[-1]
        argv = call.argv
        self.assertEqual(argv[argv.index("-U") + 1], f"{DB}_account")
        self.assertEqual(argv[argv.index("-d") + 1], DB)
        for flag in ("-x", "-b", "-C"):
            self.assertIn(flag, argv)
        self.assertEqual(argv[-2:], ["-i", "/dev/stdin"])
        self.assertEqual(argv[argv.index("-e") + 1], "SQLCMDPASSWORD")
        self.assertEqual(call.env["SQLCMDPASSWORD"], "account-pw")
        self.assertFalse(any("account-pw" in a for a in argv))
        self.assertTrue(call.stdin.startswith("SET NOCOUNT ON; DECLARE @j NVARCHAR(MAX) = (SELECT 1 AS a FOR JSON"))

    def test_decimals_stay_exact_strings(self):
        self.runner.route("login", "SELECT v", (0, framed('[{"v":12.50}]'), ""))
        self.assertEqual(self.session.query("SELECT v FROM x"), [{"v": "12.50"}])

    def test_a_failure_names_message_numbers_only(self):
        self.runner.route("login", "SELECT v", (1, f"Msg 245, Level 16, State 1 '{SENTINEL}'\n",
                                                 f"Msg 245, Level 16\nMsg 229, Level 14 {SENTINEL}"))
        with self.assertRaises(transport.TransportError) as e:
            self.session.query("SELECT v FROM x")
        self.assertEqual(str(e.exception), "SQL_FAILED (Msg 245,229)")

    def test_fixture_execute_uses_the_fixture_login_and_xact_abort(self):
        self.runner.route("login", "DELETE FROM core.tcat_bal", (0, "", ""))
        self.session.fixture_execute("DELETE FROM core.tcat_bal;")
        call = self.runner.calls[-1]
        self.assertEqual(call.argv[call.argv.index("-U") + 1], f"{DB}_fixture")
        self.assertEqual(call.env["SQLCMDPASSWORD"], self.session._s.fixture_password)
        self.assertTrue(call.stdin.startswith("SET XACT_ABORT ON; SET NOCOUNT ON; DELETE FROM core.tcat_bal;"))

    def test_logins_outside_the_protocol_and_missing_passwords_are_refused(self):
        with self.assertRaises(transport.TransportError):
            self.session.as_login("sa")
        with self.assertRaises(transport.TransportError) as e:
            self.session.as_login("reference").query("SELECT 1 a")
        self.assertEqual(e.exception.code, "LOGIN_PASSWORD_MISSING")
        self.assertEqual(self.runner.calls, [])

    def test_sqlcmd_commands_and_separators_are_refused(self):
        for sql in ("SELECT 1\n:!! id", "SELECT 1\n  :r /etc/passwd", "SELECT 1\nGO\nSELECT 2", "SELECT 1\ngo",
                    "", "SELECT 1\x00"):
            with self.assertRaises(transport.TransportError):
                self.session.execute(sql)
        for sql in ("SELECT 1 a\n:!! id", "SELECT 1 a\nGO"):
            with self.assertRaises(transport.TransportError):
                self.session.query(sql)
            with self.assertRaises(transport.TransportError):
                self.session._admin(sql)
        self.assertEqual(self.runner.calls, [])

    def test_commands_sqlcmd_takes_without_a_colon_are_refused(self):
        lines = ["!! id", "!!id", "QUIT", "quit", "EXIT", "EXIT(SELECT 1)", "exit (SELECT 1)", "RESET", "Reset",
                 "ED", "ed", "GO 2", "go"]
        lines += [prefix + line for line in ("!! id", "QUIT", "EXIT", "RESET", "ED") for prefix in ("  ", "\t", " \t")]
        for line in lines:
            for sql in ("UPDATE core.card SET x = 1\n" + line, line + "\nUPDATE core.card SET x = 1"):
                with self.assertRaises(transport.TransportError) as e:
                    self.session.execute(sql)
                self.assertEqual(e.exception.code, "SQL_SHAPE")
                with self.assertRaises(transport.TransportError):
                    self.session.query("SELECT 1 a\n" + line)
                with self.assertRaises(transport.TransportError):
                    self.session._admin("SELECT 1 a\n" + line)
                with self.assertRaises(transport.TransportError) as e:
                    with self.session.hold_lock(LOCK_SQL + "\n" + line):
                        self.fail("body ran")
                self.assertEqual(e.exception.code, "SQL_SHAPE")
        self.assertEqual(self.runner.calls, [])
        self.assertEqual(FakeProc.made, [])

    def test_the_command_words_elsewhere_are_not_commands(self):
        self.runner.route("login", "SET XACT_ABORT", (0, "", ""))
        for sql in ("UPDATE core.card SET x = 'GO' WHERE y = N'quit'", "SELECT 1 AS [exit], 2 AS [reset]",
                    "UPDATE core.card SET x = 1 -- then quit", "UPDATE core.card SET x = 1; EXIT",
                    "UPDATE core.card SET x = '!!'", "UPDATE core.card\nSET x = 1\n  WHERE ed_flag = 1",
                    "exit_code = 1", "EDITED = 1", "RESETS = 1", "QUITE = 1", "GOTO done", "x = 1 !! y"):
            self.session.execute(sql)
        self.assertEqual(len(self.runner.of("login")), 12)

    def test_the_admin_path_is_private_scripting_off_and_inherits_no_password(self):
        with self.assertRaises(transport.TransportError) as e:
            self.session._admin("SELECT 1 a", "carddemo")
        self.assertEqual(e.exception.code, "DATABASE_NOT_PRIVATE")
        self.assertEqual(self.runner.calls, [])
        self.runner.route("admin", "SELECT 1 a", (0, "", ""))
        with mock.patch.dict(os.environ, {"SQLCMDPASSWORD": "inherited"}):
            self.session._admin("SELECT 1 a", "master")
        call = self.runner.calls[-1]
        self.assertIn(" -U sa -C -b -x -h -1 -y 0 -f 65001 ", call.argv[call.argv.index("-c") + 1])
        self.assertNotIn("SQLCMDPASSWORD", call.env)
        self.assertEqual(call.argv[-1], "master")

    def test_every_fixture_batch_passes_the_shape_check(self):
        for sql in (fixtures.PII_DML, fixtures.SIGNS_DML, fixtures.archive(2, 1), fixtures.purge_edges(False),
                    fixtures.purge_edges(True)):
            transport._plain(sql)


COLUMNS = [{"name": "acct_id", "data_type": "char", "ordinal": 1, "masked": False, "can_unmask": False},
           {"name": "type_cd", "data_type": "char", "ordinal": 2, "masked": False, "can_unmask": False},
           {"name": "balance", "data_type": "decimal", "ordinal": 3, "masked": False, "can_unmask": False}]


class SnapshotTest(Base):
    def test_login_snapshot_reads_per_key_hashes_in_snapshot_isolation(self):
        self.runner.route("login", "INFORMATION_SCHEMA.COLUMNS", (0, framed(COLUMNS), ""))
        self.runner.route("login", "HASHBYTES", (0, framed([{"k0": "00000000001", "k1": "01", "h": HEX}]), ""))
        got = self.session.as_login("fixture").snapshot("core.tcat_bal", ("acct_id", "type_cd"))
        self.assertEqual(got, {("00000000001", "01"): HEX})
        read = self.runner.calls[-1]
        self.assertTrue(read.stdin.startswith("SET NOCOUNT ON; SET TRANSACTION ISOLATION LEVEL SNAPSHOT;"))
        self.assertEqual(read.argv[read.argv.index("-U") + 1], f"{DB}_fixture")

    def test_a_masked_column_without_unmask_is_refused_before_the_read(self):
        masked = COLUMNS + [{"name": "card_num", "data_type": "char", "ordinal": 4, "masked": True,
                             "can_unmask": False}]
        self.runner.route("login", "INFORMATION_SCHEMA.COLUMNS", (0, framed(masked), ""))
        with self.assertRaises(sqltext.Refused) as e:
            self.session.snapshot("core.tcat_bal", ("acct_id", "type_cd"))
        self.assertEqual(e.exception.code, "MASKED_COLUMN_NOT_EXCLUDED")
        self.assertEqual(len(self.runner.calls), 1)

    def test_snapshot_digest_uses_the_admin_path(self):
        self.runner.route("admin", "INFORMATION_SCHEMA.COLUMNS", (0, framed(COLUMNS), ""))
        self.runner.route("admin", "HASHBYTES", (0, framed([{"k0": "00000000001", "k1": "01", "h": HEX}]), ""))
        got = self.session.snapshot_digest("core.tcat_bal", ("acct_id", "type_cd"))
        self.assertEqual(got, {("00000000001", "01"): HEX})
        for call in self.runner.calls:
            self.assertEqual(call.kind, "admin")
            self.assertIn("-U sa", call.argv[call.argv.index("-c") + 1])
            self.assertIn("/run/secrets/mssql_sa_password", call.argv[call.argv.index("-c") + 1])
            self.assertNotIn("SQLCMDPASSWORD", call.env)
            self.assertEqual(call.argv[-1], DB)
        self.assertIn("SET TRANSACTION ISOLATION LEVEL SNAPSHOT;", self.runner.calls[-1].stdin)

    def test_an_aggregate_returns_rows_and_digest_only(self):
        self.runner.route("admin", "INFORMATION_SCHEMA.COLUMNS", (0, framed(COLUMNS), ""))
        self.runner.route("admin", "STRING_AGG", (0, f"{2:>20} {HEX}\n", ""))
        got = self.session.snapshot_digest("core.tcat_bal", columns=("acct_id", "balance"),
                                           order_by=("acct_id",), aggregate=True)
        self.assertEqual(got, {"rows": 2, "digest": HEX})
        self.assertTrue(self.runner.calls[-1].stdin.startswith(
            "SET NOCOUNT ON; SET TRANSACTION ISOLATION LEVEL SNAPSHOT; SET NOCOUNT ON;\nWITH r AS"))

    def test_malformed_aggregate_output_is_refused(self):
        self.runner.route("admin", "INFORMATION_SCHEMA.COLUMNS", (0, framed(COLUMNS), ""))
        for output in (f"2 {HEX}\n3 {HEX}\n", f"2 {HEX} x\n", f"-2 {HEX}\n", "", f"2 {HEX[:-1]}\n"):
            self.runner.route("admin", "STRING_AGG", (0, output, ""))
            with self.assertRaises(sqltext.Refused):
                self.session.snapshot_digest("core.tcat_bal", columns=("acct_id",), order_by=("acct_id",),
                                             aggregate=True)

    def test_shapes_and_tables_are_refused_before_any_sql(self):
        with self.assertRaises(sqltext.Refused):
            self.session.snapshot_digest("core.tcat_bal", ("acct_id",), columns=("acct_id",), aggregate=True)
        with self.assertRaises(sqltext.Refused):
            self.session.snapshot_digest("core.tcat_bal", ("acct_id",), columns=("acct_id",))
        with self.assertRaises(sqltext.Refused):
            self.session.snapshot_digest("sec.user_security", ("user_id",))
        with self.assertRaises(sqltext.Refused):
            self.session.snapshot("sec.user_security", ("user_id",))
        self.assertEqual(self.runner.calls, [])


class ApiTest(Base):
    def test_a_launch_waits_then_reads_the_execution_as_the_batch_login(self):
        self.reset()
        self.api.answer(("poll",), (200, {"executionId": 11, "executionStatus": "STARTED"}, {}),
                        (200, {"executionId": 11, "executionStatus": "COMPLETED"}, {}))
        running = self.session.as_login("fixture").launch("categoryBalanceListingJob", {"runKey": "spec/x"})
        execution = running.wait()
        self.assertEqual(self.api.calls[0], ("launch", "categoryBalanceListingJob", {"runKey": "spec/x"}))
        self.assertEqual((execution.status, execution.execution_id, execution.job_instance_id),
                         ("COMPLETED", 11, 4))
        self.assertEqual(execution.instance_root, self.live.root / "spec-001-legacy" / "var" / "out" / "root")
        self.assertEqual(execution.step_counts["list"]["WRITE_COUNT"], 50)
        self.assertEqual(execution.step_statuses, {"list": "COMPLETED"})
        for call in self.runner.calls:
            self.assertEqual(call.argv[call.argv.index("-U") + 1], f"{DB}_batch")
            self.assertEqual(call.env["SQLCMDPASSWORD"], SECRET)

    def test_execution_id_zero_is_an_execution(self):
        self.reset()
        self.api.answer(("launch", "archiveMasterJob"), (202, {"executionId": 0}, {}))
        self.runner.route("login", "batch.BATCH_JOB_EXECUTION e", lambda sql: (0, framed(job_row(0)), ""))
        self.assertEqual(self.session.launch("archiveMasterJob", {"cycleId": 1}).wait().execution_id, 0)

    def test_a_refusal_comes_back_as_the_status(self):
        self.reset()
        self.api.answer(("launch", "archiveMasterJob"), (409, {"code": "BATCH_WINDOW_REQUIRED"}, {}))
        running = self.session.launch("archiveMasterJob", {"cycleId": 1})
        self.assertTrue(running.done())
        self.assertEqual(running.wait(), Execution("BATCH_WINDOW_REQUIRED"))
        self.assertEqual(self.runner.calls, [])

    def test_transport_failures_raise(self):
        self.reset()
        for answer in ((401, {"code": "UNAUTHENTICATED"}, {}), (500, {"code": "INTERNAL"}, {}),
                       (202, {"executionId": True}, {}), (202, {"executionId": -1}, {}),
                       (409, {"code": "bad code"}, {}), (409, {}, {})):
            self.api.answer(("launch", "archiveMasterJob"), answer)
            with self.assertRaises(transport.TransportError):
                self.session.launch("archiveMasterJob", {"cycleId": 1})
        with self.assertRaises(transport.TransportError):
            self.session.launch("../internal", {})

    def test_api_and_repository_status_must_agree(self):
        self.reset()
        self.runner.route("login", "batch.BATCH_JOB_EXECUTION e", lambda sql: (0, framed(job_row(11, "FAILED")), ""))
        with self.assertRaises(transport.TransportError) as e:
            self.session.launch("archiveMasterJob", {"cycleId": 1}).wait()
        self.assertEqual(e.exception.code, "STATUS_MISMATCH")

    def test_wait_is_bounded(self):
        self.reset()
        self.api.answer(("poll",), (200, {"executionId": 11, "executionStatus": "STARTED"}, {}))
        clock = iter(range(0, 10_000, 50))
        with mock.patch.object(transport.time, "monotonic", lambda: next(clock)):
            with self.assertRaises(TimeoutError):
                self.session.launch("archiveMasterJob", {"cycleId": 1}).wait(timeout=120)

    def test_a_poll_for_another_execution_fails(self):
        self.reset()
        self.api.answer(("poll",), (200, {"executionId": 12, "executionStatus": "COMPLETED"}, {}))
        with self.assertRaises(transport.TransportError):
            self.session.launch("archiveMasterJob", {"cycleId": 1}).done()

    def test_windows_open_and_close_with_their_holder(self):
        self.reset()
        with self.session.window(cycle_id=5):
            pass
        with self.session.window(run_key="spec/legacy/sample"):
            pass
        self.assertEqual(self.api.calls, [
            ("window", "open", {"cycleId": 5}), ("window", "close", {"cycleId": 5, "complete": False}),
            ("window", "open", {"windowRunKey": "spec/legacy/sample"}),
            ("window", "close", {"windowRunKey": "spec/legacy/sample"})])

    def test_a_failing_body_still_closes_its_window_and_keeps_its_error(self):
        self.reset()
        self.api.answer(("window", "close"), (500, {}, {}))
        with self.assertRaises(KeyError):
            with self.session.window(cycle_id=5):
                raise KeyError("case")
        self.assertEqual(self.api.calls[-1], ("window", "close", {"cycleId": 5, "complete": False}))
        with self.assertRaises(transport.TransportError) as e:
            with self.session.window(cycle_id=5):
                pass
        self.assertEqual(e.exception.code, "WINDOW_CLOSE")

    def test_window_holders_are_checked(self):
        self.reset()
        for kw in ({}, {"cycle_id": 1, "run_key": "x"}, {"cycle_id": 0}, {"cycle_id": True}, {"run_key": ""},
                   {"run_key": "x" * 251}, {"run_key": "a\nb"}):
            with self.assertRaises(transport.TransportError):
                with self.session.window(**kw):
                    pass
        self.api.answer(("window", "open"), (503, {"code": "WINDOW_BUSY"}, {}))
        with self.assertRaises(transport.TransportError):
            with self.session.window(cycle_id=5):
                pass

    def test_a_completed_cycle_is_closed_with_complete_true(self):
        self.reset()
        self.api.answer(("window", "close"), (200, {"cycleId": 5, "cycle": {"status": "COMPLETED"}}, {}))
        self.assertEqual(self.session.cycle("2023-01-02", completed=True), 5)
        self.assertEqual(self.api.calls, [
            ("post", "/internal/cycles", {"businessDate": "2023-01-02", "interest": "never"}),
            ("window", "open", {"cycleId": 5}), ("window", "close", {"cycleId": 5, "complete": True})])

    def test_cycle_failures_raise(self):
        self.reset()
        with self.assertRaises(transport.TransportError):
            self.session.cycle("2022-7-6")
        self.api.answer(("post", "/internal/cycles"), (409, {"code": "CYCLE_PARAMETERS_DIFFER"}, {}))
        with self.assertRaises(transport.TransportError):
            self.session.cycle("2022-07-06")
        self.api.answer(("post", "/internal/cycles"), (201, {"cycleId": 5, "businessDate": "2022-07-06"}, {}))
        with self.assertRaises(transport.TransportError) as e:
            self.session.cycle("2022-07-06", completed=True)
        self.assertEqual(e.exception.code, "CYCLE_COMPLETE")

    def test_no_server_before_a_case(self):
        with self.assertRaises(transport.TransportError):
            self.session.cycle("2022-07-06")
        with self.assertRaises(transport.TransportError):
            self.session.var_dir


LOCK_SQL = ("SELECT TOP (1) tran_pk FROM core.transaction WITH (UPDLOCK,HOLDLOCK) "
            "WHERE in_master=1 ORDER BY tran_pk")


def holder_row(open_tx=1, busy=False, held=1, waiting=0, session_id=57):
    return {"session_id": session_id, "open_tx": open_tx, "busy": busy, "held": held, "waiting": waiting}


class LockTest(Base):
    def holder(self, *answers):
        rows = [framed(a) for a in answers]
        state = {"i": 0}

        def answer(sql):
            i = min(state["i"], len(rows) - 1)
            state["i"] += 1
            return 0, rows[i], ""
        self.runner.route("admin", "sys.dm_exec_sessions", answer)

    def test_the_lock_is_held_until_the_body_ends_then_rolled_back(self):
        self.holder([holder_row(open_tx=0, busy=True, held=0)], [holder_row(busy=True)], [holder_row()])
        with self.session.fixture_lock(LOCK_SQL + ";"):
            proc = FakeProc.made[0]
            self.assertEqual(proc.stdin.text,
                             "SET XACT_ABORT ON; SET NOCOUNT ON; SET LOCK_TIMEOUT 60000; BEGIN TRANSACTION; "
                             + LOCK_SQL + "; IF @@ROWCOUNT = 0 THROW 50000, 'HOLD_LOCK_NO_ROW', 1;\nGO\n")
        self.assertTrue(proc.stdin.text.endswith("IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\nGO\n"))
        self.assertTrue(proc.stdin.closed)
        argv = proc.argv
        token = argv[argv.index("-H") + 1]
        self.assertNotIn("-i", argv)
        self.assertEqual(argv[argv.index("-U") + 1], f"{DB}_fixture")
        self.assertEqual(proc.kw["stdout"], transport.subprocess.DEVNULL)
        self.assertEqual(proc.kw["env"]["SQLCMDPASSWORD"], self.session._s.fixture_password)
        checks = self.runner.of("admin")
        # Three until ready, and the proof once more before the ROLLBACK.
        self.assertEqual(len(checks), 4)
        self.assertEqual(len({c.stdin for c in checks}), 1)
        sql = checks[0].stdin
        self.assertIn(f"s.host_name = N'{token}'", sql)
        self.assertIn(f"s.login_name = N'{DB}_fixture'", sql)
        self.assertIn("= OBJECT_ID(N'core.transaction')", sql)
        self.assertIn("AND l.request_status = 'GRANT' AND l.request_mode IN "
                      "('U', 'X', 'RangeS-U', 'RangeX-X', 'RangeI-U', 'RangeI-X', 'RangeX-S', 'RangeX-U') "
                      "AND COALESCE(", sql)

    def test_each_condition_is_required_before_the_body_runs(self):
        for missing in (holder_row(open_tx=0), holder_row(busy=True), holder_row(held=0), holder_row(waiting=1),
                        {"session_id": 57, "open_tx": 1, "busy": False, "held": "1", "waiting": 0}, {},
                        holder_row(session_id=None), holder_row(session_id=0), holder_row(session_id=-1),
                        holder_row(session_id=True), holder_row(session_id="57"), holder_row(session_id=57.0)):
            FakeProc.made = []
            self.holder([missing] if missing else [])
            clock = iter(range(0, 10_000, 30))
            with mock.patch.object(transport.time, "monotonic", lambda: next(clock)):
                with self.assertRaises(transport.TransportError) as e:
                    with self.session.hold_lock(LOCK_SQL):
                        self.fail("body ran")
            self.assertEqual(e.exception.code, "LOCK_TIMEOUT")
            self.assertTrue(FakeProc.made[0].killed)

    def test_two_sessions_with_the_holders_name_fail(self):
        self.holder([holder_row(), holder_row()])
        with self.assertRaises(transport.TransportError) as e:
            with self.session.hold_lock(LOCK_SQL):
                self.fail("body ran")
        self.assertEqual(e.exception.code, "LOCK_HOLDER_AMBIGUOUS")

    def test_a_holder_that_exits_fails(self):
        def exits(sql):
            FakeProc.made[0].code = 1
            return 0, framed([]), ""
        self.runner.route("admin", "sys.dm_exec_sessions", exits)
        with self.assertRaises(transport.TransportError) as e:
            with self.session.hold_lock(LOCK_SQL):
                self.fail("body ran")
        self.assertEqual(e.exception.code, "LOCK_FAILED")

    def test_only_one_select_on_an_allowlisted_table(self):
        lock = "SELECT TOP (1) tran_pk FROM core.transaction WITH (UPDLOCK)"
        shapes = ["SELECT 1", "UPDATE core.card SET x=1", LOCK_SQL + "; DELETE FROM core.card",
                  lock + " UPDATE core.card SET x = 1",
                  "SELECT (SELECT TOP (1) 1 FROM core.card) c, tran_pk FROM core.transaction WITH (UPDLOCK)",
                  lock + " WHERE tran_pk IN (SELECT tran_pk FROM core.card)",
                  lock + " WHERE in_master = 1 UNION SELECT TOP (1) tran_pk FROM core.card WITH (UPDLOCK)",
                  "SELECT TOP (1) tran_pk FROM core.transaction WITH (UPDLOCK) JOIN core.card c ON 1 = 1",
                  "SELECT TOP (1) tran_pk FROM core.transaction t WITH (UPDLOCK)",
                  "SELECT TOP (1) tran_pk FROM core.transaction", "SELECT tran_pk FROM core.transaction WITH (UPDLOCK)",
                  "SELECT TOP (1) tran_pk FROM core.transaction WITH (HOLDLOCK)",
                  "SELECT TOP (1) tran_pk FROM core.transaction WITH (NOLOCK, UPDLOCK)",
                  "SELECT TOP (1) tran_pk FROM core.transaction WITH (UPDLOCK, INDEX(1))",
                  "SELECT TOP (1) * FROM core.transaction WITH (UPDLOCK)",
                  "SELECT TOP (1) tran_pk FROM transaction WITH (UPDLOCK)",
                  lock + " WHERE in_master = 1 -- x", lock + " /* x */ WHERE in_master = 1",
                  lock + " WHERE x = 'a''b'", lock + " WHERE x = y", lock + " WHERE x = 1 OR y = 2", lock + " WHERE 1 = 1",
                  lock + " ORDER BY tran_pk; EXEC sp_who", lock + "\nEXEC sp_who", "EXEC sp_who"]
        for sql in shapes:
            with self.assertRaises(transport.TransportError) as e:
                with self.session.hold_lock(sql):
                    self.fail("body ran")
            self.assertEqual(e.exception.code, "LOCK_SHAPE", sql)
        with self.assertRaises(transport.TransportError) as e:
            with self.session.hold_lock("SELECT TOP (1) user_id FROM sec.user_security WITH (UPDLOCK)"):
                self.fail("body ran")
        self.assertEqual(e.exception.code, "LOCK_TARGET")
        self.assertEqual(FakeProc.made, [])
        self.assertEqual(self.runner.calls, [])

    def test_the_lock_shapes_it_takes(self):
        self.holder([holder_row()])
        for sql, table in ((LOCK_SQL, "core.transaction"),
                           ("select top(2) [tran_pk], in_master from [CORE].[Transaction] with (xlock, rowlock) "
                            "where in_master = 1 and tran_id <> N'x-1' and y is not null order by tran_pk desc",
                            "core.transaction"),
                           ("SELECT TOP (1) acct_id FROM pauth.auth_summary WITH (UPDLOCK, SERIALIZABLE)\n"
                            "WHERE acct_id >= -5", "pauth.auth_summary")):
            self.runner.calls.clear()
            with self.session.hold_lock(sql + ";"):
                pass
            self.assertIn(f"= OBJECT_ID(N'{table}')", self.runner.of("admin")[0].stdin)
            self.assertIn(sql + "; IF @@ROWCOUNT = 0", FakeProc.made[-1].stdin.text)

    def test_a_holder_that_dies_during_the_body_is_a_lost_lock(self):
        self.holder([holder_row()])
        with self.assertRaises(transport.TransportError) as e:
            with self.session.hold_lock(LOCK_SQL):
                FakeProc.made[0].code = 1
        self.assertEqual(e.exception.code, "LOCK_LOST")
        self.assertEqual(len(self.runner.of("admin")), 1)

    def test_a_lock_gone_by_the_end_of_the_body_is_lost_after_the_rollback(self):
        for gone in ([holder_row(held=0)], [holder_row(open_tx=0)], [holder_row(waiting=1)],
                     [holder_row(busy=True)], [], [holder_row(), holder_row()], [holder_row(session_id=58)],
                     [holder_row(session_id=None)]):
            FakeProc.made = []
            self.holder([holder_row()], gone)
            with self.assertRaises(transport.TransportError) as e:
                with self.session.hold_lock(LOCK_SQL):
                    pass
            self.assertEqual(e.exception.code, "LOCK_LOST")
            self.assertTrue(FakeProc.made[0].stdin.text.endswith("IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;\nGO\n"))
            self.assertTrue(FakeProc.made[0].stdin.closed)
        FakeProc.made = []
        state = {"n": 0}

        def flaky(sql):
            state["n"] += 1
            return (0, framed([holder_row()]), "") if state["n"] == 1 else (1, "Msg 1222, Level 16\n", "")
        self.runner.route("admin", "sys.dm_exec_sessions", flaky)
        with self.assertRaises(transport.TransportError) as e:
            with self.session.hold_lock(LOCK_SQL):
                pass
        self.assertEqual(e.exception.code, "LOCK_LOST")
        self.assertIn("ROLLBACK TRANSACTION", FakeProc.made[0].stdin.text)

    def test_the_bodys_own_failure_wins_over_a_lost_lock(self):
        self.holder([holder_row()])
        with self.assertRaises(KeyError):
            with self.session.hold_lock(LOCK_SQL):
                FakeProc.made[0].code = 1
                raise KeyError("case")
        self.assertEqual(len(self.runner.of("admin")), 1)

    def test_a_failing_body_still_rolls_back(self):
        self.holder([holder_row()])
        with self.assertRaises(KeyError):
            with self.session.hold_lock("SELECT TOP (1) acct_id FROM [pauth].[auth_summary] WITH (UPDLOCK)"):
                raise KeyError("case")
        self.assertIn("ROLLBACK TRANSACTION", FakeProc.made[0].stdin.text)
        self.assertIn("= OBJECT_ID(N'pauth.auth_summary')", self.runner.of("admin")[0].stdin)

    def test_the_holder_yields_its_own_session_and_must_keep_it(self):
        self.holder([holder_row(session_id=57)])
        with self.session.fixture_lock(LOCK_SQL) as held:
            self.assertEqual(held, FixtureLock(57))
        self.assertIn("SELECT s.session_id AS session_id, ", self.runner.of("admin")[0].stdin)
        # Another session with the holder's name and lock is not the same holder.
        FakeProc.made = []
        self.holder([holder_row(session_id=57)], [holder_row(session_id=58)])
        with self.assertRaises(transport.TransportError) as e:
            with self.session.hold_lock(LOCK_SQL):
                pass
        self.assertEqual(e.exception.code, "LOCK_LOST")
        self.assertTrue(FakeProc.made[0].stdin.closed)


TAG_PREFIX = b"carddemo-exec-v1"
WAIT_KEY = "KEY: 7:72057594043760640 (8194443284a0)"


def archive_row(execution_id=11, *, tag=None, waiting=60, blocking=57, wait_type="LCK_M_X", resource=WAIT_KEY,
                db=7, hobt=72057594043760640, object_name="core.transaction"):
    if tag is None:
        tag = TAG_PREFIX + execution_id.to_bytes(8, "big", signed=True)
    return {"waiting": waiting, "blocking": blocking, "wait_type": wait_type, "wait_resource": resource,
            "tag": tag.hex().upper(), "db": db, "hobt": hobt, "object_name": object_name}


class FakeRunning:
    def __init__(self, execution_id=11, finished=False):
        self.execution_id, self.finished, self.polls = execution_id, finished, 0

    def done(self):
        self.polls += 1
        return self.finished


class ArchiveBlockTest(Base):
    def answers(self, *answers):
        rows = [framed(a) for a in answers]
        state = {"i": 0}

        def answer(sql):
            i = min(state["i"], len(rows) - 1)
            state["i"] += 1
            return 0, rows[i], ""
        self.runner.route("admin", "sys.dm_exec_requests", answer)

    def wait(self, running=None, holder=FixtureLock(57), **kw):
        clock = iter(range(0, 10_000, 7))
        with mock.patch.object(transport.time, "monotonic", lambda: next(clock)), \
                mock.patch.object(transport.time, "monotonic_ns", lambda: 123_456_789):
            return self.session.wait_for_archive_blocked(running or FakeRunning(), holder, **kw)

    def failure(self, *answers, running=None, **kw):
        if answers:
            self.answers(*answers)
        with self.assertRaises(transport.TransportError) as e:
            self.wait(running, **kw)
        self.assertEqual(e.exception.numbers, ())
        return e.exception.code

    def test_the_tag_is_the_prefix_and_eight_big_endian_twos_complement_bytes(self):
        self.assertEqual(transport.execution_tag(1), b"carddemo-exec-v1\x00\x00\x00\x00\x00\x00\x00\x01")
        self.assertEqual(transport.execution_tag(0x0102030405060708), TAG_PREFIX + bytes(range(1, 9)))
        self.assertEqual(transport.execution_tag(0), TAG_PREFIX + b"\x00" * 8)
        self.assertEqual(transport.execution_tag(2 ** 63 - 1), TAG_PREFIX + b"\x7f" + b"\xff" * 7)
        for bad in (-1, -(2 ** 63), 2 ** 63, 2 ** 64 - 1, True, False, 1.0, 0.0, "1", "0", None, [0]):
            with self.subTest(bad=bad), self.assertRaises(transport.TransportError) as e:
                transport.execution_tag(bad)
            self.assertEqual((e.exception.code, e.exception.numbers), ("ARCHIVE_EXECUTION", ()))

    def test_execution_id_zero_and_the_maximum_are_matched_by_their_bytes(self):
        """Contract 4.5: batch.BATCH_JOB_EXECUTION_SEQ starts at 0, so 0 is a
        real first execution; its tag ends in eight zero bytes, which a row
        of zeros past the 24 bytes must not confuse."""
        for execution_id, suffix in ((0, "0000000000000000"), (2 ** 63 - 1, "7FFFFFFFFFFFFFFF")):
            with self.subTest(execution_id=execution_id):
                self.runner.calls.clear()
                tag = (TAG_PREFIX.hex() + suffix).upper()
                self.answers([archive_row(execution_id=execution_id + 1 if execution_id == 0 else 0),
                              {**archive_row(), "tag": tag + "00" * 8}])
                proof = self.wait(FakeRunning(execution_id))
                self.assertEqual((proof.execution_id, proof.waiting_session_id, proof.blocking_session_id),
                                 (execution_id, 60, 57))
                self.assertEqual(len(self.runner.of("admin")), 1)

    def test_a_row_with_the_prefix_and_no_id_is_not_execution_zero(self):
        self.assertEqual(self.failure([archive_row(tag=TAG_PREFIX)], running=FakeRunning(0)),
                         "ARCHIVE_BLOCK_TIMEOUT")
        self.assertEqual(self.failure([archive_row(tag=TAG_PREFIX + b"\x00" * 7)], running=FakeRunning(0)),
                         "ARCHIVE_BLOCK_TIMEOUT")

    def test_the_proof_comes_from_one_request_row_blocked_by_the_holder(self):
        self.answers([], [archive_row(blocking=0, wait_type=None, resource=None, hobt=None, object_name=None)],
                     [archive_row(execution_id=12, blocking=57), archive_row(wait_type="LCK_M_U")])
        running = FakeRunning()
        proof = self.wait(running)
        self.assertEqual(proof, ArchiveBlockProof(True, 11, 60, 57, "core.transaction", "KEY", "LCK_M_U",
                                                  123_456_789))
        self.assertEqual(running.polls, 2)
        checks = self.runner.of("admin")
        self.assertEqual(len(checks), 3)
        self.assertEqual(len({c.stdin for c in checks}), 1)
        sql = checks[0].stdin
        self.assertEqual(checks[0].argv[-1], DB)
        self.assertIn("FROM sys.dm_exec_requests r ", sql)
        self.assertIn("r.database_id = DB_ID() AND SUBSTRING(r.context_info, 1, 16) = 0x"
                      + TAG_PREFIX.hex().upper(), sql)
        # Fixed text: the execution ID is matched here, never sent.
        self.assertNotIn(transport.execution_tag(11).hex().upper(), sql)
        self.assertEqual(self.runner.of("login"), [])

    def test_a_tag_padded_with_zero_bytes_is_the_same_tag(self):
        self.answers([archive_row(tag=transport.execution_tag(11) + b"\x00" * 104)])
        self.assertEqual(self.wait().execution_id, 11)

    def test_every_condition_is_required_until_the_deadline(self):
        other = dict(tag=transport.execution_tag(11) + b"\x00\x01")
        for row in (archive_row(blocking=58), archive_row(blocking=60), archive_row(waiting=57, blocking=57),
                    archive_row(blocking=True), archive_row(blocking="57"), archive_row(waiting=0),
                    archive_row(waiting=None), archive_row(wait_type="LCK_M_S"), archive_row(wait_type="LCK_M_IX"),
                    archive_row(wait_type="PAGELATCH_EX"), archive_row(resource="PAGE: 7:1:200"),
                    archive_row(resource="OBJECT: 7:1234:0"), archive_row(resource="KEY: 8:72057594043760640 (8194443284a0)"),
                    archive_row(resource="KEY: 7:72057594043760641 (8194443284a0)"),
                    archive_row(resource=WAIT_KEY + " x"), archive_row(db=8), archive_row(db=True),
                    archive_row(hobt=None), archive_row(object_name="core.card"),
                    archive_row(object_name="core.transaction_archive"), archive_row(object_name=None),
                    archive_row(execution_id=12), archive_row(execution_id=-11), archive_row(**other),
                    archive_row(tag=TAG_PREFIX), archive_row(tag=b"carddemo-exec-v2" + bytes(7) + b"\x0b"),
                    {**archive_row(), "tag": "zz"}, {**archive_row(), "tag": None}, {}):
            with self.subTest(row=row):
                self.runner.calls.clear()
                self.assertEqual(self.failure([row]), "ARCHIVE_BLOCK_TIMEOUT")
                self.assertGreater(len(self.runner.of("admin")), 1)

    def test_an_execution_that_ends_before_it_is_blocked_fails(self):
        running = FakeRunning(finished=True)
        self.assertEqual(self.failure([archive_row(blocking=0)], running=running), "ARCHIVE_NOT_BLOCKED")
        self.assertEqual(len(self.runner.of("admin")), 1)

    def test_two_requests_with_the_executions_tag_fail(self):
        self.assertEqual(self.failure([archive_row(), archive_row(waiting=61)]), "ARCHIVE_TAG_AMBIGUOUS")

    def test_a_failed_read_fails(self):
        self.runner.route("admin", "sys.dm_exec_requests", (1, "Msg 208, Level 16\n", ""))
        with self.assertRaises(transport.TransportError) as e:
            self.wait()
        self.assertEqual((e.exception.code, e.exception.numbers), ("ADMIN_SQL_FAILED", ("208",)))

    def test_bad_arguments_fail_before_any_call(self):
        cases = [(dict(holder=None), "LOCK_SESSION_REQUIRED"), (dict(holder=57), "LOCK_SESSION_REQUIRED"),
                 (dict(holder=(57,)), "LOCK_SESSION_REQUIRED"),
                 (dict(timeout=0), "ARCHIVE_TIMEOUT_VALUE"), (dict(timeout=-1), "ARCHIVE_TIMEOUT_VALUE"),
                 (dict(timeout=True), "ARCHIVE_TIMEOUT_VALUE"), (dict(timeout=3600), "ARCHIVE_TIMEOUT_VALUE"),
                 (dict(running=object()), "ARCHIVE_EXECUTION")]
        for value in (-1, -3, -(2 ** 63), 2 ** 63, True, False, None, "11", "0", 11.0, 0.0):
            cases.append((dict(running=FakeRunning(value)), "ARCHIVE_EXECUTION"))
        # Only the execution ID may be 0; a holder session of 0, past
        # FixtureLock's own check, is still refused.
        zero_session = object.__new__(FixtureLock)
        object.__setattr__(zero_session, "session_id", 0)
        cases.append((dict(running=FakeRunning(0), holder=zero_session), "LOCK_SESSION_REQUIRED"))
        for kw, code in cases:
            with self.subTest(kw=kw):
                self.assertEqual(self.failure(**kw), code)
        refused = transport._Refused("LAUNCH_REFUSED")
        self.assertEqual(self.failure(running=refused), "ARCHIVE_EXECUTION")
        self.assertEqual(self.runner.calls, [])
        with mock.patch.object(live.d, "LOAD_REPORT_GAP", "the gap"):
            with self.assertRaises(live.d.PipelineError):
                self.session.wait_for_archive_blocked(FakeRunning(), FixtureLock(57))
        self.assertEqual(self.runner.calls, [])

class LoadTest(Base):
    def setUp(self):
        super().setUp()
        self.reset()
        self.source = self.tmp / "source"
        self.source.mkdir()
        (self.source / "acctdata.txt").write_bytes(b"account\n")
        (self.source / "link.txt").symlink_to(self.source / "acctdata.txt")
        (self.source / "sub").mkdir()
        self.pauth = self.tmp / "pauth.dat"
        self.pauth.write_bytes(b"\xc1\xc2")
        self.manifest = self.tmp / "manifest.json"
        self.manifest.write_text("{}")

    def loads(self, rc, content: bytes | None, status="FAILED"):
        def load(argv):
            path = Path(next(a for a in argv if a.startswith(f"--{live.ID_FILE_PROPERTY}=")).split("=", 1)[1])
            if content is not None:
                path.write_bytes(content)
            return rc, "load output", ""
        self.runner.load = load
        self.runner.route("login", "batch.BATCH_JOB_EXECUTION e", lambda sql: (0, framed(job_row(7, status)), ""))
        self.runner.route("login", "batch.BATCH_STEP_EXECUTION s", lambda sql: (0, framed(step_rows(status)), ""))

    def test_a_failing_load_is_an_execution_on_private_copies(self):
        self.loads(1, b"7\n")
        execution = self.session.load(self.source, manifest=self.manifest, authorization_file=self.pauth)
        self.assertEqual((execution.status, execution.execution_id), ("FAILED", 7))
        base = self.live.root / "spec-001-legacy" / "spec-load-01"
        self.assertEqual(sorted(p.name for p in (base / "source").iterdir()), ["acctdata.txt"])
        for path in (base / "source" / "acctdata.txt", base / "pauth.dat", base / "manifest.json", base / "load.log"):
            self.assertFalse(path.is_symlink())
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.assertFalse((base / "load-id").exists())
        call = self.runner.of("java")[0]
        self.assertIn("--spring.profiles.active=demo", call.argv)
        self.assertIn(f"--carddemo.batch.pauth.source-file={base / 'pauth.dat'}", call.argv)
        self.assertIn(f"--{live.d.LAYOUT_MANIFEST_PROPERTY}={base / 'manifest.json'}", call.argv)
        self.assertIn("--carddemo.fidelity.DEF-ACUP-002=legacy", call.argv)
        self.assertEqual(call.env["CARDDEMO_LOAD_SOURCE_DIR"], str(base / "source"))
        self.assertEqual(call.env["CARDDEMO_PAUTH_SOURCE_FILE"], str(base / "pauth.dat"))
        self.assertEqual(call.cwd, live.BATCH_MODULE)
        self.assertEqual(self.pauth.read_bytes(), b"\xc1\xc2")

    def test_a_load_that_names_no_execution_is_not_bound(self):
        for content in (b"", None, b"07\n", b"x\n"):
            self.loads(1, content)
            with self.assertRaises(transport.TransportError):
                self.session.load(self.source, authorization_file=self.pauth)

    def test_exit_code_and_status_must_agree(self):
        self.loads(0, b"7\n", "FAILED")
        with self.assertRaises(transport.TransportError) as e:
            self.session.load(self.source, authorization_file=self.pauth)
        self.assertEqual(e.exception.code, "LOAD_EXIT_STATUS")
        self.loads(0, b"7\n", "COMPLETED")
        self.assertEqual(self.session.load(self.source, authorization_file=self.pauth).status, "COMPLETED")

    def test_a_linked_authorization_file_is_refused(self):
        link = self.tmp / "pauth-link.dat"
        link.symlink_to(self.pauth)
        self.loads(0, b"7\n", "COMPLETED")
        with self.assertRaises(transport.TransportError):
            self.session.load(self.source, authorization_file=link)
        self.assertEqual(self.runner.of("java"), [])


class CloseTest(Base):
    def test_close_stops_the_server_and_drops_the_fixture_login(self):
        self.reset()
        self.assertEqual(self.session.log_bytes(), b"server log\n")
        self.session.close()
        drop = self.runner.of("admin")[-1]
        sid = self.session._s.fixture_sid
        self.assertEqual(drop.stdin, transport.FIXTURE_DROP_SQL.format(
            login=f"{DB}_fixture", sid=sid, quoted=f"[{DB}_fixture]") + "\n")
        self.assertEqual(drop.argv[-1], "master")
        self.assertEqual(self.live.calls[0], ("stop",))
        self.assertFalse(self.record.exists())

    def test_close_without_a_case_touches_no_database(self):
        self.assertEqual(self.session.log_bytes(), b"")
        self.session.close()
        self.assertEqual(self.runner.calls, [])

    def test_only_private_databases(self):
        self.live.db = "carddemo"
        with self.assertRaises(transport.TransportError):
            transport.SpecSession(self.live, runner=self.runner)


class FakeServer:
    """The server principals for the fixture login's CREATE and guarded DROP
    batches, with the ways either can fail. Logins are name -> SID."""
    CREATE = re.compile(r"CREATE LOGIN \[(?P<name>[^\]]+)\] WITH PASSWORD = N'[^']*', SID = (?P<sid>0x[0-9A-F]+),")
    DROP = re.compile(r"WHERE name = N'(?P<name>[^']+)'\);\nIF @found = (?P<sid>0x[0-9A-F]+) AND DATALENGTH\(@found\) "
                      r"= 16 DROP LOGIN \[(?P=name)\];\n")

    def __init__(self, runner):
        self.logins = {}
        self.create_mode = self.drop_mode = "ok"
        runner.route("admin", "CREATE LOGIN", self.create)
        runner.route("admin", "DROP LOGIN", self.drop)

    def create(self, sql):
        m = self.CREATE.search(sql)
        assert m, "CREATE LOGIN without a SID"
        exists = m["name"] in self.logins
        if self.create_mode == "timeout_before":
            raise transport.TransportError("TIMEOUT")
        if self.create_mode == "error_before":
            return 1, "Sqlcmd: Error: Communication link failure.\n", ""
        if exists:
            return 1, "Msg 50001, Level 16, State 1\nFIXTURE_LOGIN_EXISTS\n", ""
        self.logins[m["name"]] = m["sid"]
        if self.create_mode == "timeout_after":
            raise transport.TransportError("TIMEOUT")
        if self.create_mode == "error_after":
            return 1, "Sqlcmd: Error: Communication link failure.\n", ""
        return 0, "", ""

    def drop(self, sql):
        m = self.DROP.search(sql)
        assert m, "DROP LOGIN without the name and SID guard"
        if self.drop_mode == "timeout":
            raise transport.TransportError("TIMEOUT")
        if self.drop_mode == "error":
            return 1, "Msg 15151, Level 16, State 1\n", ""
        if self.drop_mode == "garbage":
            return 0, "DROPPED\nDROPPED\n", ""
        if self.drop_mode == "owned":
            return 0, "OWNED\n", ""
        found = self.logins.get(m["name"])
        if found == m["sid"]:
            del self.logins[m["name"]]
        now = self.logins.get(m["name"])
        outcome = (("ABSENT" if found is None else "DROPPED") if now is None
                   else "OWNED" if now == m["sid"] else "FOREIGN")
        return 0, outcome + "   \n", ""


class OwnershipTest(Base):
    """The fixture login is this session's only with its name and the SID
    chosen before CREATE; anything else is preserved and fails the clean-up."""

    FOREIGN_SID = "0x" + "11" * 16

    def setUp(self):
        super().setUp()
        self.server = FakeServer(self.runner)
        self.login = f"{DB}_fixture"

    def drops(self):
        return [c.stdin for c in self.runner.of("admin") if "DROP LOGIN" in c.stdin]

    def assert_record(self, cleanup):
        record = json.loads(self.record.read_text())
        self.assertEqual((record["login"], record["sid"], record.get("cleanup")),
                         (self.login, self.session._s.fixture_sid, cleanup))
        self.assertNotIn(self.session._s.fixture_password, self.record.read_text())
        self.assertFalse(self.record.is_relative_to(self.live.root))

    def test_an_existing_login_survives_a_create_that_times_out_or_fails_unclearly(self):
        # The CREATE batch's answer is lost: the server's own refusal of the
        # existing name never reaches the session.
        for mode in ("timeout_before", "error_before"):
            with self.subTest(mode=mode):
                self.setUp()
                self.server.logins[self.login] = self.FOREIGN_SID
                self.server.create_mode = mode
                with self.assertRaises(transport.TransportError) as e:
                    self.session.reset(mode="legacy")
                self.assertEqual(e.exception.code, "PROVISION")
                self.assertFalse(any("CREATE USER" in c.stdin for c in self.runner.of("admin")))
                with self.assertRaises(transport.TransportError) as e:
                    self.session.close()
                self.assertEqual(e.exception.code, "FIXTURE_LOGIN_FOREIGN")
                self.assertEqual(self.server.logins, {self.login: self.FOREIGN_SID})
                self.assert_record("FOREIGN")

    def test_a_login_this_create_made_before_its_timeout_is_dropped_by_its_sid(self):
        for mode in ("timeout_after", "error_after", "timeout_before", "error_before"):
            with self.subTest(mode=mode):
                self.setUp()
                self.server.create_mode = mode
                with self.assertRaises(transport.TransportError):
                    self.session.reset(mode="legacy")
                self.assertTrue(self.record.exists())
                self.session.close()
                self.assertEqual(self.server.logins, {})
                self.assertFalse(self.record.exists())
                self.assertEqual(len(self.drops()), 1)

    def test_an_unclear_create_is_not_retried_in_the_next_case(self):
        self.server.create_mode = "timeout_after"
        with self.assertRaises(transport.TransportError):
            self.session.reset(mode="legacy")
        self.runner.calls.clear()
        with self.assertRaises(transport.TransportError) as e:
            self.session.reset(mode="fixed")
        self.assertEqual(e.exception.code, "FIXTURE_UNCLEAR")
        self.assertEqual(self.runner.of("admin"), [])

    def test_a_login_replaced_by_another_of_the_same_name_is_preserved(self):
        self.reset()
        self.server.logins[self.login] = self.FOREIGN_SID
        with self.assertRaises(transport.TransportError) as e:
            self.session.close()
        self.assertEqual(e.exception.code, "FIXTURE_LOGIN_FOREIGN")
        self.assertEqual(self.server.logins, {self.login: self.FOREIGN_SID})
        self.assert_record("FOREIGN")

    def test_a_failed_drop_keeps_an_actionable_record_outside_the_run_directory(self):
        for mode, cleanup, numbers in (("error", "ADMIN_SQL_FAILED", ("15151",)), ("timeout", "TIMEOUT", ()),
                                       ("garbage", "UNREADABLE", ()), ("owned", "OWNED", ())):
            with self.subTest(mode=mode):
                self.setUp()
                self.reset()
                sid = self.session._s.fixture_sid
                self.server.drop_mode = mode
                with self.assertRaises(transport.TransportError) as e:
                    self.session.close()
                self.assertEqual((e.exception.code, e.exception.numbers), ("FIXTURE_DROP", numbers))
                if mode != "garbage":
                    self.assertEqual(self.server.logins, {self.login: sid})
                self.assert_record(cleanup)
                self.assertEqual(self.session.fixture_record, self.record)
                self.assertEqual(stat.S_IMODE(self.record.stat().st_mode), 0o600)
                # The run directory goes; the record does not.
                for path in sorted(self.live.root.rglob("*"), reverse=True):
                    path.unlink() if path.is_file() else path.rmdir()
                self.assertTrue(self.record.exists())
                # The next run refuses before any login.
                other = transport.SpecSession(self.live, runner=self.runner, popen=FakeProc,
                                              new_driver=FakeDriver, switches=SWITCHES)
                self.live.root.mkdir(exist_ok=True)
                self.runner.calls.clear()
                with self.assertRaises(transport.TransportError) as e:
                    other.reset(mode="legacy")
                self.assertEqual(e.exception.code, "FIXTURE_RECORD_LEFT")
                self.assertEqual(self.runner.of("admin"), [])
                # The operator's drop is the record's own guarded batch.
                self.server.drop_mode = "ok"
                self.runner(["sh", "-c", "x"], stdin=json.loads(self.record.read_text())["drop"] + "\n",
                            timeout=1)
                if mode != "garbage":
                    self.assertEqual(self.server.logins, {})
                # A second close retries the same guarded drop.
                self.session.close()
                self.assertFalse(self.record.exists())

    def test_every_drop_is_guarded_by_the_exact_name_and_sid(self):
        self.reset()
        self.session.close()
        sid = self.session._s.fixture_sid
        self.assertEqual(len(self.drops()), 1)
        # A dropped login is not dropped again by a second close.
        self.session.close()
        self.assertEqual(len(self.drops()), 1)
        for sql in self.drops():
            self.assertEqual(sql.count("DROP LOGIN"), 1)
            self.assertIn(f"IF @found = {sid} AND DATALENGTH(@found) = 16 DROP LOGIN [{self.login}];", sql)
            self.assertIn(f"(SELECT sid FROM sys.server_principals WHERE name = N'{self.login}')", sql)
        self.assertNotIn("SUSER_ID(N'", self.drops()[0])

    def test_each_session_has_its_own_random_sid(self):
        sids = {transport.SpecSession(self.live, runner=self.runner)._s.fixture_sid for _ in range(20)}
        self.assertEqual(len(sids), 20)
        for sid in sids:
            self.assertRegex(sid, r"\A0x[0-9A-F]{32}\Z")

    def test_a_malformed_sid_is_refused_before_any_record_or_login(self):
        self.session._s.fixture_sid = "0x00; DROP LOGIN [x]"
        with self.assertRaises(transport.TransportError) as e:
            self.session.reset(mode="legacy")
        self.assertEqual(e.exception.code, "FIXTURE_SID")
        self.assertFalse(self.record.exists())
        self.assertFalse(any("LOGIN" in c.stdin for c in self.runner.of("admin")))

    def synced(self):
        synced = []
        real = os.fsync

        def fsync(fd):
            if stat.S_ISDIR(os.fstat(fd).st_mode):
                synced.append("dir")
            else:
                # The file being flushed is the only temporary one, written whole.
                temp, = self.record.parent.glob(f".{self.record.name}.*.tmp")
                synced.append(json.loads(temp.read_text()))
            real(fd)
        return synced, mock.patch.object(transport.os, "fsync", fsync)

    def test_the_record_and_its_folder_are_on_disk_before_create_login(self):
        synced, patch = self.synced()
        seen = {}
        self.runner.route("admin", "CREATE LOGIN", lambda sql: (seen.update(
            record=json.loads(self.record.read_text()), synced=list(synced)), (0, "", ""))[1])
        with patch:
            self.session.reset(mode="legacy")
        self.assertEqual(seen["record"]["sid"], self.session._s.fixture_sid)
        self.assertEqual(seen["synced"], [seen["record"], "dir"])
        self.assertEqual(sorted(p.name for p in self.record.parent.iterdir()), [self.record.name])

    def test_a_record_write_that_fails_leaves_no_record_and_sends_no_login(self):
        for broken in ("fsync", "dump", "link"):
            with self.subTest(broken=broken):
                self.setUp()

                def fail(*a, **k):
                    if broken == "dump":
                        a[1].write('{"login": ')
                    raise OSError("disk")
                target = {"fsync": (transport.os, "fsync"), "dump": (transport.json, "dump"),
                          "link": (transport.os, "link")}[broken]
                with mock.patch.object(*target, fail):
                    with self.assertRaises(transport.TransportError) as e:
                        self.session.reset(mode="legacy")
                self.assertEqual(e.exception.code, "FIXTURE_MANIFEST")
                self.assertEqual(list(self.record.parent.iterdir()), [])
                self.assertFalse(any("LOGIN" in c.stdin for c in self.runner.of("admin")))
                # Nothing was created, so the next case may try again.
                self.session.reset(mode="legacy")
                self.assertTrue(self.record.exists())

    def test_a_failure_rewrite_that_breaks_keeps_the_whole_earlier_record(self):
        self.reset()
        before = self.record.read_text()
        self.server.drop_mode = "owned"

        def partial(record, f):
            f.write('{"login": ')
            raise OSError("disk")
        with mock.patch.object(transport.json, "dump", partial):
            with self.assertRaises(transport.TransportError) as e:
                self.session.close()
        self.assertEqual(e.exception.code, "FIXTURE_DROP")
        self.assertEqual(self.record.read_text(), before)
        self.assertEqual(sorted(p.name for p in self.record.parent.iterdir()), [self.record.name])

    def test_the_failure_rewrite_replaces_the_record_and_flushes_its_folder(self):
        self.reset()
        self.server.drop_mode = "owned"
        synced, patch = self.synced()
        with patch, self.assertRaises(transport.TransportError):
            self.session.close()
        self.assert_record("OWNED")
        self.assertEqual(synced, [json.loads(self.record.read_text()), "dir"])
        self.assertEqual(stat.S_IMODE(self.record.stat().st_mode), 0o600)
        self.assertEqual(sorted(p.name for p in self.record.parent.iterdir()), [self.record.name])


class ControlCharacterTest(Base):
    def test_control_and_non_ascii_characters_never_reach_sqlcmd(self):
        bad = ["\r", "\v", "\f", "\x1b", "\x01", "\x7f", "\x85", "\x9f", "\u00e9", "\u20ac", "\u2028", "\ufeff"]
        for char in bad:
            with self.subTest(char=hex(ord(char))):
                for sql in (f"UPDATE core.card SET x = N'{char}'", f"SELECT 1 a,\n{char}EXIT", f"{char}:r x"):
                    for call in (self.session.execute, self.session.query, self.session._admin,
                                 self.session._login_sql, self.session.fixture_execute):
                        with self.assertRaises(transport.TransportError) as e:
                            call(sql)
                        self.assertEqual(e.exception.code, "SQL_SHAPE")
                with self.assertRaises(transport.TransportError) as e:
                    with self.session.hold_lock(LOCK_SQL.replace(" WITH", f"{char} WITH", 1)):
                        self.fail("body ran")
                self.assertIn(e.exception.code, ("SQL_SHAPE", "LOCK_SHAPE"))
                with self.assertRaises(transport.TransportError) as e:
                    with self.session.hold_lock(f"SELECT TOP (1) a,\n{char}EXIT FROM core.transaction WITH (UPDLOCK)"):
                        self.fail("body ran")
                self.assertEqual(e.exception.code, "SQL_SHAPE")
        self.assertEqual(self.runner.calls, [])
        self.assertEqual(FakeProc.made, [])

    def test_tabs_newlines_and_hex_latin1_values_pass(self):
        self.runner.route("login", "SET XACT_ABORT", (0, "", ""))
        value = sqltext.latin1_literal("Zo\u00eb \u00c7a\u00ff")
        self.session.execute(f"UPDATE core.customer\n\tSET first_name = {value}\n\tWHERE cust_id = 1")
        stdin = self.runner.of("login")[-1].stdin
        self.assertIn("CAST(0x5A006F00EB002000C7006100FF00 AS NVARCHAR(7))", stdin)
        self.assertTrue(stdin.isascii())


if __name__ == "__main__":
    unittest.main()
