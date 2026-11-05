"""INTCALC (CBACT04C, run through PARMRUN) after a POSTTRAN cycle."""
from __future__ import annotations

from decimal import ROUND_DOWN, Decimal

from tests import oracle
from tests.oracle import ACCOUNT, DISCGRP, TCATBAL, TRAN, XREF, Record
from tests.support import CardDemoTestCase, business_date, carddemo

PARM = carddemo.JOBS["INTCALC"].parm


class InterestCalcTest(CardDemoTestCase):
    def test_interest_transactions_and_account_updates_follow_the_rates(self):
        work = self.new_work()
        posting = work.run_chain(carddemo.JOBS["POSTTRAN"])
        self.assert_rc(posting[-1], 4)
        balances = oracle.records_of(TCATBAL, work.unload_ksds("TCATBALF"))
        accounts = oracle.keyed(oracle.records_of(ACCOUNT, work.unload_ksds("ACCTDATA")), "ACCT-ID")
        cards = {}
        for x in oracle.records_of(XREF, work.unload_ksds("CARDXREF")):
            cards.setdefault(x.raw("XREF-ACCT-ID"), x.raw("XREF-CARD-NUM"))
        rates = {r.text[:16]: r["DIS-INT-RATE"] for r in oracle.sample(DISCGRP, "discgrp.txt")}

        expected_tx, interest = [], {}
        for b in balances:
            acct_id = b.raw("TRANCAT-ACCT-ID")
            type_cat = b.raw("TRANCAT-TYPE-CD") + b.raw("TRANCAT-CD")
            group = accounts[acct_id].raw("ACCT-GROUP-ID").ljust(10)
            rate = rates.get(group + type_cat, rates.get("DEFAULT".ljust(10) + type_cat))
            self.assertIsNotNone(rate, f"no disclosure group rate for {group}{type_cat}")
            interest.setdefault(acct_id, Decimal(0))
            if rate == 0:
                continue
            amount = (b["TRAN-CAT-BAL"] * rate / 1200).quantize(Decimal("0.01"), rounding=ROUND_DOWN)
            interest[acct_id] += amount
            expected_tx.append((acct_id, amount))
        self.assertTrue(any(a != 0 for _, a in expected_tx), "sample data should yield some interest")

        (res,) = work.run_chain(carddemo.JOBS["INTCALC"])
        self.assert_rc(res, 0)
        written = carddemo.read_fixed(res.outputs["TRANSACT"][0], 350)
        self.assertEqual(len(written), len(expected_tx))
        for n, (raw, (acct_id, amount)) in enumerate(zip(written, expected_tx), start=1):
            t = Record(TRAN, raw)
            with self.subTest(tran=n, account=acct_id):
                self.assertEqual(t.raw("TRAN-ID"), f"{PARM}{n:06d}")
                self.assertEqual((t.raw("TRAN-TYPE-CD"), t.raw("TRAN-CAT-CD"), t["TRAN-SOURCE"]),
                                 ("01", "0005", "System"))
                self.assertEqual(t.raw("TRAN-DESC"), f"Int. for a/c {acct_id}".ljust(100))
                self.assertEqual(raw[330:], b" " * 20, "FILLER is spaces")
                self.assertEqual(t["TRAN-AMT"], amount)
                self.assertEqual(t.raw("TRAN-CARD-NUM"), cards[acct_id])
                self.assertEqual(t["TRAN-MERCHANT-ID"], 0)
                self.assertTrue(t.raw("TRAN-ORIG-TS").startswith(business_date()))
                self.assertEqual(t.raw("TRAN-PROC-TS"), t.raw("TRAN-ORIG-TS"))

        after = oracle.keyed(oracle.records_of(ACCOUNT, work.unload_ksds("ACCTDATA")), "ACCT-ID")
        for acct_id, before in accounts.items():
            a = after[acct_id]
            with self.subTest(account=acct_id):
                if acct_id in interest:
                    self.assertEqual(a["ACCT-CURR-BAL"], before["ACCT-CURR-BAL"] + interest[acct_id])
                    self.assertEqual((a["ACCT-CURR-CYC-CREDIT"], a["ACCT-CURR-CYC-DEBIT"]), (0, 0))
                else:
                    self.assertEqual(a.text, before.text)
