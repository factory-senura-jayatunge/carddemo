"""Spec-only live cases, invoked inside infra's existing locked Session.

No database is created here. No SQL result, input value or exception text is
printed. ONLY always ends PARTIAL (3), even if it names the entire catalogue.
"""
from __future__ import annotations

from contextlib import nullcontext
from dataclasses import replace
import time
from typing import Callable

from . import fixtures, prtcatbl, tranbkp, discgrp, purge_live
from .common import Finding, pii_scan, equal_scalar, require_integer, require_execution_id, InvalidFixture
from .layouts import ROOT
from .session import Execution, Session, FixtureLock
from modernization.verify.data.oracle import live as data_live

REQUIRED = ("PRTCATBL", "TRANBKP", "PURGE", "DISCGRP", "DATALOAD")
ATOMIC_PROBE_TIMEOUT = 30.0
CASES = {
    "PRTCATBL": ("sample", "signs", "empty"),
    "TRANBKP": ("fixture", "empty", "rerun", "no_window", "atomic"),
    "PURGE": ("sample_partial", "sample_all", "edges", "year_end", "default", "one", "zero", "underflow",
              "interval_1", "interval_10", "rerun"),
    "DISCGRP": ("sample", "changed", "out_of_order", "duplicate", "no_default", "short", "missing",
                "no_window", "snapshot", "rerun"),
    "DATALOAD": ("all",),
}


def selected(only: str | None) -> tuple[str, ...]:
    if only is None:
        return REQUIRED
    names = tuple(dict.fromkeys(part.strip().upper() for part in only.split(",") if part.strip()))
    if not names or any(n not in REQUIRED for n in names):
        raise ValueError("INVALID_JOB_SELECTION")
    return names


def window_open(session: Session) -> bool:
    rows = session.as_login("fixture").query("SELECT window_id FROM ops.batch_window WHERE closed_at IS NULL")
    for row in rows:
        require_integer(row["window_id"])
    return bool(rows)


def listing(session: Session, case: str, mode: str) -> tuple[list[Finding], Execution]:
    reader = session.as_login("fixture")
    if case == "signs":
        session.fixture_execute(fixtures.SIGNS_DML)
    elif case == "empty":
        session.fixture_execute("DELETE FROM core.tcat_bal;")
    rows = reader.query("SELECT acct_id,type_cd,cat_cd,CONVERT(varchar(32),balance) balance "
                         "FROM core.tcat_bal ORDER BY acct_id,type_cd,cat_cd")
    findings = []
    if case == "sample" and len(rows) != 50:
        findings.append(Finding(case, "SAMPLE_COUNT", "core.tcat_bal"))
    before = reader.snapshot("core.tcat_bal", ("acct_id", "type_cd", "cat_cd"))
    open_before = window_open(session)
    execution = session.launch("categoryBalanceListingJob",
                               {"runKey": f"spec/{mode}/{case}", "businessDate": "2022-07-06"}).wait()
    if execution.instance_root is None or execution.job_instance_id is None:
        return findings + [Finding(case, "OUTPUT_ID_REQUIRED", "execution")], execution
    path = execution.instance_root / "reports" / "prtcatbl" / str(execution.job_instance_id) / "PRTCATBL.txt"
    try:
        raw = path.read_bytes()
    except OSError:
        return findings + [Finding(case, "OUTPUT_REQUIRED", "PRTCATBL.txt")], execution
    # Both modes reset and apply the same fixture. Exact comparison with each
    # mode's expected file implies their only differences are negative signs
    # at position 33; compare_modes documents that same cross-mode condition.
    findings += prtcatbl.validate(case, rows, mode, raw, execution.status, before,
                                 reader.snapshot("core.tcat_bal", ("acct_id", "type_cd", "cat_cd")),
                                 open_before or window_open(session))
    if not equal_scalar(execution.step_counts.get("list", {}).get("WRITE_COUNT"), len(rows)):
        findings.append(Finding(case, "LIST_WRITE_COUNT", "execution"))
    return findings, execution


def archive_rows(session: Session) -> dict:
    hashes = session.snapshot_digest("core.transaction", ("tran_pk",), exclude=("in_master",))
    flags = session.as_login("fixture").query("SELECT tran_pk,in_master FROM core.transaction ORDER BY tran_pk")
    for row in flags:
        require_integer(row["tran_pk"])
    # Adapter snapshot keys are tuples even for one-column keys.
    return {tuple([row["tran_pk"]]): dict(in_master=bool(row["in_master"]),
                                        digest=hashes[(row["tran_pk"],)]) for row in flags}


