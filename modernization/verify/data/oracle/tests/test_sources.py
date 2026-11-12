from copy import deepcopy
from decimal import Decimal
import unittest
import tempfile
from pathlib import Path
import hashlib

from modernization.verify.data.oracle import source, hd, lookups, failures
from modernization.verify.spec_jobs.common import InvalidFixture
from modernization.verify.spec_jobs.layouts import ROOT

HD_FILE = ROOT / "app/app-authorization-ims-db2-mq/data/EBCDIC/AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat"


class SourceTest(unittest.TestCase):
    def test_live_failure_inputs_are_independently_invalid(self):
        for case in ("out_of_order", "duplicate", "manifest_bytes"):
            with tempfile.TemporaryDirectory() as tmp:
                target = Path(tmp)
                manifest = failures.prepare(ROOT / "app/data/ASCII", target, case)
                raw = (target / "acctdata.txt").read_bytes()
                with self.assertRaises(InvalidFixture):
                    source.parse("core.account", raw, mode="fixed",
                                 manifest=manifest.read_bytes() if manifest else None)
                if case == "manifest_bytes":
                    self.assertFalse(failures.rejected(case, "legacy"))
                    self.assertTrue(failures.rejected(case, "fixed"))
                    rows = source.parse("core.account", raw, mode="legacy", manifest=manifest.read_bytes())
                    self.assertTrue(all(r["zip_group_origin"] == "R" for r in rows))
                    self.assertFalse(source.validate("core.account", raw, rows, mode="legacy",
                                                     manifest=manifest.read_bytes()))
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp)
            failures.prepare(ROOT / "app/data/ASCII", target, "missing_account", "core.card_xref")
            accounts = {r["acct_id"] for r in source.parse("core.account", (target / "acctdata.txt").read_bytes())}
            xrefs = source.parse("core.card_xref", (target / "cardxref.txt").read_bytes())
            self.assertTrue(any(r["acct_id"] not in accounts for r in xrefs))

    def test_sample_tables_and_roundtrip_both_modes(self):
        for table_name, table in source.TABLES.items():
            raw = (ROOT / "app/data/ASCII" / table.filename).read_bytes()
            for mode in ("legacy", "fixed"):
                rows = source.parse(table_name, raw, mode=mode)
                self.assertEqual(len(rows), table.count)
                findings = source.validate(table_name, raw, rows, mode=mode)
                self.assertFalse(findings, "\n".join(map(str, findings)))

    def test_each_source_column_mutation(self):
        for name, table in source.TABLES.items():
            raw = (ROOT / "app/data/ASCII" / table.filename).read_bytes()
            rows = source.parse(name, raw)
            for column in rows[0]:
                changed = deepcopy(rows)
                changed[0][column] = "defect"
                self.assertTrue(source.validate(name, raw, changed), f"{name}:{column}")

    def test_order_and_duplicate_rejected(self):
        raw = (ROOT / "app/data/ASCII/acctdata.txt").read_bytes()
        rows = source.lines(raw)
        for changed in ([rows[0]] + rows, rows[1:] + rows[:1]):
            with self.assertRaises(InvalidFixture):
                source.parse("core.account", b"\n".join(changed) + b"\n")

    def test_declared_layout_mode_hash_and_mixed_blank(self):
        raw = (ROOT / "app/data/ASCII/acctdata.txt").read_bytes()
        legacy, fixed = (source.parse("core.account", raw, mode=m)[0] for m in ("legacy", "fixed"))
        self.assertEqual((legacy["addr_zip"], legacy["group_id"], legacy["zip_group_origin"]), ("A000000000", "", "R"))
        self.assertEqual((fixed["addr_zip"], fixed["group_id"], fixed["zip_group_origin"]), ("", "A000000000", "M"))
        self.assertEqual(source.account_layouts(raw + b" ", "fixed")[fixed["acct_id"]], "CVACT01Y")
        self.assertFalse(source.blank(" \0"))
        self.assertTrue(source.blank("\0" * 10))
        manifest = (f'schema = 1\nsha256 = "{hashlib.sha256(raw).hexdigest()}"\n'
                    'default_layout = "ACUP_UPDATE"\n').encode()
        self.assertEqual(source.parse("core.account", raw, mode="fixed", manifest=manifest)[0], fixed)

    def test_customer_filler_and_account_nul(self):
        raw = (ROOT / "app/data/ASCII/custdata.txt").read_bytes()
        rows = source.lines(raw)
        rows[0] = rows[0][:332] + b"\0" * 168
        changed = b"\n".join(rows) + b"\n"
        self.assertFalse(source.validate("core.customer", changed, source.parse("core.customer", changed)))
        raw = (ROOT / "app/data/ASCII/acctdata.txt").read_bytes()
        rows = source.lines(raw)
        rows[0] = rows[0][:122] + b"\0" * 178
        changed = b"\n".join(rows) + b"\n"
        self.assertFalse(source.validate("core.account", changed, source.parse("core.account", changed)))

    def test_strict_manifest_subset_and_other_layout_accounts(self):
        raw = (ROOT / "app/data/ASCII/acctdata.txt").read_bytes()
        account = source.lines(raw)[0][:11].decode()
        base = (f'schema = 1\nsha256 = "{hashlib.sha256(raw).hexdigest()}"\n'
                'default_layout = "ACUP_UPDATE"\n')
        declared = (base + f'other_layout_accounts = [\n "{account}", # one exception\n]\n').encode()
        layouts = source.account_layouts(raw, "fixed", declared)
        self.assertEqual(layouts[account], "CVACT01Y")
        self.assertEqual(sum(v == "ACUP_UPDATE" for v in layouts.values()), 49)
        invalid = [
            base.replace("schema = 1", "schema = true"), base.replace("schema = 1", 'schema = "1"'),
            base.replace("schema = 1", "schema = 2"), base + "unknown = 1\n",
            base + "other_layout_accounts = [1]\n",
            base + f'other_layout_accounts = ["{account}","{account}"]\n',
            base + 'other_layout_accounts = ["87361920451"]\n',
            base + 'other_layout_accounts = ["short"]\n',
            base.replace('schema = 1', '"schema" = 1'),
            base + "schema = 1\n", base.replace("schema = 1", "schema = 0x1"),
            base.replace("ACUP_UPDATE", r"ACUP_\u0055PDATE")]
        for number, text in enumerate(invalid):
            with self.subTest(case=number), self.assertRaises(InvalidFixture):
                source.account_layouts(raw, "fixed", text.encode())
            self.assertTrue(all(v == "CVACT01Y" for v in source.account_layouts(raw, "legacy", text.encode()).values()))

    def test_blank_nullable_dates_roundtrip(self):
        raw = (ROOT / "app/data/ASCII/acctdata.txt").read_bytes()
        lines = source.lines(raw)
        lines[0] = lines[0][:48] + b" " * 30 + lines[0][78:]
        raw = b"\n".join(lines) + b"\n"
        rows = source.parse("core.account", raw)
        self.assertIsNone(rows[0]["open_date"])
        self.assertFalse(source.validate("core.account", raw, rows))

    def test_cvv_intentionally_excluded(self):
        raw = (ROOT / "app/data/ASCII/carddata.txt").read_bytes()
        rows = source.parse("core.card", raw)
        rows[0]["cvv_cd"] = "never-read"
        self.assertFalse(source.validate("core.card", raw, rows))


