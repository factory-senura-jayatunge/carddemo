"""POSTTRAN (CBTRN02C) against an independent model of its posting rules."""
from __future__ import annotations

import re
from decimal import Decimal

from tests import oracle
from tests.oracle import ACCOUNT, DALYTRAN, TCATBAL, TRAN, XREF, Record
from tests.support import CardDemoTestCase, business_date, carddemo

REASONS = {100: "INVALID CARD NUMBER FOUND", 101: "ACCOUNT RECORD NOT FOUND",
           102: "OVERLIMIT TRANSACTION", 103: "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"}


class PostTranTest(CardDemoTestCase):
    def post(self, daily: list[bytes] | None = None, xref: list[bytes] | None = None):
        """Run POSTTRAN on the sample data, optionally replacing DALYTRAN or CARDXREF."""
        work = self.new_work()
        if daily is not None:
            carddemo.write_fixed(work.dataset_path("DALYTRAN"), daily, 350)
        if xref is not None:
            work.load_ksds("CARDXREF", xref)
        for name in carddemo.JOBS["POSTTRAN"].dd.values():
            if name.dataset and not name.new:
                work.ensure_dataset(name.dataset)
        daily_recs = oracle.records_of(DALYTRAN, carddemo.read_fixed(work.ensure_dataset("DALYTRAN"), 350))
        xref_recs = oracle.keyed(oracle.records_of(XREF, work.unload_ksds("CARDXREF")), "XREF-CARD-NUM")
        accounts = oracle.keyed(oracle.records_of(ACCOUNT, work.unload_ksds("ACCTDATA")), "ACCT-ID")
        tcatbal = {r.text[:17]: r["TRAN-CAT-BAL"]
                   for r in oracle.records_of(TCATBAL, work.unload_ksds("TCATBALF"))}
        expected = oracle.expected_posting(daily_recs, xref_recs, accounts)
        _, results = self.run_job("POSTTRAN", work)
        return work, results[-1], daily_recs, xref_recs, accounts, tcatbal, expected

    def check_outputs(self, work, result, daily, xref, accounts, tcatbal, expected):
        posted, rejected, balances = expected
        self.assert_rc(result, 4 if rejected else 0)
        self.assertIn(f"TRANSACTIONS PROCESSED :{len(daily):09d}", result.sysout)
        self.assertIn(f"TRANSACTIONS REJECTED  :{len(rejected):09d}", result.sysout)

        rejects = carddemo.read_fixed(result.outputs["DALYREJS"][0], 430)
        self.assertEqual(rejects, [t.text.encode("latin-1") + f"{code:04d}{REASONS[code]:<76}".encode()
                                   for t, code in rejected])

        trans = {r[:16].decode(): r for r in work.unload_ksds("TRANSACT")}
        self.assertEqual(sorted(trans), sorted(t.raw("DALYTRAN-ID") for t in posted))
        for t in posted:
            rec = Record(TRAN, trans[t.raw("DALYTRAN-ID")])
            self.assertEqual(rec.text[:304], t.text[:304], "TRAN fields copied from DALYTRAN")
            self.assertRegex(rec.raw("TRAN-PROC-TS"),
                             rf"^{business_date()}-\d\d\.\d\d\.\d\d\.\d{{2}}0000$")

        after = oracle.keyed(oracle.records_of(ACCOUNT, work.unload_ksds("ACCTDATA")), "ACCT-ID")
        self.assertEqual(sorted(after), sorted(accounts))
        for acct_id, (credit, debit, bal) in balances.items():
            a = after[acct_id]
            self.assertEqual((a["ACCT-CURR-CYC-CREDIT"], a["ACCT-CURR-CYC-DEBIT"], a["ACCT-CURR-BAL"]),
                             (credit, debit, bal), f"account {acct_id}")
            self.assertEqual(a.text[:12], accounts[acct_id].text[:12])
            self.assertEqual(a.text[48:78], accounts[acct_id].text[48:78], "dates unchanged")

        got = {r.text[:17]: r["TRAN-CAT-BAL"]
               for r in oracle.records_of(TCATBAL, work.unload_ksds("TCATBALF"))}
        self.assertEqual(got, oracle.expected_tcatbal(posted, xref, tcatbal))
        created = len(set(got) - set(tcatbal))
        self.assertEqual(len(re.findall(r"TCATBAL record not found for key : \d{17}\.\. Creating\.",
                                        result.sysout)), created)

    def test_sample_day_posts_and_rejects_like_the_model(self):
        run = self.post()
        posted, rejected, _ = run[-1]
        self.assertEqual((len(posted), len(rejected)), (262, 38))
        self.check_outputs(*run)

    def test_each_rejection_rule(self):
        samples = oracle.sample_records("dailytran.txt")
        base = samples[0].encode("latin-1")
        xref_sample = [r.encode("latin-1") for r in oracle.sample_records("cardxref.txt")]
        orphan_card = "4999999999999999"
        orphan = oracle.build_record(XREF, **{"XREF-CARD-NUM": orphan_card, "XREF-CUST-ID": "000000001",
                                              "XREF-ACCT-ID": "99999999999"})
        def tran(tid: str, **values) -> bytes:
            return oracle.set_fields(DALYTRAN, base, **{"DALYTRAN-ID": tid, **values})
        daily = [
            tran("T000000000000001", **{"DALYTRAN-AMT": Decimal("12.34")}),
            tran("T000000000000002", **{"DALYTRAN-CARD-NUM": "0000000000000000"}),
            tran("T000000000000003", **{"DALYTRAN-CARD-NUM": orphan_card}),
            tran("T000000000000004", **{"DALYTRAN-ORIG-TS": "2099-01-01-00.00.00.000000",
                                        "DALYTRAN-AMT": Decimal("1.00")}),
            tran("T000000000000005", **{"DALYTRAN-AMT": Decimal("999999999.99")}),
            tran("T000000000000006", **{"DALYTRAN-AMT": Decimal("-45.67"), "DALYTRAN-TYPE-CD": "03"}),
        ]
        run = self.post(daily=daily, xref=sorted(xref_sample + [orphan], key=lambda r: r[:16]))
        posted, rejected, _ = run[-1]
        self.assertEqual([t.raw("DALYTRAN-ID") for t in posted], ["T000000000000001", "T000000000000006"])
        self.assertEqual([code for _, code in rejected], [100, 101, 103, 102])
        self.check_outputs(*run)
