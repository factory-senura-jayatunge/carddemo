from copy import deepcopy
from dataclasses import replace
import hashlib
from pathlib import Path
import shutil
import tempfile
import unittest

from modernization.verify.data.oracle import metadata, source
from modernization.verify.data.oracle.live import HD_FILE
from modernization.verify.spec_jobs.layouts import ROOT
from modernization.verify.spec_jobs.session import Execution


class MetadataTest(unittest.TestCase):
    def test_source_derived_standard_counts_and_context(self):
        for mode in ("legacy", "fixed"):
            counts, contexts, job = metadata.expected(mode, ROOT / "app/data/ASCII", HD_FILE)
            self.assertEqual([counts[s]["READ_COUNT"] for s in metadata.FILE_STEPS.values()],
                             [50, 50, 50, 50, 50, 7, 18, 51])
            self.assertEqual(counts["loadPendingAuthorizations"], {"READ_COUNT": 224, "WRITE_COUNT": 223})
            self.assertEqual(counts["loadLookups"], {"WRITE_COUNT": 706})
            self.assertEqual(contexts["loadCustomers"], {
                "customerFillerNormalised": 0, "customerFillerNormalisedLines": ""})
            accounts = contexts["loadAccounts"]
            self.assertEqual(accounts["accountsAsRead"], 50 if mode == "legacy" else 0)
            self.assertEqual(accounts["accountsMigrated"], 0 if mode == "legacy" else 50)
            self.assertEqual(accounts["groupIdMode"], mode)
            self.assertTrue(accounts["acctdataSha256"] == source.SAMPLE_ACCOUNT_SHA)
            self.assertTrue(job["dataLoad.acctdataSha256"] == hashlib.sha256(
                (ROOT / "app/data/ASCII/acctdata.txt").read_bytes()).hexdigest())
            execution = Execution("COMPLETED", step_counts=counts, contexts=contexts, job_context=job)
            findings = metadata.validate(execution, mode=mode, source_dir=ROOT / "app/data/ASCII",
                                         authorization_file=HD_FILE)
            self.assertFalse(findings)

    def test_every_missing_or_mutated_count_context_and_job_key_fails(self):
        for mode in ("legacy", "fixed"):
            counts, contexts, job = metadata.expected(mode, ROOT / "app/data/ASCII", HD_FILE)
            baseline = Execution("COMPLETED", step_counts=counts, contexts=contexts, job_context=job)
            for attribute in ("step_counts", "contexts", "job_context"):
                original = getattr(baseline, attribute)
                for outer in original:
                    keys = original[outer] if attribute != "job_context" else (None,)
                    for key in keys:
                        for missing in (False, True):
                            changed = deepcopy(original)
                            target = changed if key is None else changed[outer]
                            column = outer if key is None else key
                            if missing:
                                target.pop(column)
                            else:
                                target[column] = "defect"
                            execution = replace(baseline, **{attribute: changed})
                            findings = metadata.validate(execution, mode=mode, source_dir=ROOT / "app/data/ASCII",
                                                         authorization_file=HD_FILE)
                            self.assertTrue(findings, f"{attribute}:{outer}:{key}")
                            self.assertNotIn(source.SAMPLE_ACCOUNT_SHA, "\n".join(map(str, findings)))

    def test_changed_file_changes_expected_context_not_just_rows(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / "source"
            shutil.copytree(ROOT / "app/data/ASCII", directory)
            path = directory / "custdata.txt"
            rows = source.lines(path.read_bytes())
            for i in (2, 6, 7, 8):
                rows[i] = rows[i][:332] + b"\0" * 168
            path.write_bytes(b"\n".join(rows) + b"\n")
            path = directory / "acctdata.txt"
            raw = path.read_bytes().replace(b"\n", b"\r\n")
            path.write_bytes(raw)
            _, contexts, _ = metadata.expected("fixed", directory, HD_FILE)
            self.assertEqual(contexts["loadCustomers"], {
                "customerFillerNormalised": 4, "customerFillerNormalisedLines": "3, 7-9"})
            self.assertEqual(contexts["loadAccounts"]["accountLayoutSource"], "none")
            self.assertEqual(contexts["loadAccounts"]["accountsMigrated"], 0)
            self.assertEqual(contexts["loadAccounts"]["accountLayoutWarnings"], 50)
            counts, contexts, job = metadata.expected("fixed", directory, HD_FILE)
            findings = metadata.validate(Execution("COMPLETED", step_counts=counts, contexts=contexts, job_context=job),
                                         mode="fixed", source_dir=directory, authorization_file=HD_FILE)
            self.assertFalse(findings)

    def test_line_ranges_cap_and_status(self):
        hundred = list(range(2, 201, 2))
        rendered = ", ".join(map(str, hundred))
        self.assertEqual(metadata.line_ranges(hundred), rendered)
        self.assertEqual(metadata.line_ranges(hundred + list(range(300, 310)) + [400]),
                         rendered + " and 11 more")
        self.assertEqual(metadata.line_ranges([3, 7, 8, 9, 12]), "3, 7-9, 12")
        self.assertEqual(metadata.line_ranges([7, 8]), "7-8")
        self.assertEqual(metadata.line_ranges([]), "")
        self.assertTrue(metadata.validate(Execution("FAILED"), mode="legacy",
                                         source_dir=ROOT / "app/data/ASCII", authorization_file=HD_FILE))

    def test_mode_supplied_builtin_eight_combinations(self):
        sample = (ROOT / "app/data/ASCII/acctdata.txt").read_bytes()
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "layout.toml"
            for mode in ("legacy", "fixed"):
                for builtin in (False, True):
                    raw = sample if builtin else sample.replace(b"\n", b"\r\n")
                    for supplied in (False, True):
                        with self.subTest(mode=mode, builtin=builtin, supplied=supplied):
                            declaration = (f'schema = 1\nsha256 = "{hashlib.sha256(raw).hexdigest()}"\n'
                                           'default_layout = "CVACT01Y"\n').encode()
                            manifest.write_bytes(declaration)
                            context, job = metadata.account_report(raw, mode, manifest if supplied else None)
                            wanted_source = ("ignored" if mode == "legacy" else "supplied" if supplied else
                                             "builtin" if builtin else "none")
                            self.assertEqual(context["accountLayoutSource"], wanted_source)
                            active = mode == "fixed" and supplied
                            wanted_hash = hashlib.sha256(declaration).hexdigest() if active else ""
                            self.assertTrue(context["accountLayoutManifestSha256"] == wanted_hash)
                            self.assertEqual("dataLoad.accountLayoutManifestSha256" in job, active)
                            if active:
                                self.assertTrue(job["dataLoad.accountLayoutManifestSha256"] == wanted_hash)
                            self.assertEqual(context["accountsMigrated"],
                                             50 if mode == "fixed" and builtin and not supplied else 0)
            # A missing or malformed manifest must not even be opened in legacy.
            manifest.unlink()
            self.assertEqual(metadata.account_report(sample, "legacy", manifest)[0]["accountLayoutSource"], "ignored")
            manifest.write_bytes(b"not TOML")
            self.assertEqual(metadata.account_report(sample, "legacy", manifest)[0]["accountsMigrated"], 0)

    def test_fixed_supplied_validation_and_read_failure_job_hashes(self):
        raw = (ROOT / "app/data/ASCII/acctdata.txt").read_bytes()
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "layout.toml"
            for declaration in (b"not TOML", b'schema = 1\nsha256 = "' + b"0" * 64 +
                                b'"\ndefault_layout = "ACUP_UPDATE"\n'):
                manifest.write_bytes(declaration)
                job = {"dataLoad.acctdataSha256": hashlib.sha256(raw).hexdigest(),
                       "dataLoad.accountLayoutManifestSha256": hashlib.sha256(declaration).hexdigest()}
                baseline = Execution("FAILED", job_context=job)
                self.assertFalse(metadata.validate_account_failure(baseline, raw, manifest))
                for key in job:
                    for missing in (True, False):
                        changed = dict(job)
                        if missing:
                            changed.pop(key)
                        else:
                            changed[key] = "defect"
                        self.assertTrue(metadata.validate_account_failure(replace(baseline, job_context=changed), raw, manifest))
                for key in ("accountLayoutSource", "accountLayoutManifestSha256"):
                    self.assertTrue(metadata.validate_account_failure(replace(
                        baseline, contexts={"loadAccounts": {key: ""}}), raw, manifest))
            for failure in ("missing", "directory", "oversize"):
                if manifest.is_file():
                    manifest.unlink()
                if failure == "directory":
                    manifest.mkdir()
                elif failure == "oversize":
                    manifest.rmdir()
                    manifest.write_bytes(b" " * (1024 * 1024 + 1))
                self.assertFalse(metadata.validate_account_failure(Execution("FAILED"), raw, manifest))
                for key in ("dataLoad.acctdataSha256", "dataLoad.accountLayoutManifestSha256"):
                    self.assertTrue(metadata.validate_account_failure(Execution("FAILED", job_context={key: ""}), raw, manifest))

    def test_completed_supplied_hashes_and_unexpected_legacy_job_hash_mutations(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "layout.toml"
            raw = (ROOT / "app/data/ASCII/acctdata.txt").read_bytes()
            manifest.write_bytes((f'schema = 1\nsha256 = "{hashlib.sha256(raw).hexdigest()}"\n'
                                  'default_layout = "CVACT01Y"\n').encode())
            for mode in ("legacy", "fixed"):
                counts, contexts, job = metadata.expected(mode, ROOT / "app/data/ASCII", HD_FILE, manifest)
                baseline = Execution("COMPLETED", step_counts=counts, contexts=contexts, job_context=job)
                self.assertFalse(metadata.validate(baseline, mode=mode, source_dir=ROOT / "app/data/ASCII",
                                                    authorization_file=HD_FILE, manifest=manifest))
                changed = deepcopy(contexts)
                changed["loadAccounts"]["accountLayoutManifestSha256"] = "defect"
                self.assertTrue(metadata.validate(replace(baseline, contexts=changed), mode=mode,
                                                   source_dir=ROOT / "app/data/ASCII",
                                                   authorization_file=HD_FILE, manifest=manifest))
                changed = {**job, "dataLoad.accountLayoutManifestSha256": "defect"}
                self.assertTrue(metadata.validate(replace(baseline, job_context=changed), mode=mode,
                                                   source_dir=ROOT / "app/data/ASCII",
                                                   authorization_file=HD_FILE, manifest=manifest))

    def test_warning_blank_repertoire_partial_zip_and_short_padding(self):
        template = source.lines((ROOT / "app/data/ASCII/acctdata.txt").read_bytes())[0]
        cases = [
            (b"12345     ", b" " * 10, 1),
            (b"A" * 10, b"\0" * 10, 1),
            (b" " * 5 + b"\0" * 5, b" " * 10, 1),
            (b"\0" + b" " * 9, b" " * 10, 1),
            (b"   12345  ", b" " * 10, 1),
            (b" " * 10, b" " * 10, 0),
            (b"\0" * 10, b"\0" * 10, 0),
            (b"A" * 10, b" " * 5 + b"\0" * 5, 0),
            (b"A" * 10, b"A" + b" " * 9, 0),
        ]
        for zip_field, group_field, wanted in cases:
            raw = template[:102] + zip_field + group_field + template[122:] + b"\n"
            self.assertEqual(metadata.account_report(raw, "fixed")[0]["accountLayoutWarnings"], wanted)
            self.assertEqual(metadata.account_report(raw, "legacy")[0]["accountLayoutWarnings"], 0)
        for length, wanted in ((107, 1), (102, 0)):
            raw = (template[:102] + b"12345     ")[:length]
            self.assertEqual(metadata.account_report(raw, "fixed")[0]["accountLayoutWarnings"], wanted)

    def test_partial_zip_warning_count_mutation_is_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / "source"
            shutil.copytree(ROOT / "app/data/ASCII", directory)
            path = directory / "acctdata.txt"
            rows = source.lines(path.read_bytes())
            rows = [r[:102] + b" " * 20 + r[122:] for r in rows]
            rows[0] = rows[0][:102] + b"12345     " + rows[0][112:]
            path.write_bytes(b"\n".join(rows) + b"\n")
            counts, contexts, job = metadata.expected("fixed", directory, HD_FILE)
            self.assertEqual(contexts["loadAccounts"]["accountLayoutWarnings"], 1)
            contexts["loadAccounts"]["accountLayoutWarnings"] = 0
            problems = metadata.validate(Execution("COMPLETED", step_counts=counts, contexts=contexts, job_context=job),
                                         mode="fixed", source_dir=directory, authorization_file=HD_FILE)
            self.assertEqual([(p.check, p.column) for p in problems],
                             [("LOAD_STEP_CONTEXT", "accountLayoutWarnings")])

    def test_customer_filler_ends_at_500_and_long_line_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / "source"
            shutil.copytree(ROOT / "app/data/ASCII", directory)
            path = directory / "custdata.txt"
            template = source.lines(path.read_bytes())[0]
            rows = [template[:332] + b" " * 168 + b"extra"]
            path.write_bytes(b"\n".join(rows) + b"\n")
            for mode in ("legacy", "fixed"):
                contexts = metadata.expected(mode, directory, HD_FILE)[1]
                self.assertEqual(contexts["loadCustomers"], {
                    "customerFillerNormalised": 0, "customerFillerNormalisedLines": ""})
                with self.assertRaises(source.InvalidFixture):
                    source.parse("core.customer", path.read_bytes(), mode=mode)

    def test_account_report_counts_classified_rows_not_distinct_ids(self):
        template = source.lines((ROOT / "app/data/ASCII/acctdata.txt").read_bytes())[0]
        raw = b"\n".join([template, template]) + b"\n"
        for mode in ("legacy", "fixed"):
            context, _ = metadata.account_report(raw, mode)
            self.assertEqual(context["accountsAsRead"], 2)
            self.assertEqual(context["accountsMigrated"], 0)
            with self.assertRaises(source.InvalidFixture):
                source.parse("core.account", raw, mode=mode)

    def test_lost_lease_with_missing_validation_hashes_is_fail_closed(self):
        raw = (ROOT / "app/data/ASCII/acctdata.txt").read_bytes()
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "layout.toml"
            manifest.write_bytes(b"not TOML")
            execution = Execution("FAILED", exit_description="LEASE_LOST", job_context={})
            problems = metadata.validate_account_failure(execution, raw, manifest)
            self.assertEqual({(p.check, p.column) for p in problems}, {
                ("LOAD_JOB_CONTEXT", "dataLoad.acctdataSha256"),
                ("LOAD_JOB_CONTEXT", "dataLoad.accountLayoutManifestSha256")})
            self.assertTrue(metadata.validate(execution, mode="legacy",
                                             source_dir=ROOT / "app/data/ASCII", authorization_file=HD_FILE))


if __name__ == "__main__":
    unittest.main()
