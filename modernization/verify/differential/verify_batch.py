#!/usr/bin/env python3
"""make verify-batch and make verify-harness: Spring Batch against GnuCOBOL.

  verify_batch.py               every required job (differential.REQUIRED), the
                                sample and the stress data, legacy and fixed mode
  ONLY=POSTTRAN,INTCALC         a subset: ends "verify-batch: PARTIAL (...)" and
                                exits 3 even when it passed, never "OK"
  verify_batch.py --self-test   make verify-harness: the comparison step on
                                seeded differences of real GnuCOBOL outputs
  verify_batch.py --plan        whether a full run can pass at all: every required
                                job has a Spring Batch producer and every runner job
                                is classified; ends "READY" or "SKIPPED (...)",
                                never "OK" (CI's plan job, with --github-output)

Each job compares through its spec check's own compare.py. A job whose Spring
Batch side is not built fails with what is missing: a full run passes only when
every required job matched in both modes on both data sets. Legacy mode sets
every switch batch-service declares to legacy and must match byte for byte.
Each switch then runs fixed on its own, and all of them together: NEW must
equal what differential.expected() derives from the GnuCOBOL outputs and the
input for the fixed items (nothing else may change). Only the wall-clock
fields the checks mask, and the sysout lines D10 drops, are not compared.

Needs the GnuCOBOL toolchain; the programs are built into the run's own
directory, never the checkout's build/. Ends with
"<name>: OK", "<name>: PARTIAL (...)" or "<name>: FAILED (...)".
Usage: verify_batch.py [--name NAME] [--keep DIR] [--self-test | --plan [--github-output KEY]]
"""
from __future__ import annotations

import argparse
import contextlib
import functools
import json
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path
from typing import Callable, NamedTuple

sys.path.insert(0, str(Path(__file__).resolve().parent))
import differential as d  # noqa: E402

PARTIAL_EXIT = 3
NOT_READY_EXIT = 4


def selected(only: str | None) -> list[str]:
    if not only:
        return list(d.REQUIRED)
    names = [n.strip().upper() for n in only.split(",") if n.strip()]
    unknown = [n for n in names if n not in d.REQUIRED]
    if unknown or not names:
        raise SystemExit(f"ONLY names no required job: {','.join(unknown) or only}; "
                         f"choose from {','.join(d.REQUIRED)}")
    return list(dict.fromkeys(names))


def build() -> None:
    d.carddemo.build("release", verbose=False)


def use_private_build(root: Path) -> None:
    """GnuCOBOL builds into the run's own directory, so a make check or another run that
    rebuilds the checkout's build/ meanwhile cannot change the programs under test."""
    os.environ["CARDDEMO_BUILD_DIR"] = str(root / "build")


def judge(job: str, mode: d.Mode, scenario: d.Scenario, new: Path, work: Path,
          run: d.SpringRun = d.SpringRun()) -> tuple[bool, str]:
    """NEW against what this mode expects, through the check's compare.py."""
    try:
        d.check_load(scenario, mode, run)
        exp = d.expected(job, mode, scenario.data, scenario.ref, new, work / "expected", run,
                         manifest=scenario.manifest)
    except (d.ExpectationError, d.ManifestError) as e:
        return False, f"FAIL {e}"
    return d.compare(job, d.kept_sysout(exp, work / "expected-kept"), d.kept_sysout(new, work / "new-kept"))


def run_job(job: str, root: Path, switches: list[str], out, prepare: Callable[[], None] | None = None,
            new_driver: Callable[[d.Scenario, d.Mode], d.Driver] | None = None) -> list[str]:
    """Every comparison of one job; returns its failures. prepare (the GnuCOBOL build) runs
    only once a job has a producer."""
    try:
        producer = d.spring_producer(job, new_driver, switches)
    except d.Unsupported as e:
        return [str(e)]
    if prepare:
        prepare()
    failures = []
    for case in d.CASES:
        scenarios = d.REFERENCES[job](root / job / case, case)
        for i, mode in enumerate(d.modes(switches)):
            for s in scenarios:
                if not s.runs_in(mode):
                    continue
                work = root / job / case / f"mode-{i}" / s.case.replace("/", "_")
                new = work / "new"
                try:
                    run = producer.produce(s, mode, new)
                    if not isinstance(run, d.SpringRun):
                        raise d.ExpectationError(f"the producer returned {run!r}, not a SpringRun")
                except (d.ExportError, d.ExpectationError, d.PipelineError, d.ManifestError) as e:
                    ok, lines = False, f"FAIL {e}"
                else:
                    ok, lines = judge(job, mode, s, new, work, run)
                print(f"{'ok  ' if ok else 'FAIL'} {job} {s.case} {mode.label}", file=out)
                if not ok:
                    print("".join("       " + l + "\n" for l in lines.splitlines() if l.startswith("FAIL")), end="",
                          file=out)
                    failures.append(f"{job} {s.case} {mode.label}")
    return failures


