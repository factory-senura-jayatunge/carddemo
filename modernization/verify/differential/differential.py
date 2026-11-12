"""The differential harness of make verify-batch and make verify-harness.

For each batch job of the architecture's catalogue that has a COBOL
reference, the GnuCOBOL side and the comparison are the spec checks' own
(docs/modernization/spec/checks/): the same reference runner, the same data
and stress data, and the same compare.py with its masks. The Spring Batch
side is a producer that writes the directory layout that compare.py reads.
Until a job's producer exists, the job fails: nothing passes by default.

Values the GnuCOBOL runner uses (the INTCALC PARM, job and DD names) are read
from tools/carddemo.py and the checks, never copied; the fidelity switches are
read from batch-service's BatchSwitches.java.
"""
from __future__ import annotations

import ast
import contextlib
import dataclasses
import hashlib
import importlib.util
import io
import json
import os
import re
import shutil
import sys
from pathlib import Path
from typing import Callable, NamedTuple

HERE = Path(__file__).resolve().parent
MODERNIZATION = HERE.parents[1]
REPO = MODERNIZATION.parent
CHECKS = REPO / "docs" / "modernization" / "spec" / "checks"
BATCH_SWITCHES = (MODERNIZATION / "batch" / "batch-service" / "src" / "main" / "java" / "org" / "carddemo"
                  / "batch" / "BatchSwitches.java")
sys.path.insert(0, str(CHECKS))

import d1_harness  # noqa: E402
import harness  # noqa: E402
from tests import oracle  # noqa: E402  (harness puts the repository root on sys.path)

carddemo = harness.carddemo
CASES = ("sample", "stress")
INTEREST_PARM_ENV = "CARDDEMO_INTEREST_LEGACY_PARM"


class Unsupported(Exception):
    """A required part is missing; the job fails, it is never skipped."""


@dataclasses.dataclass(frozen=True)
class CatalogueJob:
    legacy: str                  # job name in tools/carddemo.py, or the JCL job of the catalogue
    spring: tuple[str, ...]      # batch-service jobs, in run order
    check: str | None            # folder under docs/modernization/spec/checks
    reason: str = ""             # why the job is not compared here (check is None)


# architecture/batch.md, job catalogue. Every runnable job of tools/carddemo.py
# is listed; a job with a check folder is required for a full run.
CATALOGUE = (
    CatalogueJob("POSTTRAN", ("ingestDailyFileJob", "postDailyTransactionsJob"), "posttran_reimpl"),
    CatalogueJob("INTCALC", ("calculateInterestJob",), "intcalc_reimpl"),
    CatalogueJob("TRANREPT", ("transactionReportJob",), "tranrept_reimpl"),
    CatalogueJob("CREASTMT", ("statementJob",), "cbstm03a_harness"),
    CatalogueJob("CBEXPORT", ("exportMastersJob",), "cbexport_reimpl"),
    CatalogueJob("CBIMPORT", ("importMigrationJob",), "cbimport_reimpl"),
    CatalogueJob("TRANVALD", (), None, "no target job: CBTRN01C only reads and displays the daily file"),
    CatalogueJob("READACCT", (), None, "no target job: a diagnostic listing of the account file"),
    CatalogueJob("READCARD", (), None, "no target job: a diagnostic listing of the card file"),
    CatalogueJob("READXREF", (), None, "no target job: a diagnostic listing of the cross-reference file"),
    CatalogueJob("READCUST", (), None, "no target job: a diagnostic listing of the customer file"),
    CatalogueJob("WAITSTEP", (), None, "no target job: a timed wait between steps"),
)
REQUIRED = tuple(j.legacy for j in CATALOGUE if j.check)


def catalogue_job(name: str) -> CatalogueJob:
    for job in CATALOGUE:
        if job.legacy == name:
            return job
    raise KeyError(name)


def unclassified_runner_jobs() -> list[str]:
    """Runnable jobs of tools/carddemo.py that the catalogue above does not name."""
    known = {j.legacy for j in CATALOGUE}
    return sorted(name for name in carddemo.JOBS if name not in known)


# ---------------------------------------------------------------------------
# Values passed to batch-service
# ---------------------------------------------------------------------------
def interest_parm(data_dir: Path | None = None) -> str:
    """The PARM GnuCOBOL's INTCALC gets for this data: the case's parm.txt
    (d1_harness.run_reference reads it) or the runner's job definition."""
    if data_dir is not None and (data_dir / "parm.txt").exists():
        return (data_dir / "parm.txt").read_text().strip("\r\n")
    parm = carddemo.find_job("INTCALC").parm
    if not parm:
        raise Unsupported("tools/carddemo.py defines no PARM for INTCALC")
    return parm


SWITCH_RE = re.compile(r'FidelitySwitch\.of\(\s*"([A-Z]+-[A-Z0-9]+-\d{3})"')


def batch_switches(path: Path = BATCH_SWITCHES) -> list[str]:
    """The item IDs batch-service declares as switches, in declaration order."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        raise Unsupported(f"no {path.relative_to(MODERNIZATION) if path.is_relative_to(MODERNIZATION) else path}")
    ids = SWITCH_RE.findall(text)
    if not ids:
        raise Unsupported(f"{path.name} declares no switch")
    return ids


def interest_parm_of_cycle(business_date: str = carddemo.DEFAULT_BUSINESS_DATE) -> str:
    """batch.cycle.interest_parm: yyyyMMdd00 of the business date (D-59)."""
    return business_date.replace("-", "") + "00"


@dataclasses.dataclass(frozen=True)
class Mode:
    """One fidelity setting of a run: the switches set to fixed, every other one legacy."""
    label: str
    fixed: frozenset[str]


def modes(switches: list[str]) -> list[Mode]:
    """All legacy, each switch fixed alone, and all fixed: a fix is checked on its own
    and together with the others, not only as their union."""
    out = [Mode("legacy", frozenset())]
    out += [Mode(f"fixed {item}", frozenset({item})) for item in switches]
    if len(switches) > 1:
        out.append(Mode("all fixed", frozenset(switches)))
    return out


def spring_settings(mode: Mode, switches: list[str], parm: str) -> tuple[list[str], dict[str, str]]:
    """Arguments and environment of a batch-service run in one mode. Every switch is
    set explicitly, so no default decides a comparison."""
    unknown = mode.fixed - set(switches)
    if unknown:
        raise ValueError(f"not declared: {sorted(unknown)}")
    args = [f"--carddemo.fidelity.{item}={'fixed' if item in mode.fixed else 'legacy'}" for item in switches]
    return args, {INTEREST_PARM_ENV: parm}


# A fresh database's next interest TRAN-ID under DEF-INTC-010; the self-test's seeds
# start there. A live run reads the value from its database instead.
INTEREST_ID_START = 9000000000000000


@dataclasses.dataclass(frozen=True)
class SpringRun:
    """What the driver read from its own database for one Spring Batch run. Nothing is
    defaulted: a value the run did not read stays None, and an expectation that needs it fails."""
    interest_parm: str | None = None       # the cycle's, as POST /internal/cycles returned it
    interest_id_start: int | None = None   # the interest sequence's next value before INTCALC
    migrated: int | None = None            # the account load report's "migrated" count


ACUP_002 = "DEF-ACUP-002"


# ---------------------------------------------------------------------------
# DEF-ACUP-002: account layout manifests (architect ruling v4; data's layout manifest
# schema 1 and export contract v2, build-data handoff of 2026-11-11)
# ---------------------------------------------------------------------------
CVACT01Y, ACUP_UPDATE = "CVACT01Y", "ACUP_UPDATE"
LAYOUTS = (CVACT01Y, ACUP_UPDATE)
LAYOUT_MANIFEST_PROPERTY = "carddemo.data-load.account-layout-manifest"
# batch-service's only built-in entry, bound to these bytes wherever a copy lies. The
# EBCDIC copy has none: its account 49 holds ZEROAPR at 103, a population D-11 did not decide.
BUILTIN_SAMPLE = REPO / "app" / "data" / "ASCII" / "acctdata.txt"
BUILTIN_SHA256 = "c2a97b6a32dc4a87a7aafdf7f72e6712e560412d30b00c5526cca80fc9dfd260"
EBCDIC_ACCOUNTS = REPO / "app" / "data" / "EBCDIC" / "AWS.M2.CARDDEMO.ACCTDATA.PS"
ACCOUNT_LRECL = 300
MANIFEST_SCHEMA = 1
MANIFEST_MAX_BYTES = 1 << 20
MANIFEST_REQUIRED = ("schema", "sha256", "default_layout")
MANIFEST_KEYS = (*MANIFEST_REQUIRED, "other_layout_accounts")


class ManifestError(Exception):
    """What makes batch-service's account load fail before its first write."""


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


