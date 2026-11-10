"""The run manifest: which report files one test run wrote, so that its trace reads only those.

A runner such as make test-billpay calls begin() (or writes the same JSON itself) before its first
test step, and trace_matrix.py --manifest after its last:

  {"schema": 1, "run": "test-billpay", "started_ns": 1762795547123456789,
   "reports": [
     {"path": "services/billpay-service/target/spec-items", "required": true,
      "before": [{"file": "org.carddemo.billpay.BillPayApiTest.jsonl",
                  "mtime_ns": 1762795001000000000, "size": 18234, "sha256": "..."}]},
     {"path": "web/playwright-report/e2e-mock-fixed.json", "required": true, "before": []},
     {"path": "platform/test-support/target/spec-items", "required": false, "before": []}]}

`started_ns` is the time the run began (time.time_ns()). A path is a report file or a directory
searched like any report directory, relative to `base` (relative to the repository root, default
`modernization`) unless it is absolute. `before` lists the report files that already existed at that
path when the run began, as `file` (relative to a directory path, or the path itself) with its
modification time in nanoseconds, size and SHA-256.

A file belongs to the run (is current) when its modification time is at or after started_ns, in
nanoseconds, and it is not unchanged from its `before` fingerprint. Every other file at a listed
path is stale: it is not read, and the matrix lists it. A required path that is missing, or holds no
current file, or whose current files carry no test result citing an item, is a gap of the gate.

Limits: the comparison trusts the file system's modification times and the runner's clock. A file
system with coarse times (HFS+, FAT, some network mounts) can date a file written by the run before
started_ns; it then counts as stale and the gate fails, which errs on the safe side. A file server
whose clock runs ahead of the runner's can make an older file look current; the `before`
fingerprint still marks such a file stale while its content and time are unchanged. A path the run
writes afresh on each invocation (a unique output directory) avoids both.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import time
from dataclasses import dataclass, field
from pathlib import Path

from . import reports

FIELDS = {"schema", "run", "started_ns", "base", "reports"}
ENTRY_FIELDS = {"path", "required", "before"}
PRINT_FIELDS = {"file", "mtime_ns", "size", "sha256"}


@dataclass(frozen=True)
class Print:
    mtime_ns: int
    size: int
    sha256: str


@dataclass
class Entry:
    path: Path
    rel: str
    required: bool
    before: dict[Path, Print] = field(default_factory=dict)
    current: list[Path] = field(default_factory=list)
    stale: list[Path] = field(default_factory=list)
    missing: bool = False


@dataclass
class Manifest:
    source: str
    run: str
    started_ns: int
    entries: list[Entry]

    @property
    def started_text(self) -> str:
        return stamp(self.started_ns)


def stamp(ns: int) -> str:
    return dt.datetime.fromtimestamp(ns / 1e9, dt.timezone.utc).isoformat(timespec="microseconds")


def fingerprint(path: Path) -> Print:
    st = path.stat()
    return Print(st.st_mtime_ns, st.st_size, hashlib.sha256(path.read_bytes()).hexdigest())


def _files(path: Path) -> list[Path]:
    if path.is_dir():
        return reports.discover(path)
    return [path] if path.exists() else []


def _int(v) -> bool:
    return isinstance(v, int) and not isinstance(v, bool)


def begin(path: Path, root: Path, run: str, entries: list[tuple[str, bool]], base: str = "modernization") -> dict:
    """Writes the manifest of a run that begins now: started_ns, and the fingerprint of every report
    file already at each (path, required) entry, relative to root/base. Returns the manifest."""
    started = time.time_ns()
    out = []
    for p, required in entries:
        full = Path(p) if Path(p).is_absolute() else root / base / p
        before = []
        for f in _files(full):
            fp = fingerprint(f)
            name = f.relative_to(full).as_posix() if full.is_dir() else f.name
            before.append({"file": name, "mtime_ns": fp.mtime_ns, "size": fp.size, "sha256": fp.sha256})
        out.append({"path": p, "required": required, "before": before})
    data = {"schema": 1, "run": run, "started_ns": started, "base": base, "reports": out}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=1) + "\n", encoding="utf-8")
    return data


def load(path: Path, root: Path) -> tuple[Manifest | None, list[str]]:
    rel = path.relative_to(root).as_posix() if path.is_relative_to(root) else str(path)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except OSError as e:
        return None, [f"{rel}: cannot read: {e.strerror or e}"]
    except json.JSONDecodeError as e:
        return None, [f"{rel}: not JSON: {e}"]
    if not isinstance(data, dict):
        return None, [f"{rel}: not a JSON object"]
    problems = [f"{rel}: unknown field {k!r}" for k in sorted(set(data) - FIELDS)]
    if data.get("schema") != 1:
        problems.append(f"{rel}: schema must be 1")
    run = data.get("run")
    if not isinstance(run, str) or not run.strip():
        problems.append(f"{rel}: run must be a non-empty string")
    started = data.get("started_ns")
    if not _int(started) or started <= 0:
        problems.append(f"{rel}: started_ns must be the run's start time in nanoseconds since the epoch")
    base = data.get("base", "modernization")
    if not isinstance(base, str):
        problems.append(f"{rel}: base must be a string")
        base = "modernization"
    entries: list[Entry] = []
    raw = data.get("reports")
    if not isinstance(raw, list) or not raw:
        problems.append(f"{rel}: reports must be a non-empty list")
        raw = []
    for n, e in enumerate(raw, 1):
        where = f"{rel}: reports[{n}]"
        if not isinstance(e, dict):
            problems.append(f"{where} must be an object")
            continue
        problems += [f"{where}: unknown field {k!r}" for k in sorted(set(e) - ENTRY_FIELDS)]
        p, req, before = e.get("path"), e.get("required"), e.get("before", [])
        if not isinstance(p, str) or not p.strip():
            problems.append(f"{where}: path must be a non-empty string")
            continue
        if not isinstance(req, bool):
            problems.append(f"{where}: required must be true or false")
            continue
        full = Path(p) if Path(p).is_absolute() else root / base / p
        shown = full.relative_to(root).as_posix() if full.is_relative_to(root) else str(full)
        entry = Entry(full, shown, req)
        if not isinstance(before, list):
            problems.append(f"{where}: before must be a list")
            before = []
        for k, b in enumerate(before, 1):
            if (not isinstance(b, dict) or set(b) != PRINT_FIELDS or not isinstance(b["file"], str) or not b["file"]
                    or not _int(b["mtime_ns"]) or not _int(b["size"]) or not isinstance(b["sha256"], str)):
                problems.append(f"{where}: before[{k}] must have exactly file, mtime_ns, size and sha256")
                continue
            target = full if not full.is_dir() and b["file"] == full.name else full / b["file"]
            entry.before[target] = Print(b["mtime_ns"], b["size"], b["sha256"])
        entries.append(entry)
    if problems:
        return None, problems
    return Manifest(rel, run.strip(), started, entries), []


def is_current(path: Path, m: Manifest, before: dict[Path, Print]) -> bool:
    fp = fingerprint(path)
    return fp.mtime_ns >= m.started_ns and before.get(path) != fp


def select(m: Manifest) -> list[str]:
    """Sorts each entry's files into current and stale; returns the gaps of required entries
    that are missing or hold nothing the run wrote."""
    gaps: list[str] = []
    for e in m.entries:
        if not e.path.exists():
            e.missing = True
            if e.required:
                gaps.append(f"required report {e.rel} of {m.run} is missing")
            continue
        for f in _files(e.path):
            (e.current if is_current(f, m, e.before) else e.stale).append(f)
        if e.required and not e.current:
            if e.stale:
                newest = max(f.stat().st_mtime_ns for f in e.stale)
                gaps.append(f"required report {e.rel} of {m.run} is stale: newest file written {stamp(newest)}, "
                            f"run started {m.started_text}")
            else:
                gaps.append(f"required report {e.rel} of {m.run} holds no report file")
    return gaps