def verify(name: str, root: Path, only: str | None, out=sys.stdout, prepare: Callable[[], None] | None = None) -> int:
    jobs = selected(only)
    failures: list[str] = []
    unclassified = d.unclassified_runner_jobs()
    if unclassified:
        failures.append(f"tools/carddemo.py jobs missing from the catalogue: {', '.join(unclassified)}")
    try:
        switches = d.batch_switches()
        print(f"     switches: {', '.join(switches)}; INTCALC PARM from tools/carddemo.py", file=out)
    except d.Unsupported as e:
        switches = []
        failures.append(f"batch-service switches: {e}")
    unknown = d.unknown_switches(switches)
    if unknown:
        failures.append(f"no expectation in differential.EFFECTS for {', '.join(unknown)}")
        switches = []
    for f in failures:
        print(f"FAIL {f}", file=out)
    with contextlib.ExitStack() as owned:
        new_driver = None
        if switches and any(not d.producer_gaps(job) for job in jobs):
            import live  # the database, the server and the lock only when a job can run
            new_driver = owned.enter_context(live.Session(root, name)).driver
        for job in jobs:
            mine = run_job(job, root, switches, out, prepare, new_driver) if switches \
                else [f"{job}: not run without the switches"]
            for f in mine:
                print(f"FAIL {f}", file=out)
            if not mine:
                print(f"ok   {job}: every case and mode matched", file=out)
            failures += mine
    if failures:
        print(f"{name}: FAILED ({len(failures)} failure(s); {', '.join(jobs)})", file=out)
        return 1
    if only:
        print(f"{name}: PARTIAL (ONLY={','.join(jobs)} matched; not the full required set "
              f"{','.join(d.REQUIRED)})", file=out)
        return PARTIAL_EXIT
    print(f"{name}: OK", file=out)
    return 0


# ---------------------------------------------------------------------------
# plan
# ---------------------------------------------------------------------------
def readiness() -> dict[str, str]:
    """Job -> what stops it before any comparison ("" when nothing does): every
    required job's producer, through spring_producer itself, and every runner job
    the catalogue does not classify. spring_producer looks at sources, not build
    output, so this runs without a build, a JDK or GnuCOBOL."""
    state = {}
    for job in d.REQUIRED:
        state[job] = "; ".join(d.producer_gaps(job))
    for job in d.unclassified_runner_jobs():
        state[job] = "a tools/carddemo.py job that differential.CATALOGUE does not classify"
    return state


def plan(name: str, out=sys.stdout) -> tuple[bool, list[str]]:
    state = readiness()
    for job, missing in state.items():
        print(f"ready    {job}" if not missing else f"missing  {job}: {missing}", file=out)
    blocked = [job for job, missing in state.items() if missing]
    if blocked:
        print(f"{name} plan: SKIPPED (not ready: {', '.join(blocked)})", file=out)
    else:
        print(f"{name} plan: READY ({', '.join(state)})", file=out)
    return not blocked, blocked


def github_plan(key: str, name: str, out=sys.stdout) -> None:
    """The plan job's step: KEY=true|false to $GITHUB_OUTPUT, a row of the run summary,
    and a notice when skipped. A skip is never shown as a pass."""
    ready, blocked = plan(name, out)
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as f:
        f.write(f"{key}={'true' if ready else 'false'}\n")
    runs = "yes" if ready else f"skipped: not ready: {', '.join(blocked)} (the plan step says what each lacks)"
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
        f.write(f"| `{key}` | `{name}` | {runs} |\n")
    if not ready:
        print(f"::notice title={key} skipped::{name} SKIPPED, not ready: {', '.join(blocked)}", file=out)