_BARE_KEY = re.compile(r"[A-Za-z0-9_-]+")
_INTEGER = re.compile(r"[+-]?(?:0|[1-9][0-9]*)")


def _malformed(n: int, reason: str) -> ManifestError:
    return ManifestError(f"layout manifest line {n}: {reason}")


def _basic_string(rest: str, n: int) -> tuple[str, str]:
    """rest starts with the opening quote; returns the value and what follows the closing one."""
    end = rest.find('"', 1)
    if end < 0:
        raise _malformed(n, "the string is not closed on its line")
    value = rest[1:end]
    if "\\" in value:
        raise _malformed(n, "a backslash is not allowed in a string")
    if any(c < " " and c != "\t" or c == "\x7f" for c in value):
        raise _malformed(n, "a control character is not allowed in a string")
    return value, rest[end + 1:]


def _value_end(rest: str, n: int) -> None:
    rest = rest.lstrip(" \t")
    if rest and not rest.startswith("#"):
        raise _malformed(n, "unexpected text after the value")


def _manifest_values(text: str) -> dict[str, tuple[int, object]]:
    """The accepted TOML subset (contract section 1.2): top-level bare keys, each once, with a
    basic string without escapes, a decimal integer or an array of such strings that may span
    lines and end with a comma. Returns key -> (line, value)."""
    lines = text.split("\n")
    lines = [line[:-1] if line.endswith("\r") else line for line in lines]
    values: dict[str, tuple[int, object]] = {}
    i = 0
    while i < len(lines):
        n, rest = i + 1, lines[i].lstrip(" \t")
        i += 1
        if "\r" in rest:
            raise _malformed(n, "a carriage return outside a CRLF line end")
        if not rest or rest.startswith("#"):
            continue
        key = _BARE_KEY.match(rest)
        if not key:
            raise _malformed(n, "expected key = value (no tables, no quoted keys)")
        rest = rest[key.end():].lstrip(" \t")
        if rest.startswith("."):
            raise _malformed(n, "dotted keys are not allowed")
        if not rest.startswith("="):
            raise _malformed(n, "expected = after the key")
        if key[0] in values:
            raise _malformed(n, f"{key[0]} is given twice")
        rest = rest[1:].lstrip(" \t")
        if rest.startswith('"'):
            value, rest = _basic_string(rest, n)
            _value_end(rest, n)
        elif rest.startswith("["):
            value, rest, i, last = _string_array(lines, i, rest[1:], n)
            _value_end(rest, last)
        else:
            m = _INTEGER.match(rest)
            if not m:
                raise _malformed(n, "a value is a string, a decimal integer or an array of strings")
            value, rest = int(m[0]), rest[m.end():]
            _value_end(rest, n)
        values[key[0]] = (n, value)
    return values


def _string_array(lines: list[str], i: int, rest: str, n: int) -> tuple[list[str], str, int, int]:
    """rest follows the opening bracket on line n; lines[i] is the next line."""
    items: list[str] = []
    want_item, opened = True, n
    while True:
        rest = rest.lstrip(" \t")
        if not rest or rest.startswith("#"):
            if i >= len(lines):
                raise _malformed(opened, "the array is not closed")
            n, rest = i + 1, lines[i]
            i += 1
            if "\r" in rest:
                raise _malformed(n, "a carriage return outside a CRLF line end")
            continue
        if rest.startswith("]"):
            return items, rest[1:], i, n
        if not want_item:
            if not rest.startswith(","):
                raise _malformed(n, "expected , or ] in the array")
            rest, want_item = rest[1:], True
            continue
        if not rest.startswith('"'):
            raise _malformed(n, "an array holds only strings")
        item, rest = _basic_string(rest, n)
        items.append(item)
        want_item = False


@dataclasses.dataclass(frozen=True)
class LayoutManifest:
    """A layout manifest of schema 1; to_toml and parse are the only places that name its keys."""
    sha256: str
    layout: str                    # default_layout: every record not listed in other
    other: tuple[str, ...] = ()    # other_layout_accounts: account IDs in the other layout

    def to_toml(self) -> str:
        ids = ", ".join(f'"{a}"' for a in self.other)
        return (f"schema = {MANIFEST_SCHEMA}\nsha256 = \"{self.sha256}\"\ndefault_layout = \"{self.layout}\"\n"
                f"other_layout_accounts = [{ids}]\n")

    @classmethod
    def parse(cls, text: str) -> "LayoutManifest":
        """Syntax first, then each key in line order, then the missing ones (contract 1.5)."""
        values = _manifest_values(text)
        for key, (n, value) in sorted(values.items(), key=lambda kv: kv[1][0]):
            if key not in MANIFEST_KEYS:
                raise _malformed(n, f"unknown key {key}")
            if key == "schema" and value != MANIFEST_SCHEMA:
                raise _malformed(n, f"schema must be {MANIFEST_SCHEMA}")
            if key == "sha256" and not (isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value)):
                raise _malformed(n, "sha256 must be 64 lowercase hex digits")
            if key == "default_layout" and value not in LAYOUTS:
                raise _malformed(n, "default_layout must be CVACT01Y or ACUP_UPDATE")
            if key == "other_layout_accounts" and not isinstance(value, list):
                raise _malformed(n, "other_layout_accounts must be an array of strings")
        for key in MANIFEST_REQUIRED:
            if key not in values:
                raise ManifestError(f"layout manifest: missing {key}")
        other = values.get("other_layout_accounts", (0, []))[1]
        return cls(str(values["sha256"][1]), str(values["default_layout"][1]), tuple(other))

    @classmethod
    def read(cls, path: Path, configured: str) -> "LayoutManifest":
        """The supplied manifest as the load reads it; configured is the property's value."""
        if not path.is_file():
            raise ManifestError(f"layout manifest not found: {configured}")
        raw = path.read_bytes()
        if len(raw) > MANIFEST_MAX_BYTES:
            raise ManifestError("layout manifest is larger than 1 MiB")
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError as e:
            raise _malformed(raw.count(b"\n", 0, e.start) + 1, "not UTF-8") from None
        return cls.parse(text)


def write_manifest(acctdata: Path, path: Path, other: tuple[str, ...] = (), layout: str = ACUP_UPDATE) -> Path:
    """A manifest for acctdata as it is now: the hash is taken after the file is written, never pinned."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(LayoutManifest(sha256(acctdata), layout, tuple(other)).to_toml(), encoding="utf-8")
    return path


def layout_manifest_args(manifest: Path | None) -> list[str]:
    """The data load's option for a supplied manifest. It is a property, fixed when the JVM
    starts, so each case with its own manifest needs its own load."""
    return [] if manifest is None else [f"--{LAYOUT_MANIFEST_PROPERTY}={manifest.resolve()}"]


def applied_manifest(acctdata: Path, supplied: Path | None) -> LayoutManifest | None:
    """The manifest a fixed-mode load applies: the supplied one, which must name the file's
    SHA-256; else the built-in entry when the bytes are the ASCII sample's; else none."""
    digest = sha256(acctdata)
    if supplied is not None:
        manifest = LayoutManifest.read(supplied, str(supplied.resolve()))
        if manifest.sha256 != digest:
            raise ManifestError("layout manifest does not match acctdata.txt")
        return manifest
    return LayoutManifest(digest, ACUP_UPDATE) if digest == BUILTIN_SHA256 else None


