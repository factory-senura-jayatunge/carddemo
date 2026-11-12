"""Source-derived dataLoadJob counts and context, architecture check 6."""
from __future__ import annotations

import hashlib

from . import hd, lookups, source
from modernization.verify.spec_jobs.common import Finding, equal_scalar
from modernization.verify.spec_jobs.layouts import ROOT
from modernization.verify.spec_jobs.session import Execution

FILE_STEPS = dict(zip(source.TABLES, (
    "loadAccounts", "loadCustomers", "loadCards", "loadCardXref", "loadCategoryBalances",
    "loadTransactionTypes", "loadCategories", "loadDisclosureGroups")))


def line_ranges(numbers: list[int]) -> str:
    ranges = []
    for number in numbers:
        if ranges and number == ranges[-1][1] + 1:
            ranges[-1][1] = number
        else:
            ranges.append([number, number])
    result = ", ".join(str(a) if a == b else f"{a}-{b}" for a, b in ranges[:100])
    remaining = sum(b - a + 1 for a, b in ranges[100:])
    return result + (f" and {remaining} more" if remaining else "")


def account_report(raw: bytes, mode: str, manifest=None) -> tuple[dict, dict]:
    """Contract v2 sections 1.9 and 1.10; legacy never opens the manifest."""
    declaration = manifest.read_bytes() if manifest is not None and mode == "fixed" else None
    layouts = source.account_layouts(raw, mode, declaration)
    account_hash = hashlib.sha256(raw).hexdigest()
    manifest_hash = hashlib.sha256(declaration).hexdigest() if declaration is not None else ""
    layout_source = ("ignored" if mode == "legacy" else "supplied" if manifest is not None else
                     "builtin" if account_hash == source.SAMPLE_ACCOUNT_SHA else "none")
    # A partially blank or mixed space/NUL ZIP is NOT blank. Short physical
    # lines are padded before examining either ten-character field.
    records = [r.ljust(300, b" ") for r in source.lines(raw)]
    # Load-report contract §1.8 counts rows stored as R/M, not distinct IDs.
    # Duplicate keys are separately refused by the source-row validator.
    migrated = sum(layouts[r[:11].decode("latin-1")] == "ACUP_UPDATE" for r in records)
    warnings = sum(layouts[r[:11].decode("latin-1")] == "CVACT01Y" and
                   not source.blank(r[102:112].decode("latin-1")) and
                   source.blank(r[112:122].decode("latin-1"))
                   for r in records) if mode == "fixed" else 0
    context = dict(accountsAsRead=len(records) - migrated, accountsMigrated=migrated,
                   accountLayoutSource=layout_source, accountLayoutWarnings=warnings,
                   acctdataSha256=account_hash, accountLayoutManifestSha256=manifest_hash,
                   groupIdMode=mode)
    job = {"dataLoad.acctdataSha256": account_hash}
    if declaration is not None:
        job["dataLoad.accountLayoutManifestSha256"] = manifest_hash
    return context, job


def expected(mode, source_dir, authorization_file, manifest=None) -> tuple[dict, dict, dict]:
    counts = {}
    for name, step in FILE_STEPS.items():
        count = len(source.lines((source_dir / source.TABLES[name].filename).read_bytes()))
        counts[step] = dict(READ_COUNT=count, WRITE_COUNT=count)
    lists = lookups.parse((ROOT / "app/cpy/CSLKPCDY.cpy").read_text())
    counts["loadLookups"] = dict(WRITE_COUNT=sum(map(len, lists.values())))
    raw_hd = authorization_file.read_bytes()
    summaries, details = hd.parse(raw_hd)
    # RDW segment records include the blank root, but not header or trailer.
    offset, records = 0, 0
    while offset < len(raw_hd):
        offset += int.from_bytes(raw_hd[offset:offset + 2], "big")
        records += 1
    counts["loadPendingAuthorizations"] = dict(READ_COUNT=records - 2,
                                              WRITE_COUNT=len(summaries) + len(details))
    raw = (source_dir / "acctdata.txt").read_bytes()
    accounts, job = account_report(raw, mode, manifest)
    customers = source.lines((source_dir / "custdata.txt").read_bytes())
    normalised = [i for i, r in enumerate(customers, 1) if r.ljust(500, b" ")[332:500] != b" " * 168]
    contexts = {
        "loadAccounts": accounts,
        "loadCustomers": dict(customerFillerNormalised=len(normalised),
                              customerFillerNormalisedLines=line_ranges(normalised)),
    }
    return counts, contexts, job


def validate(execution: Execution, *, mode, source_dir, authorization_file, manifest=None) -> list[Finding]:
    counts, contexts, job = expected(mode, source_dir, authorization_file, manifest)
    findings = []
    if execution.status != "COMPLETED":
        findings.append(Finding(mode, "LOAD_COMPLETED", "dataLoadJob"))
    for step, wanted in counts.items():
        for key, value in wanted.items():
            if not equal_scalar(execution.step_counts.get(step, {}).get(key), value):
                findings.append(Finding(mode, "LOAD_STEP_COUNT", step, column=key))
    for step, wanted in contexts.items():
        for key, value in wanted.items():
            if not equal_scalar(execution.contexts.get(step, {}).get(key), value):
                findings.append(Finding(mode, "LOAD_STEP_CONTEXT", step, column=key))
    for key, value in job.items():
        if execution.job_context.get(key) != value:
            findings.append(Finding(mode, "LOAD_JOB_CONTEXT", "dataLoadJob", column=key))
    if "dataLoad.accountLayoutManifestSha256" not in job and "dataLoad.accountLayoutManifestSha256" in execution.job_context:
        findings.append(Finding(mode, "LOAD_JOB_CONTEXT", "dataLoadJob",
                                column="dataLoad.accountLayoutManifestSha256"))
    return findings


def validate_account_failure(execution: Execution, raw: bytes, manifest) -> list[Finding]:
    """Fixed supplied-manifest refusal, contract v2 table rows 6 to 8."""
    findings = []
    context = execution.contexts.get("loadAccounts", {})
    for key in ("accountLayoutSource", "accountLayoutManifestSha256"):
        if key in context:
            findings.append(Finding("manifest", "FAILED_ACCOUNT_REPORT_ABSENT", "loadAccounts", column=key))
    try:
        declaration = manifest.read_bytes()
        if len(declaration) > 1024 * 1024:
            raise OSError
    except OSError:
        wanted = {}
    else:
        wanted = {"dataLoad.acctdataSha256": hashlib.sha256(raw).hexdigest(),
                  "dataLoad.accountLayoutManifestSha256": hashlib.sha256(declaration).hexdigest()}
    for key in ("dataLoad.acctdataSha256", "dataLoad.accountLayoutManifestSha256"):
        if (key in wanted and execution.job_context.get(key) != wanted[key]) or (
                key not in wanted and key in execution.job_context):
            findings.append(Finding("manifest", "LOAD_JOB_CONTEXT", "dataLoadJob", column=key))
    return findings
