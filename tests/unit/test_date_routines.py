"""Date routines: the CEEDAYS and COBDATFT stand-ins and CSUTLDTC, through COBOL drivers."""
from __future__ import annotations

import datetime as dt

from tests.support import CardDemoTestCase

VALID_FC = "00" * 12
MESSAGES = {2507: "Insufficient", 2508: "Datevalue error", 2513: "Unsupp. Range",
            2517: "Invalid month", 2518: "Bad Pic String", 2520: "Nonnumeric data"}


def lilian(y: int, m: int, d: int) -> int:
    """Lilian day number: 1582-10-15 (first Gregorian day) is day 1."""
    return dt.date(y, m, d).toordinal() - dt.date(1582, 10, 15).toordinal() + 1


def condition_token(msg: int) -> str:
    """LE feedback token for a severity-3 CEE condition, in hex."""
    return f"0003{msg:04X}59C3C5C5" + "00000000"


class DateRoutinesTest(CardDemoTestCase):
    def ceedays(self, cases: list[tuple[str, str]], lengths: list[tuple[int, int]] | None = None):
        lines = []
        for i, (date, pic) in enumerate(cases):
            dlen, plen = lengths[i] if lengths else (len(date), len(pic))
            lines.append(f"{dlen:03d}{plen:03d}{date:<32}{pic:<32}")
        proc = self.run_driver("ceedays_test", "\n".join(lines) + "\n")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        out = proc.stdout.splitlines()
        self.assertEqual(len(out), len(cases), proc.stdout)
        return [(int(l.split()[0][7:]), l.split()[1][3:]) for l in out]

    def test_ceedays_returns_lilian_day_numbers(self):
        cases = {("2022-07-06", "YYYY-MM-DD"): lilian(2022, 7, 6),
                 ("1582-10-15", "YYYY-MM-DD"): 1,
                 ("1600-02-29", "YYYY-MM-DD"): lilian(1600, 2, 29),
                 ("1601-01-01", "YYYY-MM-DD"): lilian(1601, 1, 1),
                 ("9999-12-31", "YYYY-MM-DD"): lilian(9999, 12, 31),
                 ("20240229", "YYYYMMDD"): lilian(2024, 2, 29),
                 ("07/06/2022", "MM/DD/YYYY"): lilian(2022, 7, 6),
                 ("2022-07-06", "yyyy-mm-dd"): lilian(2022, 7, 6)}
        results = self.ceedays(list(cases))
        for (case, expected), (got, fc) in zip(cases.items(), results):
            with self.subTest(case=case):
                self.assertEqual((got, fc), (expected, VALID_FC))

    def test_ceedays_reports_le_conditions(self):
        cases = {("2023-02-29", "YYYY-MM-DD"): 2508,
                 ("2022-04-31", "YYYY-MM-DD"): 2508,
                 ("2022-07-00", "YYYY-MM-DD"): 2508,
                 ("2022-13-01", "YYYY-MM-DD"): 2517,
                 ("2022-00-10", "YYYY-MM-DD"): 2517,
                 ("1582-10-14", "YYYY-MM-DD"): 2513,
                 ("1500-01-01", "YYYY-MM-DD"): 2513,
                 ("2022-0A-01", "YYYY-MM-DD"): 2520,
                 ("          ", "YYYY-MM-DD"): 2507,
                 ("2022-07-06", "YYYY-XX-DD"): 2518,
                 ("2022-07-06", "YYYY-MM"): 2518}
        results = self.ceedays(list(cases))
        for (case, msg), (got, fc) in zip(cases.items(), results):
            with self.subTest(case=case):
                self.assertEqual((got, fc), (0, condition_token(msg)))

    def test_ceedays_needs_a_date_as_long_as_the_picture(self):
        (got, fc), = self.ceedays([("2022-07", "YYYY-MM-DD")], lengths=[(7, 10)])
        self.assertEqual((got, fc), (0, condition_token(2507)))

    def csutldtc(self, cases: list[tuple[str, str]]) -> list[tuple[int, str]]:
        proc = self.run_driver("csutldtc_test", "".join(f"{d:<10}{f:<10}\n" for d, f in cases))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        out = proc.stdout.splitlines()
        self.assertEqual(len(out), len(cases), proc.stdout)
        return [(int(l[3:7]), l[12:].ljust(80)) for l in out]

    def test_csutldtc_reports_valid_dates(self):
        for rc, msg in self.csutldtc([("2022-07-06", "YYYY-MM-DD"), ("2024-02-29", "YYYY-MM-DD")]):
            self.assertEqual(rc, 0)
            # The fields CORPT00C, COTRN02C and CSUTLDPY read: severity and message number.
            self.assertEqual(msg[0:4], "0000")
            self.assertEqual(msg[4:15], "Mesg Code: ")
            self.assertEqual(msg[15:19], "0000")
            self.assertEqual(msg[20:35].rstrip(), "Date is valid")
            self.assertEqual(msg[56:76], "Mask used:YYYY-MM-DD")

    def test_csutldtc_message_shows_the_date_tested(self):
        (_, msg), = self.csutldtc([("2022-07-06", "YYYY-MM-DD")])
        self.assertEqual(msg[36:55], "TstDate: 2022-07-06")

    def test_csutldtc_maps_each_condition_to_its_message(self):
        cases = {("2023-02-29", "YYYY-MM-DD"): 2508, ("2022-13-01", "YYYY-MM-DD"): 2517,
                 ("1500-01-01", "YYYY-MM-DD"): 2513, ("2022-0A-01", "YYYY-MM-DD"): 2520,
                 ("          ", "YYYY-MM-DD"): 2507, ("2022-07-06", "YYYY-XX-DD"): 2518}
        for (case, msg_no), (rc, msg) in zip(cases.items(), self.csutldtc(list(cases))):
            with self.subTest(case=case):
                self.assertEqual(rc, 3, msg)
                self.assertEqual((msg[0:4], msg[15:19]), ("0003", f"{msg_no:04d}"))
                self.assertEqual(msg[20:35].rstrip(), MESSAGES[msg_no])

    def cobdatft(self, records: list[str]) -> list[str]:
        for r in records:
            self.assertEqual(len(r), 80)
        proc = self.run_driver("cobdatft_test", "".join(r + "\n" for r in records))
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return [l[4:].ljust(80) for l in proc.stdout.splitlines()]

    @staticmethod
    def codatecn(in_type: str, date: str, out_type: str, out_date: str = "*" * 20) -> str:
        return f"{in_type}{date:<20}{out_type}{out_date:<20}{'':38}"

    def test_cobdatft_converts_between_date_formats(self):
        out = self.cobdatft([self.codatecn("1", "20220706", "1"),
                             self.codatecn("2", "2022-07-06", "2")])
        # Only the converted bytes of the output date change, as in COBDATFT.asm.
        self.assertEqual(out[0][22:42], "2022-07-06" + "*" * 10)
        self.assertEqual(out[1][22:42], "20220706" + "*" * 12)
        self.assertEqual(out[0][42:].strip(), "")
        self.assertEqual(out[1][42:].strip(), "")

    def test_cobdatft_rejects_mismatched_requests(self):
        out = self.cobdatft([self.codatecn("1", "2022-07-06", "1"),
                             self.codatecn("1", "20220706", "2"),
                             self.codatecn("2", "2022-07-06", "1"),
                             self.codatecn("3", "20220706", "1")])
        for i, rec in enumerate(out):
            with self.subTest(request=i):
                self.assertEqual(rec[42:].strip(), "INVALID INPUT")
                self.assertEqual(rec[22:42], "*" * 20)