class AccountLoad(NamedTuple):
    records: list[str]             # the stored rows as a CVACT01Y reader sees them
    migrated: int
    ambiguous_lines: list[int]     # stored as read with 113-122 blank and 103-112 not


def _blank(text: str) -> bool:
    """Ruling v4: all spaces or all U+0000, nothing else (a mix is data)."""
    return text in (" " * len(text), "\0" * len(text))


def _account_lines(acctdata: Path) -> list[tuple[int, str]]:
    out = []
    for n, raw in enumerate(acctdata.read_bytes().split(b"\n"), start=1):
        line = raw[:-1] if raw.endswith(b"\r") else raw
        if line:
            out.append((n, line.decode("latin-1").ljust(ACCOUNT_LRECL)))
    return out


def load_accounts(acctdata: Path, manifest: LayoutManifest | None) -> AccountLoad:
    """The fixed-mode account load of ruling v4, section 2, implemented here on its own:
    every check passes before a row is produced, and nothing is inferred from the content."""
    zip_f, grp, a_id = (_field(oracle.ACCOUNT, n) for n in ("ACCT-ADDR-ZIP", "ACCT-GROUP-ID", "ACCT-ID"))
    lines = _account_lines(acctdata)
    if manifest is not None:
        ids = {r[a_id] for _, r in lines}
        seen: set[str] = set()
        for i, acct in enumerate(manifest.other, start=1):
            if not re.fullmatch(r"[0-9]{11}", acct):
                raise ManifestError(f"layout manifest override {i}: not 11 digits")
            if acct in seen:
                raise ManifestError(f"layout manifest override {i}: listed twice")
            if acct not in ids:
                raise ManifestError(f"layout manifest override {i}: not in acctdata.txt")
            seen.add(acct)
    flip = {CVACT01Y: ACUP_UPDATE, ACUP_UPDATE: CVACT01Y}
    records, migrated, ambiguous = [], 0, []
    for n, rec in lines:
        layout = CVACT01Y if manifest is None else (
            flip[manifest.layout] if rec[a_id] in manifest.other else manifest.layout)
        if layout == ACUP_UPDATE:
            if not _blank(rec[grp]):
                raise ManifestError(f"acctdata.txt line {n}: declared ACUP_UPDATE but positions 113-122 are not blank")
            group = "" if _blank(rec[zip_f]) else rec[zip_f].rstrip(" ")
            rec = rec[:zip_f.start] + " " * (zip_f.stop - zip_f.start) + group.ljust(grp.stop - grp.start) \
                + rec[grp.stop:]
            migrated += 1
        elif _blank(rec[grp]) and not _blank(rec[zip_f]):
            ambiguous.append(n)
        records.append(rec)
    return AccountLoad(records, migrated, ambiguous)


# The stress generators' own constructions: the accounts each writes a group at 113 for
# (ruling v4, section 7), read from the generator's source, never from the bytes. The
# POSTTRAN generator changes dates and limits only; a group it ever writes at 113 makes the
# declared-layout check refuse the case instead of passing it unexercised.
STRESS_GROUPS = {"INTCALC": ("intcalc_reimpl", "groups")}


def stress_overrides(job: str) -> tuple[str, ...]:
    if job not in STRESS_GROUPS:
        return ()
    folder, name = STRESS_GROUPS[job]
    path = CHECKS / folder / "gen_stress.py"
    found = [node.value for node in ast.walk(ast.parse(path.read_text(encoding="utf-8")))
             if isinstance(node, ast.Assign) and [getattr(t, "id", None) for t in node.targets] == [name]]
    if len(found) != 1 or not isinstance(found[0], ast.Dict) \
            or not all(isinstance(k, ast.Constant) and isinstance(k.value, int) for k in found[0].keys):
        raise Unsupported(f"{path.name}: no single {name} = {{account number: group}} to take the overrides from")
    return tuple(f"{k.value:011d}" for k in found[0].keys)


def expected_migrated(scenario: "Scenario", mode: Mode) -> int:
    if ACUP_002 not in mode.fixed:
        return 0
    acctdata = scenario.data / "acctdata.txt"
    return load_accounts(acctdata, applied_manifest(acctdata, scenario.manifest)).migrated


def expected_warnings(scenario: "Scenario", mode: Mode) -> int:
    """The load report's accountLayoutWarnings: fixed mode only (manifest contract 1.10)."""
    if ACUP_002 not in mode.fixed:
        return 0
    acctdata = scenario.data / "acctdata.txt"
    return len(load_accounts(acctdata, applied_manifest(acctdata, scenario.manifest)).ambiguous_lines)


def ebcdic_accounts(dest: Path) -> Path:
    """The EBCDIC copy of the account file converted as a transfer would (ruling v4, E17):
    code page 037, one 300-byte record per line. Every field is DISPLAY, so the zoned signs
    come out as the ASCII sample's overpunch letters."""
    raw = EBCDIC_ACCOUNTS.read_bytes()
    if not raw or len(raw) % ACCOUNT_LRECL:
        raise ManifestError(f"{EBCDIC_ACCOUNTS.name} is not whole {ACCOUNT_LRECL}-byte records")
    text = raw.decode("cp037")
    dest.write_bytes("".join(text[i:i + ACCOUNT_LRECL] + "\n"
                             for i in range(0, len(text), ACCOUNT_LRECL)).encode("latin-1"))
    return dest


def check_load(scenario: "Scenario", mode: Mode, run: SpringRun) -> None:
    """The load report's migrated count, so a case that loaded as read cannot pass unexercised."""
    if run.migrated is None:
        raise ExpectationError("the producer did not report the account load's migrated count")
    try:
        want = expected_migrated(scenario, mode)
    except ManifestError as e:
        raise ExpectationError(f"the case's account load must fail: {e}") from None
    if run.migrated != want:
        raise ExpectationError(f"the account load migrated {run.migrated} records, expected {want}")


# What verify/differential's exec jar writes per --job; counts.json is written last.
EXPORT_FILES = {
    "POSTTRAN": ("DALYREJS.txt", "ACCTDATA.after.txt", "TCATBALF.after.txt", "TRANSACT.after.txt"),
    "INTCALC": ("TRANSACT.txt", "ACCTDATA.after.txt"),
}
# counts.json schemaVersion 2, every key in this order (load report and export v2, 3.3).
_COUNTS_HEAD = ("schemaVersion", "job", "cycleId", "attempt", "loadExecutionId", "loadAccountsAsRead",
                "loadAccountsMigrated", "loadAccountLayoutSource", "loadAcctdataSha256")
_COUNTS_TAIL = ("accountRecords", "accountOriginR", "accountOriginM", "accountOriginNone",
                "accountZipGroupRestored", "accountZipGroupRebuilt")
COUNT_FIELDS = {
    "POSTTRAN": (*_COUNTS_HEAD, "processed", "rejected", "tcatbalCreated", "rc", *_COUNTS_TAIL),
    "INTCALC": (*_COUNTS_HEAD, "accounts", "transactions", "rc", *_COUNTS_TAIL),
}
# Each counts.json load value and the load report key it repeats.
COUNTS_FROM_LOAD = (("loadExecutionId", "loadExecutionId"), ("loadAccountsAsRead", "accountsAsRead"),
                    ("loadAccountsMigrated", "accountsMigrated"), ("loadAccountLayoutSource", "accountLayoutSource"),
                    ("loadAcctdataSha256", "acctdataSha256"))


# Load report contract 1.4, section 1.1: the batch sequences start at 0, and every entry point
# takes Java's signed 64-bit range, so 0 through Long.MAX_VALUE.
MAX_EXECUTION_ID = 2 ** 63 - 1


def is_execution_id(value) -> bool:
    return type(value) is int and 0 <= value <= MAX_EXECUTION_ID


