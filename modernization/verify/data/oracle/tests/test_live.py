from copy import deepcopy
from pathlib import Path
import hashlib
import re
import tempfile
import unittest

from modernization.verify.data.oracle import failures, hd, live, lookups, source, metadata
from modernization.verify.spec_jobs.layouts import ROOT
from modernization.verify.spec_jobs.session import Execution
from modernization.verify.spec_jobs.tests.fake import FakeSession


class DataSession(FakeSession):
    """Offline adapter simulator; it never returns masked card keys or CVV."""
    def reset(self, *, mode, properties=None):
        super().reset(mode=mode, properties=properties)
        self.mode = mode
        self.populate(ROOT / "app/data/ASCII", live.HD_FILE)

    def populate(self, directory, auth_file, manifest=None):
        tables = {}
        for name, table in source.TABLES.items():
            self.failed_step = live.FILE_STEPS[name]
            tables[name] = source.parse(name, (directory / table.filename).read_bytes(), mode=self.mode,
                                       manifest=manifest.read_bytes() if manifest and self.mode == "fixed" and name == "core.account" else None)
        accounts = {r["acct_id"] for r in tables["core.account"]}
        self.failed_step = "loadCardXref"
        if any(r["acct_id"] not in accounts for r in tables["core.card_xref"]):
            raise ValueError("MISSING_ACCOUNT")
        self.failed_step = "loadPendingAuthorizations"
        auth = hd.parse(auth_file.read_bytes())
        tables.update(zip(("pauth.auth_summary", "pauth.auth_detail"), auth))
        tables.update(lookups.parse((ROOT / "app/cpy/CSLKPCDY.cpy").read_text()))
        self.tables.update(tables)
        for name in source.TABLES:
            self.column_types[name] = live.file_types(name)
        self.column_types.update(zip(("pauth.auth_summary", "pauth.auth_detail"), live.auth_types()))
        for name, rows in self.tables.items():
            for number, row in enumerate(rows, 1):
                if not name.startswith("ref.us_") and name != "ref.nanp_area_code" and name != "core.card_xref":
                    row["row_version"] = number.to_bytes(8, "big")
                if name == "pauth.auth_detail":
                    row["auth_id"] = number
                if name == "core.card":
                    row["card_id"] = number
            if name in self.column_types:
                if name != "core.card_xref":
                    self.column_types[name]["row_version"] = live.digest.Column("rowversion")
                if name == "pauth.auth_detail":
                    self.column_types[name]["auth_id"] = live.digest.Column("bigint")
                if name == "core.card":
                    self.column_types[name]["card_id"] = live.digest.Column("bigint")

    def query(self, sql):
        if "sys.database_permissions" in sql:
            return [{"column_name": c} for c in (live.CUSTOMER_MASKS if "core.customer" in sql else ("card_num",))]
        if "sys.sequences" in sql:
            return [{"next_value": "9000000000000000"}]
        if "sys.masked_columns" in sql:
            return [{"is_masked": True}]
        if "HAS_PERMS_BY_NAME" in sql:
            return [{"can_unmask": 0}]
        if "COUNT_BIG(*)" in sql:
            return [{"transactions": 0, "orphan_details": 0}]
        table = re.search(r" FROM ([a-z_.]+) ORDER BY ", sql).group(1)
        selection, ordering = sql.split(" FROM ", 1)[0], sql.rsplit(" ORDER BY ", 1)[1]
        columns = tuple(dict.fromkeys(re.findall(r"\[([a-z_0-9]+)\]", selection)))
        keys = re.findall(r"\[([a-z_0-9]+)\]", ordering)
        if table in ("core.card", "core.card_xref") and "card_num" in columns:
            raise AssertionError("MASKED_KEY_PROJECTED")
        if "cvv_cd" in columns:
            raise AssertionError("CVV_PROJECTED")
        return [{c: deepcopy(row[c]) for c in columns}
                for row in sorted(self.tables[table], key=lambda r: tuple(r[c] for c in keys))]

    def load(self, source_dir, *, manifest=None, authorization_file=None):
        try:
            self.populate(source_dir, authorization_file or live.HD_FILE, manifest)
            counts, contexts, job = metadata.expected(self.mode, source_dir, authorization_file or live.HD_FILE, manifest)
            return Execution("COMPLETED", step_counts=counts, contexts=contexts, job_context=job)
        except ValueError:
            job = {}
            if self.failed_step == "loadAccounts" and self.mode == "fixed" and manifest:
                job = {"dataLoad.acctdataSha256": hashlib.sha256((source_dir / "acctdata.txt").read_bytes()).hexdigest(),
                       "dataLoad.accountLayoutManifestSha256": hashlib.sha256(manifest.read_bytes()).hexdigest()}
            return Execution("FAILED", step_statuses={self.failed_step: "FAILED"}, job_context=job)


class LiveDataTest(unittest.TestCase):
    def test_sample_and_all_failure_cases_in_both_modes(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = DataSession(Path(tmp))
            output = []
            self.assertEqual(live.run(session, out=output.append), 0)
            self.assertEqual(len([s for s in output if s.startswith("ok ")]), 12)
            self.assertEqual(output[-1], "verify-data-oracle: OK")

    def test_masked_card_byte_mutation_cannot_hide_behind_safe_projection(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = DataSession(Path(tmp))
            session.reset(mode="fixed")
            session.tables["core.card"][0]["card_num"] = "1111222233334444"
            problems = live.validate(session, mode="fixed", source_dir=ROOT / "app/data/ASCII")
            self.assertTrue(any(p.check == "SOURCE_AGGREGATE" and p.source == "core.card" for p in problems))
            self.assertNotIn("1111222233334444", "\n".join(map(str, problems)))

    def test_missing_existing_column_grant_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = DataSession(Path(tmp))
            session.reset(mode="legacy")
            original = DataSession.query
            def refused(self, sql):
                return [] if "sys.database_permissions" in sql else original(self, sql)
            from unittest.mock import patch
            with patch.object(DataSession, "query", refused), self.assertRaises(PermissionError):
                live.validate(session, mode="legacy", source_dir=ROOT / "app/data/ASCII")

    def test_failed_step_mutation_is_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = DataSession(Path(tmp))
            session.reset(mode="fixed")
            def broken(*args, **kwargs):
                session.tables["core.account"][0]["curr_bal"] += 1
                return Execution("FAILED")
            session.load = broken
            problems, _ = live.failure(session, "duplicate", "fixed", ROOT / "app/data/ASCII",
                                       Path(tmp), live.HD_FILE)
            self.assertTrue(any(p.check == "FAILED_STEP_UNCHANGED" for p in problems))

    def test_wrong_failed_step_is_not_accepted(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = DataSession(Path(tmp))
            session.reset(mode="fixed")
            session.load = lambda *a, **k: Execution("FAILED", step_statuses={"unrelated": "FAILED"})
            problems, _ = live.failure(session, "duplicate", "fixed", ROOT / "app/data/ASCII",
                                       Path(tmp), live.HD_FILE)
            self.assertTrue(any(p.check == "EXPECTED_STEP_FAILED" for p in problems))

    def test_generated_auth_id_file_order_is_checked(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = DataSession(Path(tmp))
            session.reset(mode="fixed")
            rows = session.tables["pauth.auth_detail"]
            rows[0]["auth_id"], rows[1]["auth_id"] = rows[1]["auth_id"], rows[0]["auth_id"]
            problems = live.validate(session, mode="fixed", source_dir=ROOT / "app/data/ASCII")
            self.assertTrue(any(p.check == "AUTH_ID_FILE_ORDER" for p in problems))


if __name__ == "__main__":
    unittest.main()
