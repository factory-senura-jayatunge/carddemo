"""Tests of the live driver without a database, a JVM or a server: the session's
commands, the API and sqlcmd are stand-ins that record what they get."""
from __future__ import annotations

import base64
import hashlib
import io
import json
import os
import re
import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import differential as d  # noqa: E402
import live  # noqa: E402

SAMPLE = d.REPO / "app" / "data" / "ASCII"
SWITCHES = [d.ACUP_002, "DEF-INTC-010"]
ACUP = d.Mode("fixed DEF-ACUP-002", frozenset({d.ACUP_002}))


def own_env_file(test: unittest.TestCase, tmp: Path, text: str = "") -> Path:
    """A private .env for the test, so no test reads the operator's git-ignored one."""
    path = tmp / "env-file"
    path.write_text(text, encoding="utf-8")
    patcher = mock.patch.object(live.stack, "ENV_FILE", str(path))
    patcher.start()
    test.addCleanup(patcher.stop)
    return path


class FakeApi:
    def __init__(self, cycle_status=201, ends="COMPLETED", running=0, rejects=None):
        self.calls, self.cycle_status, self.ends = [], cycle_status, ends
        self.running, self.polls, self.rejects, self.job = running, 0, rejects or {}, None

    def post(self, path, body):
        self.calls.append(("post", path, body))
        due = body["interest"] == "always"
        cycle = {"cycleId": 3, "businessDate": body["businessDate"], "attempt": 1, "interest": due,
                 "interestParm": "2022070600" if due else None}
        return (self.cycle_status, cycle if self.cycle_status == 201 else {"code": "CYCLE_PARAMETERS_DIFFER"}, {})

    def window(self, action, body):
        self.calls.append(("window", action, body))
        return 200, {"windowId": 1}, {}

    def launch(self, job, params):
        self.calls.append(("launch", job, params))
        self.job = job
        return 202, {"executionId": 11, "restart": False}, {}

    def poll(self, execution):
        self.polls += 1
        if self.polls <= self.running:
            return 200, {"executionId": execution, "executionStatus": "STARTED", "exitStatus": "UNKNOWN",
                         "rejectCount": 0}, {}
        rejects = self.rejects.get(self.job, 0)
        exit_status = "COMPLETED_WITH_REJECTS" if self.ends == "COMPLETED" and rejects else self.ends
        return 200, {"executionId": execution, "executionStatus": self.ends, "exitStatus": exit_status,
                     "rejectCount": rejects}, {}