# ---------------------------------------------------------------------------
# verify-harness
# ---------------------------------------------------------------------------
POS, NEG = "{ABCDEFGHI", "}JKLMNOPQR"


def flip_sign(c: str) -> str:
    return NEG[POS.index(c)] if c in POS else POS[NEG.index(c)]


def edit(path: Path, fn) -> None:
    recs = d.d1_harness.split_lines(path)
    d.harness.write_records(path, [r.encode("latin-1") for r in fn(recs)])


def replace_at(rec: str, pos: int, ch: str) -> str:
    return rec[:pos] + ch + rec[pos + 1:]


def other_digit(c: str) -> str:
    return "1" if c != "1" else "2"


def with_ids(path: Path, ids) -> None:
    edit(path, lambda r: [i + x[len(i):] for i, x in zip(ids(len(r)), r)])


SEED_CYCLE, SEED_ATTEMPT, SEED_LOAD = 1, 1, 1


def seed_load(s: d.Scenario, mode: d.Mode) -> dict:
    """The load report values a correct load of the case gives (load report schemaVersion 1)."""
    acctdata = s.data / "acctdata.txt"
    digest = d.sha256(acctdata)
    if d.ACUP_002 not in mode.fixed:
        layout = "ignored"
    else:
        layout = "supplied" if s.manifest is not None else "builtin" if digest == d.BUILTIN_SHA256 else "none"
    migrated = d.expected_migrated(s, mode)
    return {"loadExecutionId": SEED_LOAD, "accountsAsRead": len(d.d1_harness.split_lines(acctdata)) - migrated,
            "accountsMigrated": migrated, "accountLayoutSource": layout,
            "accountLayoutWarnings": d.expected_warnings(s, mode), "acctdataSha256": digest}


def as_export(job: str, n: Path, s: d.Scenario, mode: d.Mode, **counts_change) -> None:
    """Turns NEW (a copy of REF) into what the exporter writes (counts.json schemaVersion 2,
    taken from REF's sysout and rc and the case's load), then back into NEW through from_export."""
    export = n.parent / "export"
    export.mkdir()
    for name in d.EXPORT_FILES[job]:
        (n / name).rename(export / name)
    sysout = (s.ref / "sysout.txt").read_text(encoding="latin-1")
    rc = int((s.ref / "rc.txt").read_text())
    load = seed_load(s, mode)
    counts = {"schemaVersion": 2, "job": job, "cycleId": SEED_CYCLE, "attempt": SEED_ATTEMPT,
              **{key: load[report_key] for key, report_key in d.COUNTS_FROM_LOAD}}
    if job == "POSTTRAN":
        counts |= {"processed": int(re.search(r"TRANSACTIONS PROCESSED :(\d+)", sysout)[1]),
                   "rejected": int(re.search(r"TRANSACTIONS REJECTED  :(\d+)", sysout)[1]),
                   "tcatbalCreated": len(d.SYSOUT_CREATED.findall(sysout)), "rc": rc}
    else:
        accounts = {r[:11] for r in d.carddemo.read_text_records(s.data / "tcatbal.txt", 50)}
        counts |= {"accounts": len(accounts), "transactions": len(d.d1_harness.split_lines(export / "TRANSACT.txt")),
                   "rc": rc}
    records = len(d.d1_harness.split_lines(export / "ACCTDATA.after.txt"))
    counts |= {"accountRecords": records, "accountOriginR": load["accountsAsRead"],
               "accountOriginM": load["accountsMigrated"], "accountOriginNone": 0,
               "accountZipGroupRestored": records, "accountZipGroupRebuilt": 0}
    counts |= counts_change
    (export / "counts.json").write_text(json.dumps(counts))
    shutil.rmtree(n)
    d.from_export(job, export, s.data, SEED_CYCLE, SEED_ATTEMPT, load, n)


class Seed(NamedTuple):
    job: str
    label: str
    must_match: bool
    mode: d.Mode
    change: Callable[[Path, d.Scenario], object]
    field: str | None = None   # the counts.json field the exporter check must name
    migrated_off: int = 0      # added to the load report's migrated count the producer returns


