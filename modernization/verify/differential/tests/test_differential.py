"""Tests of the differential harness: python3 -m unittest discover -s modernization/verify/differential/tests

No GnuCOBOL build and no database: the GnuCOBOL outputs are written from the
sample data files, and the Spring Batch side is a stand-in only where a test
says so."""
from __future__ import annotations

import contextlib
import dataclasses
import io
import json
import os
import shutil
import stat
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import differential as d  # noqa: E402
import verify_batch as vb  # noqa: E402

SAMPLE = d.REPO / "app" / "data" / "ASCII"


def sample(name: str, lrecl: int) -> list[bytes]:
    return d.carddemo.read_text_records(SAMPLE / name, lrecl)


def tran(i: int, ts: str) -> bytes:
    """A 350-byte transaction record with TRAN-ID i and the timestamp ts where the checks mask it."""
    rec = bytearray(b"0" * 350)
    rec[0:16] = f"{i:016d}".encode()
    rec[278:304] = ts.encode()
    rec[304:330] = ts.encode()
    return bytes(rec)


def write_refs(root: Path) -> dict[str, Path]:
    """POSTTRAN and INTCALC outputs in the layouts their compare.py reads."""
    ts = "2022-07-06-12.00.00.000000"
    post = root / "ref-posttran"
    post.mkdir(parents=True)
    d.harness.write_records(post / "DALYREJS.txt", [])
    d.harness.write_records(post / "ACCTDATA.after.txt", sample("acctdata.txt", 300)[:5])
    d.harness.write_records(post / "TCATBALF.after.txt", sample("tcatbal.txt", 50)[:5])
    d.harness.write_records(post / "TRANSACT.after.txt", [tran(i, ts) for i in range(1, 6)])
    (post / "sysout.txt").write_text("START OF EXECUTION OF PROGRAM CBTRN02C\nTRANSACTIONS PROCESSED :000000005\n"
                                     "TRANSACTIONS REJECTED  :000000000\nEND OF EXECUTION OF PROGRAM CBTRN02C\n")
    (post / "rc.txt").write_text("0\n")
    inter = root / "ref-intcalc"
    inter.mkdir()
    d.harness.write_records(inter / "TRANSACT.txt", [tran(i, ts) for i in range(1, 4)])
    d.harness.write_records(inter / "ACCTDATA.after.txt", sample("acctdata.txt", 300)[:3])
    (inter / "sysout.txt").write_text("START OF EXECUTION OF PROGRAM CBACT04C\nEND OF EXECUTION OF PROGRAM CBACT04C\n")
    (inter / "stderr.txt").write_text("")
    (inter / "rc.txt").write_text("0\n")
    return {"POSTTRAN": post, "INTCALC": inter}


LEGACY = d.Mode("legacy", frozenset())


class Copy(d.Producer):
    """Writes the GnuCOBOL outputs, with the fixed DEF-INTC-010 IDs: what a correct Spring Batch side would."""

    def produce(self, scenario, mode, out):
        shutil.copytree(scenario.ref, out)
        if scenario.job == "INTCALC" and "DEF-INTC-010" in mode.fixed:
            vb.with_ids(out / "TRANSACT.txt", lambda k: [str(9000000000000000 + i) for i in range(k)])
        return d.SpringRun(interest_id_start=d.INTEREST_ID_START, migrated=d.expected_migrated(scenario, mode))


def v2_counts(job_name: str, load: dict, records: int, body: dict, /, **change) -> dict:
    """counts.json schemaVersion 2 in its key order, for one load and ACCTDATA.after.txt."""
    counts = {"schemaVersion": 2, "job": job_name, "cycleId": 3, "attempt": 1,
              **{key: load[report_key] for key, report_key in d.COUNTS_FROM_LOAD}, **body,
              "accountRecords": records, "accountOriginR": load["accountsAsRead"],
              "accountOriginM": load["accountsMigrated"], "accountOriginNone": 0,
              "accountZipGroupRestored": records, "accountZipGroupRebuilt": 0}
    for key, value in change.items():
        if value is None:
            del counts[key]
        else:
            counts[key] = value
    return counts


class DifferentialTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="differential-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    # catalogue -------------------------------------------------------------
    def test_the_required_jobs_are_the_decided_set(self):
        self.assertEqual(set(d.REQUIRED), {"POSTTRAN", "INTCALC", "TRANREPT", "CREASTMT", "CBEXPORT", "CBIMPORT"})

    def test_every_runner_job_is_in_the_catalogue(self):
        self.assertEqual(d.unclassified_runner_jobs(), [])
        for job in d.CATALOGUE:
            self.assertTrue(job.check or job.reason, job.legacy)

    def test_every_required_job_names_an_existing_check_folder(self):
        for job in d.REQUIRED:
            self.assertTrue((d.CHECKS / d.catalogue_job(job).check).is_dir(), job)

    # values from the runner -----------------------------------------------
    def test_the_interest_parm_is_the_runner_s(self):
        self.assertEqual(d.interest_parm(), d.carddemo.find_job("INTCALC").parm)
        _, env = d.spring_settings(LEGACY, ["DEF-INTC-010"], d.interest_parm())
        self.assertEqual(env, {"CARDDEMO_INTEREST_LEGACY_PARM": d.carddemo.find_job("INTCALC").parm})

    def test_a_case_parm_file_wins_as_in_the_reference_run(self):
        (self.tmp / "parm.txt").write_text("2023123100\n")
        self.assertEqual(d.interest_parm(self.tmp), "2023123100")

    def test_every_switch_is_set_explicitly_in_each_mode(self):
        switches = ["DEF-ACUP-002", "DEF-INTC-012"]
        got = {m.label: d.spring_settings(m, switches, "P")[0] for m in d.modes(switches)}
        self.assertEqual(got, {
            "legacy": ["--carddemo.fidelity.DEF-ACUP-002=legacy", "--carddemo.fidelity.DEF-INTC-012=legacy"],
            "fixed DEF-ACUP-002": ["--carddemo.fidelity.DEF-ACUP-002=fixed", "--carddemo.fidelity.DEF-INTC-012=legacy"],
            "fixed DEF-INTC-012": ["--carddemo.fidelity.DEF-ACUP-002=legacy", "--carddemo.fidelity.DEF-INTC-012=fixed"],
            "all fixed": ["--carddemo.fidelity.DEF-ACUP-002=fixed", "--carddemo.fidelity.DEF-INTC-012=fixed"],
        })
        with self.assertRaises(ValueError):
            d.spring_settings(d.Mode("x", frozenset({"DEF-X-001"})), switches, "P")

    def test_the_matrix_is_legacy_each_switch_alone_and_all_fixed(self):
        labels = [m.label for m in d.modes(["A", "B", "C", "D"])]
        self.assertEqual(labels, ["legacy", "fixed A", "fixed B", "fixed C", "fixed D", "all fixed"])
        self.assertEqual([m.label for m in d.modes(["A"])], ["legacy", "fixed A"])

    def test_the_exporter_command_line_is_export_v2(self):
        jar, exp, out = Path("/j/x-exec.jar"), Path("/e"), Path("/o")
        self.assertEqual(d.exporter_command(jar, exp, "POSTTRAN", 7, 5, out),
                         ["java", "-jar", "/j/x-exec.jar", "--export", "/e", "--job", "POSTTRAN", "--cycle-id", "7",
                          "--load-execution-id", "5", "--output-dir", "/o"])
        self.assertEqual(d.exporter_command(jar, exp, "INTCALC", 7, 5)[-2:], ["--load-execution-id", "5"])
        self.assertNotIn("--fidelity", d.exporter_command(jar, exp, "INTCALC", 7, 5, out))
        # Load report contract 1.4: 0 through Long.MAX_VALUE.
        for good in (0, 1, 2 ** 63 - 1):
            with self.subTest(good=good):
                self.assertEqual(d.exporter_command(jar, exp, "INTCALC", 7, good)[-2:],
                                 ["--load-execution-id", str(good)])
        for bad in (-1, 2 ** 63, None, True, False, "5", "0", 5.0):
            with self.subTest(bad=bad), self.assertRaisesRegex(d.PipelineError, "needs the load's execution ID"):
                d.exporter_command(jar, exp, "POSTTRAN", 7, bad, out)
        with self.assertRaises(d.Unsupported):
            d.exporter_command(jar, exp, "TRANREPT", 7, 5)

    def test_every_declared_switch_needs_an_expectation(self):
        self.assertEqual(d.unknown_switches(d.batch_switches()), [])
        self.assertEqual(d.unknown_switches(["DEF-INTC-010", "DEF-XXXX-999"]), ["DEF-XXXX-999"])
        self.assertEqual(d.unknown_switches(["DEF-JPCAT-001"]), [])
        self.assertEqual(d.EFFECTS["DEF-JPCAT-001"] & set(d.REQUIRED), set())

    def test_the_switches_come_from_batch_switches_java(self):
        self.assertIn("DEF-ACUP-002", d.batch_switches())
        src = self.tmp / "BatchSwitches.java"
        src.write_text('X = FidelitySwitch.of("DEF-JINTC-001",\n "a", "b");\nY = FidelitySwitch.of( "DEF-INTC-010", "a", "b");\n')
        self.assertEqual(d.batch_switches(src), ["DEF-JINTC-001", "DEF-INTC-010"])
        with self.assertRaises(d.Unsupported):
            d.batch_switches(self.tmp / "missing.java")
        (self.tmp / "empty.java").write_text("class BatchSwitches {}\n")
        with self.assertRaises(d.Unsupported):
            d.batch_switches(self.tmp / "empty.java")

    # fail closed -----------------------------------------------------------
    def run_verify(self, only=None):
        out = io.StringIO()
        rc = vb.verify("verify-batch", self.tmp / "work", only, out)
        return rc, out.getvalue()

    def test_without_the_spring_side_every_required_job_fails(self):
        rc, out = self.run_verify()
        self.assertEqual(rc, 1, out)
        for job in d.REQUIRED:
            self.assertRegex(out, rf"(?m)^FAIL {job}: .*load report has no readable source", job)
        self.assertRegex(out, r"(?m)^verify-batch: FAILED \(")
        self.assertNotRegex(out, r"verify-batch: (OK|PARTIAL)")

    def test_a_subset_without_the_spring_side_fails(self):
        rc, out = self.run_verify("posttran,INTCALC")
        self.assertEqual(rc, 1, out)
        self.assertNotIn("TRANREPT", out)

    def test_an_unknown_job_in_only_is_refused(self):
        with self.assertRaises(SystemExit) as e:
            vb.selected("POSTTRAN,PRTCATBL")
        self.assertIn("PRTCATBL", str(e.exception.code))
        with self.assertRaises(SystemExit):
            vb.selected(" , ")

    # plan ------------------------------------------------------------------
    def run_plan(self, github=False):
        out = io.StringIO()
        if github:
            env = {"GITHUB_OUTPUT": str(self.tmp / "output"), "GITHUB_STEP_SUMMARY": str(self.tmp / "summary")}
            with mock.patch.dict(os.environ, env):
                vb.github_plan("batch", "verify-batch", out)
            return out.getvalue(), (self.tmp / "output").read_text(), (self.tmp / "summary").read_text()
        ready, blocked = vb.plan("verify-batch", out)
        return ready, blocked, out.getvalue()

    def producers_for(self, *jobs):
        real = d.producer_gaps
        return mock.patch.object(d, "producer_gaps", lambda job: [] if job in jobs else real(job))

    def test_the_plan_skips_while_no_job_has_a_producer(self):
        ready, blocked, out = self.run_plan()
        self.assertFalse(ready)
        self.assertEqual(blocked, list(d.REQUIRED))
        for job in d.REQUIRED:
            self.assertRegex(out, rf"(?m)^missing  {job}: .*load report has no readable source")
        self.assertTrue(out.rstrip().endswith(f"verify-batch plan: SKIPPED (not ready: {', '.join(d.REQUIRED)})"), out)
        self.assertNotRegex(out, r"READY|OK")
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(vb.main(["--plan"]), vb.NOT_READY_EXIT)

    def test_the_plan_skips_when_one_job_lacks_its_producer(self):
        with self.producers_for(*[j for j in d.REQUIRED if j != "CBIMPORT"]):
            ready, blocked, out = self.run_plan()
        self.assertFalse(ready)
        self.assertEqual(blocked, ["CBIMPORT"])
        self.assertRegex(out, r"(?m)^ready    POSTTRAN$")
        self.assertTrue(out.rstrip().endswith("verify-batch plan: SKIPPED (not ready: CBIMPORT)"), out)

    def test_the_plan_is_ready_only_with_every_producer(self):
        with self.producers_for(*d.REQUIRED):
            ready, blocked, out = self.run_plan()
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(vb.main(["--plan"]), 0)
        self.assertTrue(ready)
        self.assertEqual(blocked, [])
        self.assertTrue(out.rstrip().endswith(f"verify-batch plan: READY ({', '.join(d.REQUIRED)})"), out)

    def test_the_plan_skips_a_new_required_job_without_a_reference_or_producer(self):
        new = d.CatalogueJob("PRTCATBL", ("printCategoryBalancesJob",), "prtcatbl_reimpl")
        with self.producers_for(*d.REQUIRED), \
                mock.patch.object(d, "CATALOGUE", d.CATALOGUE + (new,)), \
                mock.patch.object(d, "REQUIRED", d.REQUIRED + ("PRTCATBL",)):
            ready, blocked, out = self.run_plan()
        self.assertFalse(ready)
        self.assertEqual(blocked, ["PRTCATBL"])
        self.assertRegex(out, r"(?m)^missing  PRTCATBL: no GnuCOBOL scenario for checks/prtcatbl_reimpl")

    def test_the_plan_skips_a_runner_job_the_catalogue_does_not_classify(self):
        with mock.patch.dict(d.carddemo.JOBS, {"PRTCATBL": d.carddemo.JOBS["POSTTRAN"]}), \
                self.producers_for(*d.REQUIRED):
            ready, blocked, out = self.run_plan()
        self.assertFalse(ready)
        self.assertEqual(blocked, ["PRTCATBL"])
        self.assertRegex(out, r"(?m)^missing  PRTCATBL: a tools/carddemo.py job that differential.CATALOGUE")

    def test_the_github_plan_sets_false_with_a_notice_and_never_shows_a_pass(self):
        out, output, summary = self.run_plan(github=True)
        self.assertEqual(output, "batch=false\n")
        self.assertEqual(summary, f"| `batch` | `verify-batch` | skipped: not ready: {', '.join(d.REQUIRED)} "
                                  f"(the plan step says what each lacks) |\n")
        self.assertRegex(out, r"(?m)^::notice title=batch skipped::verify-batch SKIPPED, not ready: POSTTRAN, ")
        (self.tmp / "output").unlink()
        (self.tmp / "summary").unlink()
        with self.producers_for(*d.REQUIRED):
            out, output, summary = self.run_plan(github=True)
        self.assertEqual(output, "batch=true\n")
        self.assertEqual(summary, "| `batch` | `verify-batch` | yes |\n")
        self.assertNotIn("::notice", out)
    def test_missing_switches_fail_every_job(self):
        with mock.patch.object(d, "BATCH_SWITCHES", self.tmp / "missing.java"), \
                mock.patch.object(d.batch_switches, "__defaults__", (self.tmp / "missing.java",)):
            rc, out = self.run_verify("POSTTRAN")
        self.assertEqual(rc, 1, out)
        self.assertIn("FAIL batch-service switches", out)

    def with_stand_in(self, switches=("DEF-INTC-010",), producer=Copy):
        refs = write_refs(self.tmp / "refs")

        def reference(job):
            def make(root, case):
                ref = root / "ref"
                shutil.copytree(refs[job if job in refs else "POSTTRAN"], ref)
                return [d.Scenario(job, case, root, ref)]
            return make

        def compare(job, ref, new):
            if job in refs:
                return _real(job, ref, new)
            return (not vb.differs(ref, new)), ""

        _real = d.compare
        return [
            mock.patch.object(d, "spring_producer", lambda job, *a: producer()),
            mock.patch.dict(d.REFERENCES, {job: reference(job) for job in d.REQUIRED}),
            mock.patch.object(d, "batch_switches", lambda *a: list(switches)),
            mock.patch.object(d, "compare", compare),
        ]

    def test_a_full_match_is_ok_and_a_subset_match_is_only_partial(self):
        patches = self.with_stand_in()
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        rc, out = self.run_verify()
        self.assertEqual(rc, 0, out)
        self.assertTrue(out.rstrip().endswith("verify-batch: OK"), out)
        for job in d.REQUIRED:
            for case in d.CASES:
                for mode in ("legacy", "fixed DEF-INTC-010"):
                    self.assertRegex(out, rf"(?m)^ok +{job} {case} {mode}$")
        shutil.rmtree(self.tmp / "work")
        rc, out = self.run_verify("INTCALC")
        self.assertEqual(rc, vb.PARTIAL_EXIT, out)
        self.assertRegex(out, r"(?m)^verify-batch: PARTIAL \(ONLY=INTCALC matched; not the full required set")
        self.assertNotIn("verify-batch: OK", out)

    def test_a_switch_without_expectation_fails_every_job(self):
        patches = self.with_stand_in(switches=("DEF-INTC-010", "DEF-XXXX-999"))
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        rc, out = self.run_verify("POSTTRAN")
        self.assertEqual(rc, 1, out)
        self.assertIn("FAIL no expectation in differential.EFFECTS for DEF-XXXX-999", out)

    def test_a_legacy_difference_fails_the_run(self):
        class OneByte(Copy):
            def produce(self, scenario, mode, out):
                run = super().produce(scenario, mode, out)
                vb.edit(out / "ACCTDATA.after.txt", lambda r: [vb.replace_at(r[0], 1, vb.other_digit(r[0][1]))] + r[1:])
                return run
        patches = self.with_stand_in(producer=OneByte)
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        rc, out = self.run_verify("POSTTRAN")
        self.assertEqual(rc, 1, out)
        self.assertRegex(out, r"(?m)^FAIL POSTTRAN sample legacy$")
        self.assertRegex(out, r"(?m)^ +FAIL ACCTDATA\.after\.txt ")

    # fixed-mode expectations ---------------------------------------------
    def interest_case(self):
        """Two accounts and three category balances with a 12.00 % rate, written by hand:
        +100.00 earns 1.00, -50.00 earns -0.50, -0.01 earns -0.00 (truncated)."""
        data = self.tmp / "data"
        data.mkdir()
        acct = lambda i, bal: (f"{i:011d}Y" + bal + "0" * 78 + " " * 10 + "A000000000").ljust(300)
        (data / "acctdata.txt").write_text(acct(1, "00000100000{") + "\n" + acct(2, "00000000050{") + "\n")
        (data / "tcatbal.txt").write_text("".join(s.ljust(50) + "\n" for s in (
            "00000000001010001" + "0000001000{", "00000000001010002" + "0000000500}",
            "00000000002010001" + "0000000000J")))
        (data / "discgrp.txt").write_text("".join(s.ljust(50) + "\n" for s in (
            "A000000000010001" + "00120{", "A000000000010002" + "00120{")))
        return data

    @staticmethod
    def interest(tid: str, acct: int, amount: str) -> str:
        ts = "2022-07-06-12.00.00.000000"
        return (tid + "01" + "0005" + "System".ljust(10) + f"Int. for a/c {acct:011d}".ljust(100) + amount
                + "0" * 9 + " " * 110 + f"{acct:016d}" + ts + ts + " " * 20)

    def interest_dir(self, name, trans, balances):
        out = self.tmp / name
        out.mkdir()
        acct = lambda i, bal: (f"{i:011d}Y" + bal + "0" * 78 + " " * 10 + "A000000000").ljust(300)
        (out / "TRANSACT.txt").write_text("".join(x + "\n" for x in trans))
        (out / "ACCTDATA.after.txt").write_text("".join(acct(i + 1, b) + "\n" for i, b in enumerate(balances)))
        (out / "sysout.txt").write_text("START OF EXECUTION OF PROGRAM CBACT04C\nEND OF EXECUTION OF PROGRAM CBACT04C\n")
        (out / "stderr.txt").write_text("")
        (out / "rc.txt").write_text("0\n")
        return out

    def judge(self, fixed, new, data, ref, run=d.SpringRun(interest_id_start=d.INTEREST_ID_START), manifest=None):
        s = d.Scenario("INTCALC", "t", data, ref, manifest)
        mode = d.Mode("t", frozenset(fixed))
        work = Path(tempfile.mkdtemp(prefix=f"work-{new.name}-", dir=self.tmp))
        try:
            migrated = d.expected_migrated(s, mode)
        except d.ManifestError:
            migrated = 0
        return vb.judge("INTCALC", mode, s, new, work, dataclasses.replace(run, migrated=migrated))

    def test_intc_012_drops_by_input_sign_and_renumbers_the_legacy_ids(self):
        data = self.interest_case()
        ref = self.interest_dir("ref", [self.interest("2022071800000001", 1, "0000000010{"),
                                        self.interest("2022071800000002", 1, "0000000005}"),
                                        self.interest("2022071800000003", 2, "0000000000}")],
                                ["00000100005{", "00000000050{"])
        new = self.interest_dir("new", [self.interest("2022071800000001", 1, "0000000010{")],
                                ["00000100010{", "00000000050{"])
        ok, lines = self.judge({"DEF-INTC-012"}, new, data, ref)
        self.assertTrue(ok, lines)
        self.assertFalse(self.judge({"DEF-INTC-012"}, ref, data, ref)[0])
        self.assertFalse(self.judge(set(), new, data, ref)[0])
        unadjusted = self.interest_dir("unadjusted", [self.interest("2022071800000001", 1, "0000000010{")],
                                       ["00000100005{", "00000000050{"])
        self.assertFalse(self.judge({"DEF-INTC-012"}, unadjusted, data, ref)[0])

    def test_intc_012_with_intc_010_takes_the_new_ids_only_if_consecutive_from_the_9_range(self):
        data = self.interest_case()
        ref = self.interest_dir("ref", [self.interest("2022071800000001", 1, "0000000010{"),
                                        self.interest("2022071800000002", 1, "0000000005}"),
                                        self.interest("2022071800000003", 2, "0000000000}")],
                                ["00000100005{", "00000000050{"])
        new = self.interest_dir("new", [self.interest("9000000000000000", 1, "0000000010{")],
                                ["00000100010{", "00000000050{"])
        self.assertTrue(self.judge({"DEF-INTC-012", "DEF-INTC-010", "DEF-JINTC-001"}, new, data, ref)[0])
        later = self.interest_dir("later", [self.interest("9000000000000417", 1, "0000000010{")],
                                  ["00000100010{", "00000000050{"])
        ok, lines = self.judge({"DEF-INTC-012", "DEF-INTC-010"}, later, data, ref)
        self.assertFalse(ok)
        self.assertIn("the sequence starts at 9000000000000000", lines)
        self.assertTrue(self.judge({"DEF-INTC-012", "DEF-INTC-010"}, later, data, ref,
                                   d.SpringRun(interest_id_start=9000000000000417))[0])
        low = self.interest_dir("low", [self.interest("8999999999999999", 1, "0000000010{")],
                                ["00000100010{", "00000000050{"])
        self.assertFalse(self.judge({"DEF-INTC-012", "DEF-INTC-010"}, low, data, ref)[0])

    def test_input_that_does_not_line_up_with_the_reference_fails(self):
        data = self.interest_case()
        ref = self.interest_dir("ref", [self.interest("2022071800000001", 1, "0000000010{"),
                                        self.interest("2022071800000002", 1, "0000000099}"),
                                        self.interest("2022071800000003", 2, "0000000000}")],
                                ["00000100050{", "00000000050{"])
        ok, lines = self.judge({"DEF-INTC-012"}, ref, data, ref)
        self.assertFalse(ok)
        self.assertIn("does not match balance", lines)

    def test_jintc_001_prefix_is_the_cycle_interest_parm(self):
        self.assertEqual(d.interest_parm_of_cycle(), d.carddemo.DEFAULT_BUSINESS_DATE.replace("-", "") + "00")
        data = self.interest_case()
        ref = self.interest_dir("ref", [self.interest("2022071800000001", 1, "0000000010{")], ["00000100050{"])
        new = self.interest_dir("new", [self.interest(d.interest_parm_of_cycle() + "000001", 1, "0000000010{")],
                                ["00000100050{"])
        self.assertTrue(self.judge({"DEF-JINTC-001"}, new, data, ref)[0])
        self.assertFalse(self.judge({"DEF-JINTC-001"}, ref, data, ref)[0])

    def test_no_switch_changes_posttran(self):
        refs = write_refs(self.tmp / "refs")
        for item in d.EFFECTS:
            m = d.Mode("t", frozenset({item}))
            self.assertEqual(d.expected("POSTTRAN", m, self.tmp, refs["POSTTRAN"], refs["POSTTRAN"], self.tmp / "x",
                                        manifest=None), refs["POSTTRAN"])

    def test_the_report_switches_change_only_tranrept_and_have_no_rule_yet(self):
        refs = write_refs(self.tmp / "refs")
        for item in ("DEF-TRPT-001", "DEF-TRPT-004"):
            self.assertEqual(d.EFFECTS[item], {"TRANREPT"})
            with self.subTest(item=item), self.assertRaisesRegex(d.ExpectationError, "TRANREPT: no expectation"):
                d.expected("TRANREPT", d.Mode("t", frozenset({item})), self.tmp, refs["POSTTRAN"], refs["POSTTRAN"],
                           self.tmp / "x", manifest=None)
        self.assertEqual(d.expected("TRANREPT", d.Mode("legacy", frozenset()), self.tmp, refs["POSTTRAN"],
                                    refs["POSTTRAN"], self.tmp / "x", manifest=None), refs["POSTTRAN"])

    def test_the_statement_switch_changes_only_creastmt_and_has_no_rule_yet(self):
        refs = write_refs(self.tmp / "refs")
        self.assertEqual(d.EFFECTS["DEF-STMT-005"], {"CREASTMT"})
        with self.assertRaisesRegex(d.ExpectationError, "CREASTMT: no expectation"):
            d.expected("CREASTMT", d.Mode("t", frozenset({"DEF-STMT-005"})), self.tmp, refs["POSTTRAN"],
                       refs["POSTTRAN"], self.tmp / "x", manifest=None)
        self.assertEqual(d.expected("CREASTMT", d.Mode("legacy", frozenset()), self.tmp, refs["POSTTRAN"],
                                    refs["POSTTRAN"], self.tmp / "x", manifest=None), refs["POSTTRAN"])

    # DEF-ACUP-002 (ruling v4) ----------------------------------------------
    @staticmethod
    def account(i: int, bal: str = "00000100000{", zip_: str = " " * 10, grp: str = " " * 10) -> str:
        return (f"{i:011d}Y" + bal + "0" * 78 + zip_ + grp).ljust(300)

    def accounts_file(self, name: str, records: list[str]) -> Path:
        path = self.tmp / name / "acctdata.txt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes("".join(r + "\n" for r in records).encode("latin-1"))
        return path

    def manifest(self, acctdata: Path, other=(), layout=d.ACUP_UPDATE, digest=None) -> Path:
        path = self.tmp / f"manifest-{len(list(self.tmp.glob('manifest-*')))}.toml"
        path.write_text(d.LayoutManifest(digest or d.sha256(acctdata), layout, tuple(other)).to_toml())
        return path

    def load(self, acctdata: Path, manifest: Path | None):
        return d.load_accounts(acctdata, d.applied_manifest(acctdata, manifest))

    def test_e1_e12_the_built_in_entry_is_the_ascii_sample_s_content(self):
        got = self.load(SAMPLE / "acctdata.txt", None)
        self.assertEqual(got.migrated, 50)
        self.assertEqual({(r[102:112], r[112:122]) for r in got.records}, {(" " * 10, "A000000000")})
        copy = self.tmp / "elsewhere" / "acctdata.txt"
        copy.parent.mkdir()
        shutil.copy(SAMPLE / "acctdata.txt", copy)
        self.assertEqual(self.load(copy, None).migrated, 50)

    def test_e2_one_byte_changed_loads_as_read_and_warns_by_line(self):
        recs = [r.decode("latin-1") for r in sample("acctdata.txt", 300)]
        path = self.accounts_file("e2", [recs[0][:200] + "X" + recs[0][201:]] + recs[1:])
        got = self.load(path, None)
        self.assertEqual(got.migrated, 0)
        self.assertEqual(got.records, [r.ljust(300) for r in d.harness.read_records(path)])
        self.assertEqual(got.ambiguous_lines, list(range(1, 51)))

    def test_e17_the_ebcdic_copy_has_no_built_in_entry(self):
        ebcdic = d.REPO / "app" / "data" / "EBCDIC" / "AWS.M2.CARDDEMO.ACCTDATA.PS"
        self.assertTrue(ebcdic.exists())
        self.assertIsNone(d.applied_manifest(ebcdic, None))
        self.assertEqual(d.BUILTIN_SAMPLE, SAMPLE / "acctdata.txt")

    def test_e6_a_supplied_manifest_must_name_the_file_s_hash(self):
        path = self.accounts_file("e6", [self.account(1, zip_="A000000000")])
        with self.assertRaisesRegex(d.ManifestError, "^layout manifest does not match acctdata.txt$"):
            self.load(path, self.manifest(path, digest="0" * 64))

    def test_e11_manifest_entries_are_refused_by_position_never_by_account(self):
        path = self.accounts_file("e11", [self.account(1, zip_="A000000000"), self.account(2, zip_="A000000000")])
        for other, message in ((["123"], "override 1: not 11 digits"), (["0000000000\u0661"], "override 1: not 11 digits"),
                               (["00000000001", "00000000001"], "override 2: listed twice"),
                               (["00000000002", "00000000099"], "override 2: not in acctdata.txt")):
            with self.subTest(other=other), self.assertRaises(d.ManifestError) as e:
                self.load(path, self.manifest(path, other))
            self.assertIn(message, str(e.exception))
            self.assertNotRegex(str(e.exception), r"\d{11}")
        bad = self.tmp / "bad.toml"
        bad.write_text(f'schema = 1\nsha256 = "{d.sha256(path)}"\ndefault_layout = "SHIFTED"\n')
        with self.assertRaisesRegex(d.ManifestError, "^layout manifest line 3: default_layout must be CVACT01Y or "
                                                     "ACUP_UPDATE$"):
            self.load(path, bad)
        bad.write_text("sha256 = \n")
        with self.assertRaisesRegex(d.ManifestError, "^layout manifest line 1: "):
            self.load(path, bad)

    def test_declared_and_undeclared_records_follow_the_ruling_s_cases(self):
        sp, nul = " " * 10, "\0" * 10
        cases = [  # (103, 113, declared, migrated group or None when as read, ambiguous)
            ("A000000000", nul, True, "A000000000", False),          # E7
            (nul, sp, True, "", False),                              # E8
            ("A0000\0    ", sp, True, "A0000\0", False),              # E14
            ("27601     ", sp, False, None, True),                   # E9
            ("A000000000", "NOSUCHGRP ", False, None, False),        # E10
            ("A000000000", "     \0\0\0\0\0", False, None, False),   # E16
        ]
        recs = [self.account(i + 1, zip_=z, grp=g) for i, (z, g, *_rest) in enumerate(cases)]
        path = self.accounts_file("cases", recs)
        other = [f"{i + 1:011d}" for i, c in enumerate(cases) if not c[2]]
        got = self.load(path, self.manifest(path, other))
        self.assertEqual(got.migrated, 3)
        self.assertEqual(got.ambiguous_lines, [4])
        for rec, before, (z, g, declared, group, _) in zip(got.records, recs, cases):
            if group is None:
                self.assertEqual(rec, before)
            else:
                self.assertEqual(rec, before[:102] + " " * 10 + group.ljust(10) + before[122:])
        self.assertEqual(self.load(path, None).records, recs)   # undeclared (E4): every record as read

    def test_e5_e15_a_declared_record_with_data_at_113_refuses_the_whole_load(self):
        for grp in ("ZEROAPR   ", "     \0\0\0\0\0", " " * 9 + "\0"):
            with self.subTest(grp=grp):
                path = self.accounts_file(f"e5-{len(list(self.tmp.iterdir()))}",
                                          [self.account(1, zip_="A000000000"), self.account(2, zip_="A000000000", grp=grp)])
                with self.assertRaisesRegex(d.ManifestError, "^acctdata.txt line 2: declared ACUP_UPDATE but "
                                                             "positions 113-122 are not blank$"):
                    self.load(path, self.manifest(path))

    def test_a_manifest_round_trips_and_goes_to_the_data_load_property(self):
        m = d.LayoutManifest("ab" * 32, d.ACUP_UPDATE, ("00000000001", "00000000007"))
        self.assertEqual(d.LayoutManifest.parse(m.to_toml()), m)
        self.assertEqual(m.to_toml(), f'schema = 1\nsha256 = "{"ab" * 32}"\ndefault_layout = "ACUP_UPDATE"\n'
                                      'other_layout_accounts = ["00000000001", "00000000007"]\n')
        self.assertEqual(d.LayoutManifest.parse(f'schema = 1\nsha256 = "{"ab" * 32}"\ndefault_layout = "CVACT01Y"\n').other,
                         ())
        path = self.tmp / "m.toml"
        self.assertEqual(d.layout_manifest_args(path),
                         [f"--carddemo.data-load.account-layout-manifest={path.resolve()}"])
        self.assertEqual(d.layout_manifest_args(None), [])

    def test_the_manifest_hash_is_taken_after_the_file_is_written(self):
        path = self.accounts_file("w", [self.account(1, zip_="A000000000")])
        m = d.write_manifest(path, self.tmp / "out" / "m.toml", ("00000000001",), d.CVACT01Y)
        self.assertEqual(d.LayoutManifest.parse(m.read_text()),
                         d.LayoutManifest(d.sha256(path), d.CVACT01Y, ("00000000001",)))

    def test_the_stress_overrides_come_from_the_generator_s_construction(self):
        self.assertEqual(d.stress_overrides("INTCALC"), tuple(f"{n:011d}" for n in (1, 2, 3, 5, 6, 7)))
        self.assertEqual(d.stress_overrides("POSTTRAN"), ())
        gen = self.tmp / "gen"
        gen.mkdir()
        for body in ("x = 1\n", "groups = {}\ngroups = {1: 'A'}\n", "groups = {'1': 'A'}\n"):
            with self.subTest(body=body):
                (gen / "gen_stress.py").write_text(body)
                with mock.patch.dict(d.STRESS_GROUPS, {"INTCALC": (str(gen), "groups")}), \
                        self.assertRaises(d.Unsupported):
                    d.stress_overrides("INTCALC")

    def test_the_load_report_s_migrated_count_is_checked(self):
        path = self.accounts_file("load", [self.account(1, zip_="A000000000"), self.account(2, zip_="A000000000")])
        s = d.Scenario("INTCALC", "t", path.parent, self.tmp, self.manifest(path, ["00000000002"]))
        acup = d.Mode("t", frozenset({d.ACUP_002}))
        d.check_load(s, acup, d.SpringRun(migrated=1))
        d.check_load(s, LEGACY, d.SpringRun(migrated=0))
        for mode, run, text in ((acup, d.SpringRun(), "did not report"), (acup, d.SpringRun(migrated=2), "migrated 2"),
                                (LEGACY, d.SpringRun(migrated=1), "migrated 1 records, expected 0")):
            with self.subTest(text=text), self.assertRaisesRegex(d.ExpectationError, text):
                d.check_load(s, mode, run)
        refused = d.Scenario("INTCALC", "t", path.parent, self.tmp, self.manifest(path, digest="1" * 64))
        with self.assertRaisesRegex(d.ExpectationError, "must fail: layout manifest does not match"):
            d.check_load(refused, acup, d.SpringRun(migrated=0))

    def acup_rerun(self, rerun_dir):
        """run_intcalc_reference replaced by a copy of rerun_dir; records the input it got."""
        seen = {}

        def fake(data, out):
            seen["acctdata"] = (data / "acctdata.txt").read_text().splitlines()
            shutil.copytree(rerun_dir, out)
        return mock.patch.object(d, "run_intcalc_reference", fake), seen

    def acup_case(self, groups=(" " * 10, " " * 10)):
        """Two accounts with the group at 103 (as the sample) and 113 as given; REF's run took DEFAULT's rates."""
        data = self.interest_case()
        recs = [self.account(i, bal, "A000000000", g) for i, bal, g in ((1, "00000100000{", groups[0]),
                                                                        (2, "00000000050{", groups[1]))]
        (data / "acctdata.txt").write_text("".join(r + "\n" for r in recs))
        ref = self.interest_dir("ref", [self.interest("2022071800000001", 1, "0000000010{")], [])
        d.harness.write_records(ref / "ACCTDATA.after.txt", [self.account(1, "00000100010{", "A000000000", groups[0]).encode(),
                                                             self.account(2, "00000000050{", "A000000000", groups[1]).encode()])
        return data, ref

    def test_acup_002_reruns_cobol_on_the_declared_records_and_keeps_the_exported_layout(self):
        data, ref = self.acup_case()
        # The rerun (rates of A000000000): one more record, other amounts; its ACCTDATA as the load stored it.
        rerun = self.interest_dir("rerun", [self.interest("2022071800000001", 1, "0000000020{"),
                                            self.interest("2022071800000002", 2, "0000000001{")], [])
        d.harness.write_records(rerun / "ACCTDATA.after.txt",
                                [self.account(1, "00000100020{", " " * 10, "A000000000").encode(),
                                 self.account(2, "00000000051{", " " * 10, "A000000000").encode()])
        patch, seen = self.acup_rerun(rerun)

        def outputs(name, zip_, grp):
            out = self.interest_dir(name, [self.interest("2022071800000001", 1, "0000000020{"),
                                           self.interest("2022071800000002", 2, "0000000001{")], [])
            d.harness.write_records(out / "ACCTDATA.after.txt", [self.account(1, "00000100020{", zip_, grp).encode(),
                                                                 self.account(2, "00000000051{", zip_, grp).encode()])
            return out
        good, zip_moved = outputs("good", "A000000000", " " * 10), outputs("zip_moved", " " * 10, "A000000000")
        manifest = self.manifest(data / "acctdata.txt")
        with patch:
            ok, lines = self.judge({"DEF-ACUP-002"}, good, data, ref, manifest=manifest)
            self.assertTrue(ok, lines)
            self.assertEqual([(r[102:112], r[112:122]) for r in seen["acctdata"]], [(" " * 10, "A000000000")] * 2)
            self.assertFalse(self.judge({"DEF-ACUP-002"}, zip_moved, data, ref, manifest=manifest)[0])
            self.assertFalse(self.judge({"DEF-ACUP-002"}, ref, data, ref, manifest=manifest)[0])
            # Undeclared (E4): no rerun, legacy's outputs are expected.
            seen.clear()
            self.assertTrue(self.judge({"DEF-ACUP-002"}, ref, data, ref, manifest=None)[0])
            self.assertFalse(self.judge({"DEF-ACUP-002"}, good, data, ref, manifest=None)[0])
            self.assertEqual(seen, {})
            # Both accounts declared CVACT01Y: nothing is migrated, legacy's outputs again.
            other = self.manifest(data / "acctdata.txt", layout=d.CVACT01Y)
            self.assertTrue(self.judge({"DEF-ACUP-002"}, ref, data, ref, manifest=other)[0])
            self.assertEqual(seen, {})
        self.assertFalse(self.judge(set(), good, data, ref, manifest=manifest)[0])

    def test_acup_002_only_the_declared_records_take_their_group_from_103(self):
        data, ref = self.acup_case((" " * 10, "ZEROAPR   "))
        rerun = self.interest_dir("rerun", [self.interest("2022071800000001", 1, "0000000020{")], [])
        d.harness.write_records(rerun / "ACCTDATA.after.txt",
                                [self.account(1, "00000100020{", " " * 10, "A000000000").encode(),
                                 self.account(2, "00000000050{", "A000000000", "ZEROAPR   ").encode()])
        patch, seen = self.acup_rerun(rerun)
        good = self.interest_dir("good", [self.interest("2022071800000001", 1, "0000000020{")], [])
        d.harness.write_records(good / "ACCTDATA.after.txt",
                                [self.account(1, "00000100020{", "A000000000", " " * 10).encode(),
                                 self.account(2, "00000000050{", "A000000000", "ZEROAPR   ").encode()])
        with patch:
            ok, lines = self.judge({"DEF-ACUP-002"}, good, data, ref,
                                   manifest=self.manifest(data / "acctdata.txt", ["00000000002"]))
            self.assertTrue(ok, lines)
            self.assertEqual([(r[102:112], r[112:122]) for r in seen["acctdata"]],
                             [(" " * 10, "A000000000"), ("A000000000", "ZEROAPR   ")])
            # Declaring account 2 too is the declared-layout mismatch the load refuses.
            ok, lines = self.judge({"DEF-ACUP-002"}, good, data, ref, manifest=self.manifest(data / "acctdata.txt"))
        self.assertFalse(ok)
        self.assertIn("line 2: declared ACUP_UPDATE but positions 113-122 are not blank", lines)

    def test_acup_002_with_intc_010_takes_the_rerun_s_records_with_new_ids(self):
        data, _ = self.acup_case()
        ref = self.interest_dir("ref-ids", [self.interest("2022071800000001", 1, "0000000010{")],
                                ["00000100010{", "00000000050{"])
        rerun = self.interest_dir("rerun", [self.interest("2022071800000001", 1, "0000000010{"),
                                            self.interest("2022071800000002", 2, "0000000001{")],
                                  ["00000100010{", "00000000051{"])
        new = self.interest_dir("new", [self.interest("9000000000000005", 1, "0000000010{"),
                                        self.interest("9000000000000006", 2, "0000000001{")],
                                ["00000100010{", "00000000051{"])
        d.harness.write_records(new / "ACCTDATA.after.txt",
                                [self.account(1, "00000100010{", "A000000000").encode(),
                                 self.account(2, "00000000051{", "A000000000").encode()])
        patch, _ = self.acup_rerun(rerun)
        manifest = self.manifest(data / "acctdata.txt")
        with patch:
            run = d.SpringRun(interest_id_start=9000000000000005)
            ok, lines = self.judge({"DEF-ACUP-002", "DEF-INTC-010"}, new, data, ref, run, manifest=manifest)
            self.assertTrue(ok, lines)
            # Never a constant in its place: a run that read no sequence start cannot pass.
            ok, lines = self.judge({"DEF-ACUP-002", "DEF-INTC-010"}, new, data, ref, d.SpringRun(), manifest=manifest)
            self.assertFalse(ok)
            self.assertIn("did not read the interest sequence's start", lines)
            self.assertFalse(self.judge({"DEF-INTC-010"}, new, data, ref, run, manifest=manifest)[0])

    def test_rerun_accounts_must_be_ref_s(self):
        data, ref = self.acup_case()
        rerun = self.interest_dir("rerun", [self.interest("2022071800000001", 1, "0000000010{")], ["00000100010{"])
        patch, _ = self.acup_rerun(rerun)
        with patch:
            ok, lines = self.judge({"DEF-ACUP-002"}, ref, data, ref, manifest=self.manifest(data / "acctdata.txt"))
        self.assertFalse(ok)
        self.assertIn("the rerun's accounts differ", lines)

    def test_the_acup_measurements_must_be_the_ruling_s(self):
        good = vb.ACUP_RULING["stress"] | {"layout kept": True, "undeclared is legacy": True}
        self.assertEqual(vb.acup_measurement_failures("stress", good), [])
        for change in ({"fixed": 140}, {"changed": [2, 3, 4, 5, 8, 9, 10]}, {"migrated": 50},
                       {"layout kept": False}, {"undeclared is legacy": False}, {"undeclared warnings": 0}):
            with self.subTest(change=change):
                self.assertEqual(len(vb.acup_measurement_failures("stress", good | change)), 1)
        out = io.StringIO()
        with mock.patch.object(vb, "measure_acup", lambda s, work: good | {"fixed": 140}):
            bad = vb.check_acup({"stress": None}, self.tmp, out)
        self.assertEqual(bad, ["INTCALC stress oracle fixed DEF-ACUP-002"])
        self.assertIn("FAIL INTCALC stress oracle fixed DEF-ACUP-002: fixed is 140, the ruling gives 128", out.getvalue())
        out = io.StringIO()
        with mock.patch.object(vb, "measure_acup", lambda s, work: good):
            self.assertEqual(vb.check_acup({"stress": None}, self.tmp, out), [])
        self.assertIn("ok   INTCALC stress oracle fixed DEF-ACUP-002: 128 records (legacy 124), 44 migrated, "
                      "accounts changed: 4, 8, 9, 10", out.getvalue())

    def test_the_ebcdic_accounts_convert_to_the_ascii_sample_but_account_49(self):
        """E17: cp037, one 300-byte record per line; only account 49 (ZEROAPR at 103) differs."""
        data = self.tmp / "ebcdic"
        data.mkdir()
        d.ebcdic_accounts(data / "acctdata.txt")
        got = d.d1_harness.split_lines(data / "acctdata.txt")
        want = d.d1_harness.split_lines(d.BUILTIN_SAMPLE)
        self.assertEqual(len(got), 50)
        self.assertTrue(all(len(r) == d.ACCOUNT_LRECL for r in got))
        self.assertEqual([i for i, (a, b) in enumerate(zip(got, want), start=1) if a != b], [49])
        self.assertEqual(got[48][102:112].rstrip(), "ZEROAPR")
        self.assertNotEqual(d.sha256(data / "acctdata.txt"), d.BUILTIN_SHA256)
        s = d.Scenario("POSTTRAN", "sample/ebcdic", data, data, None, acup_fixed_only=True)
        acup = d.Mode("fixed DEF-ACUP-002", frozenset({d.ACUP_002}))
        self.assertEqual((d.expected_migrated(s, acup), d.expected_warnings(s, acup)), (0, 50))
        self.assertEqual((d.expected_migrated(s, LEGACY), d.expected_warnings(s, LEGACY)), (0, 0))
        self.assertEqual(d.pipeline(s, acup, [d.ACUP_002]).layout_source, "none")

    def test_a_case_of_the_acup_ruling_runs_only_where_def_acup_002_is_fixed(self):
        seen = []

        class Record(d.Producer):
            def produce(self, scenario, mode, out):
                seen.append((scenario.case, mode.label))
                raise d.ExpectationError("recorded")
        refs = write_refs(self.tmp / "refs")

        def make(root, case):
            root.mkdir(parents=True)
            d.harness.write_records(root / "acctdata.txt", sample("acctdata.txt", 300)[:5])
            return [d.Scenario("INTCALC", case, root, refs["INTCALC"]),
                    d.Scenario("INTCALC", f"{case}/undeclared", root, refs["INTCALC"], acup_fixed_only=True)]
        with mock.patch.object(d, "spring_producer", lambda *a: Record()), \
                mock.patch.dict(d.REFERENCES, {"INTCALC": make}):
            vb.run_job("INTCALC", self.tmp / "w", [d.ACUP_002, "DEF-INTC-010"], io.StringIO())
        modes = ["legacy", "fixed DEF-ACUP-002", "fixed DEF-INTC-010", "all fixed"]
        want = []
        for c in d.CASES:
            for m in modes:
                want.append((c, m))
                if m in ("fixed DEF-ACUP-002", "all fixed"):
                    want.append((f"{c}/undeclared", m))
        self.assertEqual(seen, want)
    def test_jintc_001_takes_the_cycle_s_interest_parm(self):
        data = self.interest_case()
        ref = self.interest_dir("ref", [self.interest("2022071800000001", 1, "0000000010{")], ["00000100010{"])
        new = self.interest_dir("new", [self.interest("2022070700000001", 1, "0000000010{")], ["00000100010{"])
        s = d.Scenario("INTCALC", "t", data, ref)
        m = d.Mode("t", frozenset({"DEF-JINTC-001"}))
        self.assertTrue(vb.judge("INTCALC", m, s, new, self.tmp / "w1", d.SpringRun("2022070700", migrated=0))[0])
        self.assertFalse(vb.judge("INTCALC", m, s, new, self.tmp / "w2", d.SpringRun("2022070600", migrated=0))[0])
        self.assertFalse(vb.judge("INTCALC", m, s, new, self.tmp / "w3", d.SpringRun("20220707", migrated=0))[0])

    # exporter output -------------------------------------------------------
    def export(self, job, counts, files):
        exp = self.tmp / f"export-{job}"
        exp.mkdir()
        for name, recs in files.items():
            d.harness.write_records(exp / name, recs)
        (exp / "counts.json").write_text(counts if isinstance(counts, str) else json.dumps(counts))
        return exp

    LOAD = {"loadExecutionId": 5, "accountsAsRead": 2, "accountsMigrated": 0, "accountLayoutSource": "ignored",
            "acctdataSha256": "a" * 64}

    def posttran_export(self, **change):
        data = self.tmp / "pdata"
        data.mkdir(exist_ok=True)
        tcat = sample("tcatbal.txt", 50)
        d.harness.write_records(data / "tcatbal.txt", tcat[:4])
        created = tcat[4][:17].decode()
        counts = v2_counts("POSTTRAN", self.LOAD, 2, {"processed": 7, "rejected": 1, "tcatbalCreated": 1, "rc": 4},
                           **change)
        files = {"DALYREJS.txt": [b"R" * 430], "ACCTDATA.after.txt": sample("acctdata.txt", 300)[:2],
                 "TCATBALF.after.txt": tcat[:5], "TRANSACT.after.txt": [tran(1, "2022-07-06-12.00.00.000000")]}
        return data, self.export("POSTTRAN", counts, files), created

    def test_a_posttran_export_becomes_the_check_s_layout(self):
        data, exp, created = self.posttran_export()
        self.assertEqual(list(json.loads((exp / "counts.json").read_text())), list(d.COUNT_FIELDS["POSTTRAN"]))
        new = self.tmp / "new"
        d.from_export("POSTTRAN", exp, data, 3, 1, self.LOAD, new)
        self.assertEqual(sorted(p.name for p in new.iterdir()),
                         sorted([*d.EXPORT_FILES["POSTTRAN"], "sysout.txt", "rc.txt", "stderr.txt"]))
        self.assertEqual((new / "sysout.txt").read_text().splitlines(), [
            "START OF EXECUTION OF PROGRAM CBTRN02C", "TRANSACTIONS PROCESSED :000000007",
            "TRANSACTIONS REJECTED  :000000001", f"TCATBAL record not found for key : {created}.. Creating.",
            "END OF EXECUTION OF PROGRAM CBTRN02C"])
        self.assertEqual((new / "rc.txt").read_text(), "4\n")
        self.assertEqual((new / "DALYREJS.txt").read_bytes(), (exp / "DALYREJS.txt").read_bytes())
        self.assertEqual(stat.S_IMODE(os.stat(new).st_mode), 0o700)
        self.assertEqual(stat.S_IMODE(os.stat(new / "TRANSACT.after.txt").st_mode), 0o600)

    def test_an_export_whose_counts_disagree_is_refused(self):
        for i, (change, text) in enumerate((
                ({"rejected": 2}, "rejected is 2"), ({"tcatbalCreated": 0}, "tcatbalCreated is 0"),
                ({"rc": 0}, "rc is 0"), ({"cycleId": 4}, "cycleId"), ({"attempt": 2}, "attempt is 2, expected 1"),
                ({"job": "INTCALC"}, "job"), ({"schemaVersion": 1}, "schemaVersion is 1, expected 2"),
                ({"loadExecutionId": 6}, "loadExecutionId is 6, expected 5"),
                ({"loadExecutionId": "5"}, "loadExecutionId is '5', expected 5"),
                ({"loadAccountsAsRead": 1}, "loadAccountsAsRead is 1, expected 2"),
                ({"loadAccountsMigrated": 1}, "loadAccountsMigrated is 1, expected 0"),
                ({"loadAccountsMigrated": False}, "loadAccountsMigrated is False, expected 0"),
                ({"loadAccountsAsRead": 2.0}, "loadAccountsAsRead is 2.0, expected 2"),
                ({"accountOriginNone": False}, "accountOriginNone is False, expected 0"),
                ({"loadAccountLayoutSource": "supplied"}, "loadAccountLayoutSource is 'supplied'"),
                ({"loadAcctdataSha256": "b" * 64}, "loadAcctdataSha256 is 'b"),
                ({"accountRecords": 3}, "accountRecords is 3, expected 2"),
                ({"accountOriginR": 1, "accountOriginM": 1}, "accountOriginR is 1, expected 2"),
                ({"accountOriginNone": 1}, "accountOriginNone is 1, expected 0"),
                ({"accountZipGroupRebuilt": 1}, "accountZipGroupRebuilt is 1, expected 0"),
                ({"accountZipGroupRestored": 3}, "accountZipGroupRestored is 3, not 0 to 2"),
                ({"accountZipGroupRestored": 1}, "accountZipGroupRebuilt is 0, expected 1"),
                ({"accountZipGroupRestored": True}, "accountZipGroupRestored is True"),
                ({"accountOriginNone": None}, "has the fields"), ({"groupIdMode": "legacy"}, "has the fields"))):
            with self.subTest(change=change):
                shutil.rmtree(self.tmp / "export-POSTTRAN", True)
                data, exp, _ = self.posttran_export(**change)
                with self.assertRaisesRegex(d.ExportError, text):
                    d.from_export("POSTTRAN", exp, data, 3, 1, self.LOAD, self.tmp / f"new-{i}")

    def test_an_export_of_another_load_shape_is_refused(self):
        data, exp, _ = self.posttran_export()
        counts = json.loads((exp / "counts.json").read_text())
        for i, (text, message) in enumerate((
                (json.dumps(dict(reversed(list(counts.items())))), "has the fields"),
                (json.dumps(counts)[:-1] + ', "rc": 4}', "has the fields"),
                ("[2]", "not one JSON object"), ("{", "not one JSON object"))):
            with self.subTest(text=text[:20]):
                (exp / "counts.json").write_text(text)
                with self.assertRaisesRegex(d.ExportError, message):
                    d.from_export("POSTTRAN", exp, data, 3, 1, self.LOAD, self.tmp / f"shape-{i}")
        uneven = self.LOAD | {"accountsAsRead": 2, "accountsMigrated": 1}
        (exp / "counts.json").write_text(json.dumps(v2_counts(
            "POSTTRAN", uneven, 2, {"processed": 7, "rejected": 1, "tcatbalCreated": 1, "rc": 4})))
        with self.assertRaisesRegex(d.ExportError, "origins do not add up"):
            d.from_export("POSTTRAN", exp, data, 3, 1, uneven, self.tmp / "sum")

    def test_an_incomplete_or_extra_export_is_refused(self):
        data, exp, _ = self.posttran_export()
        (exp / "extra.txt").write_text("")
        with self.assertRaisesRegex(d.ExportError, "holds"):
            d.from_export("POSTTRAN", exp, data, 3, 1, self.LOAD, self.tmp / "n1")
        (exp / "extra.txt").unlink()
        (exp / "counts.json").unlink()
        with self.assertRaisesRegex(d.ExportError, "not complete"):
            d.from_export("POSTTRAN", exp, data, 3, 1, self.LOAD, self.tmp / "n2")
        (exp / "counts.json").write_text(json.dumps({"schemaVersion": 2, "job": "POSTTRAN"}))
        with self.assertRaisesRegex(d.ExportError, "fields"):
            d.from_export("POSTTRAN", exp, data, 3, 1, self.LOAD, self.tmp / "n3")
        (exp / "counts.json").write_text(json.dumps({"job": "POSTTRAN", "cycleId": 3, "attempt": 1}))
        with self.assertRaisesRegex(d.ExportError, "schemaVersion is None, expected 2"):
            d.from_export("POSTTRAN", exp, data, 3, 1, self.LOAD, self.tmp / "n4")

    def test_an_intcalc_export_counts_records_and_the_input_s_accounts(self):
        data = self.interest_case()
        ts = "2022-07-06-12.00.00.000000"
        load = self.LOAD | {"accountsAsRead": 0, "accountsMigrated": 2, "accountLayoutSource": "supplied"}
        counts = v2_counts("INTCALC", load, 2, {"accounts": 2, "transactions": 1, "rc": 0})
        files = {"TRANSACT.txt": [tran(1, ts)], "ACCTDATA.after.txt": sample("acctdata.txt", 300)[:2]}
        new = self.tmp / "new"
        d.from_export("INTCALC", self.export("INTCALC", counts, files), data, 3, 1, load, new)
        self.assertEqual((new / "sysout.txt").read_text().splitlines(),
                         ["START OF EXECUTION OF PROGRAM CBACT04C", "END OF EXECUTION OF PROGRAM CBACT04C"])
        shutil.rmtree(self.tmp / "export-INTCALC")
        with self.assertRaisesRegex(d.ExportError, "accounts is 3"):
            d.from_export("INTCALC", self.export("INTCALC", counts | {"accounts": 3}, files), data, 3, 1, load,
                          self.tmp / "new2")

    def test_only_the_kept_sysout_lines_are_compared(self):
        src = self.tmp / "src"
        src.mkdir()
        (src / "sysout.txt").write_text("START OF EXECUTION OF PROGRAM CBTRN02C\n0000000001 a record\n"
                                        "TCATBAL record not found for key : 00000000009010001.. Creating.\n"
                                        "TCATBAL record not found for key : 00000000002010001.. Creating.\n"
                                        "TRANSACTIONS PROCESSED :000000262\nTRANSACTIONS REJECTED  :000000000\n"
                                        "END OF EXECUTION OF PROGRAM CBTRN02C\n")
        kept = d.kept_sysout(src, self.tmp / "kept")
        self.assertEqual((kept / "sysout.txt").read_text().splitlines(),
                         ["START OF EXECUTION OF PROGRAM CBTRN02C", "TRANSACTIONS PROCESSED :000000262",
                          "TRANSACTIONS REJECTED  :000000000", "END OF EXECUTION OF PROGRAM CBTRN02C",
                          "TCATBAL record not found for key : 00000000002010001.. Creating.",
                          "TCATBAL record not found for key : 00000000009010001.. Creating."])

    def scenarios(self):
        """The stand-in outputs, their input, and a DEF-ACUP-002 rerun that changes nothing (as on the sample)."""
        refs = write_refs(self.tmp / "refs")
        data = self.tmp / "data"
        data.mkdir()
        d.harness.write_records(data / "tcatbal.txt", sample("tcatbal.txt", 50)[:5])
        d.harness.write_records(data / "acctdata.txt", sample("acctdata.txt", 300)[:3])
        patch, _ = self.acup_rerun(refs["INTCALC"])
        patch.start()
        self.addCleanup(patch.stop)
        manifest = d.write_manifest(data / "acctdata.txt", self.tmp / "manifests" / "sample.toml")
        # POSTTRAN's input has as many accounts as its after-state, as on real runs.
        post = self.tmp / "post-data"
        shutil.copytree(data, post)
        d.harness.write_records(post / "acctdata.txt", sample("acctdata.txt", 300)[:5])
        return {"POSTTRAN": d.Scenario("POSTTRAN", "sample", post, refs["POSTTRAN"]),
                "INTCALC": d.Scenario("INTCALC", "sample", data, refs["INTCALC"], manifest)}

    # verify-harness --------------------------------------------------------
    def test_the_self_test_reports_every_seeded_difference(self):
        out = io.StringIO()
        rc = vb.self_test("verify-harness", self.tmp / "work", out, scenarios=self.scenarios())
        self.assertEqual(rc, 0, out.getvalue())
        text = out.getvalue()
        for label in ("one byte", "one missing record", "two records swapped", "one sign", "one byte next to the mask",
                      "exporter counts: one reject too many", "exporter counts: one TCATBAL creation too few",
                      "exporter counts: one transaction more processed", "exporter counts: schemaVersion 1",
                      "exporter counts: another load execution", "exporter counts: a migrated account counted as read",
                      "exporter counts: another attempt of the cycle"):
            self.assertRegex(text, rf"(?m)^ok +POSTTRAN {label}: reported")
        for label in ("fixed DEF-ACUP-002: an ADDR-ZIP byte", "fixed DEF-ACUP-002: a GROUP-ID byte",
                      "exporter counts: one interest record too many", "fixed DEF-INTC-010: consecutive IDs from another start"):
            self.assertRegex(text, rf"(?m)^ok +INTCALC {label}: reported")
        for job in ("POSTTRAN", "INTCALC"):
            self.assertRegex(text, rf"(?m)^ok +{job} exporter files of identical outputs: equal")
        self.assertRegex(text, r"(?m)^ok +INTCALC fixed DEF-ACUP-002: identical outputs \(no sample rate changes\): equal")
        self.assertRegex(text, r"(?m)^ok +POSTTRAN wall-clock PROC-TS \(masked\): equal")
        self.assertTrue(text.rstrip().endswith("verify-harness: OK"))

    def test_an_exporter_seed_counts_only_when_its_own_field_is_named(self):
        real = d.from_export
        scenarios = self.scenarios()
        refuse = mock.Mock(side_effect=d.ExportError("POSTTRAN: the export holds ['counts.json']"))
        out = io.StringIO()
        with mock.patch.object(d, "from_export", refuse):
            rc = vb.self_test("verify-harness", self.tmp / "work", out, scenarios=scenarios)
        self.assertEqual(rc, 1, out.getvalue())
        for field in ("rejected", "tcatbalCreated", "schemaVersion", "loadExecutionId", "accountOriginR", "attempt"):
            self.assertRegex(out.getvalue(), rf"(?m)^FAIL POSTTRAN exporter counts: .*: the exporter error does not name {field}$")
        self.assertRegex(out.getvalue(), r"(?m)^FAIL POSTTRAN exporter files of identical outputs: an exporter error for no seeded field$")

        def ignores_attempt(job, export, data, cycle_id, attempt, load, new):
            counts = json.loads((export / "counts.json").read_text())
            (export / "counts.json").write_text(json.dumps(counts | {"attempt": attempt}))
            return real(job, export, data, cycle_id, attempt, load, new)
        out = io.StringIO()
        with mock.patch.object(d, "from_export", ignores_attempt):
            rc = vb.self_test("verify-harness", self.tmp / "work2", out, scenarios=scenarios)
        self.assertEqual(rc, 1, out.getvalue())
        self.assertRegex(out.getvalue(),
                         r"(?m)^FAIL POSTTRAN exporter counts: another attempt of the cycle: the exporter accepted a changed attempt$")
        self.assertEqual(len([l for l in out.getvalue().splitlines() if l.startswith("FAIL")]), 1, out.getvalue())

    def test_the_build_is_private_to_the_run(self):
        keep = self.tmp / "keep"
        seen = {}

        def fake_self_test(name, root, out=sys.stdout, scenarios=None):
            seen["build"] = d.carddemo.build_root()
            seen["root"] = root
            return 0
        with mock.patch.dict(os.environ), mock.patch.object(vb, "self_test", fake_self_test):
            self.assertEqual(vb.main(["--self-test", "--keep", str(keep)]), 0)
        self.assertEqual(seen["build"], keep.resolve() / "build")
        self.assertNotEqual(seen["build"], d.REPO / "build")

    def test_the_build_runs_only_once_a_job_has_a_producer(self):
        prepare = mock.Mock()
        vb.verify("verify-batch", self.tmp / "w1", None, io.StringIO(), prepare)
        prepare.assert_not_called()
        for p in self.with_stand_in():
            p.start()
            self.addCleanup(p.stop)
        rc = vb.verify("verify-batch", self.tmp / "w2", "POSTTRAN", io.StringIO(), prepare)
        self.assertEqual(rc, vb.PARTIAL_EXIT)
        prepare.assert_called()

    def test_a_producer_must_return_what_it_read(self):
        class NoRun(Copy):
            def produce(self, scenario, mode, out):
                super().produce(scenario, mode, out)
                return "2022070600"
        for p in self.with_stand_in(producer=NoRun):
            p.start()
            self.addCleanup(p.stop)
        rc, out = self.run_verify("POSTTRAN")
        self.assertEqual(rc, 1, out)
        self.assertIn("not a SpringRun", out)
    def test_the_self_test_fails_when_the_comparison_misses_a_difference(self):
        real = d.compare
        blind = lambda job, ref, new: (True, "") if job == "POSTTRAN" else real(job, ref, new)
        out = io.StringIO()
        with mock.patch.object(d, "compare", blind):
            rc = vb.self_test("verify-harness", self.tmp / "work", out, scenarios=self.scenarios())
        self.assertEqual(rc, 1, out.getvalue())
        self.assertRegex(out.getvalue(), r"(?m)^FAIL POSTTRAN one sign: equal \(expected reported\)")
        self.assertRegex(out.getvalue(), r"(?m)^verify-harness: FAILED \(")


