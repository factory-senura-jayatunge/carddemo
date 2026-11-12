"""Boolean mutations of numeric metadata must fail, not coerce to 0 or 1."""
from copy import deepcopy
from dataclasses import replace
from pathlib import Path
import tempfile
import unittest

from modernization.verify.spec_jobs import common, prtcatbl, purge, purge_live, runner, tranbkp
from modernization.verify.spec_jobs.session import Execution
from modernization.verify.spec_jobs.tests.fake import FakeSession
from modernization.verify.data.oracle import metadata, source, hd, live
from modernization.verify.data.oracle.tests.test_live import DataSession
from modernization.verify.spec_jobs.layouts import ROOT


class NumericMetadataTest(unittest.TestCase):
    def test_exact_integer_gate_preserves_legitimate_booleans(self):
        for value in (0, 1, 2):
            self.assertEqual(common.require_integer(value), value)
            self.assertTrue(common.equal_scalar(value, value))
        for value in (False, True, 0.0, 1.0, "0", "1"):
            with self.assertRaises(common.InvalidFixture):
                common.require_integer(value)
        self.assertFalse(common.equal_scalar(False, 0))
        self.assertFalse(common.equal_scalar(True, 1))
        self.assertTrue(common.equal_scalar(True, True))
        self.assertTrue(common.equal_scalar(False, False))

    def test_every_purge_numeric_context_rejects_matching_boolean(self):
        for number in (0, 1):
            contexts = {
                "purge": {**dict.fromkeys(purge_live.PUBLIC_CONTEXT_KEYS, number),
                          "fraudDetailsKept": number, "creditReleased": "0" if number == 0 else "0.00"},
                "purgeMessages": {"processedMessagesDeleted": number, "outboxRowsDeleted": number,
                                  "messagesBefore": "2022-07-02T00:00"},
            }
            purge_live.totals(Execution("COMPLETED", contexts=contexts))
            for step, values in contexts.items():
                for key, value in values.items():
                    if type(value) is not int:
                        continue
                    changed = deepcopy(contexts)
                    changed[step][key] = bool(number)
                    with self.subTest(step=step, key=key, number=number), self.assertRaises(common.InvalidFixture):
                        purge_live.totals(Execution("COMPLETED", contexts=changed))

    def test_load_counts_and_numeric_contexts_refuse_booleans(self):
        for mode in ("legacy", "fixed"):
            counts, contexts, job = metadata.expected(mode, ROOT / "app/data/ASCII", live.HD_FILE)
            baseline = Execution("COMPLETED", step_counts=counts, contexts=contexts, job_context=job)
            for attribute in ("step_counts", "contexts"):
                for step, values in getattr(baseline, attribute).items():
                    for key, value in values.items():
                        if type(value) is not int:
                            continue
                        changed = deepcopy(getattr(baseline, attribute))
                        changed[step][key] = bool(value)
                        problems = metadata.validate(replace(baseline, **{attribute: changed}), mode=mode,
                                                     source_dir=ROOT / "app/data/ASCII", authorization_file=live.HD_FILE)
                        self.assertTrue(any(p.column == key and p.source == step for p in problems))

    def test_numeric_ids_refuse_booleans(self):
        for value in (False, True):
            for key in ("execution_id", "job_instance_id"):
                with self.assertRaises(common.InvalidFixture):
                    Execution("COMPLETED", **{key: value})
            for key in ("auth_id", "outbox_id"):
                with self.assertRaises(common.InvalidFixture):
                    purge_live.indexed([{key: value}], key)
            session = FakeSession(Path("/unused"), tables={"core.transaction": [
                dict(tran_pk=value, in_master=True)]})
            session.queries["SELECT tran_pk,in_master"] = lambda s: s.tables["core.transaction"]
            with self.assertRaises(common.InvalidFixture):
                runner.archive_rows(session)
            session.cycle = lambda *a, **k: value
            with self.assertRaises(common.InvalidFixture):
                runner.archive(session, "empty", "legacy")

    def test_listing_count_boolean_zero_and_one_are_not_equal(self):
        for mode in ("legacy", "fixed"):
            for number in (0, 1):
                with tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp)
                    rows = [dict(acct_id="12345678901", type_cd="01", cat_cd="0001", balance="0.00")] * number
                    session = FakeSession(root, tables={"core.tcat_bal": rows})
                    session.queries["FROM core.tcat_bal"] = lambda _: rows
                    session.queries["FROM ops.batch_window"] = lambda _: []
                    def launch(s, params):
                        path = root / "reports/prtcatbl/1/PRTCATBL.txt"
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_bytes(prtcatbl.expected(rows, mode))
                        return Execution("COMPLETED", job_instance_id=1, instance_root=root,
                                         step_counts={"list": {"WRITE_COUNT": bool(number)}})
                    session.jobs["categoryBalanceListingJob"] = launch
                    problems, _ = runner.listing(session, "empty" if number == 0 else "signs", mode)
                    self.assertTrue(any(p.check == "LIST_WRITE_COUNT" for p in problems))

    def test_archive_recorded_and_purge_model_counts_refuse_boolean(self):
        for number in (0, 1):
            before = {1: dict(in_master=True, digest="same")} if number else {}
            after = {k: {**v, "in_master": False} for k, v in before.items()}
            problems = tranbkp.validate("fixture", before, after, status="COMPLETED",
                                       recorded_count=bool(number), index_valid=True, daily_before={}, daily_after={})
            self.assertTrue(any(p.check == "ARCHIVE_COUNT" for p in problems))
            wanted = purge.Result({}, {}, {}, {}, {"details_deleted": number})
            actual = replace(wanted, totals={"details_deleted": bool(number)})
            self.assertTrue(purge.validate("fixture", wanted, actual, status="COMPLETED", fraud_before={}, fraud_after={}))
            wanted = purge.Result({"a": {"approved_auth_cnt": number}}, {}, {}, {}, {})
            actual = replace(wanted, summaries={"a": {"approved_auth_cnt": bool(number)}})
            self.assertTrue(purge.validate("fixture", wanted, actual, status="COMPLETED", fraud_before={}, fraud_after={}))

    def test_sql_numeric_projection_refuses_boolean_auth_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = DataSession(Path(tmp))
            session.reset(mode="fixed")
            session.tables["pauth.auth_detail"][0]["auth_id"] = True
            with self.assertRaises(common.InvalidFixture):
                live.validate(session, mode="fixed", source_dir=ROOT / "app/data/ASCII")

    def test_source_and_hd_integer_columns_do_not_coerce_boolean(self):
        raw = (ROOT / "app/data/ASCII/custdata.txt").read_bytes()
        rows = source.lines(raw)
        rows[0] = rows[0][:329] + b"001" + rows[0][332:]
        raw = b"\n".join(rows) + b"\n"
        for mode in ("legacy", "fixed"):
            actual = source.parse("core.customer", raw, mode=mode)
            actual[0]["fico_score"] = True
            self.assertTrue(any(p.column == "fico_score" for p in source.validate(
                "core.customer", raw, actual, mode=mode)))
        raw = live.HD_FILE.read_bytes()
        summaries, details = hd.parse(raw)
        column = next(c for c in ("approved_auth_cnt", "declined_auth_cnt")
                      if any(r[c] in (0, 1) for r in summaries))
        row = next(r for r in summaries if r[column] in (0, 1))
        row[column] = bool(row[column])
        self.assertTrue(any(p.column == column for p in hd.validate(raw, summaries, details)))