class FakeSession:
    """live.Session's interface; records each command with the environment values that matter."""

    def __init__(self, root: Path, layout="supplied", sequence="9000000000000417\n", api=None, written="11\n",
                 load_fails=False, report_change=None, makes_root=True):
        self.root, self.db, self.makes_root = root, "carddemo_verify_batch", makes_root
        self.steps, self.layout, self.sequence = [], layout, sequence
        self.api = api or FakeApi()
        self.server = None
        self.sql_queries = []
        self.written, self.load_fails, self.report_change = written, load_fails, report_change or {}
        self.id_file = self.loaded = None
        self.reports = []

    def env(self, var_dir):
        return {"CARDDEMO_DB_NAME": self.db, "CARDDEMO_DB_LOGIN_PREFIX": f"{self.db}_",
                "CARDDEMO_VAR_DIR": SHARED_VAR if var_dir is None else str(var_dir), "CARDDEMO_BATCH_INTERNAL_TOKEN": "t"}

    def jars(self):
        return "/jars/batch-service-1-exec.jar", "/jars/verify-differential-1-exec.jar"

    def step(self, what, argv, env, cwd, log=None, exporter=False):
        keep = {k: env[k] for k in env if k.startswith("CARDDEMO_") and k != "CARDDEMO_BATCH_INTERNAL_TOKEN"}
        self.exporter_steps = getattr(self, "exporter_steps", []) + ([what] if exporter else [])
        self.steps.append((what, argv, keep, cwd, log))
        if what == "db-reset" and self.makes_root:
            # make passes RUN_VAR_DIR to db-reset's JVM only.
            run_var = [a.split("=", 1)[1] for a in argv if a.startswith("RUN_VAR_DIR=")]
            make_root(Path(run_var[0]), env["CARDDEMO_DB_NAME"])
        if what == "load":
            # As the one-shot load does: CREATE_NEW 0600, then the ID once launched.
            prop = [a for a in argv if a.startswith(f"--{live.ID_FILE_PROPERTY}=")]
            self.id_file = Path(prop[0].split("=", 1)[1])
            self.id_dir_mode = self.id_file.parent.stat().st_mode & 0o777
            if self.written is not None:
                fd = os.open(self.id_file, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                os.write(fd, self.written.encode())
                os.close(fd)
            source = Path(env["CARDDEMO_LOAD_SOURCE_DIR"])
            manifest = [a.split("=", 1)[1] for a in argv if a.startswith(f"--{d.LAYOUT_MANIFEST_PROPERTY}=")]
            self.loaded = (d.sha256(source / "acctdata.txt"), d.sha256(Path(manifest[0])) if manifest else "")
            if self.load_fails:
                raise d.PipelineError("load: exited 1, see load.log")
        return ""

    def report(self, what, argv, env, cwd, log):
        self.reports.append((what, argv, log))
        legacy = self.layout == "ignored"
        body = {"schemaVersion": 1, "loadExecutionId": int(argv[-1]), "jobName": "dataLoadJob",
                "status": "COMPLETED", "exitCode": "COMPLETED", "businessDate": "2022-07-06",
                "groupIdMode": "legacy" if legacy else "fixed", "accountRecords": 50,
                "accountsAsRead": 50 if legacy else 0, "accountsMigrated": 0 if legacy else 50,
                "accountLayoutSource": self.layout, "accountLayoutWarnings": 0, "acctdataSha256": self.loaded[0],
                "accountLayoutManifestSha256": self.loaded[1] if self.layout == "supplied" else ""}
        return json.dumps(body | self.report_change, indent=2) + "\n"

    def sql(self, query, env):
        self.sql_queries.append((query, env["CARDDEMO_VAR_DIR"]))
        return self.sequence

    def start_server(self, args, env, log_file):
        self.server = (args, {k: v for k, v in env.items() if k != "CARDDEMO_BATCH_INTERNAL_TOKEN"}, log_file)
        return self.api

    def stop_server(self):
        self.steps.append(("stop", None, None, None, None))


INSTANCE = "0f1e2d3c-4b5a-4968-8776-655443322110"
SHARED_VAR = "/shared/var"


def make_root(var: Path, db: str, instance=INSTANCE, marker=None) -> Path:
    """What db-migrate leaves under a fresh CARDDEMO_VAR_DIR: the instance root and its marker."""
    root = var / "out" / hashlib.sha256(db.encode()).hexdigest() / instance
    root.mkdir(parents=True, mode=0o700)
    name = base64.urlsafe_b64encode(db.encode()).rstrip(b"=")
    (root / ".instance").write_bytes(marker if marker is not None else
                                     b"carddemo-output-root 1\ndatabase=" + name + b"\ninstance=" + instance.encode() + b"\n")
    return root


class LiveTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="live-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.env_file = own_env_file(self, self.tmp)
        patcher = mock.patch.object(live.stack, "java_bin", lambda: "/jdk/bin/java")
        patcher.start()
        self.addCleanup(patcher.stop)

    def intcalc(self):
        data = self.tmp / "data"
        shutil.copytree(SAMPLE, data)
        with open(data / "acctdata.txt", "ab") as f:
            f.write(b"")
        (data / "acctdata.txt").write_bytes((SAMPLE / "acctdata.txt").read_bytes().replace(b"A000000000", b"A000000001", 1))
        ref = self.tmp / "ref"
        ref.mkdir()
        (ref / "ACCTDATA.after.txt").write_text("x\n")
        m = d.write_manifest(data / "acctdata.txt", self.tmp / "m.toml")
        return d.Scenario("INTCALC", "sample", data, ref, m)

    def posttran(self):
        data = self.tmp / "pdata"
        shutil.copytree(SAMPLE, data)
        return d.Scenario("POSTTRAN", "sample", data, self.tmp / "ref")

    def test_the_database_is_the_run_s_own(self):
        self.assertEqual(live.verify_db({}), "carddemo_verify_batch")
        self.assertEqual(live.verify_db({live.DB_ENV: "carddemo_verify_batch_ci2"}), "carddemo_verify_batch_ci2")
        for name in ("carddemo", "carddemo_batch_e2e", "carddemo_verify_batchx", "carddemo_verify_batch_A",
                     "carddemo_verify_batch;DROP"):
            with self.subTest(name), self.assertRaises(d.Unsupported):
                live.verify_db({live.DB_ENV: name})
        for other in ("CARDDEMO_DB_NAME", "CARDDEMO_TEST_MSSQL_DB", "CARDDEMO_E2E_DB_NAME", "CARDDEMO_BATCH_E2E_DB_NAME"):
            with self.subTest(other), self.assertRaisesRegex(d.Unsupported, "is also the demo"):
                live.verify_db({other: "carddemo_verify_batch"})
            self.env_file.write_text(f"{other}=carddemo_verify_batch_x\n", encoding="utf-8")
            with self.subTest(f"{other} in .env"), self.assertRaisesRegex(d.Unsupported, "is also the demo"):
                live.verify_db({live.DB_ENV: "carddemo_verify_batch_x"})
            self.env_file.write_text("", encoding="utf-8")
        self.env_file.unlink()
        with self.assertRaisesRegex(d.Unsupported, "env-file is missing; run make setup"):
            live.verify_db({})

    def test_the_sequence_value(self):
        self.assertEqual(live.parse_sequence("\n9000000000000417\n"), 9000000000000417)
        for text in ("", "9000000000000417\n9000000000000418\n", "NULL\n", "8999999999999999\n", "1\n"):
            with self.subTest(text=text), self.assertRaises(d.PipelineError):
                live.parse_sequence(text)

    def test_without_a_load_report_source_the_run_touches_nothing(self):
        s = self.intcalc()
        session = FakeSession(self.tmp / "live")
        driver = live.LiveDriver(session, self.tmp / "live" / "001", ACUP)
        self.assertTrue(d.LOAD_REPORT_GAP)
        with self.assertRaisesRegex(d.PipelineError, "load report has no readable source"):
            d.execute(d.pipeline(s, ACUP, SWITCHES), driver)
        self.assertEqual(session.steps, [])
        self.assertFalse(driver.work.exists())

    def run_with_hash(self, s, session, mode=ACUP):
        """The run as it will be once data's load report and export v2 are merged."""
        driver = live.LiveDriver(session, self.tmp / "live" / f"{len(list(self.tmp.glob('live/*'))):03d}", mode)
        with mock.patch.object(d, "LOAD_REPORT_GAP", ""):
            return d.execute(d.pipeline(s, mode, SWITCHES), driver), driver

    def test_the_report_is_of_the_execution_the_load_wrote(self):
        s = self.intcalc()
        session = FakeSession(self.tmp / "live")
        done, driver = self.run_with_hash(s, session)
        self.assertTrue(session.id_file.is_absolute())
        self.assertTrue(str(session.id_file).startswith(str(driver.work)))
        self.assertEqual(session.id_dir_mode, 0o700)
        self.assertFalse(session.id_file.parent.exists())
        self.assertEqual(driver.load_execution, 11)
        [(what, argv, log)] = session.reports
        self.assertEqual(argv, ["/jdk/bin/java", "-jar", "/jars/verify-differential-1-exec.jar", "--load-report", "11"])
        self.assertEqual(log, driver.work / "load-report.log")
        order = [st[0] for st in session.steps if st[0] != "stop"]
        self.assertEqual(order[:2], ["db-reset", "load"])
        self.assertEqual(done.run.migrated, 50)

    def test_no_report_is_asked_for_while_the_gap_is_open(self):
        s = self.intcalc()
        session = FakeSession(self.tmp / "live")
        driver = live.LiveDriver(session, self.tmp / "live" / "001", ACUP)
        driver.work.mkdir(parents=True)
        with self.assertRaisesRegex(d.PipelineError, "load report has no readable source"):
            driver.load(s.data.resolve(), [])
        self.assertEqual(session.reports, [])

    def test_a_load_without_an_execution_stops_the_case(self):
        s = self.intcalc()
        for name, session, message in (
                ("exit 0, empty file", FakeSession(self.tmp / "a", written=""), "exited 0 but named no execution"),
                ("not a number", FakeSession(self.tmp / "b", written="latest\n"), "holds no execution ID"),
                ("no LF", FakeSession(self.tmp / "c", written="11"), "holds no execution ID"),
                ("negative", FakeSession(self.tmp / "d", written="-1\n"), "holds no execution ID"),
                ("leading zero", FakeSession(self.tmp / "i", written="011\n"), "holds no execution ID"),
                ("failed after launch", FakeSession(self.tmp / "e", load_fails=True), r"exited 1.*\(load execution 11\)"),
                ("failed before launch", FakeSession(self.tmp / "f", load_fails=True, written=""),
                 r"\(load execution unknown, not bound\)$"),
                ("failed, no ID file", FakeSession(self.tmp / "h", load_fails=True, written=None),
                 r"\(load execution unknown, not bound\)$"),
                ("failed, ID file unreadable", FakeSession(self.tmp / "g", load_fails=True, written="x\n"),
                 r"load: exited 1, see load.log \(load execution unknown, not bound: the load's execution-ID file "
                 r"holds no execution ID\)")):
            with self.subTest(name):
                with self.assertRaisesRegex(d.PipelineError, message):
                    self.run_with_hash(s, session)
                self.assertEqual(session.reports, [])
                self.assertIsNone(session.server)
                self.assertFalse(session.id_file.parent.exists())

    def test_execution_zero_and_the_64_bit_maximum_are_bound_like_any_other(self):
        s = self.intcalc()
        for name, execution in (("a", "0"), ("m", "9223372036854775807")):
            session = FakeSession(self.tmp / name, written=execution + "\n")
            with self.subTest(execution=execution):
                done, driver = self.run_with_hash(s, session)
                self.assertEqual(driver.load_execution, int(execution))
                self.assertEqual([r[1][-2:] for r in session.reports], [["--load-report", execution]])
                export = [st for st in session.steps if st[0] == "export"][0][1]
                self.assertEqual(export[export.index("--load-execution-id") + 1], execution)
                self.assertEqual(done.load["loadExecutionId"], int(execution))
        failed = FakeSession(self.tmp / "b", written="0\n", load_fails=True)
        with self.assertRaisesRegex(d.PipelineError, r"load: exited 1, see load.log \(load execution 0\)$"):
            self.run_with_hash(s, failed)
        self.assertEqual(failed.reports, [])

    def test_the_load_report_is_checked_key_by_key(self):
        s = self.intcalc()
        for change, message in (
                ({"schemaVersion": 2}, "not schemaVersion 1"),
                ({"loadExecutionId": 12}, "wrong in loadExecutionId"),
                ({"loadExecutionId": 2 ** 63}, "loadExecutionId is not a 64-bit execution ID"),
                ({"loadExecutionId": -1}, "loadExecutionId is not a int"),
                ({"jobName": "postDailyTransactionsJob"}, "wrong in jobName"),
                ({"status": "FAILED"}, "wrong in status"),
                ({"businessDate": "2022-07-07"}, "wrong in businessDate"),
                ({"accountRecords": 51}, "wrong in accountRecords"),
                ({"groupIdMode": "legacy"}, "wrong in groupIdMode"),
                ({"acctdataSha256": "A" * 64}, "wrong in acctdataSha256"),
                ({"accountLayoutManifestSha256": ""}, "wrong in accountLayoutManifestSha256"),
                ({"accountsMigrated": True}, "accountsMigrated is not a int"),
                ({"accountsMigrated": "50"}, "accountsMigrated is not a int"),
                ({"extra": 1}, "has the keys"),
                ({"acctdataSha256": "0" * 64}, "the load read acctdata.txt 000000000000")):
            session = FakeSession(self.tmp / f"k{len(list(self.tmp.glob('k*')))}", report_change=change)
            with self.subTest(change=change), self.assertRaisesRegex(d.PipelineError, message):
                self.run_with_hash(s, session)
            self.assertIsNone(session.server)

    def test_the_report_s_shape(self):
        body = {k: v for k, v in zip([k for k, _t in live.REPORT_KEYS],
                                     [1, 4, "dataLoadJob", "COMPLETED", "COMPLETED", "2022-07-06", "legacy", 50, 50, 0,
                                      "ignored", 0, "c" * 64, ""])}
        self.assertEqual(live.parse_load_report(json.dumps(body) + "\n", 4)["accountsAsRead"], 50)
        for execution in (0, 2 ** 63 - 1):
            report = live.parse_load_report(json.dumps(body | {"loadExecutionId": execution}) + "\n", execution)
            self.assertEqual(report["loadExecutionId"], execution)
        # The bound holds on its own, not only through the binding to the ID file's value.
        with self.assertRaisesRegex(d.PipelineError, "not a 64-bit execution ID"):
            live.parse_load_report(json.dumps(body | {"loadExecutionId": 2 ** 63}) + "\n", 2 ** 63)
        reordered = dict(reversed(list(body.items())))
        swapped = dict(body)
        swapped["jobName"] = swapped.pop("jobName")
        for text, message in (("", "not one JSON object"), (json.dumps(body), "not one JSON object"),
                              ("[1]\n", "not one JSON object"), (json.dumps(reordered) + "\n", "not schemaVersion 1"),
                              (json.dumps(swapped) + "\n", "has the keys"),
                              (json.dumps(body | {"accountsMigrated": 1}) + "\n", "legacy counts"),
                              (json.dumps(body).replace('"jobName"', '"schemaVersion": 1, "jobName"', 1) + "\n",
                               "has the keys")):
            with self.subTest(text=text[:30]), self.assertRaisesRegex(d.PipelineError, message):
                live.parse_load_report(text, 4)

    def test_intcalc_commands_api_calls_and_values_from_the_database(self):
        s = self.intcalc()
        session = FakeSession(self.tmp / "live")
        done, driver = self.run_with_hash(s, session)
        reset, load, export = (st for st in session.steps if st[0] != "stop")
        self.assertEqual(reset[2]["CARDDEMO_DB_LOGIN_PREFIX"], "carddemo_verify_batch_")
        self.assertEqual(reset[1][-2:], ["db-reset", f"RUN_VAR_DIR={driver.var}"])
        self.assertEqual(reset[2]["CARDDEMO_VAR_DIR"], SHARED_VAR)
        self.assertEqual(session.exporter_steps, ["export"])
        self.assertEqual(load[2]["CARDDEMO_VAR_DIR"], str(driver.var))
        self.assertEqual(load[2]["CARDDEMO_LOAD_SOURCE_DIR"], str(s.data.resolve()))
        self.assertEqual(load[1][:4], ["/jdk/bin/java", "-jar", "/jars/batch-service-1-exec.jar", "--spring.profiles.active=demo"])
        self.assertIn(f"--{d.LAYOUT_MANIFEST_PROPERTY}={s.manifest.resolve()}", load[1])
        self.assertIn(f"--carddemo.fidelity.{d.ACUP_002}=fixed", load[1])
        self.assertEqual(load[4], driver.work / "load.log")
        self.assertEqual(export[1][:3], ["/jdk/bin/java", "-jar", "/jars/verify-differential-1-exec.jar"])
        self.assertEqual(export[1][export[1].index("--output-dir") + 1], str(driver.output_root))
        self.assertEqual(export[1][export[1].index("--load-execution-id") + 1], "11")
        self.assertNotIn("--fidelity", export[1])
        self.assertEqual(export[2]["CARDDEMO_VAR_DIR"], str(driver.var))
        args, env, log_file = session.server
        self.assertNotIn(f"--{d.LAYOUT_MANIFEST_PROPERTY}={s.manifest.resolve()}", args)
        self.assertEqual(env["CARDDEMO_VAR_DIR"], str(driver.var))
        self.assertEqual(log_file, driver.work / "server.log")
        self.assertTrue((driver.var / "inbound").is_dir())
        self.assertFalse((driver.var / "inbound" / "dailytran").exists())
        self.assertEqual([c[:2] for c in session.api.calls],
                         [("post", "/internal/cycles"), ("window", "open"), ("launch", "calculateInterestJob"),
                          ("window", "close")])
        self.assertEqual(session.sql_queries, [(live.SEQUENCE_SQL, str(driver.var))])
        self.assertEqual(done.run, d.SpringRun(interest_parm="2022070600", interest_id_start=9000000000000417,
                                               migrated=50))
        self.assertEqual((done.cycle_id, done.export), (3, driver.work / "export"))

    def test_posttran_stages_the_case_s_daily_file_in_the_run_s_inbound(self):
        s = self.posttran()
        # The sample's daily file has 38 rejects: postDailyTransactionsJob ends COMPLETED_WITH_REJECTS.
        session = FakeSession(self.tmp / "live", layout="ignored", api=FakeApi(rejects={"postDailyTransactionsJob": 38}))
        done, driver = self.run_with_hash(s, session, d.Mode("legacy", frozenset()))
        staged = driver.var / "inbound" / "dailytran" / "2022-07-06.txt"
        self.assertEqual(staged.read_bytes(), (SAMPLE / "dailytran.txt").read_bytes())
        self.assertEqual(staged.stat().st_mode & 0o777, 0o600)
        for folder in (driver.work, driver.var, driver.var / "inbound", staged.parent):
            self.assertEqual(folder.stat().st_mode & 0o777, 0o700, folder)
        _args, env, _log = session.server
        self.assertEqual(env["CARDDEMO_VAR_DIR"], str(driver.var))
        self.assertEqual(sorted(set(env) & set(live.LEGACY_SETTINGS)), [])
        self.assertEqual([c[2] for c in session.api.calls if c[0] == "launch"],
                         [{"cycleId": 3}, {"cycleId": 3}])
        self.assertEqual([c[1] for c in session.api.calls if c[0] == "launch"],
                         ["ingestDailyFileJob", "postDailyTransactionsJob"])
        self.assertEqual(session.sql_queries, [])
        self.assertIsNone(done.run.interest_parm)
        self.assertEqual((done.cycle_id, done.export), (3, driver.work / "export"))

    def test_a_job_is_polled_until_it_ends(self):
        session = FakeSession(self.tmp / "live", api=FakeApi(running=2))
        with mock.patch.object(live.time, "sleep", lambda s: None):
            done, _ = self.run_with_hash(self.intcalc(), session)
        self.assertEqual(session.api.polls, 3)
        self.assertEqual(done.cycle_id, 3)

    def test_the_api_must_answer_as_the_contract_says(self):
        s = self.intcalc()
        for name, session, message in (
                ("cycle 409", FakeSession(self.tmp / "a", api=FakeApi(cycle_status=409)), "register the cycle: HTTP 409"),
                ("job failed", FakeSession(self.tmp / "b", api=FakeApi(ends="FAILED")), "ended FAILED / FAILED"),
                ("sequence outside the range", FakeSession(self.tmp / "c", sequence="42\n"), "not one 16-digit"),
                ("as read", FakeSession(self.tmp / "d", layout="none"), "layout source is 'none'")):
            with self.subTest(name), self.assertRaisesRegex(d.PipelineError, message):
                self.run_with_hash(s, session)
            self.assertNotIn("export", [st[0] for st in session.steps])

    def test_a_second_run_on_the_same_database_is_refused(self):
        with mock.patch.object(live, "STATE_DIR", self.tmp / "state"):
            with live.Session(self.tmp / "one", "verify-batch", {}) as first:
                self.assertTrue((self.tmp / "one" / "live").is_dir())
                with self.assertRaisesRegex(d.Unsupported, "another run uses carddemo_verify_batch: verify-batch pid"):
                    live.Session(self.tmp / "two", "verify-batch", {}).__enter__()
                self.assertEqual(first.driver(self.intcalc(), ACUP).work.name, "001-INTCALC-sample")
            with live.Session(self.tmp / "three", "verify-batch", {}):
                pass
            self.assertEqual((self.tmp / "state").stat().st_mode & 0o777, 0o700)
            self.assertEqual((self.tmp / "state" / "carddemo_verify_batch.lock").stat().st_mode & 0o777, 0o600)

    def test_a_case_without_its_output_root_stops_before_the_load(self):
        session = FakeSession(self.tmp / "live", makes_root=False)
        with self.assertRaisesRegex(d.PipelineError, "db-reset made no output root"):
            self.run_with_hash(self.intcalc(), session)
        self.assertEqual([st[0] for st in session.steps if st[0] != "stop"], ["db-reset"])

    def test_the_output_root_is_the_one_db_reset_made(self):
        var = self.tmp / "var"
        root = make_root(var, "carddemo")
        self.assertEqual(root.parent.name, "5840ec35206deb1526e4d5157357eb33967810eeaf78b62d08f273555c7cab0d")
        self.assertIn(b"database=Y2FyZGRlbW8\n", (root / ".instance").read_bytes())
        self.assertEqual(live.instance_root(var, "carddemo"), root)
        with self.assertRaisesRegex(d.PipelineError, "no marker of carddemo_verify_batch"):
            live.instance_root(make_root(self.tmp / "v2", "carddemo_verify_batch", marker=b"x\n").parents[2],
                               "carddemo_verify_batch")
        with self.assertRaisesRegex(d.PipelineError, "no marker of carddemo"):
            other = make_root(self.tmp / "v3", "carddemo")
            (other / ".instance").write_bytes((root / ".instance").read_bytes().replace(b"Y2FyZGRlbW8", b"Y2FyZA"))
            live.instance_root(self.tmp / "v3", "carddemo")
        make_root(var, "carddemo", instance="1f1e2d3c-4b5a-4968-8776-655443322110")
        with self.assertRaisesRegex(d.PipelineError, "not one instance root"):
            live.instance_root(var, "carddemo")
        link = self.tmp / "v4" / "out" / root.parent.name
        link.mkdir(parents=True)
        (link / INSTANCE).symlink_to(root)
        with self.assertRaisesRegex(d.PipelineError, "not one instance root"):
            live.instance_root(self.tmp / "v4", "carddemo")

    def test_the_server_settings_may_not_move_the_case_s_folders(self):
        driver = live.LiveDriver(FakeSession(self.tmp / "live"), self.tmp / "live" / "001", ACUP)
        for name in (*live.LEGACY_SETTINGS, "CARDDEMO_VAR_DIR", "CARDDEMO_DB_NAME"):
            with self.subTest(name), self.assertRaisesRegex(d.PipelineError, f"may not set {name}"):
                driver.start([], {name: "/elsewhere"}, None, "2022-07-06")

    def test_the_export_is_bound_to_the_case_s_load(self):
        driver = live.LiveDriver(FakeSession(self.tmp / "live"), self.tmp / "live" / "001", ACUP)
        driver.load_execution = 11
        with self.assertRaisesRegex(d.PipelineError, "names load 12, this case loaded 11"):
            driver.export("INTCALC", 3, 12)


class SessionTest(unittest.TestCase):
    """The real Session's environment, sqlcmd and server files, with the processes stubbed."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="live-session-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        own_env_file(self, self.tmp)
        patcher = mock.patch.object(live, "STATE_DIR", self.tmp / "state")
        patcher.start()
        self.addCleanup(patcher.stop)
        self.session = live.Session(self.tmp, "verify-batch", {})

    def test_no_legacy_setting_and_the_case_s_var_dir(self):
        shared = {name: "/shared/legacy" for name in live.LEGACY_SETTINGS}
        with mock.patch.object(live.stack, "child_env", lambda: dict(shared, CARDDEMO_VAR_DIR="/shared/var", PATH="/bin")):
            env = self.session.env(self.tmp / "case" / "var")
        self.assertEqual(sorted(set(env) & set(live.LEGACY_SETTINGS)), [])
        self.assertEqual(env["CARDDEMO_VAR_DIR"], str(self.tmp / "case" / "var"))
        self.assertEqual((env["CARDDEMO_DB_NAME"], env["CARDDEMO_DB_LOGIN_PREFIX"]),
                         ("carddemo_verify_batch", "carddemo_verify_batch_"))

    def test_the_sequence_is_read_as_the_run_s_batch_login(self):
        seen = []

        def run(cmd, **kw):
            seen.append((cmd, kw["env"]))
            return mock.Mock(returncode=0, stdout="9000000000000417\n", stderr="")

        infra = mock.patch.dict(live.os.environ, {"CARDDEMO_INFRA_DIR": "/infra"})
        infra.start()
        self.addCleanup(infra.stop)
        with mock.patch.object(live.subprocess, "run", run):
            self.assertEqual(self.session.sql("SELECT 1", {"CARDDEMO_DB_BATCH_PASSWORD": "pw-1"}), "9000000000000417\n")
        [(cmd, env)] = seen
        self.assertEqual(cmd[cmd.index("-U") + 1], "carddemo_verify_batch_batch")
        self.assertEqual(cmd[cmd.index("-d") + 1], "carddemo_verify_batch")
        self.assertEqual(cmd[cmd.index("-e") + 1], "SQLCMDPASSWORD")
        self.assertEqual(cmd[cmd.index("--project-directory") + 1], "/infra")
        self.assertNotIn("pw-1", " ".join(cmd))
        self.assertNotIn("sa", cmd)
        self.assertEqual(env["SQLCMDPASSWORD"], "pw-1")
        with mock.patch.object(live.subprocess, "run", run), self.assertRaisesRegex(d.Unsupported, "BATCH_PASSWORD"):
            self.session.sql("SELECT 1", {})
        self.assertEqual(len(seen), 1)

    def test_the_server_log_and_pid_file_are_the_owner_s(self):
        proc = mock.Mock(pid=4242, returncode=1)
        proc.poll.return_value = 1
        log = self.tmp / "case" / "server.log"
        log.parent.mkdir(mode=0o700)
        live.STATE_DIR.mkdir(mode=0o700)
        with mock.patch.object(self.session, "jars", lambda: ("/jars/b.jar", "/jars/v.jar")), \
                mock.patch.object(live.stack, "java_bin", lambda: "/jdk/bin/java"), \
                mock.patch.object(live.batch_e2e, "free_port", lambda: 40000), \
                mock.patch.object(live.subprocess, "Popen", lambda *a, **kw: proc), \
                mock.patch.object(self.session, "stop_server", lambda: None):
            with self.assertRaisesRegex(d.PipelineError, f"the server exited 1; see {log}"):
                self.session.start_server(["--x"], {"CARDDEMO_BATCH_INTERNAL_TOKEN": "t"}, log)
        self.assertEqual(log.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.session.pid_file.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.session.pid_file.read_text(), "4242 /jars/b.jar\n")

    def test_make_gets_the_stack_s_folder_and_a_jvm_the_case_s(self):
        with mock.patch.object(live.stack, "child_env", lambda: {"CARDDEMO_VAR_DIR": "/shared/var", "PATH": "/bin"}), \
                mock.patch.object(live.stack, "VAR_DIR", "/shared/var"):
            self.assertEqual(self.session.env(None)["CARDDEMO_VAR_DIR"], "/shared/var")
            self.assertEqual(self.session.env(self.tmp / "v")["CARDDEMO_VAR_DIR"], str(self.tmp / "v"))
        self.assertTrue({"CARDDEMO_BATCH_FILES_OUTPUTDIR", "CARDDEMO_BATCH_FILES_INBOUNDDIR",
                         "CARDDEMO_TRANSACTION_REPORT_ROOT"} <= set(live.LEGACY_SETTINGS))

    def test_an_exporter_without_its_database_settings_stops_the_run(self):
        """Load report contract 1.3: exit 2, nothing on stdout, one FAILED line on stderr."""
        line = "verify-differential: FAILED (CARDDEMO_DB_URL is not set)"
        log = self.tmp / "r.log"

        def answer(rc, stdout, stderr):
            return mock.patch.object(live.subprocess, "run",
                                     lambda *a, **kw: mock.Mock(returncode=rc, stdout=stdout, stderr=stderr))
        with answer(2, "", line + "\n"), self.assertRaises(d.Unsupported) as e:
            self.session.report("load report", ["java"], {}, self.tmp, log)
        self.assertIn(f"load report: exited 2: {line}", str(e.exception))
        self.assertEqual(log.stat().st_mode & 0o777, 0o600)
        for rc, stdout in ((2, "{}\n"), (1, "")):
            with self.subTest(rc=rc, stdout=stdout), answer(rc, stdout, line + "\n"), \
                    self.assertRaisesRegex(d.PipelineError, f"exited {rc}: verify-differential: FAILED"):
                self.session.report("load report", ["java"], {}, self.tmp, log)
        with answer(2, line + "\n", ""), self.assertRaisesRegex(d.Unsupported, re.escape(f"export: exited 2: {line}")):
            self.session.step("export", ["java"], {}, self.tmp, log, exporter=True)
        with answer(2, "", ""), self.assertRaisesRegex(d.PipelineError, "load: exited 2"):
            self.session.step("load", ["java"], {}, self.tmp, log)

    def test_an_old_pid_file_becomes_the_owner_s(self):
        proc = mock.Mock(pid=4242, returncode=1)
        proc.poll.return_value = 1
        log = self.tmp / "case" / "server.log"
        log.parent.mkdir(mode=0o700)
        live.STATE_DIR.mkdir(mode=0o700)
        self.session.pid_file.write_text("1 old\n")
        os.chmod(self.session.pid_file, 0o644)
        with mock.patch.object(self.session, "jars", lambda: ("/jars/b.jar", "/jars/v.jar")), \
                mock.patch.object(live.stack, "java_bin", lambda: "/jdk/bin/java"), \
                mock.patch.object(live.batch_e2e, "free_port", lambda: 40000), \
                mock.patch.object(live.subprocess, "Popen", lambda *a, **kw: proc), \
                mock.patch.object(self.session, "stop_server", lambda: None), \
                self.assertRaises(d.PipelineError):
            self.session.start_server([], {"CARDDEMO_BATCH_INTERNAL_TOKEN": "t"}, log)
        self.assertEqual(self.session.pid_file.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.session.pid_file.read_text(), "4242 /jars/b.jar\n")


class ReadExecutionIdTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.path = self.tmp / "execution-id"

    def read(self, data: bytes):
        self.path.write_bytes(data)
        return live.read_execution_id(self.path)

    def test_canonical_ids(self):
        self.assertEqual(self.read(b"0\n"), 0)
        self.assertEqual(self.read(b"7\n"), 7)
        self.assertEqual(self.read(b"999999999999999999\n"), 10 ** 18 - 1)
        self.assertEqual(self.read(b"1000000000000000000\n"), 10 ** 18)
        self.assertEqual(self.read(b"9223372036854775807\n"), 2 ** 63 - 1)
        self.assertIsNone(self.read(b""))

    def test_anything_else_is_refused(self):
        for data in (b"00\n", b"01\n", b"-0\n", b"-1\n", b"+1\n", b" 1\n", b"1 \n", b"1\n\n", b"1\r\n", b"0",
                     b"1", b"\n", b"1e3\n", b"0x1\n", b"9223372036854775808\n", b"9999999999999999999\n",
                     b"10000000000000000000\n", b"09223372036854775807\n", b"1.0\n", b"1_0\n",
                     "\u0661\n".encode(), "1\u0660\n".encode(), "\uff11\n".encode()):
            with self.subTest(data=data), self.assertRaisesRegex(d.PipelineError, "holds no execution ID"):
                self.read(data)

    def test_a_missing_file_is_refused(self):
        with self.assertRaisesRegex(d.PipelineError, "did not create its execution-ID file"):
            live.read_execution_id(self.path)


if __name__ == "__main__":
    unittest.main()