def exporter_command(jar: Path, export: Path, job: str, cycle_id: int, load_execution: int,
                     output_dir: Path | None = None) -> list[str]:
    """Export v2: --load-execution-id binds the export to the load the report came from, and
    the exporter refuses a cycle job that started before it. No --fidelity: v2 puts the
    account records back together by each row's stored origin."""
    if job not in EXPORT_FILES:
        raise Unsupported(f"{job}: the exporter has no --job {job}")
    if not is_execution_id(load_execution):
        raise PipelineError(f"{job}: the export needs the load's execution ID, not {load_execution!r}")
    cmd = ["java", "-jar", str(jar), "--export", str(export), "--job", job, "--cycle-id", str(cycle_id),
           "--load-execution-id", str(load_execution)]
    if output_dir is not None:
        cmd += ["--output-dir", str(output_dir)]
    return cmd


class ExportError(Exception):
    """An export that is incomplete, or whose counts disagree with its files or the input."""


TCAT_KEY = 17


def _created_lines(data: Path, after: Path) -> list[str]:
    """CBTRN02C's line for each category balance it had to create: the keys of the
    after-state that the input did not hold. Sorted, as kept_sysout sorts them."""
    before = {r.decode("latin-1")[:TCAT_KEY] for r in carddemo.read_text_records(data / "tcatbal.txt", 50)}
    keys = sorted({r[:TCAT_KEY] for r in d1_harness.split_lines(after)} - before)
    return [f"TCATBAL record not found for key : {k}.. Creating." for k in keys]


class JsonPairs(list):
    """A JSON object's members in their order, duplicates kept."""


def from_export(job: str, export: Path, data: Path, cycle_id: int, attempt: int, load: dict, new: Path) -> dict:
    """NEW in the layout of the check's compare.py, from one exporter run (v2): its record
    files, rc.txt from counts.json, and a sysout.txt of the lines D10 keeps, built from
    counts.json. Each count must agree with the files, the input and the load report."""
    counts_path = export / "counts.json"
    if not counts_path.is_file():
        raise ExportError(f"{job}: no counts.json, the export is not complete")
    try:
        pairs = json.loads(counts_path.read_text(encoding="utf-8"), object_pairs_hook=JsonPairs)
    except ValueError:
        pairs = None
    if not isinstance(pairs, JsonPairs):
        raise ExportError(f"{job}: counts.json is not one JSON object")
    counts = dict(pairs)
    if counts.get("schemaVersion") != 2:
        raise ExportError(f"{job}: counts.json schemaVersion is {counts.get('schemaVersion')!r}, expected 2")
    if [k for k, _v in pairs] != list(COUNT_FIELDS[job]):
        raise ExportError(f"{job}: counts.json has the fields {[k for k, _v in pairs]}")
    for key, want in (("job", job), ("cycleId", cycle_id), ("attempt", attempt),
                      *((key, load[report_key]) for key, report_key in COUNTS_FROM_LOAD)):
        if counts[key] != want or type(counts[key]) is not type(want):
            raise ExportError(f"{job}: counts.json {key} is {counts[key]!r}, expected {want!r}")
    present = sorted(p.name for p in export.iterdir())
    if present != sorted((*EXPORT_FILES[job], "counts.json")):
        raise ExportError(f"{job}: the export holds {present}")
    new.mkdir(parents=True, mode=0o700)
    for name in EXPORT_FILES[job]:
        (new / name).write_bytes((export / name).read_bytes())
        os.chmod(new / name, 0o600)
    records = len(d1_harness.split_lines(new / "ACCTDATA.after.txt"))
    restored = counts["accountZipGroupRestored"]
    if type(restored) is not int or not 0 <= restored <= records:
        raise ExportError(f"{job}: counts.json accountZipGroupRestored is {restored!r}, not 0 to {records}")
    # No batch job adds or drops an account, so every row keeps the origin its load gave it.
    for key, got in (("accountRecords", records), ("accountOriginR", load["accountsAsRead"]),
                     ("accountOriginM", load["accountsMigrated"]), ("accountOriginNone", 0),
                     ("accountZipGroupRebuilt", records - restored)):
        if counts[key] != got or type(counts[key]) is not int:
            raise ExportError(f"{job}: counts.json {key} is {counts[key]!r}, expected {got!r}")
    if load["accountsAsRead"] + load["accountsMigrated"] != records:
        raise ExportError(f"{job}: the load's origins do not add up to the export's {records} account records")
    if job == "POSTTRAN":
        rejected = len(d1_harness.split_lines(new / "DALYREJS.txt"))
        created = _created_lines(data, new / "TCATBALF.after.txt")
        for key, got in (("rejected", rejected), ("tcatbalCreated", len(created)), ("rc", 4 if rejected else 0)):
            if counts[key] != got:
                raise ExportError(f"POSTTRAN: counts.json {key} is {counts[key]}, the files give {got}")
        sysout = ["START OF EXECUTION OF PROGRAM CBTRN02C", f"TRANSACTIONS PROCESSED :{counts['processed']:09d}",
                  f"TRANSACTIONS REJECTED  :{counts['rejected']:09d}", *created,
                  "END OF EXECUTION OF PROGRAM CBTRN02C"]
    else:
        tcat_accounts = {r[:11] for r in carddemo.read_text_records(data / "tcatbal.txt", 50)}
        for key, got in (("transactions", len(d1_harness.split_lines(new / "TRANSACT.txt"))),
                         ("accounts", len(tcat_accounts)), ("rc", 0)):
            if counts[key] != got:
                raise ExportError(f"INTCALC: counts.json {key} is {counts[key]}, expected {got}")
        sysout = ["START OF EXECUTION OF PROGRAM CBACT04C", "END OF EXECUTION OF PROGRAM CBACT04C"]
    (new / "sysout.txt").write_text("".join(line + "\n" for line in sysout), encoding="latin-1")
    (new / "rc.txt").write_text(f"{counts['rc']}\n")
    (new / "stderr.txt").write_text("")
    return counts


# ---------------------------------------------------------------------------
# Fixed mode: what each switched item changes, computed from REF and the input
# ---------------------------------------------------------------------------
# The jobs whose compared outputs an item changes. A declared switch missing here
# fails every run: a new fix needs its expectation before it can pass.
EFFECTS: dict[str, frozenset[str]] = {
    # Declared ACUP_UPDATE records get their group from 103; INTCALC takes their rates from
    # it. Unedited rows re-export their load-time 103-122, so no account layout changes.
    "DEF-ACUP-002": frozenset({"INTCALC"}),
    "DEF-JINTC-001": frozenset({"INTCALC"}),
    "DEF-INTC-010": frozenset({"INTCALC"}),
    "DEF-INTC-012": frozenset({"INTCALC"}),
    # PRTCATBL's listing only, which verify-batch-spec checks; no required job's output.
    "DEF-JPCAT-001": frozenset(),
    # TRANREPT's report: DEF-TRPT-001 its trailing page break, DEF-TRPT-004 its order.
    # expected() has no TRANREPT rule yet, so a fixed mode of either fails the job.
    "DEF-TRPT-001": frozenset({"TRANREPT"}),
    "DEF-TRPT-004": frozenset({"TRANREPT"}),
    # CREASTMT's statements: fixed mode writes one PDF per account and period instead of
    # STATEMNT.txt and STATEMNT.html (D-43). expected() has no CREASTMT rule, so it fails the job.
    "DEF-STMT-005": frozenset({"CREASTMT"}),
}


def unknown_switches(switches: list[str]) -> list[str]:
    return [s for s in switches if s not in EFFECTS]


def _field(layout: dict, name: str) -> slice:
    f = layout[name]
    return slice(f.offset, f.offset + f.length)


class ExpectationError(Exception):
    """The input and the GnuCOBOL outputs do not line up, or NEW breaks an item's rule."""


def _units(text: str) -> int:
    """A zoned field as an integer of its last digit's units (sign from the overpunch)."""
    v = oracle.zoned(text)
    return int(v.scaleb(-v.as_tuple().exponent)) if v.as_tuple().exponent < 0 else int(v)


def _store(value: int, length: int) -> str:
    """MOVE of a computed value to S9(length): low-order digits kept, sign kept."""
    negative = value < 0
    digits = str(abs(value) % (10 ** length)).rjust(length, "0")
    if int(digits) == 0:
        negative = False if value == 0 else negative
    return digits[:-1] + ("}JKLMNOPQR" if negative else "{ABCDEFGHI")[int(digits[-1])]