class HdTest(unittest.TestCase):
    def test_each_hd_column_mutation_and_own_roundtrip(self):
        raw = HD_FILE.read_bytes()
        summaries, details = hd.parse(raw)
        self.assertFalse(hd.validate(raw, summaries, details))
        for rows, which in ((summaries, 0), (details, 1)):
            for column in rows[0]:
                actual = [deepcopy(summaries), deepcopy(details)]
                actual[which][0][column] = "defect"
                self.assertTrue(hd.validate(raw, *actual), f"{which}:{column}")

    def test_sample_counts_and_independent_totals(self):
        summaries, details = hd.parse(HD_FILE.read_bytes())
        self.assertEqual((len(summaries), len(details)), (21, 202))
        self.assertEqual(sum(s["approved_auth_cnt"] for s in summaries), 202)
        self.assertEqual(sum(s["approved_auth_amt"] for s in summaries),
                         sum(d["approved_amt"] for d in details))
        self.assertTrue(all(d["processed_at"].startswith(("2023-", "2024-")) for d in details))

    def test_timestamp_literal_and_year_end(self):
        self.assertEqual(hd.timestamp(99999 - 23365, 999999999 - 235959123), "2023-12-31T23:59:59.123")
        for d, t in ((99999 - 23366, 999999999), (99999 - 24000, 999999999), (99999 - 24001, -1)):
            with self.assertRaises(InvalidFixture):
                hd.timestamp(d, t)

    def test_packed_literals_sign_padding_and_mutations(self):
        self.assertEqual(hd.packed(bytes.fromhex("0012345d"), 6, 2), Decimal("-123.45"))
        self.assertEqual(hd.packed(bytes.fromhex("12345c"), 5), Decimal(12345))
        for sign in ("a", "c", "e", "f"):
            self.assertEqual(hd.packed(bytes.fromhex("12345" + sign), 5), Decimal(12345))
        for raw in ("123459", "1a345c", "1012345c"):
            with self.assertRaises(InvalidFixture):
                hd.packed(bytes.fromhex(raw), 5 if len(raw) == 6 else 6)

    def test_blank_response_is_a_load_failure(self):
        with self.assertRaises(InvalidFixture):
            hd.parse(failures.blank_response(HD_FILE.read_bytes()))

    def test_descriptor_and_segment_mutations(self):
        raw = HD_FILE.read_bytes()
        for changed in (raw[:-1], b"\0\0" + raw[2:], raw[:2] + b"xx" + raw[4:],
                        raw[:8] + b"xxxxxxxx" + raw[16:]):
            with self.assertRaises(InvalidFixture):
                hd.parse(changed)


class LookupTest(unittest.TestCase):
    def test_spec_source_counts_and_mutation(self):
        raw = (ROOT / "app/cpy/CSLKPCDY.cpy").read_text()
        tables = lookups.parse(raw)
        self.assertEqual([len(tables[n]) for n in ("ref.us_state", "ref.us_state_zip_prefix", "ref.nanp_area_code")],
                         [56, 240, 410])
        with self.assertRaises(InvalidFixture):
            lookups.parse(raw.replace("VALID-US-STATE-CODE", "REMOVED"))


if __name__ == "__main__":
    unittest.main()