def _archive_atomic(session: Session, params: dict) -> tuple[Execution, list[dict], tranbkp.AtomicEvidence]:
    running = None
    try:
        with session.fixture_lock("SELECT TOP (1) tran_pk FROM core.transaction WITH (UPDLOCK,HOLDLOCK) "
                                  "WHERE in_master=1 ORDER BY tran_pk") as holder:
            if not isinstance(holder, FixtureLock):
                raise InvalidFixture("LOCK_SESSION_REQUIRED")
            launched = session.launch("archiveMasterJob", params)
            # An invalid ID is a usage error. Do not pass it to any observer,
            # including cleanup wait(), which may use the ID in database work.
            execution_id = require_execution_id(launched.execution_id)
            running = launched
            started = time.monotonic_ns()
            deadline = started + int(ATOMIC_PROBE_TIMEOUT * 1_000_000_000)
            proof = session.wait_for_archive_blocked(running, holder, timeout=ATOMIC_PROBE_TIMEOUT)
            received = time.monotonic_ns()
            done = running.done()
            observation_started = time.monotonic_ns()
            evidence = tranbkp.AtomicEvidence(holder, proof, execution_id, done, started, received,
                                              observation_started, observation_started, deadline)
            problems = tranbkp.atomic_evidence_findings("atomic", evidence)
            if problems:
                raise InvalidFixture(problems[0].check)
            # Only a fresh, execution-bound blocking proof permits this
            # SNAPSHOT read, while the exact holder remains entered.
            view = archive_rows(session)
            evidence = replace(evidence, observation_received_ns=time.monotonic_ns())
            problems = tranbkp.atomic_evidence_findings("atomic", evidence)
            if problems:
                raise InvalidFixture(problems[0].check)
    finally:
        # Release the exact holder before draining a valid-ID launch, also on
        # proof refusal. Invalid IDs must never reach an observer callback.
        if running is not None:
            execution = running.wait()
    if not equal_scalar(execution.execution_id, evidence.execution_id):
        raise InvalidFixture("BLOCK_EXECUTION")
    return execution, [view, archive_rows(session)], evidence


def archive(session: Session, case: str, mode: str) -> tuple[list[Finding], Execution]:
    previous = require_integer(session.cycle("2022-07-05"))
    cycle = require_integer(session.cycle("2022-07-06"))
    if case != "empty":
        session.fixture_execute(fixtures.archive(cycle, previous))
    before = archive_rows(session)
    daily = session.snapshot_digest("core.daily_tran", ("daily_pk",))
    params = {"cycleId": cycle}
    observations = []
    atomic_evidence = None
    count_before = None
    with (nullcontext() if case == "no_window" else session.window(cycle_id=cycle)):
        if case == "atomic":
            execution, observations, atomic_evidence = _archive_atomic(session, params)
        else:
            execution = session.launch("archiveMasterJob", params).wait()
        if case == "rerun":
            before = archive_rows(session)
            count_before = 300
            execution = session.launch("archiveMasterJob", params).wait()
    after = archive_rows(session)
    count = session.query(f"SELECT archived_count value FROM batch.cycle WHERE cycle_id={cycle}")[0]["value"]
    index = session.query(
        "SELECT i.is_unique,i.filter_definition,c.name column_name,ic.key_ordinal FROM sys.indexes i "
        "JOIN sys.index_columns ic ON i.object_id=ic.object_id AND i.index_id=ic.index_id "
        "JOIN sys.columns c ON c.object_id=ic.object_id AND c.column_id=ic.column_id "
        "WHERE i.object_id=OBJECT_ID('core.transaction') AND i.name='UX_tran_master' AND ic.key_ordinal>0")
    valid_index = len(index) == 1 and index[0]["column_name"] == "tran_id" and bool(index[0]["is_unique"])
    if valid_index:
        predicate = "".join(c for c in index[0]["filter_definition"] if c not in "[]() \t\r\n")
        valid_index = predicate == "in_master=1"
    findings = tranbkp.validate(case, before, after, status=execution.status, recorded_count=count,
                           index_valid=valid_index, daily_before=daily,
                           daily_after=session.snapshot_digest("core.daily_tran", ("daily_pk",)),
                           previous_count=count_before, no_window=case == "no_window",
                           observations=tuple(observations), atomic_evidence=atomic_evidence)
    if execution.status == "COMPLETED" and not equal_scalar(
            execution.step_counts.get("archive", {}).get("WRITE_COUNT"),
            sum(bool(r["in_master"]) for r in before.values())):
        findings.append(Finding(case, "ARCHIVE_WRITE_COUNT", "execution"))
    return findings, execution


def disclosure_input(case: str) -> bytes:
    raw = (ROOT / "app/data/ASCII/discgrp.txt").read_bytes()
    rows = discgrp.parse(raw)
    if case == "changed":
        rows[0]["int_rate"] += 1
        rows.pop(1)
        rows.append(dict(rows[-1], group_id="ZZSPEC"))
    elif case == "snapshot":
        # Keep DEFAULT and use sample values under 4,999 distinct group keys.
        rows = [next(r for r in rows if r["group_id"] == "DEFAULT")] + [
            dict(rows[i % len(rows)], group_id=f"S{i:09d}") for i in range(4999)]
    records = sorted(discgrp.encode(row) for row in rows)
    if case == "out_of_order":
        records[0], records[1] = records[1], records[0]
    elif case == "duplicate":
        records.insert(1, records[0])
    elif case == "no_default":
        records = [r for r in records if r[:10].rstrip(b" ") != b"DEFAULT"]
    elif case == "short":
        records[0] = records[0][:-1]
    return b"\n".join(records) + b"\n"


