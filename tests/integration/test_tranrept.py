"""TRANREPT (CBTRN03C): the transaction detail report for the posted day."""
from __future__ import annotations

from decimal import Decimal

from tests import oracle
from tests.oracle import DALYTRAN, TRANCATG, TRANTYPE, XREF, edited_amount
from tests.support import CardDemoTestCase, business_date, carddemo


class TransactionReportTest(CardDemoTestCase):
    def test_report_lists_posted_transactions_with_correct_totals(self):
        _, results = self.run_job("TRANREPT")
        self.assertEqual([r.job for r in results], ["POSTTRAN", "TRANREPT"])
        self.assert_rc(results[-1], 0)
        lines = [r.decode("latin-1").rstrip()
                 for r in carddemo.read_fixed(results[-1].outputs["TRANREPT"][0], 133)]

        daily = oracle.sample(DALYTRAN, "dailytran.txt")
        xref = oracle.keyed(oracle.sample(XREF, "cardxref.txt"), "XREF-CARD-NUM")
        accounts = oracle.keyed(oracle.sample(oracle.ACCOUNT, "acctdata.txt"), "ACCT-ID")
        posted, _, _ = oracle.expected_posting(daily, xref, accounts)
        in_window = carddemo.REPORT_START <= business_date() <= carddemo.REPORT_END
        selected = sorted(posted, key=lambda t: t.raw("DALYTRAN-ID")) if in_window else []
        selected.sort(key=lambda t: t.raw("DALYTRAN-CARD-NUM"))
        types = {r.raw("TRAN-TYPE"): r.raw("TRAN-TYPE-DESC") for r in oracle.sample(TRANTYPE, "trantype.txt")}
        cats = {r.text[:6]: r.raw("TRAN-CAT-TYPE-DESC") for r in oracle.sample(TRANCATG, "trancatg.txt")}

        expected_details = []
        for t in selected:
            type_cd, cat_cd = t.raw("DALYTRAN-TYPE-CD"), t.raw("DALYTRAN-CAT-CD")
            expected_details.append((t.raw("DALYTRAN-ID"), xref[t.raw("DALYTRAN-CARD-NUM")].raw("XREF-ACCT-ID"),
                                     f"{type_cd}-{types[type_cd][:15]}".rstrip(),
                                     f"{cat_cd}-{cats[type_cd + cat_cd][:29]}".rstrip(),
                                     t["DALYTRAN-SOURCE"], t["DALYTRAN-AMT"]))

        self.assertEqual(lines[0], "DALYREPT".ljust(38) + "Daily Transaction Report".ljust(41)
                         + f"Date Range: {carddemo.REPORT_START} to {carddemo.REPORT_END}")
        details, account_totals, page_totals, grand = [], [], [], []
        group: list[Decimal] = []
        for line in lines:
            if line[:16].isdigit():
                details.append((line[0:16], line[17:28], line[29:47].rstrip(), line[48:82].rstrip(),
                                line[83:93].rstrip(), edited_amount(line[97:113])))
                group.append(details[-1][-1])
            elif line.startswith("Account Total"):
                account_totals.append((edited_amount(line[97:]), sum(group, Decimal(0))))
                group = []
            elif line.startswith("Page Total"):
                page_totals.append(edited_amount(line[97:]))
            elif line.startswith("Grand Total"):
                grand.append(edited_amount(line[97:]))

        self.assertEqual(details, expected_details)
        accounts_in_report = len({d[1] for d in details})
        self.assertEqual(len(account_totals), accounts_in_report, "one Account Total per card group")
        for shown, summed in account_totals:
            self.assertEqual(shown, summed)
        total = sum((d[-1] for d in details), Decimal(0))
        self.assertEqual(grand, [total])
        self.assertEqual(sum(page_totals, Decimal(0)), total)