def interest_sources(data: Path, interest: list[str]) -> list[str]:
    """For each interest record GnuCOBOL wrote, the input category balance record it
    came from: CBACT04C walks TCATBALF in key order and writes one record for each
    balance whose disclosure rate (the account's group, else DEFAULT) is not zero.
    Each pairing is cross-checked against the record's account and amount."""
    acct_id, grp = _field(oracle.ACCOUNT, "ACCT-ID"), _field(oracle.ACCOUNT, "ACCT-GROUP-ID")
    t_acct, t_type, t_cat = (_field(oracle.TCATBAL, n) for n in ("TRANCAT-ACCT-ID", "TRANCAT-TYPE-CD", "TRANCAT-CD"))
    t_bal = _field(oracle.TCATBAL, "TRAN-CAT-BAL")
    rate_f = _field(oracle.DISCGRP, "DIS-INT-RATE")
    key_len = oracle.DISCGRP["DIS-INT-RATE"].offset
    tcat = {}
    for r in carddemo.read_text_records(data / "tcatbal.txt", 50):
        tcat.setdefault(r.decode("latin-1")[:t_cat.stop], r.decode("latin-1"))
    accounts = {}
    for r in carddemo.read_text_records(data / "acctdata.txt", 300):
        accounts.setdefault(r.decode("latin-1")[acct_id], r.decode("latin-1"))
    disc = {}
    for r in carddemo.read_text_records(data / "discgrp.txt", 50):
        disc.setdefault(r.decode("latin-1")[:key_len], r.decode("latin-1"))
    sources = []
    for key in sorted(tcat):
        rec = tcat[key]
        account = accounts.get(rec[t_acct])
        if account is None:
            raise ExpectationError(f"tcatbal {rec[t_acct]}: no account in the input")
        tail = rec[t_type] + rec[t_cat]
        d = disc.get(account[grp] + tail) or disc.get("DEFAULT".ljust(grp.stop - grp.start) + tail)
        if d is None:
            raise ExpectationError(f"tcatbal {key}: no disclosure group")
        if _units(d[rate_f]) != 0:
            sources.append((rec, _units(d[rate_f])))
    if len(sources) != len(interest):
        raise ExpectationError(f"{len(sources)} balances with a rate, {len(interest)} interest records")
    desc, amt = _field(oracle.TRAN, "TRAN-DESC"), _field(oracle.TRAN, "TRAN-AMT")
    out = []
    for (rec, rate), tran in zip(sources, interest):
        bal = _units(rec[t_bal])
        monthly = abs(bal * rate) // 120000 * (-1 if bal * rate < 0 else 1)
        if rec[t_acct] not in tran[desc] or _units(tran[amt]) != _units(_store(monthly, amt.stop - amt.start)):
            raise ExpectationError(f"interest record {tran[:16]} does not match balance {rec[:17]}")
        out.append(rec)
    return out


def _ids(records: list[str]) -> list[str]:
    return [r[_field(oracle.TRAN, "TRAN-ID")] for r in records]


def run_intcalc_reference(data: Path, out: Path) -> None:
    d1_harness.run_reference("INTCALC", data, out, ("ACCTDATA",))


def acup_fixed_reference(data: Path, ref: Path, work: Path, manifest: Path | None) -> tuple[Path, Path]:
    """The GnuCOBOL INTCALC run that fixed DEF-ACUP-002 corresponds to (rule C): the input
    as the load stores it, each declared ACUP_UPDATE record with its 103-112 group as
    ACCT-GROUP-ID, every other record as read. CBACT04C then picks the rates, adds, drops and
    numbers the records itself. Its ACCTDATA gets the input's 103-122 back, as the export of
    a row whose ZIP and group are unchanged since the load. With no record migrated (no
    manifest applies, or all declared CVACT01Y) the run is legacy's."""
    zip_f, grp, a_id = (_field(oracle.ACCOUNT, n) for n in ("ACCT-ADDR-ZIP", "ACCT-GROUP-ID", "ACCT-ID"))
    acctdata = data / "acctdata.txt"
    load = load_accounts(acctdata, applied_manifest(acctdata, manifest))
    if not load.migrated:
        return data, ref
    fixed_data = work / "acup-data"
    shutil.copytree(data, fixed_data)
    harness.write_records(fixed_data / "acctdata.txt", [r.encode("latin-1") for r in load.records])
    fixed_ref = work / "acup-ref"
    run_intcalc_reference(fixed_data, fixed_ref)
    loaded: dict[str, str] = {}
    for _, r in _account_lines(acctdata):
        loaded.setdefault(r[a_id], r)
    got = d1_harness.split_lines(fixed_ref / "ACCTDATA.after.txt")
    if [r[a_id] for r in got] != [r[a_id] for r in d1_harness.split_lines(ref / "ACCTDATA.after.txt")]:
        raise ExpectationError("fixed DEF-ACUP-002: the rerun's accounts differ from REF's")
    harness.write_records(fixed_ref / "ACCTDATA.after.txt",
                          [(g[:zip_f.start] + loaded[g[a_id]][zip_f.start:grp.stop] + g[grp.stop:]).encode("latin-1")
                           for g in got])
    return fixed_data, fixed_ref


def expected_intcalc(fixed: frozenset[str], data: Path, ref: Path, new: Path, out: Path,
                     run: SpringRun = SpringRun(), *, manifest: Path | None) -> None:
    """REF changed as the fixed items describe (switches.md, data's verify-batch review)."""
    tid, amt = _field(oracle.TRAN, "TRAN-ID"), _field(oracle.TRAN, "TRAN-AMT")
    t_bal = _field(oracle.TCATBAL, "TRAN-CAT-BAL")
    a_id, a_bal = _field(oracle.ACCOUNT, "ACCT-ID"), _field(oracle.ACCOUNT, "ACCT-CURR-BAL")
    if ACUP_002 in fixed:
        data, ref = acup_fixed_reference(data, ref, out.parent / "acup", manifest)
    trans = d1_harness.split_lines(ref / "TRANSACT.txt")
    accounts = d1_harness.split_lines(ref / "ACCTDATA.after.txt")
    if "DEF-INTC-012" in fixed:
        # Dropped on the input balance's sign, not on the amount's: a small negative balance
        # whose interest truncates to zero is dropped too.
        sources = interest_sources(data, trans)
        keep = [_units(src[t_bal]) >= 0 for src in sources]
        dropped: dict[str, int] = {}
        for k, src, tran in zip(keep, sources, trans):
            if not k:
                dropped[src[:11]] = dropped.get(src[:11], 0) + 1
        if dropped:
            inputs = {r.decode("latin-1")[a_id]: r.decode("latin-1")
                      for r in carddemo.read_text_records(data / "acctdata.txt", 300)}
            totals: dict[str, list[int]] = {}
            for k, src, tran in zip(keep, sources, trans):
                t = totals.setdefault(src[:11], [0, 0])
                t[0] = _units(_store(t[0] + _units(tran[amt]), 11))
                if k:
                    t[1] = _units(_store(t[1] + _units(tran[amt]), 11))
            patched = []
            for rec in accounts:
                acct = rec[a_id]
                if acct in dropped:
                    cur = _units(inputs[acct][a_bal])
                    if _store(cur + totals[acct][0], 12) != rec[a_bal]:
                        raise ExpectationError(f"account {acct}: balance after INTCALC does not follow from its input")
                    rec = rec[:a_bal.start] + _store(cur + totals[acct][1], 12) + rec[a_bal.stop:]
                patched.append(rec)
            accounts = patched
        trans = [t for k, t in zip(keep, trans) if k]
        if "DEF-INTC-010" not in fixed:
            trans = [t[:tid.start + 10] + f"{(i + 1) % 1000000:06d}" + t[tid.start + 16:]
                     for i, t in enumerate(trans)]
    if "DEF-INTC-010" in fixed:
        got = _ids(d1_harness.split_lines(new / "TRANSACT.txt"))
        if len(got) != len(trans):
            raise ExpectationError(f"fixed DEF-INTC-010: {len(got)} interest records, expected {len(trans)}")
        if not all(re.fullmatch(r"9\d{15}", g) for g in got) or any(
                int(b) != int(a) + 1 for a, b in zip(got, got[1:])):
            raise ExpectationError("fixed DEF-INTC-010: IDs are not consecutive 16-digit IDs from the 9 range")
        if got and run.interest_id_start is None:
            raise ExpectationError("fixed DEF-INTC-010: the run did not read the interest sequence's start")
        if got and got[0] != f"{run.interest_id_start:016d}":
            raise ExpectationError(f"fixed DEF-INTC-010: the first ID is {got[0]}, the sequence starts at "
                                   f"{run.interest_id_start:016d}")
        trans = [g + t[tid.stop:] for g, t in zip(got, trans)]
    elif "DEF-JINTC-001" in fixed:
        prefix = run.interest_parm or interest_parm_of_cycle()
        if not re.fullmatch(r"\d{10}", prefix):
            raise ExpectationError(f"the cycle's interestParm {prefix!r} is not 10 digits")
        trans = [prefix + t[tid.start + 10:] for t in trans]
    out.mkdir(parents=True, exist_ok=True)
    for name in (p.name for p in ref.iterdir() if p.is_file()):
        (out / name).write_bytes((ref / name).read_bytes())
    harness.write_records(out / "TRANSACT.txt", [t.encode("latin-1") for t in trans])
    harness.write_records(out / "ACCTDATA.after.txt", [r.encode("latin-1") for r in accounts])