def seeds() -> list[Seed]:
    """The seeded changes to copies of the POSTTRAN and INTCALC outputs."""
    legacy = d.Mode("legacy", frozenset())
    ids = d.Mode("fixed DEF-INTC-010", frozenset({"DEF-INTC-010"}))
    prefix = d.Mode("fixed DEF-JINTC-001", frozenset({"DEF-JINTC-001"}))
    bal = d.oracle.ACCOUNT["ACCT-CURR-BAL"]
    sign_at = bal.offset + bal.length - 1
    tid = d.oracle.TRAN["TRAN-ID"]
    lo, hi = d.check_module("POSTTRAN").PROC_TS
    after_id = tid.offset + tid.length
    acup = d.Mode("fixed DEF-ACUP-002", frozenset({d.ACUP_002}))
    zip_at, grp_at = d.oracle.ACCOUNT["ACCT-ADDR-ZIP"].offset, d.oracle.ACCOUNT["ACCT-GROUP-ID"].offset
    start = d.INTEREST_ID_START
    return [Seed(*t) for t in [
        ("POSTTRAN", "identical outputs", True, legacy, lambda n, s: None),
        ("POSTTRAN", "one byte", False, legacy,
         lambda n, s: edit(n / "ACCTDATA.after.txt", lambda r: [replace_at(r[0], 1, other_digit(r[0][1]))] + r[1:])),
        ("POSTTRAN", "one missing record", False, legacy, lambda n, s: edit(n / "TCATBALF.after.txt", lambda r: r[:-1])),
        ("POSTTRAN", "two records swapped", False, legacy,
         lambda n, s: edit(n / "TRANSACT.after.txt", lambda r: [r[1], r[0]] + r[2:])),
        ("POSTTRAN", "one sign", False, legacy,
         lambda n, s: edit(n / "ACCTDATA.after.txt",
                        lambda r: [replace_at(r[0], sign_at, flip_sign(r[0][sign_at]))] + r[1:])),
        ("POSTTRAN", "wall-clock PROC-TS (masked)", True, legacy,
         lambda n, s: edit(n / "TRANSACT.after.txt",
                        lambda r: [x[:lo] + "2022-07-06-23.59.59.990000" + x[hi:] for x in r])),
        ("POSTTRAN", "one byte next to the mask", False, legacy,
         lambda n, s: edit(n / "TRANSACT.after.txt",
                        lambda r: [replace_at(r[0], lo - 1, "X" if r[0][lo - 1] != "X" else "Y")] + r[1:])),
        ("POSTTRAN", "a record dump in sysout (D10: not compared)", True, legacy,
         lambda n, s: (n / "sysout.txt").write_bytes((n / "sysout.txt").read_bytes() + b"dump of a record\n")),
        ("INTCALC", "identical outputs", True, legacy, lambda n, s: None),
        ("INTCALC", "legacy: TRAN-ID changed", False, legacy,
         lambda n, s: with_ids(n / "TRANSACT.txt", lambda k: [str(9000000000000001 + i) for i in range(k)])),
        ("INTCALC", "fixed DEF-INTC-010: consecutive IDs from the sequence start", True, ids,
         lambda n, s: with_ids(n / "TRANSACT.txt", lambda k: [str(start + i) for i in range(k)])),
        ("INTCALC", "fixed DEF-INTC-010: consecutive IDs from another start", False, ids,
         lambda n, s: with_ids(n / "TRANSACT.txt", lambda k: [str(start + 1 + i) for i in range(k)])),
        ("INTCALC", "fixed DEF-INTC-010: IDs not consecutive", False, ids,
         lambda n, s: with_ids(n / "TRANSACT.txt", lambda k: [str(start + 2 * i) for i in range(k)])),
        ("INTCALC", "fixed DEF-INTC-010: a byte outside TRAN-ID", False, ids,
         lambda n, s: (with_ids(n / "TRANSACT.txt", lambda k: [str(start + i) for i in range(k)]),
                    edit(n / "TRANSACT.txt", lambda r: [replace_at(r[0], after_id, other_digit(r[0][after_id]))]
                         + r[1:]))),
        ("INTCALC", "fixed DEF-JINTC-001: the cycle's interestParm as prefix", True, prefix,
         lambda n, s: with_ids(n / "TRANSACT.txt", lambda k: [d.interest_parm_of_cycle()] * k)),
        ("INTCALC", "fixed DEF-JINTC-001: the JCL PARM kept as prefix", False, prefix, lambda n, s: None),
        ("INTCALC", "fixed DEF-ACUP-002: identical outputs (no sample rate changes)", True, acup, lambda n, s: None),
        ("INTCALC", "fixed DEF-ACUP-002: an ADDR-ZIP byte", False, acup,
         lambda n, s: edit(n / "ACCTDATA.after.txt", lambda r: [replace_at(r[0], zip_at, other_digit(r[0][zip_at]))]
                           + r[1:])),
        ("INTCALC", "fixed DEF-ACUP-002: a GROUP-ID byte", False, acup,
         lambda n, s: edit(n / "ACCTDATA.after.txt", lambda r: [replace_at(r[0], grp_at, "X")] + r[1:])),
        ("INTCALC", "fixed DEF-ACUP-002: the load migrated one record less", False, acup, lambda n, s: None,
         None, -1),
        ("POSTTRAN", "legacy: the load migrated a record (the manifest was not ignored)", False, legacy,
         lambda n, s: None, None, 1),
        ("POSTTRAN", "exporter files of identical outputs", True, legacy, lambda n, s: as_export("POSTTRAN", n, s, legacy)),
        ("POSTTRAN", "exporter files in fixed DEF-ACUP-002", True, acup, lambda n, s: as_export("POSTTRAN", n, s, acup)),
        ("POSTTRAN", "exporter counts: one reject too many", False, legacy,
         lambda n, s: as_export("POSTTRAN", n, s, legacy, rejected=1 + len(d.d1_harness.split_lines(n / "DALYREJS.txt"))),
         "rejected"),
        ("POSTTRAN", "exporter counts: one TCATBAL creation too few", False, legacy,
         lambda n, s: as_export("POSTTRAN", n, s, legacy, tcatbalCreated=len(
             d.SYSOUT_CREATED.findall((s.ref / "sysout.txt").read_text(encoding="latin-1"))) - 1), "tcatbalCreated"),
        ("POSTTRAN", "exporter counts: one transaction more processed", False, legacy,
         lambda n, s: as_export("POSTTRAN", n, s, legacy, processed=1 + int(re.search(
             r"TRANSACTIONS PROCESSED :(\d+)", (s.ref / "sysout.txt").read_text(encoding="latin-1"))[1]))),
        ("POSTTRAN", "exporter counts: schemaVersion 1", False, legacy,
         lambda n, s: as_export("POSTTRAN", n, s, legacy, schemaVersion=1), "schemaVersion"),
        ("POSTTRAN", "exporter counts: another load execution", False, legacy,
         lambda n, s: as_export("POSTTRAN", n, s, legacy, loadExecutionId=SEED_LOAD + 1), "loadExecutionId"),
        ("POSTTRAN", "exporter counts: a migrated account counted as read", False, acup,
         lambda n, s: as_export("POSTTRAN", n, s, acup, accountOriginR=seed_load(s, acup)["accountsAsRead"] + 1,
                                accountOriginM=seed_load(s, acup)["accountsMigrated"] - 1), "accountOriginR"),
        ("POSTTRAN", "exporter counts: another attempt of the cycle", False, legacy,
         lambda n, s: as_export("POSTTRAN", n, s, legacy, attempt=SEED_ATTEMPT + 1), "attempt"),
        ("INTCALC", "exporter files of identical outputs", True, legacy, lambda n, s: as_export("INTCALC", n, s, legacy)),
        ("INTCALC", "exporter counts: one interest record too many", False, legacy,
         lambda n, s: as_export("INTCALC", n, s, legacy,
                                transactions=1 + len(d.d1_harness.split_lines(n / "TRANSACT.txt"))), "transactions"),
    ]]


