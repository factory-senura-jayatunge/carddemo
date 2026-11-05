"""The build manifest classifies every COBOL source and matches the JCL and programs."""
from __future__ import annotations

import re
import unittest
from pathlib import Path

from tests import oracle
from tests.support import ROOT, carddemo

SUBSYSTEM_CALL = re.compile(r"EXEC\s+(CICS|SQL|DLI)\b|CBLTDLI|AIBTDLI|'MQ(CONN|OPEN|PUT1?|GET)'", re.I)


def code_text(path: Path) -> str:
    """Program text without sequence numbers and comment lines."""
    lines = []
    for raw in path.read_text(errors="replace").splitlines():
        if len(raw) > 6 and raw[6] in "*/":
            continue
        lines.append(raw[7:72] if len(raw) > 7 else "")
    return "\n".join(lines)


def assigned_dds(path: Path) -> set[str]:
    return {m.upper() for m in re.findall(r"\bASSIGN\s+TO\s+([A-Z0-9-]+)", code_text(path), re.I)}


def jcl_step_dds(jcl: Path, program: str) -> set[str]:
    """DD names of the step that runs PGM=<program>."""
    dds, in_step = set(), False
    for line in jcl.read_text(errors="replace").splitlines():
        if line.startswith("//*"):
            continue
        m = re.match(r"//(\S*)\s+(EXEC|DD)\s*(.*)", line)
        if not m:
            continue
        name, op, rest = m.groups()
        if op == "EXEC":
            in_step = bool(re.match(rf"PGM={program}\b", rest))
        elif in_step and name:
            dds.add(name.upper())
    return dds


class ManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.manifest = carddemo.load_manifest()

    def test_every_cobol_source_is_built_or_excluded(self):
        _, _, unclassified = carddemo.classify_sources(self.manifest)
        self.assertEqual(unclassified, [], "add these sources to tools/batch-programs.toml")

    def test_no_source_is_both_built_and_excluded(self):
        built, excluded, _ = carddemo.classify_sources(self.manifest)
        self.assertEqual(sorted(set(built) & set(excluded)), [])

    def test_every_listed_source_exists(self):
        listed = [p["source"] for p in self.manifest["program"]]
        listed += [s["source"] for s in self.manifest["shim"]]
        listed += [d["source"] for d in self.manifest["driver"]]
        listed += [s for e in self.manifest["exclude"] for s in e["sources"]]
        self.assertEqual([s for s in listed if not (ROOT / s).is_file()], [])

    def test_excluded_sources_need_cics_db2_ims_or_mq(self):
        for entry in self.manifest["exclude"]:
            for src in entry["sources"]:
                with self.subTest(src=src):
                    self.assertRegex(code_text(ROOT / src), SUBSYSTEM_CALL)

    def test_built_programs_make_no_subsystem_calls(self):
        for prog in self.manifest["program"]:
            with self.subTest(program=prog["name"]):
                self.assertNotRegex(code_text(ROOT / prog["source"]), SUBSYSTEM_CALL)

    def test_job_dds_match_the_program_select_clauses(self):
        for job in carddemo.JOBS.values():
            with self.subTest(job=job.name):
                source = ROOT / carddemo.program_entry(job.program)["source"]
                self.assertEqual(set(job.dd), assigned_dds(source))

    def test_jcl_supplies_every_dd_the_program_assigns(self):
        for job in carddemo.JOBS.values():
            if not job.jcl:
                continue
            with self.subTest(job=job.name, jcl=job.jcl):
                source = ROOT / carddemo.program_entry(job.program)["source"]
                missing = assigned_dds(source) - jcl_step_dds(ROOT / job.jcl, job.program)
                self.assertEqual(missing, set(), f"{job.jcl} has no DD for {sorted(missing)}")

    def test_sample_data_fits_the_dataset_definitions(self):
        for ds in carddemo.datasets(self.manifest).values():
            with self.subTest(dataset=ds.name):
                if ds.key:
                    self.assertLessEqual(ds.key[0] + ds.key[1], ds.lrecl)
                for off, length, _ in ds.alternates:
                    self.assertLessEqual(off + length, ds.lrecl)
                if ds.source:
                    records = carddemo.read_text_records(carddemo.data_dir() / ds.source, ds.lrecl)
                    self.assertGreater(len(records), 0)
                    if ds.key:
                        keys = [r[ds.key[0]:ds.key[0] + ds.key[1]] for r in records]
                        self.assertEqual(len(keys), len(set(keys)), "duplicate primary keys")

    def test_copybook_record_lengths_match_dataset_lrecl(self):
        pairs = {"ACCTDATA": oracle.ACCOUNT, "CARDDATA": oracle.CARD, "CARDXREF": oracle.XREF,
                 "CUSTDATA": oracle.CUSTOMER, "TCATBALF": oracle.TCATBAL, "DISCGRP": oracle.DISCGRP,
                 "TRANTYPE": oracle.TRANTYPE, "TRANCATG": oracle.TRANCATG,
                 "DALYTRAN": oracle.DALYTRAN, "TRANSACT": oracle.TRAN}
        sets = carddemo.datasets(self.manifest)
        for name, layout in pairs.items():
            with self.subTest(dataset=name):
                self.assertEqual(layout["__LENGTH__"].length, sets[name].lrecl)

    def test_installed_cobc_matches_the_pinned_version(self):
        info = carddemo.check_toolchain(verbose=False)
        self.assertTrue(info["cobc"].startswith(self.manifest["toolchain"]["cobc_version"] + "."))