def disclosure(session: Session, case: str, mode: str) -> tuple[list[Finding], Execution]:
    reader = session.as_login("fixture")
    raw = disclosure_input(case)
    path = session.var_dir / "inbound/discgrp/2022-07-06.txt"
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if case != "missing":
        path.write_bytes(raw)
        path.chmod(0o600)
    elif path.exists():
        path.unlink()
    keys = ("group_id", "type_cd", "cat_cd")
    before = reader.snapshot("ref.disclosure_group", keys)
    params = dict(businessDate="2022-07-06", windowRunKey=f"spec/{mode}/{case}")
    observations = []
    with (nullcontext() if case == "no_window" else session.window(run_key=params["windowRunKey"])):
        running = session.launch("disclosureGroupReloadJob", params)
        if case == "snapshot":
            observations.append(reader.snapshot("ref.disclosure_group", keys))
            deadline = time.monotonic() + 120
            while not running.done():
                if time.monotonic() > deadline:
                    raise TimeoutError("RELOAD_TIMEOUT")
                observations.append(reader.snapshot("ref.disclosure_group", keys))
                time.sleep(0.05)
        execution = running.wait()
        if case == "rerun":
            # Reload rewrites every row, so only this rerun comparison excludes
            # generated row versions. Refused reloads below keep every column.
            first = reader.snapshot("ref.disclosure_group", keys, exclude=("row_version",))
            params["runKey"] = f"spec/{mode}/{case}/second"
            execution = session.launch("disclosureGroupReloadJob", params).wait()
            if reader.snapshot("ref.disclosure_group", keys, exclude=("row_version",)) != first:
                return [Finding(case, "RERUN_UNCHANGED", "ref.disclosure_group")], execution
    after = reader.snapshot("ref.disclosure_group", keys)
    if case in ("out_of_order", "duplicate", "no_default", "short", "missing", "no_window"):
        return discgrp.validate_rejected(case, status=execution.status, before=before, after=after,
                                         exit_description=execution.exit_description), execution
    rows = reader.query("SELECT group_id,type_cd,cat_cd,CONVERT(varchar(32),int_rate) int_rate "
                         "FROM ref.disclosure_group ORDER BY group_id,type_cd,cat_cd")
    findings = discgrp.validate(case, raw, rows, status=execution.status, source_after=path.read_bytes(),
                               observations=tuple(observations), old_hashes=before, new_hashes=after)
    counts = execution.step_counts.get("reload", {})
    if any(not equal_scalar(counts.get(k), len(discgrp.lines(raw))) for k in ("READ_COUNT", "WRITE_COUNT")):
        findings.append(Finding(case, "RELOAD_COUNTS", "execution"))
    return findings, execution


HANDLERS: dict[str, Callable] = {"PRTCATBL": listing, "TRANBKP": archive, "DISCGRP": disclosure,
                              "PURGE": purge_live.run}


def run(session: Session, only: str | None = None, out=print) -> int:
    jobs = selected(only)
    failures = []
    if "PURGE" in jobs:
        out("note PURGE: row_version excluded only for summaries expected to update totals or credit_balance")
    for job in jobs:
        if job == "DATALOAD":
            try:
                code = data_live.run(session, out=out)
                if code != 0:
                    failures.append(Finding("all", "DATA_ORACLE_FAILED", "dataLoadJob"))
            except Exception:
                failures.append(Finding("all", "DATA_ORACLE_EXECUTION_FAILED", "dataLoadJob"))
            continue
        for mode in ("legacy", "fixed"):
            for case in CASES[job]:
                execution = Execution("NOT_RUN")
                findings = []
                try:
                    properties = ({"carddemo.batch.pauth.commit-interval": case.rsplit("_", 1)[1]}
                                  if job == "PURGE" and case.startswith("interval_") else None)
                    session.reset(mode=mode, properties=properties)
                    session.fixture_execute(fixtures.PII_DML)
                    if job not in HANDLERS:
                        findings = [Finding(case, "VALIDATOR_NOT_READY", job)]
                    else:
                        findings, execution = HANDLERS[job](session, case, mode)
                except Exception:
                    # Never stringify exceptions: drivers may include SQL or PII.
                    findings.append(Finding(case, "CASE_EXECUTION_FAILED", job))
                try:
                    report = "\n".join(map(str, findings))
                    findings += pii_scan(case, {"validator.report": report,
                                                "execution.exit_description": execution.exit_description,
                                                "server.log": session.log_bytes()}, fixtures.SENTINELS)
                except Exception:
                    findings.append(Finding(case, "PII_SCAN_FAILED", job))
                out(f"{'FAIL' if findings else 'ok'} {job} {mode} {case}")
                for finding in findings:
                    out(str(finding))
                failures.extend(findings)
    if failures:
        out(f"verify-batch-spec: FAILED ({len(failures)} checks)")
        return 1
    if only is not None:
        out("verify-batch-spec: PARTIAL")
        return 3
    out("verify-batch-spec: OK")
    return 0
