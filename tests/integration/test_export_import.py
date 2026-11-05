"""CBEXPORT -> CBIMPORT: the multi-record export file splits back into the original data,
and a record of unknown type goes to ERROUT."""
from __future__ import annotations

from collections import Counter

from tests.support import CardDemoTestCase, carddemo


class ExportImportTest(CardDemoTestCase):
    def test_round_trip_reproduces_every_source_file(self):
        work, results = self.run_job("CBIMPORT")
        self.assertEqual([r.job for r in results], ["POSTTRAN", "CBEXPORT", "CBIMPORT"])
        for res in results:
            self.assert_rc(res, carddemo.JOBS[res.job].max_rc if res.job == "POSTTRAN" else 0)
        export, imported = results[1], results[2]

        sources = {"CUSTOUT": "CUSTDATA", "ACCTOUT": "ACCTDATA", "XREFOUT": "CARDXREF",
                   "TRNXOUT": "TRANSACT", "CARDOUT": "CARDDATA"}
        expected = {dd: work.unload_ksds(ds) for dd, ds in sources.items()}
        total = sum(len(v) for v in expected.values())
        self.assertEqual(export.outputs["EXPFILE"][1], total)

        exported = work.unload_ksds("EXPORT")
        self.assertEqual([r[27:31] for r in exported], sorted(r[27:31] for r in exported),
                         "export records are keyed by their sequence number")
        self.assertEqual(Counter(chr(r[0]) for r in exported),
                         Counter({"C": len(expected["CUSTOUT"]), "A": len(expected["ACCTOUT"]),
                                  "X": len(expected["XREFOUT"]), "T": len(expected["TRNXOUT"]),
                                  "D": len(expected["CARDOUT"])}))

        for dd, records in expected.items():
            with self.subTest(dd=dd):
                path, _ = imported.outputs[dd]
                self.assertEqual(carddemo.read_fixed(path, carddemo.JOBS["CBIMPORT"].dd[dd].lrecl), records)
        self.assertEqual(imported.outputs["ERROUT"][1], 0)

    def test_unknown_record_type_goes_to_errout(self):
        work, results = self.run_job("CBEXPORT")
        self.assert_rc(results[-1], 0)
        exported = work.unload_ksds("EXPORT")
        seq = max(int.from_bytes(r[27:31], "big") for r in exported) + 1
        # Type 'Z' matches no WHEN in 2200-PROCESS-RECORD-BY-TYPE; the key (sequence) follows the existing ones.
        unknown = b"Z" + b" " * 26 + seq.to_bytes(4, "big") + b" " * (len(exported[0]) - 31)
        work.load_ksds("EXPORT", exported + [unknown])

        res = work.run_job(carddemo.JOBS["CBIMPORT"])
        self.assert_rc(res, 0)
        path, count = res.outputs["ERROUT"]
        self.assertEqual(count, 1)
        err = carddemo.read_fixed(path, carddemo.JOBS["CBIMPORT"].dd["ERROUT"].lrecl)[0].decode()
        self.assertRegex(err[:26], r"^\d{16}([+-]\d{4}|0{5}) {5}$", "FUNCTION CURRENT-DATE timestamp")
        self.assertEqual(err[26:37], f"|Z|{seq:07d}|")
        self.assertEqual(err[37:].rstrip(), "Unknown record type encountered")
        self.assertEqual(sum(res.outputs[dd][1] for dd in ("CUSTOUT", "ACCTOUT", "XREFOUT", "TRNXOUT", "CARDOUT")),
                         len(exported), "every known record is still imported")
        for line in (f"Total Records Read: {len(exported) + 1:09d}", "Errors Written: 000000001",
                     "Unknown Record Types: 000000001"):
            self.assertIn(f"CBIMPORT: {line}", res.sysout)