# ---------------------------------------------------------------------------
# The DEF-ACUP-002 oracle on real GnuCOBOL runs
# ---------------------------------------------------------------------------
# Ruling v4 (rule C, computed independently in the DEF-ACUP-002 investigation): interest
# records in legacy and fixed mode, records migrated, and the accounts whose interest or
# balances change. The withdrawn rule U figures are not expectations here.
ACUP_RULING = {
    "sample": {"legacy": 50, "fixed": 50, "migrated": 50, "changed": []},
    # E4: the stress bytes with no manifest load as read and warn about the 44 records the
    # manifest would migrate.
    "stress": {"legacy": 124, "fixed": 128, "migrated": 44, "changed": [4, 8, 9, 10], "undeclared warnings": 44},
}
INTEREST_ACCOUNT = re.compile(r"Int\. for a/c (\d{11})")


def _by_account(records: list[str], key: Callable[[str], str], part: slice) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for r in records:
        out.setdefault(key(r), []).append(r[part])
    return {k: sorted(v) for k, v in out.items()}


def measure_acup(s: d.Scenario, work: Path) -> dict:
    """What the fixed DEF-ACUP-002 expectation derives from one INTCALC case's GnuCOBOL runs."""
    acup = d.Mode("fixed DEF-ACUP-002", frozenset({d.ACUP_002}))
    exp = d.expected("INTCALC", acup, s.data, s.ref, s.ref, work / "expected", d.SpringRun(), manifest=s.manifest)
    tid, ts = d.oracle.TRAN["TRAN-ID"], d.oracle.TRAN["TRAN-ORIG-TS"]
    compared = slice(tid.offset + tid.length, ts.offset)   # neither the ID nor the wall-clock times

    def interest(path: Path) -> dict[str, list[str]]:
        def account(r: str) -> str:
            m = INTEREST_ACCOUNT.search(r)
            if not m:
                raise d.ExpectationError(f"interest record {r[:16]} names no account")
            return m[1]
        return _by_account(d.d1_harness.split_lines(path), account, compared)

    def accounts(path: Path) -> dict[str, list[str]]:
        return _by_account(d.d1_harness.split_lines(path), lambda r: r[:11], slice(None))

    old_i, new_i = interest(s.ref / "TRANSACT.txt"), interest(exp / "TRANSACT.txt")
    old_a, new_a = accounts(s.ref / "ACCTDATA.after.txt"), accounts(exp / "ACCTDATA.after.txt")
    keys = set(old_i) | set(new_i) | set(old_a) | set(new_a)
    loaded = {r[:11]: r for _, r in d._account_lines(s.data / "acctdata.txt")}
    zip_at = d.oracle.ACCOUNT["ACCT-ADDR-ZIP"].offset
    return {
        "legacy": len(d.d1_harness.split_lines(s.ref / "TRANSACT.txt")),
        "fixed": len(d.d1_harness.split_lines(exp / "TRANSACT.txt")),
        "migrated": d.expected_migrated(s, acup),
        "changed": sorted(int(k) for k in keys if old_i.get(k) != new_i.get(k) or old_a.get(k) != new_a.get(k)),
        # From the ZIP on, every account record is the input's in fixed mode too.
        "layout kept": all(r[zip_at:] == loaded[k][zip_at:] for k, v in new_a.items() for r in v),
        # A load no manifest applies to stores every record as read: legacy's run (E4).
        "undeclared is legacy": d.acup_fixed_reference(s.data, s.ref, work / "undeclared", None) == (s.data, s.ref),
        "undeclared warnings": d.expected_warnings(d.Scenario(s.job, s.case, s.data, s.ref, None), acup),
    }