def expected(job: str, mode: Mode, data: Path, ref: Path, new: Path, out: Path,
             run: SpringRun = SpringRun(), *, manifest: Path | None) -> Path:
    """The directory NEW must match in this mode: REF itself unless a fixed item changes the job.
    run holds the cycle's interestParm (by default the business date's) and the interest
    sequence start (by default a fresh database's); manifest is the account layout manifest
    the case supplies to the load (None: the built-in entry or none applies)."""
    if not any(job in EFFECTS.get(item, ()) for item in mode.fixed):
        return ref
    if job == "INTCALC":
        expected_intcalc(mode.fixed, data, ref, new, out, run, manifest=manifest)
        return out
    raise ExpectationError(f"{job}: no expectation for {sorted(mode.fixed)}")


# D10: Spring Batch writes no record dumps; only the START/END lines, the count lines and
# CBTRN02C's category balance creations (counted as tcatbalCreated) are compared.
SYSOUT_KEPT = re.compile(r"(START|END) OF EXECUTION OF PROGRAM \w+|TRANSACTIONS (PROCESSED|REJECTED) *:\d+")
SYSOUT_CREATED = re.compile(r"TCATBAL record not found for key : \d{17}\.\. Creating\.")


def kept_sysout(src: Path, out: Path) -> Path:
    """A copy of an output directory whose sysout.txt holds only the lines D10 keeps. The
    creation lines are sorted: their number is compared, not the order of the postings."""
    shutil.copytree(src, out)
    if (out / "sysout.txt").exists():
        lines = [l.rstrip() for l in d1_harness.split_lines(out / "sysout.txt")]
        kept = [l for l in lines if SYSOUT_KEPT.fullmatch(l)] + sorted(l for l in lines if SYSOUT_CREATED.fullmatch(l))
        (out / "sysout.txt").write_bytes("".join(l + "\n" for l in kept).encode("latin-1"))
    return out


