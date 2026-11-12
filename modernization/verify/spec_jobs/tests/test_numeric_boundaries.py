"""Semantic 0/1 cases that distinguish Boolean equality from integer typing."""
from copy import deepcopy
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

from modernization.verify.spec_jobs import common, discgrp, purge_live, runner
from modernization.verify.spec_jobs.layouts import ROOT
from modernization.verify.spec_jobs.session import Execution
from modernization.verify.spec_jobs.tests.fake import FakeSession
from modernization.verify.spec_jobs.tests.test_purge_live import PurgeSession
from modernization.verify.data.oracle import digest, live, metadata, source
from modernization.verify.data.oracle.tests.test_live import DataSession


class NumericBoundaryTest(unittest.TestCase):
    @staticmethod
    def archive_session(number, count):
        rows = [dict(tran_pk=1, in_master=True)] if number else []
        session = FakeSession(Path("/unused"), tables={"core.transaction": rows})
        session.queries["SELECT tran_pk,in_master"] = lambda s: s.tables["core.transaction"]
        session.queries["SELECT archived_count"] = lambda _: [{"value": number}]
        session.queries["FROM sys.indexes"] = lambda _: [
            dict(column_name="tran_id", is_unique=True, filter_definition="([in_master]=(1))")]
        def launch(s, params):
            for row in rows:
                row["in_master"] = False
            return Execution("COMPLETED", step_counts={"archive": {"WRITE_COUNT": count}})
        session.jobs["archiveMasterJob"] = launch
        return session

    def test_real_load_step_counts_zero_and_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / "source"
            shutil.copytree(ROOT / "app/data/ASCII", directory)
            account = source.lines((directory / "acctdata.txt").read_bytes())[0]
            for mode in ("legacy", "fixed"):
                for number in (0, 1):
                    (directory / "acctdata.txt").write_bytes(account + b"\n" if number else b"")
                    counts, contexts, job = metadata.expected(mode, directory, live.HD_FILE)
                    self.assertEqual(counts["loadAccounts"], {"READ_COUNT": number, "WRITE_COUNT": number})
                    baseline = Execution("COMPLETED", step_counts=counts, contexts=contexts, job_context=job)
                    self.assertFalse(metadata.validate(baseline, mode=mode, source_dir=directory,
                                                       authorization_file=live.HD_FILE))
                    for key in ("READ_COUNT", "WRITE_COUNT"):
                        changed = deepcopy(counts)
                        changed["loadAccounts"][key] = bool(number)
                        problems = metadata.validate(Execution("COMPLETED", step_counts=changed, contexts=contexts,
                                                               job_context=job), mode=mode, source_dir=directory,
                                                     authorization_file=live.HD_FILE)
                        self.assertEqual([(p.check, p.column) for p in problems], [("LOAD_STEP_COUNT", key)])

    def test_archive_write_count_zero_and_one(self):
        for mode in ("legacy", "fixed"):
            for number in (0, 1):
                case = "fixture" if number else "empty"
                self.assertFalse(runner.archive(self.archive_session(number, number), case, mode)[0])
                problems, _ = runner.archive(self.archive_session(number, bool(number)), case, mode)
                self.assertEqual([p.check for p in problems], ["ARCHIVE_WRITE_COUNT"])

    def test_reload_read_and_write_counts_one(self):
        # A successful reload cannot have zero rows: DEFAULT is required.
        raw = discgrp.encode(dict(group_id="DEFAULT", type_cd="01", cat_cd="0001", int_rate="0.00")) + b"\n"
        for mode in ("legacy", "fixed"):
            for key in (None, "READ_COUNT", "WRITE_COUNT"):
                with tempfile.TemporaryDirectory() as tmp:
                    session = FakeSession(Path(tmp))
                    session.queries["FROM ref.disclosure_group"] = lambda s: s.tables["ref.disclosure_group"]
                    def launch(s, params):
                        s.tables["ref.disclosure_group"] = discgrp.parse(raw)
                        counts = {"READ_COUNT": 1, "WRITE_COUNT": 1}
                        if key:
                            counts[key] = True
                        return Execution("COMPLETED", step_counts={"reload": counts})
                    session.jobs["disclosureGroupReloadJob"] = launch
                    with patch.object(runner, "disclosure_input", return_value=raw):
                        problems, _ = runner.disclosure(session, "sample", mode)
                    self.assertEqual([p.check for p in problems], ["RELOAD_COUNTS"] if key else [])

    def test_aggregate_rows_zero_and_one(self):
        types = {"id": digest.Column("int")}
        for number in (0, 1):
            rows = [{"id": 1}] if number else []
            result = digest.aggregate(rows, types=types, columns=("id",), order_by=("id",))
            session = FakeSession(Path("/unused"))
            session.snapshot_digest = lambda *a, **k: result
            self.assertFalse(live.compare_aggregate(session, "t", rows, types, ("id",), "boundary"))
            result["rows"] = bool(number)
            self.assertEqual([p.check for p in live.compare_aggregate(session, "t", rows, types, ("id",), "boundary")],
                             ["SOURCE_AGGREGATE"])

    def test_cross_table_and_cvv_integer_zero_scalars(self):
        # Each scalar's accepted value is exactly integer zero, never one.
        for mode in ("legacy", "fixed"):
            with tempfile.TemporaryDirectory() as tmp:
                session = DataSession(Path(tmp))
                session.reset(mode=mode)
                original = session.query
                self.assertFalse(live.validate(session, mode=mode, source_dir=ROOT / "app/data/ASCII"))
                for key in ("transactions", "orphan_details", "can_unmask"):
                    def query(sql):
                        rows = original(sql)
                        if (key == "can_unmask" and "HAS_PERMS_BY_NAME" in sql) or (
                                key != "can_unmask" and "COUNT_BIG(*)" in sql):
                            rows[0][key] = False
                        return rows
                    session.query = query
                    problems = live.validate(session, mode=mode, source_dir=ROOT / "app/data/ASCII")
                    wanted = "CVV_UNMASK_FORBIDDEN" if key == "can_unmask" else "LOAD_CROSS_TABLE"
                    self.assertTrue(problems)
                    self.assertTrue(all(p.check == wanted for p in problems))
                session.query = original

    def test_failure_snapshot_rows_shape_zero_and_one(self):
        session = FakeSession(Path("/unused"))
        for number in (0, 1):
            session.snapshot_digest = lambda *a, **k: {"rows": number, "digest": "opaque"}
            self.assertEqual(live.failure_snapshot(session, "core.account")["rows"], number)
            session.snapshot_digest = lambda *a, **k: {"rows": bool(number), "digest": "opaque"}
            with self.assertRaises(common.InvalidFixture):
                live.failure_snapshot(session, "core.account")

    def test_purge_cycle_id_shape_zero_and_one(self):
        for mode in ("legacy", "fixed"):
            for number in (0, 1):
                session = PurgeSession(Path("/unused"))
                session.seed()
                session.cycle = lambda *a, **k: number
                self.assertFalse(purge_live.run(session, "one", mode)[0])
                session.seed()
                session.cycle = lambda *a, **k: bool(number)
                with self.assertRaises(common.InvalidFixture):
                    purge_live.run(session, "one", mode)

    def test_archive_previous_and_current_cycle_id_guards_separately(self):
        for mode in ("legacy", "fixed"):
            for field in ("2022-07-05", "2022-07-06"):
                for number in (0, 1):
                    session = self.archive_session(0, 0)
                    session.cycle = lambda date, **k: number if date == field else 2
                    self.assertFalse(runner.archive(session, "empty", mode)[0])
                    session = self.archive_session(0, 0)
                    session.cycle = lambda date, **k: bool(number) if date == field else 2
                    with self.assertRaises(common.InvalidFixture):
                        runner.archive(session, "empty", mode)

    def test_listing_window_id_shape_zero_and_one(self):
        session = FakeSession(Path("/unused"))
        for number in (0, 1):
            session.queries["FROM ops.batch_window"] = lambda _: [{"window_id": number}]
            self.assertTrue(runner.window_open(session))
            session.queries["FROM ops.batch_window"] = lambda _: [{"window_id": bool(number)}]
            with self.assertRaises(common.InvalidFixture):
                runner.window_open(session)

    def test_purge_window_id_shape_zero_and_one(self):
        for mode in ("legacy", "fixed"):
            for number in (0, 1):
                session = PurgeSession(Path("/unused"))
                session.seed()
                original = session.query
                session.query = lambda sql: [{"window_id": number}] if "FROM ops.batch_window" in sql else original(sql)
                # A real open window is a cycle-order finding, not a type error.
                problems, _ = purge_live.run(session, "one", mode)
                self.assertEqual([p.check for p in problems], ["PURGE_CYCLE_ORDER"])
                session.seed()
                session.query = lambda sql: [{"window_id": bool(number)}] if "FROM ops.batch_window" in sql else original(sql)
                with self.assertRaises(common.InvalidFixture):
                    purge_live.run(session, "one", mode)