def acup_measurement_failures(case: str, got: dict) -> list[str]:
    want = ACUP_RULING[case] | {"layout kept": True, "undeclared is legacy": True}
    return [f"{key} is {got.get(key)!r}, the ruling gives {value!r}" for key, value in want.items()
            if got.get(key) != value]


def check_acup(cases: dict[str, d.Scenario], root: Path, out) -> list[str]:
    bad = []
    for case, s in cases.items():
        try:
            got = measure_acup(s, root / "acup-oracle" / case)
            failures = acup_measurement_failures(case, got)
        except (d.ExpectationError, d.ManifestError) as e:
            got, failures = {}, [str(e)]
        for f in failures:
            print(f"FAIL INTCALC {case} oracle fixed DEF-ACUP-002: {f}", file=out)
        if failures:
            bad.append(f"INTCALC {case} oracle fixed DEF-ACUP-002")
        else:
            print(f"ok   INTCALC {case} oracle fixed DEF-ACUP-002: {got['fixed']} records (legacy {got['legacy']}), "
                  f"{got['migrated']} migrated, accounts changed: {', '.join(map(str, got['changed'])) or 'none'}",
                  file=out)
    return bad


# ---------------------------------------------------------------------------
# The pipelines of the real GnuCOBOL cases
# ---------------------------------------------------------------------------
def pipeline_facts(cases: dict[str, d.Scenario], switches: list[str]) -> list[tuple[str, list[str], str]]:
    """(label, what does not hold, what the pipeline does): what each case's pipeline loads
    and launches, in legacy and fixed DEF-ACUP-002 mode. INTCALC sample must load the POSTTRAN sample's after-state (its
    accounts and category balances) and launch no POSTTRAN job; every manifest must name the
    bytes the load reads, not REF's after-state."""
    legacy, acup = d.Mode("legacy", frozenset()), d.Mode("fixed DEF-ACUP-002", frozenset({d.ACUP_002}))
    posttran = cases["POSTTRAN sample"]
    out = []
    for label, s in cases.items():
        for mode in (legacy, acup):
            try:
                p = d.pipeline(s, mode, switches)
                d.check_pipeline(p)
            except (d.PipelineError, d.ManifestError) as e:
                out.append((f"{label} {mode.label}", [str(e)], ""))
                continue
            load = d.sha256(s.data / "acctdata.txt")
            want = {"loads the case's input": p.source_dir == s.data.resolve() and p.acctdata_sha256 == load,
                    "not REF's after-state": load != d.sha256(s.ref / "ACCTDATA.after.txt")}
            if mode is legacy:
                want["no manifest, layout ignored"] = p.manifest is None and p.layout_source == "ignored"
            elif label == "POSTTRAN sample":
                want["built-in entry"] = p.manifest is None and p.layout_source == "builtin" \
                    and load == d.BUILTIN_SHA256
            else:
                want["supplied manifest of the loaded bytes"] = p.layout_source == "supplied" and \
                    d.LayoutManifest.read(p.manifest, str(p.manifest)).sha256 == load
            if label == "INTCALC sample":
                want["POSTTRAN sample's after-state"] = load == d.sha256(posttran.ref / "ACCTDATA.after.txt") \
                    and d.sha256(s.data / "tcatbal.txt") == d.sha256(posttran.ref / "TCATBALF.after.txt") \
                    and load != d.BUILTIN_SHA256
            want["only its own jobs"] = p.jobs == d.catalogue_job(s.job).spring and \
                (p.daily_file is None) == (d.INGEST_JOB not in p.jobs)
            detail = (f"acctdata.txt {load[:12]}, manifest {p.manifest.name if p.manifest else 'none'} "
                      f"({p.layout_source}), jobs {', '.join(p.jobs)}")
            out.append((f"{label} {mode.label}", [f"not {k}" for k, ok in want.items() if not ok], detail))
    return out