# ---------------------------------------------------------------------------
# Comparison: the check's own compare.py
# ---------------------------------------------------------------------------
def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise Unsupported(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _load_check_script(folder: str, file: str):
    """A check's run_check.py, which imports its own compare.py as "compare": loaded with
    its folder first on sys.path and without leaving that name behind for another check."""
    saved = sys.modules.pop("compare", None)
    sys.path.insert(0, str(CHECKS / folder))
    try:
        return _load(f"verify_batch_{folder}_{Path(file).stem}", CHECKS / folder / file)
    finally:
        sys.path.remove(str(CHECKS / folder))
        sys.modules.pop("compare", None)
        if saved is not None:
            sys.modules["compare"] = saved


def check_module(job: str):
    """The check's compare.py as a module (its masks are read from it)."""
    folder = catalogue_job(job).check
    path = CHECKS / str(folder) / "compare.py"
    if not folder or not path.exists():
        raise Unsupported(f"{job}: no compare.py in docs/modernization/spec/checks/{folder}")
    return _load(f"verify_batch_{job.lower()}_compare", path)


def check_compare(job: str) -> Callable[[Path, Path], int]:
    return check_module(job).compare


def compare(job: str, ref: Path, new: Path) -> tuple[bool, str]:
    """Runs the check's compare(REF, NEW); returns (passed, its PASS/FAIL lines)."""
    fn = check_compare(job)
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        rc = fn(ref, new)
    return rc == 0, out.getvalue()


# ---------------------------------------------------------------------------
# The GnuCOBOL side, as each check's run_check.py prepares it
# ---------------------------------------------------------------------------
@dataclasses.dataclass(frozen=True)
class Scenario:
    job: str
    case: str
    data: Path      # input of both sides
    ref: Path       # GnuCOBOL outputs in the layout compare.py reads
    manifest: Path | None = None   # the account layout manifest supplied to the load, if any
    # A load case of the ruling (E4, E17) that says nothing in legacy mode, where every
    # load is as read: it runs only in the modes that fix DEF-ACUP-002.
    acup_fixed_only: bool = False

    def runs_in(self, mode: Mode) -> bool:
        return not self.acup_fixed_only or ACUP_002 in mode.fixed


def _case_dirs(data: Path) -> list[tuple[str, Path]]:
    """gen_stress.py may write several independent data sets as data/case-*/ (d1_harness.run_check)."""
    cases = sorted(d for d in data.iterdir() if d.is_dir() and d.name.startswith("case-"))
    return [(d.name, d) for d in cases] or [("", data)]


def _gen_stress(job: str, out: Path) -> None:
    import subprocess
    script = CHECKS / str(catalogue_job(job).check) / "gen_stress.py"
    subprocess.run([sys.executable, str(script), str(out)], check=True)


def _label(case: str, sub: str) -> str:
    return f"{case}/{sub}" if sub else case


def reference_posttran(root: Path, case: str) -> list[Scenario]:
    """posttran_reimpl/run_check.py: the sample data as it is, or gen_stress.py's."""
    unload = _load_check_script("posttran_reimpl", "run_check.py").UNLOAD
    if case == "stress":
        data = root / "data"
        _gen_stress("POSTTRAN", data)
        dirs = _case_dirs(data)
    else:
        dirs = [("", harness.SAMPLE_DATA)]
    out = []
    for sub, d in dirs:
        ref = root / "ref" / sub
        harness.run_reference("POSTTRAN", d, ref, list(unload))
        # The sample is the built-in entry's own bytes; generated files get their own manifest.
        manifest = write_manifest(d / "acctdata.txt", root / "manifests" / f"{sub or case}.toml",
                                  stress_overrides("POSTTRAN")) if case == "stress" else None
        out.append(Scenario("POSTTRAN", _label(case, sub), d, ref, manifest))
    if case == "sample":
        # E17: the EBCDIC copy, converted, with no manifest: no built-in entry names it,
        # so every account is stored as read and account 49 keeps ZEROAPR as its ZIP.
        data = root / "ebcdic-data"
        shutil.copytree(harness.SAMPLE_DATA, data)
        ebcdic_accounts(data / "acctdata.txt")
        ref = root / "ref" / "ebcdic"
        harness.run_reference("POSTTRAN", data, ref, list(unload))
        out.append(Scenario("POSTTRAN", "sample/ebcdic", data, ref, None, acup_fixed_only=True))
    return out


def reference_intcalc(root: Path, case: str) -> list[Scenario]:
    """intcalc_reimpl/run_check.py: the sample data after the sample POSTTRAN, or gen_stress.py's."""
    data = root / "data"
    if case == "stress":
        _gen_stress("INTCALC", data)
    else:
        shutil.copytree(harness.SAMPLE_DATA, data)
        _load_check_script("intcalc_reimpl", "run_check.py").prepare(data, root)
    # The sample case loads POSTTRAN's after-state, whose hash is not the built-in entry's.
    overrides = stress_overrides("INTCALC") if case == "stress" else ()
    out = []
    for sub, d in _case_dirs(data):
        ref = root / "ref" / sub
        d1_harness.run_reference("INTCALC", d, ref, ("ACCTDATA",))
        manifest = write_manifest(d / "acctdata.txt", root / "manifests" / f"{sub or case}.toml", overrides)
        out.append(Scenario("INTCALC", _label(case, sub), d, ref, manifest))
        if case == "stress":
            # E4: the same bytes with no manifest load as read, so fixed mode is legacy's run.
            out.append(Scenario("INTCALC", _label(case, f"{sub}/undeclared" if sub else "undeclared"), d, ref, None,
                                acup_fixed_only=True))
    return out


REFERENCES: dict[str, Callable[[Path, str], list[Scenario]]] = {
    "POSTTRAN": reference_posttran,
    "INTCALC": reference_intcalc,
}


# ---------------------------------------------------------------------------
# The Spring Batch side
# ---------------------------------------------------------------------------
# The pipeline of one scenario, from the batch execution contract (sections 1, 2, 6) and
# the layout manifest contract (sections 1.3, 1.7, 1.8, 4.3): a fresh private database, one
# one-shot dataLoadJob of the scenario's own input with its manifest, then the catalogue
# entry's jobs alone in their cycle's window. The INTCALC input is POSTTRAN's after-state
# already, so no POSTTRAN job may run on it again.
INTEREST_JOB = "calculateInterestJob"
REJECTS_EXIT = "COMPLETED_WITH_REJECTS"
# What a completed cycle job may exit with besides COMPLETED. batch-service
# exits COMPLETED_WITH_REJECTS (the legacy return code 4) only for a completed
# job whose execution context holds a positive carddemo.rejectCount, and only
# postDailyTransactionsJob's exportRejects step writes one (CBTRN02C's DALYREJS
# file, whose records the comparison still checks). Every other job must exit
# COMPLETED.
JOB_EXITS = {"postDailyTransactionsJob": frozenset({"COMPLETED", REJECTS_EXIT})}
INGEST_JOB = "ingestDailyFileJob"
BUSINESS_DATE = carddemo.DEFAULT_BUSINESS_DATE
# Checked by execute() before the server starts; schemaVersion first.
LOAD_REPORT_KEYS = ("schemaVersion", "loadExecutionId", "jobName", "status", "exitCode", "accountRecords",
                    "accountsAsRead", "accountsMigrated", "accountLayoutSource", "accountLayoutWarnings",
                    "acctdataSha256", "accountLayoutManifestSha256")


class PipelineError(Exception):
    """The run would not show what the comparison assumes about it; nothing is compared."""


@dataclasses.dataclass(frozen=True)
class Pipeline:
    job: str
    source_dir: Path               # carddemo.data-load.source-dir of the one-shot load
    acctdata_sha256: str           # of source_dir/acctdata.txt: the bytes the load must read
    manifest: Path | None          # supplied to the load (fixed DEF-ACUP-002 only)
    layout_source: str             # the accountLayoutSource the load must report
    migrated: int                  # the accountsMigrated the load must report
    warnings: int                  # the accountLayoutWarnings the load must report
    switch_args: tuple[str, ...]   # every fidelity switch, for the load and the server
    server_env: tuple[tuple[str, str], ...]
    daily_file: Path | None        # staged as <inbound>/dailytran/<businessDate>.txt
    cycle: tuple[tuple[str, str], ...]   # the POST /internal/cycles body
    jobs: tuple[str, ...]          # launched in this order in the cycle's window

    @property
    def load_args(self) -> list[str]:
        return [*self.switch_args, *layout_manifest_args(self.manifest)]


def pipeline(scenario: Scenario, mode: Mode, switches: list[str]) -> Pipeline:
    entry = catalogue_job(scenario.job)
    source = scenario.data.resolve()
    digest = sha256(source / "acctdata.txt")
    manifest = scenario.manifest if ACUP_002 in mode.fixed else None
    if manifest is not None:
        named = LayoutManifest.read(manifest, str(manifest.resolve())).sha256
        if named != digest:
            raise PipelineError(f"{scenario.job} {scenario.case}: the manifest names acctdata.txt {named[:12]}, "
                                f"the load reads {digest[:12]}")
    if ACUP_002 not in mode.fixed:
        layout = "ignored"
    else:
        layout = "supplied" if manifest is not None else "builtin" if digest == BUILTIN_SHA256 else "none"
    try:
        migrated, warnings = expected_migrated(scenario, mode), expected_warnings(scenario, mode)
    except ManifestError as e:
        raise PipelineError(f"{scenario.job} {scenario.case}: the account load must fail: {e}") from None
    args, env = spring_settings(mode, switches, interest_parm(scenario.data))
    daily = source / "dailytran.txt" if INGEST_JOB in entry.spring else None
    interest = "always" if INTEREST_JOB in entry.spring else "never"
    return Pipeline(scenario.job, source, digest, manifest, layout, migrated, warnings, tuple(args),
                    tuple(sorted(env.items())),
                    daily, (("businessDate", BUSINESS_DATE), ("interest", interest)), entry.spring)


def check_pipeline(p: Pipeline) -> None:
    """What every run must hold, also for a pipeline not built by pipeline()."""
    entry = catalogue_job(p.job)
    if p.jobs != entry.spring:
        raise PipelineError(f"{p.job}: launches {list(p.jobs)}, the catalogue runs {list(entry.spring)}")
    others = {j for e in CATALOGUE if e.legacy != p.job for j in e.spring}
    if others & set(p.jobs):
        raise PipelineError(f"{p.job}: launches {sorted(others & set(p.jobs))} of another job")
    if (INGEST_JOB in p.jobs) != (p.daily_file is not None):
        raise PipelineError(f"{p.job}: a daily file is staged only for {INGEST_JOB}")
    if p.daily_file is not None and p.daily_file.parent != p.source_dir:
        raise PipelineError(f"{p.job}: the daily file is not the loaded input's")
    if dict(p.cycle).get("interest") != ("always" if INTEREST_JOB in p.jobs else "never"):
        raise PipelineError(f"{p.job}: the cycle's interest policy does not match its jobs")
    if p.manifest is not None and LayoutManifest.read(p.manifest, str(p.manifest.resolve())).sha256 \
            != p.acctdata_sha256:
        raise PipelineError(f"{p.job}: the manifest does not name the bytes the load reads")
    if not isinstance(p.migrated, int) or p.migrated < 0 or (p.layout_source == "ignored" and p.migrated):
        raise PipelineError(f"{p.job}: the load cannot migrate {p.migrated!r} records with layout {p.layout_source}")
    if not isinstance(p.warnings, int) or p.warnings < 0 or (p.layout_source == "ignored" and p.warnings):
        raise PipelineError(f"{p.job}: the load cannot warn about {p.warnings!r} records with layout {p.layout_source}")


class Driver:
    """batch-service on the run's own database. Each method is one step of the pipeline; the
    real driver goes through the internal API and the one-shot load, a test spies on it."""

    def reset(self) -> None:
        raise NotImplementedError

    def load(self, source_dir: Path, args: list[str]) -> dict:
        """One-shot dataLoadJob; returns its load report (LOAD_REPORT_KEYS among its keys)."""
        raise NotImplementedError

    def start(self, args: list[str], env: dict[str, str], daily_file: Path | None, business_date: str) -> None:
        raise NotImplementedError

    def register(self, body: dict) -> dict:
        raise NotImplementedError

    def window(self, action: str, body: dict) -> dict:
        raise NotImplementedError

    def interest_sequence_next(self) -> int:
        raise NotImplementedError

    def launch(self, job: str, params: dict) -> dict:
        """Launches and waits; returns the terminal poll."""
        raise NotImplementedError

    def export(self, job: str, cycle_id: int, load_execution: int) -> Path:
        raise NotImplementedError


class PipelineRun(NamedTuple):
    run: SpringRun
    export: Path
    cycle_id: int
    attempt: int
    load: dict   # the load report the export's counts must repeat


def check_job_end(pipeline_job: str, job: str, done: dict) -> None:
    """A cycle job's terminal poll answer: COMPLETED, with an exit status its job may end
    with (JOB_EXITS), and a reject count that agrees with it, as batch-service sets both."""
    status, exit_status, rejects = done.get("executionStatus"), done.get("exitStatus"), done.get("rejectCount")
    if status != "COMPLETED" or exit_status not in JOB_EXITS.get(job, frozenset({"COMPLETED"})):
        raise PipelineError(f"{pipeline_job}: {job} ended {status} / {exit_status}")
    if type(rejects) is not int or rejects < 0 or (rejects > 0) != (exit_status == REJECTS_EXIT):
        raise PipelineError(f"{pipeline_job}: {job} ended {status} / {exit_status} with reject count {rejects!r}")


def execute(p: Pipeline, driver: Driver) -> PipelineRun:
    """Drives one pipeline. Every value the expectation takes comes from the driver: the load
    report, the cycle and the sequence; none is defaulted."""
    check_pipeline(p)
    driver.reset()
    if sha256(p.source_dir / "acctdata.txt") != p.acctdata_sha256:
        raise PipelineError(f"{p.job}: acctdata.txt changed after the pipeline was made")
    report = driver.load(p.source_dir, p.load_args)
    if report.get("schemaVersion") != 1:
        raise PipelineError(f"{p.job}: the load report is schemaVersion {report.get('schemaVersion')!r}, not 1")
    missing = [k for k in LOAD_REPORT_KEYS if k not in report]
    if missing:
        raise PipelineError(f"{p.job}: the load report lacks {', '.join(missing)}")
    execution = report["loadExecutionId"]
    if not is_execution_id(execution):
        raise PipelineError(f"{p.job}: the load report's loadExecutionId is {execution!r}")
    if (report["jobName"], report["status"], report["exitCode"]) != ("dataLoadJob", "COMPLETED", "COMPLETED"):
        raise PipelineError(f"{p.job}: the load report is of {report['jobName']} {report['status']} "
                            f"{report['exitCode']}, not a completed dataLoadJob")
    if report["acctdataSha256"] != p.acctdata_sha256:
        raise PipelineError(f"{p.job}: the load read acctdata.txt {str(report['acctdataSha256'])[:12]}, "
                            f"the pipeline planned {p.acctdata_sha256[:12]}")
    records = len(d1_harness.split_lines(p.source_dir / "acctdata.txt"))
    if report["accountRecords"] != records:
        raise PipelineError(f"{p.job}: the load wrote {report['accountRecords']!r} account records, "
                            f"acctdata.txt has {records} lines")
    if report["accountsAsRead"] + report["accountsMigrated"] != records:
        raise PipelineError(f"{p.job}: the load's {report['accountsAsRead']!r} as read and "
                            f"{report['accountsMigrated']!r} migrated are not its {records} account records")
    if report["accountLayoutSource"] != p.layout_source:
        raise PipelineError(f"{p.job}: the load's layout source is {report['accountLayoutSource']!r}, "
                            f"expected {p.layout_source!r}")
    if report["accountsMigrated"] != p.migrated:
        raise PipelineError(f"{p.job}: the load migrated {report['accountsMigrated']!r} records, "
                            f"the manifest declares {p.migrated}")
    if type(report["accountLayoutWarnings"]) is not int or report["accountLayoutWarnings"] != p.warnings:
        raise PipelineError(f"{p.job}: the load warned about {report['accountLayoutWarnings']!r} records, "
                            f"the ruling's count is {p.warnings}")
    supplied = sha256(p.manifest) if p.manifest is not None else ""
    if report["accountLayoutManifestSha256"] != supplied:
        raise PipelineError(f"{p.job}: the load read the layout manifest "
                            f"{str(report['accountLayoutManifestSha256'])[:12] or 'none'}, "
                            f"the pipeline supplied {supplied[:12] or 'none'}")
    driver.start(list(p.switch_args), dict(p.server_env), p.daily_file, BUSINESS_DATE)
    cycle = driver.register(dict(p.cycle))
    due = INTEREST_JOB in p.jobs
    if cycle.get("interest") is not due or cycle.get("businessDate") != BUSINESS_DATE:
        raise PipelineError(f"{p.job}: the cycle came back as {cycle.get('businessDate')!r}, "
                            f"interest {cycle.get('interest')!r}")
    parm = cycle.get("interestParm")
    if due and not (isinstance(parm, str) and re.fullmatch(r"[0-9]{10}", parm)):
        raise PipelineError(f"{p.job}: the cycle's interestParm is {parm!r}")
    cycle_id, attempt = cycle["cycleId"], cycle["attempt"]
    driver.window("open", {"cycleId": cycle_id})
    start = None
    for job in p.jobs:
        if job == INTEREST_JOB:
            start = driver.interest_sequence_next()
        check_job_end(p.job, job, driver.launch(job, {"cycleId": cycle_id}))
    driver.window("close", {"cycleId": cycle_id, "complete": False})
    export = driver.export(p.job, cycle_id, execution)
    run = SpringRun(interest_parm=parm if due else None, interest_id_start=start, migrated=report["accountsMigrated"])
    return PipelineRun(run, export, cycle_id, attempt, dict(report))


class Producer:
    """Writes the Spring Batch outputs of one scenario in the layout of the
    check's compare.py. The real one drives batch-service on a private
    database and exports with verify/differential's exec jar."""

    def produce(self, scenario: Scenario, mode: Mode, out: Path) -> SpringRun:
        """Writes NEW to out (through from_export, with the cycle and attempt it ran) and
        returns what it read from its database for the expectation."""
        raise NotImplementedError


class PipelineProducer(Producer):
    """A producer that runs pipeline() through a driver, one fresh driver per scenario and mode."""

    def __init__(self, new_driver: Callable[[Scenario, Mode], Driver], switches: list[str]):
        self.new_driver, self.switches = new_driver, switches

    def produce(self, scenario: Scenario, mode: Mode, out: Path) -> SpringRun:
        done = execute(pipeline(scenario, mode, self.switches), self.new_driver(scenario, mode))
        from_export(scenario.job, done.export, scenario.data, done.cycle_id, done.attempt, done.load, out)
        return done.run


# The pipeline checks the load report, acctdataSha256 included, against what it planned.
# Its one source is verify-differential --load-report on the execution ID the one-shot
# load wrote, and the export must be v2 (--load-execution-id), which refuses a cycle that
# started before that load. Until data's commit with both is merged, no job can run.
LOAD_REPORT_GAP = ("the load report has no readable source yet (data's verify-differential --load-report, "
                   "the load's execution-ID file and export v2 are not merged)")


def producer_gaps(job: str) -> list[str]:
    """What a job's producer lacks. Only sources are looked at, so the plan runs without a build."""
    entry = catalogue_job(job)
    if not entry.check:
        return [f"not compared: {entry.reason}"]
    missing = []
    if job not in REFERENCES:
        missing.append(f"no GnuCOBOL scenario for checks/{entry.check} in the harness")
    if not (MODERNIZATION / "verify" / "differential" / "pom.xml").exists():
        missing.append("no verify/differential exporter")
    if LOAD_REPORT_GAP:
        missing.append(LOAD_REPORT_GAP)
    return missing


def spring_producer(job: str, new_driver: Callable[[Scenario, Mode], Driver] | None = None,
                    switches: list[str] = ()) -> Producer:
    """The producer of a job, or Unsupported naming what is missing."""
    missing = producer_gaps(job)
    if missing:
        raise Unsupported(f"{job}: {'; '.join(missing)}")
    if new_driver is None:
        raise Unsupported(f"{job}: no driver session")
    return PipelineProducer(new_driver, list(switches))
