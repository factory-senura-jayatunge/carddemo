import copy
import unittest

from modernization.verify.spec_jobs import prtcatbl, tranbkp
from modernization.verify.spec_jobs.common import InvalidFixture, pii_scan
from modernization.verify.spec_jobs.session import ArchiveBlockProof, FixtureLock


class ListingTest(unittest.TestCase):
    def setUp(self):
        self.rows = [dict(acct_id="12345678901", type_cd="01", cat_cd="0002", balance="-12.34")]

    def test_literal_layout(self):
        self.assertEqual(prtcatbl.expected(self.rows, "fixed"),
                         b"12345678901 01 0002 000000012.34-       \n")
        self.assertEqual(prtcatbl.expected(self.rows, "legacy"),
                         b"12345678901 01 0002 000000012.34        \n")

    def test_limits_and_zero(self):
        for value in ("-0.01", "0.00", "999999999.99", "-999999999.99"):
            rows = [{**self.rows[0], "balance": value}]
            for mode in ("legacy", "fixed"):
                self.assertEqual(len(prtcatbl.expected(rows, mode)), 41)
        self.assertEqual(prtcatbl.expected([], "fixed"), b"")

    def test_mutations_every_byte_and_length(self):
        wanted = prtcatbl.expected(self.rows, "fixed")
        mutants = [wanted[:i] + b"!" + wanted[i+1:] for i in range(len(wanted))]
        mutants += [wanted[:-1], wanted + b" ", wanted.replace(b"-", b" ")]
        for mutant in mutants:
            with self.subTest(size=len(mutant)):
                self.assertTrue(prtcatbl.validate("signs", self.rows, "fixed", mutant, "COMPLETED", {}, {}, False))

    def test_order_mutation(self):
        rows = self.rows + [{**self.rows[0], "cat_cd": "0001"}]
        expected = prtcatbl.expected(rows, "fixed")
        self.assertTrue(prtcatbl.validate("signs", rows, "fixed", expected[41:] + expected[:41], "COMPLETED", {}, {}, False))

    def test_unchanged_status_window(self):
        output = prtcatbl.expected(self.rows, "legacy")
        for status, after, window in (("FAILED", {}, False), ("COMPLETED", {1: "changed"}, False), ("COMPLETED", {}, True)):
            self.assertTrue(prtcatbl.validate("sample", self.rows, "legacy", output, status, {}, after, window))

    def test_modes(self):
        a, b = (prtcatbl.expected(self.rows, mode) for mode in ("legacy", "fixed"))
        self.assertFalse(prtcatbl.compare_modes("signs", self.rows, a, b))
        self.assertTrue(prtcatbl.compare_modes("signs", self.rows, a, a))

    def test_invalid_range(self):
        for value in ("1000000000", "0.001", "NaN"):
            with self.assertRaises(InvalidFixture):
                prtcatbl.expected([{**self.rows[0], "balance": value}], "fixed")

    def test_category_must_be_four_stored_ascii_digits(self):
        for value in (2, "2", " 002", "+002", "0_02", "０００２"):
            with self.subTest(category=value), self.assertRaises(InvalidFixture):
                prtcatbl.expected([{**self.rows[0], "cat_cd": value}], "fixed")


class ArchiveTest(unittest.TestCase):
    def setUp(self):
        self.before = {1: dict(in_master=True, digest="a"), 2: dict(in_master=False, digest="b")}
        self.after = {k: {**v, "in_master": False} for k, v in self.before.items()}
        proof = ArchiveBlockProof(True, 7, 102, 101, "core.transaction", "KEY", "LCK_M_U", 110)
        self.evidence = tranbkp.AtomicEvidence(FixtureLock(101), proof, 7, False, 100, 120, 130, 140, 200)

    def check(self, after=None, **kwargs):
        params = dict(status="COMPLETED", recorded_count=1, index_valid=True, daily_before={}, daily_after={})
        params.update(kwargs)
        return tranbkp.validate("fixture", self.before, self.after if after is None else after, **params)

    def test_correct(self):
        self.assertFalse(self.check())

    def test_row_loss_and_change_mutations(self):
        for mutant in ({}, self.before, {**self.after, 3: self.after[1]},
                       {**self.after, 2: dict(in_master=False, digest="changed")}):
            self.assertTrue(self.check(mutant))

    def test_count_index_waiting_add_mutations(self):
        for params in (dict(recorded_count=0), dict(index_valid=False), dict(daily_after={1: "changed"})):
            self.assertTrue(self.check(**params))

    def test_failed_run_unchanged(self):
        self.assertTrue(self.check(status="FAILED"))
        self.assertFalse(tranbkp.validate("atomic", self.before, self.before, status="FAILED", recorded_count=0,
                         index_valid=True, daily_before={}, daily_after={}, observations=(self.before,),
                         atomic_evidence=self.evidence))

    def test_atomic_partial_mutation(self):
        params = dict(status="COMPLETED", recorded_count=1, index_valid=True, daily_before={}, daily_after={},
                      atomic_evidence=self.evidence)
        partial = copy.deepcopy(self.after)
        partial[2]["digest"] = "changed"
        self.assertTrue(tranbkp.validate("atomic", self.before, self.after, observations=(partial,), **params))
        self.assertTrue(tranbkp.validate("atomic", self.before, self.after, **params))

    def test_rerun_preserves_recorded_count(self):
        self.assertFalse(tranbkp.validate("rerun", self.after, self.after, status="COMPLETED",
                         recorded_count=300, previous_count=300, index_valid=True, daily_before={}, daily_after={}))

    def test_rerun_refuses_remaining_master_or_any_change(self):
        params = dict(status="COMPLETED", recorded_count=300, previous_count=300,
                      index_valid=True, daily_before={}, daily_after={})
        problems = tranbkp.validate("rerun", self.before, self.after, **params)
        self.assertTrue(any(p.check == "RERUN_MASTER_EMPTY" for p in problems))
        changed = copy.deepcopy(self.after)
        changed[1]["digest"] = "changed"
        self.assertTrue(tranbkp.validate("rerun", self.after, changed, **params))

    def test_no_window(self):
        self.assertFalse(self.check(self.before, status="BATCH_WINDOW_REQUIRED", no_window=True))
        self.assertTrue(self.check(no_window=True))


class PrivacyTest(unittest.TestCase):
    def test_scan_and_value_free_diagnostic(self):
        sentinel = "validator-private-sentinel"
        findings = pii_scan("sample", {"service.log": "prefix\n" + sentinel}, [sentinel])
        self.assertEqual(len(findings), 1)
        self.assertNotIn(sentinel, str(findings))
        self.assertFalse(pii_scan("sample", {"report": "safe"}, [sentinel]))


if __name__ == "__main__":
    unittest.main()