def check_pipelines(cases: dict[str, d.Scenario], out) -> list[str]:
    try:
        switches = d.batch_switches()
    except d.Unsupported as e:
        print(f"FAIL pipeline: {e}", file=out)
        return [f"pipeline: {e}"]
    bad = []
    for label, problems, detail in pipeline_facts(cases, switches):
        if problems:
            print(f"FAIL pipeline {label}: {'; '.join(problems)}{'; ' + detail if detail else ''}", file=out)
            bad.append(f"pipeline {label}")
        else:
            print(f"ok   pipeline {label}: {detail}", file=out)
    return bad


def differs(a: Path, b: Path) -> bool:
    names = {p.name for p in a.iterdir() if p.is_file()} | {p.name for p in b.iterdir() if p.is_file()}
    return any(not (a / n).exists() or not (b / n).exists() or (a / n).read_bytes() != (b / n).read_bytes()
               for n in names)


def self_test(name: str, root: Path, out=sys.stdout, scenarios: dict[str, d.Scenario] | None = None,
              acup_cases: dict[str, d.Scenario] | None = None,
              pipeline_cases: dict[str, d.Scenario] | None = None) -> int:
    if scenarios is None:
        build()
        scenarios = {"POSTTRAN": d.reference_posttran(root / "POSTTRAN", "sample")[0],
                     "INTCALC": d.reference_intcalc(root / "INTCALC", "sample")[0]}
        acup_cases = {"sample": scenarios["INTCALC"],
                      "stress": d.reference_intcalc(root / "INTCALC-stress", "stress")[0]}
        pipeline_cases = {"POSTTRAN sample": scenarios["POSTTRAN"], "INTCALC sample": scenarios["INTCALC"],
                          "INTCALC stress": acup_cases["stress"]}
    bad = check_acup(acup_cases or {}, root, out)
    bad += check_pipelines(pipeline_cases, out) if pipeline_cases else []
    for i, (job, label, must_match, mode, change, field, migrated_off) in enumerate(seeds()):
        s = scenarios[job]
        work = root / "seeded" / f"{i:02d}"
        new = work / "new"
        shutil.copytree(s.ref, new)
        try:
            change(new, s)
        except d.ExportError as e:
            matched = False
            print(f"     {job} {label}: {e}", file=out)
            # Reported only by the check on the changed field, not by any other refusal.
            if field is None or f"counts.json {field} " not in str(e):
                why = f"the exporter error does not name {field}" if field else "an exporter error for no seeded field"
                print(f"FAIL {job} {label}: {why}", file=out)
                bad.append(f"{job} {label}: {why}")
                continue
        else:
            if field is not None:
                print(f"FAIL {job} {label}: the exporter accepted a changed {field}", file=out)
                bad.append(f"{job} {label}: the exporter accepted a changed {field}")
                continue
            if not must_match and mode.label == "legacy" and not migrated_off and not differs(s.ref, new):
                print(f"FAIL {job} {label}: the seed changed nothing", file=out)
                bad.append(f"{job} {label}: the seed changed nothing")
                continue
            run = d.SpringRun(interest_id_start=d.INTEREST_ID_START, migrated=d.expected_migrated(s, mode) + migrated_off)
            matched, _ = judge(job, mode, s, new, work, run)
        good = matched == must_match
        print(f"{'ok  ' if good else 'FAIL'} {job} {label}: {'equal' if matched else 'reported'}"
              f" (expected {'equal' if must_match else 'reported'})", file=out)
        if not good:
            bad.append(f"{job} {label}")
    if bad:
        print(f"{name}: FAILED ({'; '.join(bad)})", file=out)
        return 1
    print(f"{name}: OK", file=out)
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--name")
    ap.add_argument("--keep", help="work in DIR and keep it (default: a temporary directory)")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--plan", action="store_true")
    ap.add_argument("--github-output", metavar="KEY",
                    help="with --plan: write KEY=true|false and a summary row for GitHub Actions; exit 0 either way")
    args = ap.parse_args(argv)
    name = args.name or ("verify-harness" if args.self_test else "verify-batch")
    if args.plan:
        if args.github_output:
            github_plan(args.github_output, name, sys.stdout)
            return 0
        return 0 if plan(name, sys.stdout)[0] else NOT_READY_EXIT
    with tempfile.TemporaryDirectory(prefix="verify-batch-") as tmp:
        root = Path(args.keep).resolve() if args.keep else Path(tmp)
        if args.keep and root.exists() and any(root.iterdir()):
            print(f"{name}: FAILED (--keep {root} is not empty)", file=sys.stderr)
            return 2
        # The Spring Batch exports hold full card numbers.
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(root, 0o700)
        use_private_build(root)
        try:
            if args.self_test:
                return self_test(name, root)
            return verify(name, root, os.environ.get("ONLY"), prepare=functools.cache(build))
        except SystemExit as e:
            if isinstance(e.code, str):
                print(f"{name}: FAILED ({e.code})", file=sys.stderr)
                return 2
            raise
        except (d.carddemo.CardDemoError, d.Unsupported, OSError) as e:
            print(f"{name}: FAILED ({e})", file=sys.stderr)
            return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
