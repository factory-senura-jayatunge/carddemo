"""CBEXPORT -> CBIMPORT: the multi-record export file splits back into the original data."""
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
