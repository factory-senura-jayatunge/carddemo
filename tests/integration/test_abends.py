"""Every batch program abends (CEE3ABD, U999) when an input dataset is missing."""
from __future__ import annotations

from tests.support import CardDemoTestCase, carddemo

# program -> (first DD it opens, message it displays when that open fails)
FIRST_OPEN = {
    "CBACT01C": ("ACCTFILE", "ERROR OPENING ACCTFILE"),
    "CBACT02C": ("CARDFILE", "ERROR OPENING CARDFILE"),
    "CBACT03C": ("XREFFILE", "ERROR OPENING XREFFILE"),
    "CBCUS01C": ("CUSTFILE", "ERROR OPENING CUSTFILE"),
    "CBTRN01C": ("DALYTRAN", "ERROR OPENING DAILY TRANSACTION FILE"),
    "CBTRN02C": ("DALYTRAN", "ERROR OPENING DALYTRAN"),
    "CBTRN03C": ("TRANFILE", "ERROR OPENING TRANFILE"),
    "CBACT04C": ("TCATBALF", "ERROR OPENING TRANSACTION CATEGORY BALANCE"),
}


class MissingInputAbendTest(CardDemoTestCase):
    def test_missing_first_input_abends_with_u999(self):
        for program, (ddname, message) in FIRST_OPEN.items():
            with self.subTest(program=program):
                work = self.new_work(program)
                proc = self.run_program(program, {ddname: work.path / "missing.dat"}, work,
                                        parm=carddemo.JOBS["INTCALC"].parm)
                self.assertEqual(proc.returncode, 16, proc.stdout + proc.stderr)
                self.assertIn(message, proc.stdout)
                self.assertIn("FILE STATUS IS: NNNN0035", proc.stdout)
                self.assertIn("ABENDING PROGRAM", proc.stdout)
                self.assertIn("CEE3ABD: USER ABEND U0999", proc.stderr)

    def test_export_stops_when_an_input_is_missing(self):
        work = self.new_work()
        proc = self.run_program("CBEXPORT", {"CUSTFILE": work.path / "missing.dat",
                                             "EXPFILE": work.path / "export.ksds"}, work)
        self.assertEqual(proc.returncode, 16, proc.stdout + proc.stderr)
        self.assertIn("ERROR: Cannot open CUSTOMER-INPUT, Status: 35", proc.stdout)
        self.assertIn("CEE3ABD: USER ABEND U", proc.stderr)

    def test_import_stops_when_the_export_file_is_missing(self):
        work = self.new_work()
        proc = self.run_program("CBIMPORT", {"EXPFILE": work.path / "missing.ksds"}, work)
        self.assertEqual(proc.returncode, 16, proc.stdout + proc.stderr)
        self.assertIn("ERROR: Cannot open EXPORT-INPUT, Status: 35", proc.stdout)
        self.assertIn("CEE3ABD: USER ABEND U", proc.stderr)
