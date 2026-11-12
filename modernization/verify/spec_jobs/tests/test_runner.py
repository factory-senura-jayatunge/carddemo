from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from modernization.verify.spec_jobs import discgrp, prtcatbl, runner
from modernization.verify.spec_jobs.common import Finding
from modernization.verify.spec_jobs.session import Execution, ArchiveBlockProof
from modernization.verify.spec_jobs.tests.fake import FakeSession, PermissionDenied
from modernization.verify.data.oracle.digest import Column


class RunnerTest(unittest.TestCase):
    def test_masked_snapshot_fails_instead_of_hashing_masks(self):
        s = FakeSession(Path("/unused"), tables={"t": [{"id": 1, "secret": "a"}]},
                        column_types={"t": {"id": Column("int"), "secret": Column("nvarchar")}},
                        masked={"t": {"secret"}})
        with self.assertRaises(PermissionDenied):
            s.snapshot("t", ("id",))
        self.assertTrue(s.snapshot("t", ("id",), exclude=("secret",)))
        before = s.snapshot_digest("t", ("id",))
        s.tables["t"][0]["secret"] = "b"
        self.assertNotEqual(before, s.snapshot_digest("t", ("id",)))
        before = s.snapshot_digest("t", order_by=("id",), columns=("id", "secret"), aggregate=True)
        s.tables["t"][0]["secret"] = "c"
        self.assertNotEqual(before, s.snapshot_digest("t", order_by=("id",),
                                                     columns=("id", "secret"), aggregate=True))

    def test_subset_is_partial_even_when_names_cover_catalogue(self):
        s = FakeSession(Path("/unused"))
        calls = []
        def handle(session, case, mode):
            calls.append((case, mode))
            return [], Execution("COMPLETED")
        with patch.dict(runner.HANDLERS, {job: handle for job in runner.REQUIRED}, clear=True), \
                patch.object(runner.data_live, "run", return_value=0) as data:
            output = []
            self.assertEqual(runner.run(s, ",".join(runner.REQUIRED), output.append), 3)
            self.assertEqual(len(calls), sum(len(v) for k, v in runner.CASES.items() if k != "DATALOAD") * 2)
            self.assertEqual(data.call_count, 1)
            self.assertEqual(output[-1], "verify-batch-spec: PARTIAL")
            self.assertEqual(runner.run(s, out=lambda _: None), 0)

    def test_dataload_selector_routes_once_and_propagates_failure(self):
        s = FakeSession(Path("/unused"))
        for code, wanted in ((0, 3), (1, 1)):
            output = []
            with patch.object(runner.data_live, "run", return_value=code) as data:
                self.assertEqual(runner.run(s, "DATALOAD", output.append), wanted)
                data.assert_called_once()
        self.assertEqual(runner.selected("DATALOAD"), ("DATALOAD",))

    def test_exception_and_pii_fail_without_echoing_values(self):
        s = FakeSession(Path("/unused"))
        secret = runner.fixtures.SENTINELS[1]
        def broken(*args):
            raise ValueError(secret)
        with patch.dict(runner.HANDLERS, {"PRTCATBL": broken}, clear=True):
            output = []
            self.assertEqual(runner.run(s, "PRTCATBL", output.append), 1)
            self.assertNotIn(secret, "\n".join(output))
        s.log = secret.encode()
        with patch.dict(runner.HANDLERS, {"PRTCATBL": lambda *args: ([], Execution("COMPLETED"))}, clear=True):
            output = []
            self.assertEqual(runner.run(s, "PRTCATBL", output.append), 1)
            self.assertIn("PII_SENTINEL", "\n".join(output))
            self.assertNotIn(secret, "\n".join(output))

    def test_disclosure_live_cases_and_failure_mutation(self):
        for case in runner.CASES["DISCGRP"]:
            with self.subTest(case=case), tempfile.TemporaryDirectory() as tmp:
                s = FakeSession(Path(tmp))
                s.tables["ref.disclosure_group"] = discgrp.parse(runner.disclosure_input("sample"))
                for row in s.tables["ref.disclosure_group"]:
                    row["row_version"] = 0
                s.queries["FROM ref.disclosure_group"] = lambda x: x.tables["ref.disclosure_group"]
                versions = [0]
                def launch(session, params):
                    path = session.var_dir / "inbound/discgrp/2022-07-06.txt"
                    if not session.windows:
                        return Execution("BATCH_WINDOW_REQUIRED")
                    if not path.exists():
                        return Execution("FAILED", exit_description="INBOUND_FILE_MISSING")
                    try:
                        rows = discgrp.parse(path.read_bytes())
                    except discgrp.InputError:
                        return Execution("FAILED")
                    versions[0] += 1
                    for row in rows:
                        row["row_version"] = versions[0]
                    session.tables["ref.disclosure_group"] = rows
                    return Execution("COMPLETED", step_counts={"reload": {"READ_COUNT": len(rows), "WRITE_COUNT": len(rows)}})
                s.jobs["disclosureGroupReloadJob"] = launch
                findings, _ = runner.disclosure(s, case, "legacy")
                self.assertFalse(findings, "\n".join(map(str, findings)))

    def test_disclosure_rerun_content_mutation_and_rejected_version_mutation(self):
        for mode in ("legacy", "fixed"):
            for case in ("rerun", "duplicate", "no_window"):
                with self.subTest(mode=mode, case=case), tempfile.TemporaryDirectory() as tmp:
                    s = FakeSession(Path(tmp))
                    rows = discgrp.parse(runner.disclosure_input("sample"))
                    s.tables["ref.disclosure_group"] = rows
                    s.queries["FROM ref.disclosure_group"] = lambda x: x.tables["ref.disclosure_group"]
                    calls = [0]
                    def launch(session, params):
                        calls[0] += 1
                        for row in rows:
                            row["row_version"] = calls[0]
                        if case == "rerun" and calls[0] == 2:
                            rows[0]["int_rate"] += 1
                        status = "COMPLETED" if case == "rerun" else (
                            "BATCH_WINDOW_REQUIRED" if case == "no_window" else "FAILED")
                        return Execution(status, step_counts={
                            "reload": {"READ_COUNT": len(rows), "WRITE_COUNT": len(rows)}})
                    s.jobs["disclosureGroupReloadJob"] = launch
                    self.assertTrue(runner.disclosure(s, case, mode)[0])

    def test_disclosure_fixture_sizes(self):
        self.assertEqual(len(discgrp.parse(runner.disclosure_input("sample"))), 51)
        self.assertEqual(len(discgrp.parse(runner.disclosure_input("snapshot"))), 5000)
        for case in ("out_of_order", "duplicate", "no_default", "short"):
            with self.assertRaises(discgrp.InputError):
                discgrp.parse(runner.disclosure_input(case))

    def test_listing_live_output_path_and_value_mutation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            rows = [dict(acct_id=f"{i:011d}", type_cd="01", cat_cd="0001", balance="-1.23")
                    for i in range(50)]
            s = FakeSession(root, tables={"core.tcat_bal": rows})
            s.queries["FROM core.tcat_bal"] = lambda _: rows
            s.queries["FROM ops.batch_window"] = lambda _: []
            def launch(session, params):
                path = root / "reports/prtcatbl/91/PRTCATBL.txt"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(prtcatbl.expected(rows, "fixed"))
                return Execution("COMPLETED", job_instance_id=91, instance_root=root,
                                 step_counts={"list": {"WRITE_COUNT": len(rows)}})
            s.jobs["categoryBalanceListingJob"] = launch
            self.assertFalse(runner.listing(s, "sample", "fixed")[0])
            s.jobs["categoryBalanceListingJob"] = lambda *_: Execution("COMPLETED")
            self.assertTrue(runner.listing(s, "sample", "fixed")[0])

    def test_archive_live_masked_rows_and_count_mutation(self):
        for case in runner.CASES["TRANBKP"]:
            with self.subTest(case=case):
                rows = [] if case == "empty" else [
                    dict(tran_pk=i, in_master=i < 300, card_num="masked-value", amount="1.00")
                    for i in range(350)]
                s = FakeSession(Path("/unused"), tables={"core.transaction": rows},
                                masked={"core.transaction": {"card_num"}})
                recorded = [0]
                s.queries["SELECT tran_pk,in_master"] = lambda x: x.tables["core.transaction"]
                s.queries["SELECT archived_count"] = lambda _: [{"value": recorded[0]}]
                s.queries["FROM sys.indexes"] = lambda _: [
                    dict(column_name="tran_id", is_unique=True, filter_definition="([in_master]=(1))")]
                def launch(session, params):
                    if not session.windows:
                        return Execution("BATCH_WINDOW_REQUIRED")
                    count = sum(bool(r["in_master"]) for r in rows)
                    if count:
                        recorded[0] = count
                    for row in rows:
                        row["in_master"] = False
                    return Execution("COMPLETED", execution_id=7, step_counts={"archive": {"WRITE_COUNT": count}})
                s.jobs["archiveMasterJob"] = launch
                if case == "atomic":
                    class Pending:
                        execution_id = 7

                        def done(self):
                            return False

                        def wait(self, timeout=120):
                            return launch(s, {})
                    s.launch = lambda job, params: Pending()
                    s.wait_for_archive_blocked = lambda running, holder, **kw: ArchiveBlockProof(
                        True, running.execution_id, 102, holder.session_id,
                        "core.transaction", "KEY", "LCK_M_U", time.monotonic_ns())
                self.assertFalse(runner.archive(s, case, "legacy")[0])
                if case == "fixture":
                    # The job can complete yet report the wrong count.
                    rows[0]["in_master"] = True
                    s.queries["SELECT archived_count"] = lambda _: [{"value": -1}]
                    self.assertTrue(runner.archive(s, case, "legacy")[0])


if __name__ == "__main__":
    unittest.main()
