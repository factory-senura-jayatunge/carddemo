"""The read/print utilities (READACCT, READCARD, READXREF, READCUST) and TRANVALD."""
from __future__ import annotations

from decimal import Decimal

from tests import oracle
from tests.oracle import ACCOUNT, DALYTRAN, XREF, Record, zoned
from tests.support import CardDemoTestCase, carddemo


def program_lines(sysout: str, program: str) -> list[str]:
    """SYSOUT lines between the program's START and END banners."""
    lines = sysout.splitlines()
    start = lines.index(f"START OF EXECUTION OF PROGRAM {program}")
    end = lines.index(f"END OF EXECUTION OF PROGRAM {program}")
    return lines[start + 1:end]


def in_key_order(filename: str, key_len: int) -> list[str]:
    return sorted(oracle.sample_records(filename), key=lambda r: r[:key_len])


class ReaderJobsTest(CardDemoTestCase):
    def test_readcard_prints_every_card_in_key_order(self):
        _, (res,) = self.run_job("READCARD")
        self.assert_rc(res, 0)
        self.assertEqual([l.rstrip() for l in program_lines(res.sysout, "CBACT02C")],
                         [r.rstrip() for r in in_key_order("carddata.txt", 16)])

    def test_readxref_prints_every_xref_record_once(self):
        _, (res,) = self.run_job("READXREF")
        self.assert_rc(res, 0)
        expected = [r.rstrip() for r in in_key_order("cardxref.txt", 16)]
        self.assertEqual([l.rstrip() for l in program_lines(res.sysout, "CBACT03C")], expected)

    def test_readcust_prints_every_customer_twice(self):
        # CBCUS01C DISPLAYs the record both in 1000-CUSTFILE-GET-NEXT and in its main loop.
        _, (res,) = self.run_job("READCUST")
        self.assert_rc(res, 0)
        expected = [r.rstrip() for r in in_key_order("custdata.txt", 9) for _ in range(2)]
        self.assertEqual([l.rstrip() for l in program_lines(res.sysout, "CBCUS01C")], expected)

    def test_readacct_writes_the_three_extract_files(self):
        _, (res,) = self.run_job("READACCT")
        self.assert_rc(res, 0)
        accounts = [Record(ACCOUNT, r) for r in in_key_order("acctdata.txt", 11)]
        out = carddemo.read_fixed(res.outputs["OUTFILE"][0], 107)
        arry = carddemo.read_fixed(res.outputs["ARRYFILE"][0], 110)
        vbrc = carddemo.read_vb(res.outputs["VBRCFILE"][0])
        self.assertEqual((len(out), len(arry), len(vbrc)), (len(accounts), len(accounts), 2 * len(accounts)))
        for i, a in enumerate(accounts):
            with self.subTest(account=a.raw("ACCT-ID")):
                o = out[i].decode("latin-1")
                # OUT-ACCT-REC: id, status, balance, limits and dates copied; the reissue
                # date converted by COBDATFT from YYYY-MM-DD to YYYYMMDD. The last two
                # bytes of OUT-ACCT-REISSUE-DATE are never set by the conversion.
                self.assertEqual(o[:48], a.text[:48])
                self.assertEqual(o[48:68], a.text[48:68])
                self.assertEqual(o[68:76], a.raw("ACCT-REISSUE-DATE").replace("-", ""))
                self.assertEqual(zoned(o[78:90], 2), a["ACCT-CURR-CYC-CREDIT"])
                if a["ACCT-CURR-CYC-DEBIT"] == 0:
                    self.assertEqual(oracle.unpack_comp3(out[i][90:97], 2), Decimal("2525.00"))
                self.assertEqual(o[97:107], a.raw("ACCT-GROUP-ID").ljust(10))

                r = arry[i]
                self.assertEqual(r[:11].decode(), a.raw("ACCT-ID"))
                slots = [(zoned(r[11 + 19 * k:23 + 19 * k].decode("latin-1"), 2),
                          oracle.unpack_comp3(r[23 + 19 * k:30 + 19 * k], 2)) for k in range(5)]
                bal = a["ACCT-CURR-BAL"]
                self.assertEqual(slots, [(bal, Decimal("1005.00")), (bal, Decimal("1525.00")),
                                         (Decimal("-1025.00"), Decimal("-2500.00")),
                                         (0, 0), (0, 0)])
                self.assertEqual(r[106:], b"    ")

                self.assertEqual(vbrc[2 * i].decode("latin-1"), a.text[:12])
                self.assertEqual(vbrc[2 * i + 1].decode("latin-1"),
                                 a.raw("ACCT-ID") + a.text[12:36] + a.raw("ACCT-REISSUE-DATE")[:4])

    def test_tranvald_verifies_cards_and_accounts(self):
        work = self.new_work()
        base = oracle.sample_records("dailytran.txt")[0].encode("latin-1")
        orphan = oracle.build_record(XREF, **{"XREF-CARD-NUM": "4999999999999999",
                                              "XREF-CUST-ID": "000000001", "XREF-ACCT-ID": "99999999999"})
        xref = sorted([r.encode("latin-1") for r in oracle.sample_records("cardxref.txt")] + [orphan],
                      key=lambda r: r[:16])
        work.load_ksds("CARDXREF", xref)
        daily = [oracle.set_fields(DALYTRAN, base, **{"DALYTRAN-ID": "V000000000000002",
                                                      "DALYTRAN-CARD-NUM": "0000000000000000"}),
                 oracle.set_fields(DALYTRAN, base, **{"DALYTRAN-ID": "V000000000000003",
                                                      "DALYTRAN-CARD-NUM": "4999999999999999"}),
                 oracle.set_fields(DALYTRAN, base, **{"DALYTRAN-ID": "V000000000000001"})]
        carddemo.write_fixed(work.dataset_path("DALYTRAN"), daily, 350)
        _, (res,) = self.run_job("TRANVALD", work)
        self.assert_rc(res, 0)
        lines = [l.rstrip() for l in program_lines(res.sysout, "CBTRN01C")]
        for rec in daily:
            self.assertIn(rec.decode("latin-1").rstrip(), lines)
        self.assertIn("CARD NUMBER 0000000000000000 COULD NOT BE VERIFIED. "
                      "SKIPPING TRANSACTION ID-V000000000000002", lines)
        self.assertIn("ACCOUNT 99999999999 NOT FOUND", lines)
        good = Record(DALYTRAN, base).raw("DALYTRAN-CARD-NUM")
        self.assertIn(f"CARD NUMBER: {good}", lines)
        self.assertIn("SUCCESSFUL READ OF ACCOUNT FILE", lines)
