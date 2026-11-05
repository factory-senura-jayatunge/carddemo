"""tools/carddemo.py: data preparation, KSDS utilities, job lookup and the CLI."""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

from tests import oracle
from tests.support import ROOT, CardDemoTestCase, carddemo


def repro_fields(source: str) -> list[tuple[str, int, int]]:
    """(name, offset, length) of the KS-REC fields in a generated REPRO program."""
    fields, pos, in_rec = [], 0, False
    for line in source.splitlines():
        if "01  KS-REC." in line:
            in_rec = True
            continue
        if in_rec:
            m = re.match(r"\s+05\s+(\S+)\s+PIC X\((\d+)\)\.", line)
            if not m:
                break
            fields.append((m.group(1), pos, int(m.group(2))))
            pos += int(m.group(2))
    return fields


class CardDemoToolTest(CardDemoTestCase):
    def test_repro_source_places_primary_and_alternate_keys(self):
        ds = carddemo.datasets()["CARDXREF"]
        source = carddemo.generate_repro(ds)
        fields = repro_fields(source)
        self.assertEqual(sum(f[2] for f in fields), ds.lrecl)
        self.assertIn(("KS-KEY", 0, 16), fields)
        self.assertIn(("KS-ALT1", 25, 11), fields)
        self.assertIn("ALTERNATE RECORD KEY IS KS-ALT1 WITH DUPLICATES", source)
        self.assertNotIn("@", source, "unreplaced template placeholder")

    def test_repro_source_for_a_key_inside_the_record(self):
        fields = repro_fields(carddemo.generate_repro(carddemo.datasets()["EXPORT"]))
        self.assertEqual(fields, [("FILLER", 0, 27), ("KS-KEY", 27, 4), ("FILLER", 31, 469)])

    def test_repro_rejects_overlapping_keys(self):
        ds = carddemo.Dataset("BAD", 50, "indexed", key=(0, 10), alternates=((5, 10, False),))
        with self.assertRaises(carddemo.CardDemoError):
            carddemo.generate_repro(ds)

    def test_text_records_are_padded_and_lose_carriage_returns(self):
        path = self.tmp / "in.txt"
        path.write_bytes(b"AB\r\nCDE\n\nF")
        self.assertEqual(carddemo.read_text_records(path, 4), [b"AB  ", b"CDE ", b"F   "])

    def test_text_record_longer_than_lrecl_is_an_error(self):
        path = self.tmp / "in.txt"
        path.write_bytes(b"ABCDE\n")
        with self.assertRaisesRegex(carddemo.CardDemoError, r"in.txt:1: record is 5 bytes"):
            carddemo.read_text_records(path, 4)

    def test_fixed_records_round_trip(self):
        path = self.tmp / "f.ps"
        carddemo.write_fixed(path, [b"A", b"BB", b"CCC"], 3)
        self.assertEqual(path.read_bytes(), b"A  BB CCC")
        self.assertEqual(carddemo.read_fixed(path, 3), [b"A  ", b"BB ", b"CCC"])
        with self.assertRaises(carddemo.CardDemoError):
            carddemo.read_fixed(path, 4)

    def test_variable_records_are_read_from_their_headers(self):
        path = self.tmp / "v.ps"
        path.write_bytes(b"\x00\x02\x00\x00AB" + b"\x00\x03\x00\x00CDE")
        self.assertEqual(carddemo.read_vb(path), [b"AB", b"CDE"])
        self.assertEqual(carddemo.count_vb(path), 2)
        path.write_bytes(b"\x00\x05\x00\x00AB")
        with self.assertRaises(carddemo.CardDemoError):
            carddemo.read_vb(path)

    def test_business_date_maps_to_cob_current_date(self):
        self.assertEqual(carddemo.business_date_setting("2022-07-06"), "2022/07/06 12:00:00")
        self.assertEqual(carddemo.business_date_setting(" 2023-01-31 08:15:30 "), "2023/01/31 08:15:30")
        self.assertIsNone(carddemo.business_date_setting("now"))
        for bad in ("06/07/2022", "2022-7-6", "yesterday"):
            with self.subTest(value=bad), self.assertRaises(carddemo.CardDemoError):
                carddemo.business_date_setting(bad)

    def test_work_directory_refuses_to_clear_other_directories(self):
        foreign = self.tmp / "precious"
        foreign.mkdir()
        (foreign / "keep.txt").write_text("user data")
        with self.assertRaises(carddemo.CardDemoError):
            carddemo.WorkDir.fresh(foreign)
        self.assertTrue((foreign / "keep.txt").exists())

    def test_work_directory_is_recreated_empty(self):
        work = self.new_work("again")
        (work.path / "leftover.ps").write_text("x")
        work = self.new_work("again")
        self.assertEqual(sorted(p.name for p in work.path.iterdir()), [carddemo.WORK_MARKER])

    def test_find_job_by_job_or_program_name(self):
        self.assertEqual(carddemo.find_job("posttran").name, "POSTTRAN")
        self.assertEqual(carddemo.find_job("CBTRN02C").name, "POSTTRAN")
        self.assertEqual(carddemo.find_job("cbact04c").name, "INTCALC")
        with self.assertRaisesRegex(carddemo.CardDemoError, "cannot run locally: it reads z/OS"):
            carddemo.find_job("CBSTM03A")
        with self.assertRaisesRegex(carddemo.CardDemoError, "no job for 'NOPE'"):
            carddemo.find_job("NOPE")

    def test_step_status_accepts_only_rc_up_to_the_job_maximum(self):
        job = carddemo.JOBS["POSTTRAN"]
        result = lambda rc: carddemo.StepResult("POSTTRAN", "CBTRN02C", rc, "", 0.0, {})  # noqa: E731
        self.assertTrue(carddemo.step_ok(result(0), job))
        self.assertTrue(carddemo.step_ok(result(4), job))
        self.assertFalse(carddemo.step_ok(result(8), job))
        self.assertFalse(carddemo.step_ok(result(-11), job), "a signal is a failure")

    def test_ksds_load_and_unload_return_key_order(self):
        work = self.new_work()
        records = [r.encode("latin-1").ljust(50) for r in oracle.sample_records("cardxref.txt")]
        work.load_ksds("CARDXREF", list(reversed(records)))
        self.assertEqual(work.unload_ksds("CARDXREF"), sorted(records, key=lambda r: r[:16]))

    def test_ksds_load_rejects_duplicate_keys(self):
        work = self.new_work()
        rec = oracle.sample_records("cardxref.txt")[0].encode("latin-1").ljust(50)
        with self.assertRaisesRegex(carddemo.CardDemoError, r"WRITE KSFILE\s+FILE STATUS 22"):
            work.load_ksds("CARDXREF", [rec, rec])

    def test_tranrept_presort_keeps_the_date_window_and_sorts_by_card(self):
        work = self.new_work()

        def tran(tid: str, card: str, proc_date: str) -> bytes:
            return oracle.build_record(oracle.TRAN, **{
                "TRAN-ID": tid, "TRAN-CARD-NUM": card, "TRAN-AMT": "1.00",
                "TRAN-PROC-TS": f"{proc_date}-10.00.00.000000"})
        records = [tran("0000000000000001", "4000000000000009", "2022-03-01"),
                   tran("0000000000000002", "4000000000000001", "2021-12-31"),
                   tran("0000000000000003", "4000000000000001", "2022-07-06"),
                   tran("0000000000000004", "4000000000000005", "2022-07-07"),
                   tran("0000000000000005", "4000000000000001", "2022-01-01")]
        work.load_ksds("TRANSACT", records)
        carddemo.pre_tranrept(work)
        out = carddemo.read_fixed(work.path / "TRANSACT.DALY.ps", 350)
        self.assertEqual([r[:16].decode() for r in out],
                         ["0000000000000003", "0000000000000005", "0000000000000001"])

    def cli(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run([sys.executable, str(ROOT / "tools" / "carddemo.py"), *args],
                              capture_output=True, text=True, check=False, timeout=60)

    def test_cli_list_reports_no_unclassified_sources(self):
        proc = self.cli("list")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertIn("CBTRN02C  executable", proc.stdout)
        self.assertNotIn("UNCLASSIFIED", proc.stdout)

    def test_cli_jobs_lists_every_job(self):
        proc = self.cli("jobs")
        self.assertEqual(proc.returncode, 0, proc.stderr)
        for name in carddemo.JOBS:
            self.assertRegex(proc.stdout, rf"(?m)^{name}\s")

    def test_cli_run_of_an_unknown_job_exits_2(self):
        proc = self.cli("run", "NOSUCHJOB", "--no-build", "--workdir", str(self.tmp / "w"))
        self.assertEqual(proc.returncode, 2)
        self.assertIn("no job for 'NOSUCHJOB'", proc.stderr)
