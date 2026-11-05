"""CBSTM03B, the file-access subroutine of the statement program, through a COBOL driver.

CBSTM03A itself cannot run off z/OS (it walks the PSA/TCB/TIOT control
blocks), so the subroutine is exercised directly with the request areas
CBSTM03A builds.
"""
from __future__ import annotations

from tests import oracle
from tests.support import CardDemoTestCase


def statement_transactions(records: list[bytes]) -> list[bytes]:
    """CREASTMT.JCL STEP010: SORT FIELDS=(263,16,CH,A,1,16,CH,A),
    OUTREC=(1:263,16,17:1,262,279:279,50) - card number moved in front of the record."""
    ordered = sorted(records, key=lambda r: (r[262:278], r[0:16]))
    return [(r[262:278] + r[0:262] + r[278:328]).ljust(350) for r in ordered]


def request(dd: str, op: str, key: str = "") -> str:
    return f"{dd:<8}{op}{len(key):04d}{key:<25}\n"


class StatementFileAccessTest(CardDemoTestCase):
    def setUp(self) -> None:
        super().setUp()
        self.work = self.new_work()
        daily = [r.encode("latin-1").ljust(350) for r in oracle.sample_records("dailytran.txt")[:12]]
        self.trnx = statement_transactions(daily)
        self.work.load_ksds("TRXFL", self.trnx)
        self.dd = {"TRNXFILE": self.work.dataset_path("TRXFL"),
                   "XREFFILE": self.work.ensure_dataset("CARDXREF"),
                   "CUSTFILE": self.work.ensure_dataset("CUSTDATA"),
                   "ACCTFILE": self.work.ensure_dataset("ACCTDATA")}

    def call(self, requests: list[str]) -> list[tuple[str, str]]:
        proc = self.run_driver("cbstm03b_test", "".join(requests), work=self.work, dd=self.dd)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        out = [(l[3:5], l[11:].rstrip()) for l in proc.stdout.splitlines()]
        self.assertEqual(len(out), len(requests), proc.stdout)
        return out

    def test_sequential_reads_return_records_in_key_order_then_eof(self):
        n = len(self.trnx)
        out = self.call([request("TRNXFILE", "O")] + [request("TRNXFILE", "R")] * (n + 1)
                        + [request("TRNXFILE", "C")])
        self.assertEqual(out[0], ("00", ""))
        self.assertEqual([o[0] for o in out[1:n + 1]], ["00"] * n)
        self.assertEqual([o[1] for o in out[1:n + 1]],
                         [r.decode("latin-1").rstrip() for r in self.trnx])
        self.assertEqual(out[n + 1][0], "10")
        self.assertEqual(out[n + 2][0], "00")

    def test_xref_file_is_read_sequentially(self):
        xref = sorted(oracle.sample_records("cardxref.txt"), key=lambda r: r[:16])
        out = self.call([request("XREFFILE", "O"), request("XREFFILE", "R"), request("XREFFILE", "R"),
                         request("XREFFILE", "C")])
        self.assertEqual([o[0] for o in out], ["00"] * 4)
        self.assertEqual([out[1][1], out[2][1]], [xref[0].rstrip(), xref[1].rstrip()])

    def test_keyed_reads_of_customer_and_account(self):
        cust = {r[:9]: r for r in oracle.sample_records("custdata.txt")}
        acct = {r[:11]: r for r in oracle.sample_records("acctdata.txt")}
        cust_id, acct_id = sorted(cust)[7], sorted(acct)[-1]
        out = self.call([request("CUSTFILE", "O"), request("CUSTFILE", "K", cust_id),
                         request("CUSTFILE", "K", "999999999"), request("CUSTFILE", "C"),
                         request("ACCTFILE", "O"), request("ACCTFILE", "K", acct_id),
                         request("ACCTFILE", "K", "99999999999"), request("ACCTFILE", "C")])
        self.assertEqual(out[1], ("00", cust[cust_id].rstrip()))
        self.assertEqual(out[2][0], "23")
        self.assertEqual(out[5], ("00", acct[acct_id].rstrip()))
        self.assertEqual(out[6][0], "23")
        self.assertEqual([out[i][0] for i in (0, 3, 4, 7)], ["00"] * 4)

    def test_unknown_dd_or_operation_leaves_the_request_untouched(self):
        out = self.call([request("NOSUCHDD", "O"), request("TRNXFILE", "O"),
                         request("TRNXFILE", "W"), request("TRNXFILE", "C")])
        self.assertEqual(out[0], ("..", ""))
        # An unsupported operation still reports the file status of the last I/O.
        self.assertEqual([o[0] for o in out[1:]], ["00", "00", "00"])

    def test_reading_an_unopened_file_reports_status_47(self):
        out = self.call([request("XREFFILE", "R")])
        self.assertEqual(out[0][0], "47")