SHA = "ab" * 32
VALID = f'schema = 1\nsha256 = "{SHA}"\ndefault_layout = "ACUP_UPDATE"\n'


class ManifestSchemaTest(unittest.TestCase):
    """Layout manifest schema 1: keys, the accepted TOML subset and the messages of section 1.5."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="manifest-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_the_contract_s_example_parses_with_lf_and_crlf(self):
        text = (f'# the INTCALC stress file\nschema = 1\nsha256 = "{SHA}"   # after the file is written\n'
                'default_layout = "ACUP_UPDATE"\nother_layout_accounts = [\n  "00000000001", "00000000002",\n'
                '  # a comment inside the array\n  "00000000005",\n]\n\n')
        want = d.LayoutManifest(SHA, d.ACUP_UPDATE, ("00000000001", "00000000002", "00000000005"))
        self.assertEqual(d.LayoutManifest.parse(text), want)
        self.assertEqual(d.LayoutManifest.parse(text.replace("\n", "\r\n")), want)
        self.assertEqual(d.LayoutManifest.parse(VALID + "other_layout_accounts = []\n").other, ())

    def test_text_outside_the_subset_or_the_keys_names_its_line(self):
        cases = [
            (VALID + "[table]\n", "line 4: expected key = value"),
            (VALID + '"quoted" = 1\n', "line 4: expected key = value"),
            (VALID + "a.b = 1\n", "line 4: dotted keys are not allowed"),
            ("schema = 1\nschema = 1\n", "line 2: schema is given twice"),
            ('schema = 1\nsha256 = "a\\\\b"\n', "line 2: a backslash is not allowed"),
            ('schema = 1\nsha256 = "ab\n', "line 2: the string is not closed"),
            ("schema = 1 junk\n", "line 1: unexpected text after the value"),
            ("schema = 01\n", "line 1: unexpected text after the value"),
            ("schema = 1.0\n", "line 1: unexpected text after the value"),
            ("schema = true\n", "line 1: a value is a string, a decimal integer or an array of strings"),
            ("schema = 1\rx = 2\n", "line 1: a carriage return outside a CRLF line end"),
            (VALID + "other_layout_accounts = [\n  1,\n]\n", "line 5: an array holds only strings"),
            (VALID + 'other_layout_accounts = ["a" "b"]\n', "line 4: expected , or ] in the array"),
            (VALID + 'other_layout_accounts = [\n  "a",\n', "line 4: the array is not closed"),
            (VALID + "extra = 1\n", "line 4: unknown key extra"),
            (VALID.replace("schema = 1", "schema = 2"), "line 1: schema must be 1"),
            (VALID.replace("schema = 1", 'schema = "1"'), "line 1: schema must be 1"),
            (VALID.replace(SHA, SHA.upper()), "line 2: sha256 must be 64 lowercase hex digits"),
            (VALID.replace(SHA, SHA[:-1]), "line 2: sha256 must be 64 lowercase hex digits"),
            (VALID.replace('"ACUP_UPDATE"', '"acup_update"'), "line 3: default_layout must be CVACT01Y or ACUP_UPDATE"),
            (VALID.replace('"ACUP_UPDATE"', "1"), "line 3: default_layout must be CVACT01Y or ACUP_UPDATE"),
            (VALID + 'other_layout_accounts = "00000000001"\n', "line 4: other_layout_accounts must be an array"),
            # Order: syntax anywhere first, then keys in line order, then the missing ones.
            ('extra = 1\nsha256 = "x"\n[t]\n', "line 3: expected key = value"),
            ('extra = 1\nsha256 = "x"\n', "line 1: unknown key extra"),
        ]
        for text, message in cases:
            with self.subTest(text=text), self.assertRaises(d.ManifestError) as e:
                d.LayoutManifest.parse(text)
            self.assertRegex(str(e.exception), "^layout manifest " + message)
        for key in d.MANIFEST_REQUIRED:
            text = "".join(line + "\n" for line in VALID.splitlines() if not line.startswith(key + " "))
            with self.subTest(missing=key), self.assertRaisesRegex(d.ManifestError, f"^layout manifest: missing {key}$"):
                d.LayoutManifest.parse(text)

    def test_reading_a_supplied_manifest(self):
        path = self.tmp / "m.toml"
        with self.assertRaisesRegex(d.ManifestError, "^layout manifest not found: as/configured.toml$"):
            d.LayoutManifest.read(path, "as/configured.toml")
        path.write_bytes(VALID.encode() + b"#" * (d.MANIFEST_MAX_BYTES - len(VALID) + 1))
        with self.assertRaisesRegex(d.ManifestError, "^layout manifest is larger than 1 MiB$"):
            d.LayoutManifest.read(path, "m")
        path.write_bytes(VALID.encode() + b"#" * (d.MANIFEST_MAX_BYTES - len(VALID)))
        self.assertEqual(d.LayoutManifest.read(path, "m").sha256, SHA)
        path.write_bytes(b"schema = 1\n# \xff\n")
        with self.assertRaisesRegex(d.ManifestError, "^layout manifest line 2: not UTF-8$"):
            d.LayoutManifest.read(path, "m")
        acct = self.tmp / "acctdata.txt"
        acct.write_text("x\n")
        with self.assertRaisesRegex(d.ManifestError, f"^layout manifest not found: {self.tmp.resolve()}/gone.toml$"):
            d.applied_manifest(acct, self.tmp / "gone.toml")

    def test_the_built_in_entry_is_the_contract_s_hash_of_the_sample(self):
        self.assertEqual(d.sha256(d.BUILTIN_SAMPLE), d.BUILTIN_SHA256)
        self.assertEqual(d.BUILTIN_SHA256, "c2a97b6a32dc4a87a7aafdf7f72e6712e560412d30b00c5526cca80fc9dfd260")


SWITCHES = [d.ACUP_002, "DEF-INTC-010"]
ACUP = d.Mode("fixed DEF-ACUP-002", frozenset({d.ACUP_002}))
SEQUENCE_START = 9000000000000417
LOAD_ID = 5


class Spy(d.Driver):
    """Records each step with the hashes of the files it is given, and answers as batch-service
    would for the arguments it got (layout source from the fidelity and manifest options)."""

    def __init__(self, export=None, report=None, cycle=None, ends=None, migrated=None):
        self.calls, self.export_dir = [], export
        self.report_change, self.cycle_change, self.ends = report or {}, cycle or {}, ends or {}
        self.migrated = migrated

    def reset(self):
        self.calls.append(("reset",))

    def load(self, source_dir, args):
        digest = d.sha256(source_dir / "acctdata.txt")
        manifest = [a.split("=", 1)[1] for a in args if a.startswith(f"--{d.LAYOUT_MANIFEST_PROPERTY}=")]
        named = d.LayoutManifest.read(Path(manifest[0]), manifest[0]).sha256 if manifest else None
        self.calls.append(("load", digest, named))
        acctdata = source_dir / "acctdata.txt"
        if f"--carddemo.fidelity.{d.ACUP_002}=fixed" not in args:
            layout, migrated, warnings = "ignored", 0, 0
        else:
            layout = "supplied" if manifest else "builtin" if digest == d.BUILTIN_SHA256 else "none"
            loaded = d.load_accounts(acctdata, d.applied_manifest(acctdata, Path(manifest[0]) if manifest else None))
            migrated, warnings = loaded.migrated, len(loaded.ambiguous_lines)
        migrated = migrated if self.migrated is None else self.migrated
        records = len(d.d1_harness.split_lines(acctdata))
        return {"schemaVersion": 1, "loadExecutionId": LOAD_ID, "jobName": "dataLoadJob", "status": "COMPLETED",
                "exitCode": "COMPLETED", "accountRecords": records, "accountsAsRead": records - migrated,
                "accountsMigrated": migrated, "accountLayoutSource": layout, "accountLayoutWarnings": warnings,
                "acctdataSha256": digest, "accountLayoutManifestSha256": d.sha256(Path(manifest[0])) if manifest else ""} | self.report_change

    def start(self, args, env, daily_file, business_date):
        self.calls.append(("start", daily_file and d.sha256(daily_file), business_date))

    def register(self, body):
        self.calls.append(("register", body["interest"]))
        due = body["interest"] == "always"
        return {"cycleId": 7, "businessDate": body["businessDate"], "attempt": 1, "interest": due,
                "interestParm": body["businessDate"].replace("-", "") + "00" if due else None} | self.cycle_change

    def window(self, action, body):
        self.calls.append(("window", action, tuple(sorted(body.items()))))
        return {}

    def interest_sequence_next(self):
        self.calls.append(("sequence",))
        return SEQUENCE_START

    def launch(self, job, params):
        self.calls.append(("launch", job, params["cycleId"]))
        return {"executionStatus": "COMPLETED", "exitStatus": "COMPLETED", "rejectCount": 0} | self.ends.get(job, {})

    def export(self, job, cycle_id, load_execution):
        self.calls.append(("export", job, cycle_id, load_execution))
        return self.export_dir


class PipelineTest(unittest.TestCase):
    """Which bytes each case loads, which jobs it launches and in what order, from the batch
    execution contract and the layout manifest contract; the driver is a spy."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="pipeline-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.refs = write_refs(self.tmp / "refs")

    def posttran(self):
        data = self.tmp / "posttran-data"
        shutil.copytree(SAMPLE, data)
        return d.Scenario("POSTTRAN", "sample", data, self.refs["POSTTRAN"])

    def intcalc(self, after=None, name="intcalc"):
        """INTCALC's input: the sample with POSTTRAN's after-state accounts and balances, and its manifest."""
        data = self.tmp / f"{name}-data"
        shutil.copytree(SAMPLE, data)
        post = after or self.refs["POSTTRAN"]
        shutil.copy(post / "ACCTDATA.after.txt", data / "acctdata.txt")
        shutil.copy(post / "TCATBALF.after.txt", data / "tcatbal.txt")
        manifest = d.write_manifest(data / "acctdata.txt", self.tmp / "manifests" / f"{name}.toml")
        return d.Scenario("INTCALC", "sample", data, self.refs["INTCALC"], manifest)

    def test_intcalc_loads_its_own_after_state_once_and_launches_only_the_interest_job(self):
        s = self.intcalc()
        spy = Spy(export=self.tmp / "export")
        done = d.execute(d.pipeline(s, ACUP, SWITCHES), spy)
        load = d.sha256(s.data / "acctdata.txt")
        self.assertEqual(spy.calls, [
            ("reset",), ("load", load, load), ("start", None, "2022-07-06"), ("register", "always"),
            ("window", "open", (("cycleId", 7),)), ("sequence",), ("launch", "calculateInterestJob", 7),
            ("window", "close", (("complete", False), ("cycleId", 7))), ("export", "INTCALC", 7, LOAD_ID)])
        self.assertNotEqual(load, d.sha256(s.ref / "ACCTDATA.after.txt"))
        self.assertNotEqual(load, d.BUILTIN_SHA256)
        migrated = d.expected_migrated(s, ACUP)
        self.assertEqual(done.run, d.SpringRun(interest_parm="2022070600", interest_id_start=SEQUENCE_START,
                                               migrated=migrated))
        self.assertEqual(done.load["loadExecutionId"], LOAD_ID)
        self.assertEqual((done.cycle_id, done.attempt, done.export), (7, 1, self.tmp / "export"))

    def test_posttran_stages_its_daily_file_and_registers_no_interest(self):
        s = self.posttran()
        for mode, layout in ((LEGACY, "ignored"), (ACUP, "builtin")):
            with self.subTest(mode=mode.label):
                p = d.pipeline(s, mode, SWITCHES)
                self.assertEqual((p.layout_source, p.manifest), (layout, None))
                spy = Spy()
                d.execute(p, spy)
                self.assertEqual([c[0] for c in spy.calls],
                                 ["reset", "load", "start", "register", "window", "launch", "launch", "window", "export"])
                self.assertEqual(spy.calls[1], ("load", d.BUILTIN_SHA256, None))
                self.assertEqual(spy.calls[2], ("start", d.sha256(SAMPLE / "dailytran.txt"), "2022-07-06"))
                self.assertEqual(spy.calls[3], ("register", "never"))
                self.assertEqual([c[1] for c in spy.calls if c[0] == "launch"],
                                 ["ingestDailyFileJob", "postDailyTransactionsJob"])

    def test_legacy_passes_no_manifest(self):
        p = d.pipeline(self.intcalc(), LEGACY, SWITCHES)
        self.assertEqual((p.manifest, p.layout_source), (None, "ignored"))
        self.assertFalse([a for a in p.load_args if d.LAYOUT_MANIFEST_PROPERTY in a])
        self.assertIn(f"--carddemo.fidelity.{d.ACUP_002}=legacy", p.load_args)

    def test_a_manifest_of_other_bytes_than_the_load_reads_is_refused_before_any_step(self):
        s = self.intcalc()
        for name, other in (("REF's after-state", s.ref / "ACCTDATA.after.txt"), ("the raw sample", SAMPLE / "acctdata.txt")):
            with self.subTest(name):
                wrong = dataclasses.replace(s, manifest=d.write_manifest(other, self.tmp / "wrong.toml"))
                with self.assertRaisesRegex(d.PipelineError, "the manifest names acctdata.txt"):
                    d.pipeline(wrong, ACUP, SWITCHES)
        p = d.pipeline(s, ACUP, SWITCHES)
        d.write_manifest(s.ref / "ACCTDATA.after.txt", p.manifest)
        spy = Spy()
        with self.assertRaisesRegex(d.PipelineError, "does not name the bytes the load reads"):
            d.execute(p, spy)
        self.assertEqual(spy.calls, [])

    def test_a_pipeline_that_would_post_again_or_mix_jobs_is_refused_before_any_step(self):
        p = d.pipeline(self.intcalc(), ACUP, SWITCHES)
        post = d.pipeline(self.posttran(), ACUP, SWITCHES)
        for name, bad, message in (
                ("POSTTRAN's jobs first", dataclasses.replace(p, jobs=(*post.jobs, *p.jobs)), "the catalogue runs"),
                ("a daily file", dataclasses.replace(p, daily_file=p.source_dir / "dailytran.txt"),
                 "a daily file is staged only"),
                ("interest never", dataclasses.replace(p, cycle=(("businessDate", "2022-07-06"), ("interest", "never"))),
                 "interest policy"),
                ("no daily file", dataclasses.replace(post, daily_file=None), "a daily file is staged only"),
                ("another input's daily file", dataclasses.replace(post, daily_file=SAMPLE / "dailytran.txt"),
                 "not the loaded input's")):
            with self.subTest(name):
                spy = Spy()
                with self.assertRaisesRegex(d.PipelineError, message):
                    d.execute(bad, spy)
                self.assertEqual(spy.calls, [])

    def test_the_load_must_report_the_planned_bytes_and_layout(self):
        s = self.intcalc()
        for name, spy, message in (
                ("other bytes", Spy(report={"acctdataSha256": "0" * 64}), "the load read acctdata.txt 000000000000"),
                ("as read", Spy(report={"accountLayoutSource": "none"}), "layout source is 'none', expected 'supplied'"),
                ("other manifest", Spy(report={"accountLayoutManifestSha256": "1" * 64}),
                 "the load read the layout manifest 111111111111, the pipeline supplied [0-9a-f]{12}"),
                ("no manifest", Spy(report={"accountLayoutManifestSha256": ""}),
                 "the layout manifest none, the pipeline supplied [0-9a-f]{12}")):
            with self.subTest(name), self.assertRaisesRegex(d.PipelineError, message):
                d.execute(d.pipeline(s, ACUP, SWITCHES), spy)
            self.assertEqual([c[0] for c in spy.calls], ["reset", "load"])

        class Without(Spy):
            def __init__(self, *keys):
                super().__init__()
                self.keys = keys

            def load(self, source_dir, args):
                report = super().load(source_dir, args)
                for key in self.keys:
                    del report[key]
                return report
        with self.assertRaisesRegex(d.PipelineError, "the load report lacks accountsMigrated"):
            d.execute(d.pipeline(s, ACUP, SWITCHES), Without("accountsMigrated"))
        # schemaVersion is read first: an unknown version is never judged by its other keys.
        with self.assertRaisesRegex(d.PipelineError, "the load report is schemaVersion None, not 1"):
            d.execute(d.pipeline(s, ACUP, SWITCHES), Without("schemaVersion", "accountsMigrated"))

    def test_the_load_report_must_be_of_a_completed_load_that_migrated_what_the_manifest_declares(self):
        s = self.intcalc()
        p = d.pipeline(s, ACUP, SWITCHES)
        records = len(d.d1_harness.split_lines(s.data / "acctdata.txt"))
        for name, change, message in (
                ("schema 2", {"schemaVersion": 2, "jobName": None}, "schemaVersion 2, not 1"),
                ("negative ID", {"loadExecutionId": -1}, "loadExecutionId is -1"),
                ("ID over 64 bits", {"loadExecutionId": 2 ** 63}, "loadExecutionId is 9223372036854775808"),
                ("ID false", {"loadExecutionId": False}, "loadExecutionId is False"),
                ("ID true", {"loadExecutionId": True}, "loadExecutionId is True"),
                ("ID text", {"loadExecutionId": "5"}, "loadExecutionId is '5'"),
                ("other job", {"jobName": "calculateInterestJob"}, "not a completed dataLoadJob"),
                ("failed", {"status": "FAILED"}, "not a completed dataLoadJob"),
                ("exit", {"exitCode": "NOOP"}, "not a completed dataLoadJob"),
                ("records", {"accountRecords": records + 1}, f"wrote {records + 1} account records, acctdata.txt has {records}"),
                ("origins", {"accountsAsRead": records - p.migrated + 1}, "are not its"),
                ("migrated", {"accountsMigrated": p.migrated + 1, "accountsAsRead": records - p.migrated - 1},
                 f"migrated {p.migrated + 1} records, the manifest declares {p.migrated}"),
                ("warnings", {"accountLayoutWarnings": p.warnings + 1},
                 f"warned about {p.warnings + 1} records, the ruling's count is {p.warnings}"),
                ("warnings null", {"accountLayoutWarnings": None}, "warned about None records"),
                ("warnings false", {"accountLayoutWarnings": False}, "warned about False records")):
            spy = Spy(report=change)
            with self.subTest(name), self.assertRaisesRegex(d.PipelineError, message):
                d.execute(p, spy)
            self.assertEqual([c[0] for c in spy.calls], ["reset", "load"])

    def test_the_export_is_bound_to_load_execution_zero_and_to_the_64_bit_maximum(self):
        s = self.intcalc()
        for execution in (0, 2 ** 63 - 1):
            spy = Spy(export=self.tmp / "export", report={"loadExecutionId": execution})
            with self.subTest(execution=execution):
                done = d.execute(d.pipeline(s, ACUP, SWITCHES), spy)
                self.assertEqual(spy.calls[-1], ("export", "INTCALC", 7, execution))
                self.assertEqual(done.load["loadExecutionId"], execution)

    def test_input_changed_after_planning_stops_before_the_load(self):
        s = self.intcalc()
        p = d.pipeline(s, ACUP, SWITCHES)
        with open(s.data / "acctdata.txt", "ab") as f:
            f.write(b"\n")
        spy = Spy()
        with self.assertRaisesRegex(d.PipelineError, "changed after the pipeline was made"):
            d.execute(p, spy)
        self.assertEqual(spy.calls, [("reset",)])

    def test_the_cycle_and_the_jobs_must_answer_as_the_contract_says(self):
        s = self.intcalc()
        for name, spy, message in (
                ("interest false", Spy(cycle={"interest": False}), "interest False"),
                ("other date", Spy(cycle={"businessDate": "2022-07-07"}), "came back as '2022-07-07'"),
                ("no parm", Spy(cycle={"interestParm": None}), "interestParm is None"),
                ("failed job", Spy(ends={"calculateInterestJob": {"executionStatus": "FAILED", "exitStatus": "FAILED"}}),
                 "calculateInterestJob ended FAILED / FAILED"),
                ("interest with rejects", Spy(ends={"calculateInterestJob": {"exitStatus": "COMPLETED_WITH_REJECTS",
                                                                             "rejectCount": 1}}),
                 "calculateInterestJob ended COMPLETED / COMPLETED_WITH_REJECTS$"),
                ("interest rejects without the exit", Spy(ends={"calculateInterestJob": {"rejectCount": 2}}),
                 "calculateInterestJob ended COMPLETED / COMPLETED with reject count 2")):
            with self.subTest(name), self.assertRaisesRegex(d.PipelineError, message):
                d.execute(d.pipeline(s, ACUP, SWITCHES), spy)
            self.assertNotIn("export", [c[0] for c in spy.calls])

    def test_posttran_ends_plain_completed_without_rejects_and_with_rejects_otherwise(self):
        # CBTRN02C's return code 4 when it rejects (the sample's daily file has 38), 0 when not.
        s = self.posttran()
        for exit_status, count in (("COMPLETED_WITH_REJECTS", 38), ("COMPLETED_WITH_REJECTS", 1), ("COMPLETED", 0)):
            spy = Spy(export=self.tmp / "export", ends={"postDailyTransactionsJob": {
                "exitStatus": exit_status, "rejectCount": count}})
            with self.subTest(exit_status=exit_status, count=count):
                done = d.execute(d.pipeline(s, ACUP, SWITCHES), spy)
                self.assertEqual(spy.calls[-1], ("export", "POSTTRAN", 7, LOAD_ID))
                self.assertEqual(done.export, self.tmp / "export")
    def test_only_post_daily_transactions_may_end_with_rejects_and_only_with_a_count_that_agrees(self):
        s = self.posttran()
        rejects = {"exitStatus": "COMPLETED_WITH_REJECTS", "rejectCount": 38}
        for name, ends, message in (
                ("ingest with rejects", {"ingestDailyFileJob": rejects},
                 "ingestDailyFileJob ended COMPLETED / COMPLETED_WITH_REJECTS$"),
                ("failed with rejects", {"postDailyTransactionsJob": rejects | {"executionStatus": "FAILED"}},
                 "postDailyTransactionsJob ended FAILED / COMPLETED_WITH_REJECTS$"),
                ("failed", {"postDailyTransactionsJob": {"executionStatus": "FAILED", "exitStatus": "FAILED"}},
                 "postDailyTransactionsJob ended FAILED / FAILED$"),
                ("stopped", {"postDailyTransactionsJob": {"executionStatus": "STOPPED", "exitStatus": "COMPLETED"}},
                 "postDailyTransactionsJob ended STOPPED / COMPLETED$"),
                ("custom exit", {"postDailyTransactionsJob": {"exitStatus": "NOOP"}},
                 "postDailyTransactionsJob ended COMPLETED / NOOP$"),
                ("rejects exit, no rejects", {"postDailyTransactionsJob": rejects | {"rejectCount": 0}},
                 "COMPLETED_WITH_REJECTS with reject count 0"),
                ("rejects, plain exit", {"postDailyTransactionsJob": {"rejectCount": 38}},
                 "COMPLETED / COMPLETED with reject count 38"),
                ("negative count", {"postDailyTransactionsJob": {"rejectCount": -1}}, "with reject count -1"),
                ("no count", {"postDailyTransactionsJob": {"rejectCount": None}}, "with reject count None"),
                ("count true", {"postDailyTransactionsJob": rejects | {"rejectCount": True}}, "with reject count True"),
                ("count false", {"postDailyTransactionsJob": {"rejectCount": False}}, "with reject count False"),
                ("count text", {"postDailyTransactionsJob": rejects | {"rejectCount": "38"}}, "with reject count '38'"),
                ("count float", {"postDailyTransactionsJob": rejects | {"rejectCount": 38.0}}, "with reject count 38.0")):
            spy = Spy(export=self.tmp / "export", ends=ends)
            with self.subTest(name), self.assertRaisesRegex(d.PipelineError, "^POSTTRAN: .*" + message):
                d.execute(d.pipeline(s, ACUP, SWITCHES), spy)
            self.assertNotIn("export", [c[0] for c in spy.calls])

        class Missing(Spy):
            def launch(self, job, params):
                done = super().launch(job, params)
                del done["rejectCount"]
                return done
        with self.assertRaisesRegex(d.PipelineError, "POSTTRAN: ingestDailyFileJob ended COMPLETED / COMPLETED with reject count None"):
            d.execute(d.pipeline(s, ACUP, SWITCHES), Missing())
    def test_the_pipeline_producer_writes_new_from_the_export(self):
        s = self.intcalc()
        export = self.tmp / "export"
        export.mkdir()
        for name in d.EXPORT_FILES["INTCALC"]:
            shutil.copy(s.ref / name, export / name)
        accounts = {r[:11] for r in d.carddemo.read_text_records(s.data / "tcatbal.txt", 50)}
        migrated = d.expected_migrated(s, ACUP)
        # INTCALC keeps every account of its input.
        shutil.copy(s.data / "acctdata.txt", export / "ACCTDATA.after.txt")
        records = len(d.d1_harness.split_lines(s.data / "acctdata.txt"))
        load = {"loadExecutionId": LOAD_ID, "accountsAsRead": records - migrated, "accountsMigrated": migrated,
                "accountLayoutSource": "supplied", "acctdataSha256": d.sha256(s.data / "acctdata.txt")}
        (export / "counts.json").write_text(json.dumps(v2_counts(
            "INTCALC", load, records, {"accounts": len(accounts),
                                       "transactions": len(d.d1_harness.split_lines(s.ref / "TRANSACT.txt")), "rc": 0},
            cycleId=7)))
        drivers = []
        producer = d.PipelineProducer(lambda sc, m: drivers.append(Spy(export=export)) or drivers[-1], SWITCHES)
        run = producer.produce(s, ACUP, self.tmp / "new")
        self.assertEqual(run.migrated, migrated)
        self.assertEqual(sorted(p.name for p in (self.tmp / "new").iterdir()),
                         sorted([*d.EXPORT_FILES["INTCALC"], "rc.txt", "stderr.txt", "sysout.txt"]))
        self.assertEqual(len(drivers), 1)

    def cases(self, intcalc_after=None):
        post = self.posttran()
        return {"POSTTRAN sample": post, "INTCALC sample": self.intcalc(intcalc_after),
                "INTCALC stress": dataclasses.replace(self.intcalc(intcalc_after, "stress"), case="stress")}

    def test_the_self_test_states_what_each_real_case_loads_and_launches(self):
        out = io.StringIO()
        with mock.patch.object(d, "batch_switches", lambda: SWITCHES):
            self.assertEqual(vb.check_pipelines(self.cases(), out), [], out.getvalue())
        self.assertRegex(out.getvalue(), r"(?m)^ok   pipeline INTCALC sample fixed DEF-ACUP-002: acctdata.txt [0-9a-f]{12}, "
                                         r"manifest intcalc.toml \(supplied\), jobs calculateInterestJob$")
        self.assertRegex(out.getvalue(), r"(?m)^ok   pipeline POSTTRAN sample fixed DEF-ACUP-002: acctdata.txt c2a97b6a32dc, "
                                         r"manifest none \(builtin\), jobs ingestDailyFileJob, postDailyTransactionsJob$")

    def test_an_intcalc_input_that_is_not_posttran_s_after_state_fails_the_self_test(self):
        raw = self.tmp / "raw"
        raw.mkdir()
        shutil.copy(SAMPLE / "acctdata.txt", raw / "ACCTDATA.after.txt")
        shutil.copy(SAMPLE / "tcatbal.txt", raw / "TCATBALF.after.txt")
        out = io.StringIO()
        with mock.patch.object(d, "batch_switches", lambda: SWITCHES):
            bad = vb.check_pipelines(self.cases(intcalc_after=raw), out)
        self.assertIn("pipeline INTCALC sample legacy", bad)
        self.assertRegex(out.getvalue(), r"(?m)^FAIL pipeline INTCALC sample legacy: not POSTTRAN sample's after-state;")
        self.assertNotIn("pipeline INTCALC stress legacy", bad)

if __name__ == "__main__":
    unittest.main()
