#!/usr/bin/env python3
"""Check the CardDemo natural-language specs under docs/modernization/spec.

The rules are in docs/modernization/spec/format.md, "Machine-checked rules":

  * front matter: required keys and values, id = file name, unique unit_code
    (and the one reserved in areas/README.md), source files exist, sha256
    prefixes match the files as they are now;
  * sections 1-16 present in order;
  * item IDs well formed, of the spec's unit, unique;
  * references to items, entities (ENT-...) and invariants (INV-NNN) resolve;
  * source citations (`path:N-M`, `:N-M`) name existing files and lines;
  * paragraph coverage: every paragraph of a COBOL primary source (every step
    of a JCL primary source) has exactly one row in section 16, with the
    right line range and a non-empty items cell.

It also reports which in-scope sources (COBOL, assembler, JCL, procedures,
CSD, schedules under app/) have a spec. Standard library only.

Usage:
    python3 docs/modernization/spec/tools/check_spec.py
    python3 docs/modernization/spec/tools/check_spec.py --program CBTRN02C
    python3 docs/modernization/spec/tools/check_spec.py --require-all --json build/spec-coverage.json

Exit codes: 0 = no errors, 1 = errors found, 2 = usage error.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

SPEC_DIR = Path(__file__).resolve().parent.parent
ROOT = SPEC_DIR.parents[2]

REQUIRED_KEYS = ("id", "title", "kind", "module", "unit_code", "source_rev", "sources", "spec_status")
PROGRAM_KINDS = {"online", "batch", "subprogram", "utility"}
JOB_KINDS = {"job", "proc", "csd", "schedule"}
MODULES = {"core", "auth-ims-db2-mq", "trantype-db2", "vsam-mq"}
STATUSES = {"draft", "reviewed", "verified"}
ITEM_KINDS = ("BR", "VAL", "FLOW", "SE", "ERR", "NUM", "DEF")
NEEDS_SOURCE = {"BR", "VAL", "FLOW", "SE", "ERR"}
SECTIONS = 16
SKIP_DIRS = {".git", "build", "dist", "__pycache__", "node_modules"}

UNIT_RE = re.compile(r"^[A-Z][A-Z0-9]{2,5}$")
HEAD_RE = re.compile(r"^####\s+([A-Z]+-[A-Z0-9]+-\d+)\b(.*)$")
ITEM_ID_RE = re.compile(r"^(" + "|".join(ITEM_KINDS) + r")-([A-Z][A-Z0-9]{2,5})-(\d{3})$")
Q_ROW_RE = re.compile(r"^\|\s*(Q-([A-Z][A-Z0-9]{2,5})-(\d{3}))\s*\|")
REF_RE = re.compile(r"(?<![\w-])((?:" + "|".join(ITEM_KINDS) + r"|Q)-[A-Z][A-Z0-9]{2,5})-(\d{3})(?:\.\.(\d{3}))?(?![\w])")
ENT_RE = re.compile(r"(?<![\w-])(ENT-[A-Z][A-Z0-9]*(?:-[A-Z][A-Z0-9]*)*)(?![\w-])")
ENT_HEAD_RE = re.compile(r"^##\s+(ENT-[A-Z][A-Z0-9-]*[A-Z0-9])\b")
INV_RE = re.compile(r"(?<![\w-])(INV-\d{3})(?![\w-])")
INV_ROW_RE = re.compile(r"^\|\s*(INV-\d{3})\s*\|")
SPAN_RE = re.compile(r"`([^`\n]+)`")
CITE_RE = re.compile(r"^(?P<path>[\w./-]*\.[A-Za-z0-9]+)?:(?P<ranges>\d+(?:-\d+)?(?:,\s*\d+(?:-\d+)?)*)$")
SECTION_RE = re.compile(r"^##\s+(\d+)\.\s")
COVERAGE_RE = re.compile(r"^##\s+(?:\d+\.\s+)?(?:Paragraph|Step) coverage\b", re.I)
RANGE_RE = re.compile(r"^(\d+)(?:-(\d+))?$")
RESERVED_RE = re.compile(r"\|\s*([A-Z][A-Z0-9]{1,7})\s*\|\s*`([A-Z][A-Z0-9]{2,5})`\s*\|")
COBOL_EXT = {".cbl"}
JCL_EXT = {".jcl", ".prc", ".proc"}
NOT_LABEL = {"EXIT", "GOBACK", "CONTINUE", "ELSE", "STOP", "DECLARATIVES"}


# ---------------------------------------------------------------- front matter
class FrontMatterError(ValueError):
    pass


def _strip_comment(line: str) -> str:
    quote = None
    for i, ch in enumerate(line):
        if quote:
            if ch == quote:
                quote = None
        elif ch in "\"'":
            quote = ch
        elif ch == "#" and (i == 0 or line[i - 1].isspace()):
            return line[:i].rstrip()
    return line.rstrip()


def _parse_flow(s: str, i: int, stops: str):
    while i < len(s) and s[i] == " ":
        i += 1
    if i >= len(s):
        return "", i
    ch = s[i]
    if ch in "[{":
        close = "]" if ch == "[" else "}"
        out: list | dict = [] if ch == "[" else {}
        i += 1
        while True:
            while i < len(s) and s[i] in " ,":
                i += 1
            if i >= len(s):
                raise FrontMatterError(f"unclosed {ch!r} in {s!r}")
            if s[i] == close:
                return out, i + 1
            if ch == "[":
                value, i = _parse_flow(s, i, ",]")
                out.append(value)
            else:
                j = s.find(":", i)
                if j < 0:
                    raise FrontMatterError(f"missing ':' in {s!r}")
                key = s[i:j].strip().strip("\"'")
                value, i = _parse_flow(s, j + 1, ",}")
                out[key] = value
    if ch in "\"'":
        j = s.find(ch, i + 1)
        if j < 0:
            raise FrontMatterError(f"unclosed quote in {s!r}")
        return s[i + 1:j], j + 1
    j = i
    while j < len(s) and s[j] not in stops:
        j += 1
    return s[i:j].strip(), j


def parse_value(s: str):
    s = s.strip()
    value, end = _parse_flow(s, 0, "")
    if s[end:].strip():
        raise FrontMatterError(f"unexpected text after value: {s!r}")
    return value


def parse_front_matter(text: str) -> tuple[dict, int]:
    """Return (mapping, number of lines it occupies including both --- lines)."""
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        raise FrontMatterError("no front matter (the file must start with ---)")
    data: dict = {}
    key = None
    for n, raw in enumerate(lines[1:], 2):
        if raw.strip() == "---":
            return data, n
        line = _strip_comment(raw)
        if not line.strip():
            continue
        try:
            if not line[0].isspace():
                m = re.match(r"^([A-Za-z_][\w-]*):(.*)$", line)
                if not m:
                    raise FrontMatterError(f"expected 'key: value': {line!r}")
                key, rest = m.group(1), m.group(2).strip()
                data[key] = parse_value(rest) if rest else []
            elif line.strip().startswith("- ") and key is not None and isinstance(data[key], list):
                data[key].append(parse_value(line.strip()[2:]))
            else:
                raise FrontMatterError(f"unsupported line: {line!r}")
        except FrontMatterError as exc:
            raise FrontMatterError(f"line {n}: {exc}") from None
    raise FrontMatterError("front matter is not closed with ---")


# ---------------------------------------------------------------- sources
def read_lines(path: Path) -> list[str]:
    return path.read_bytes().decode("latin-1").replace("\r\n", "\n").replace("\r", "\n").split("\n")


def cobol_paragraphs(path: Path) -> tuple[list[tuple[str, int, int]], tuple[int, int] | None]:
    """Paragraphs and sections of the PROCEDURE DIVISION as (name, label line, last code line),
    and the (first, last) code lines before the first label, if any."""
    labels: list[tuple[str, int]] = []
    code_lines: list[int] = []
    in_proc = False
    for n, raw in enumerate(read_lines(path), 1):
        raw = raw.expandtabs(4)
        if len(raw) < 8 or raw[6] in "*/":
            continue
        area = raw[7:72].rstrip()
        if not area.strip():
            continue
        if not in_proc:
            if re.match(r"\s*PROCEDURE\s+DIVISION", area, re.I):
                in_proc = True
            continue
        code_lines.append(n)
        if area[:1] == " ":
            continue
        m = re.match(r"^([A-Z0-9][A-Z0-9-]*)(\s+SECTION)?\s*\.\s*$", area, re.I)
        if m:
            name = m.group(1).upper()
            if name not in NOT_LABEL and not name.startswith("END-"):
                labels.append((name, n))
    paragraphs = []
    for i, (name, start) in enumerate(labels):
        stop = labels[i + 1][1] if i + 1 < len(labels) else None
        last = max(c for c in code_lines if c >= start and (stop is None or c < stop))
        paragraphs.append((name, start, last))
    first_label = labels[0][1] if labels else None
    pre = [c for c in code_lines if first_label is None or c < first_label]
    return paragraphs, ((pre[0], pre[-1]) if pre else None)


def jcl_steps(path: Path) -> list[tuple[str, int, int]]:
    lines = read_lines(path)
    starts = []
    for n, raw in enumerate(lines, 1):
        m = re.match(r"^//([A-Z@#$][A-Z0-9@#$]{0,7})\s+EXEC\s", raw.upper())
        if m:
            starts.append((m.group(1), n))
    steps = []
    for i, (name, start) in enumerate(starts):
        stop = starts[i + 1][1] if i + 1 < len(starts) else len(lines) + 1
        last = start
        for n in range(start, stop):
            raw = lines[n - 1].rstrip()
            if raw and not raw.startswith("//*") and raw != "//":
                last = n
        steps.append((name, start, last))
    return steps


# ---------------------------------------------------------------- model
@dataclass
class Spec:
    path: Path
    rel: str
    folder: str
    meta: dict = field(default_factory=dict)
    body_start: int = 1
    lines: list[str] = field(default_factory=list)
    items: dict[str, int] = field(default_factory=dict)
    primary: Path | None = None
    units: int = 0
    covered: int = 0

    @property
    def id(self) -> str:
        return str(self.meta.get("id", self.path.stem))


class Checker:
    def __init__(self, root: Path = ROOT, spec_dir: Path = SPEC_DIR) -> None:
        self.root = root.resolve()
        self.spec_dir = spec_dir.resolve()
        self.errors: list[str] = []
        self.warnings: list[str] = []
        self.specs: list[Spec] = []
        self.defined: dict[str, str] = {}
        self.entities: set[str] = set()
        self.invariants: set[str] = set()
        self._tree: dict[str, list[Path]] | None = None
        self._line_counts: dict[Path, int] = {}
        self.counts = {"citations": 0, "references": 0}

    def rel(self, path: Path) -> str:
        try:
            return path.resolve().relative_to(self.root).as_posix()
        except ValueError:
            return str(path)

    def error(self, where: str, msg: str) -> None:
        self.errors.append(f"{where}: {msg}")

    def warn(self, where: str, msg: str) -> None:
        self.warnings.append(f"{where}: {msg}")

    # -- files ---------------------------------------------------------
    def tree(self) -> dict[str, list[Path]]:
        if self._tree is None:
            self._tree = {}
            for dirpath, dirnames, filenames in os.walk(self.root):
                dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
                for name in filenames:
                    self._tree.setdefault(name, []).append(Path(dirpath) / name)
        return self._tree

    def resolve(self, name: str) -> tuple[Path | None, str]:
        if "/" in name:
            p = self.root / name
            return (p, "") if p.is_file() else (None, f"file {name} does not exist")
        hits = self.tree().get(name, [])
        if len(hits) == 1:
            return hits[0], ""
        if not hits:
            return None, f"file {name} does not exist"
        return None, f"file name {name} is ambiguous ({', '.join(sorted(self.rel(h) for h in hits))}); use the full path"

    def line_count(self, path: Path) -> int:
        if path not in self._line_counts:
            lines = read_lines(path)
            self._line_counts[path] = len(lines) - (1 if lines and lines[-1] == "" else 0)
        return self._line_counts[path]

    # -- loading -------------------------------------------------------
    def load(self) -> None:
        for folder in ("programs", "jobs"):
            for path in sorted((self.spec_dir / folder).glob("*.md")):
                if path.name == "README.md":
                    continue
                spec = Spec(path, self.rel(path), folder)
                text = path.read_text(encoding="utf-8")
                spec.lines = text.splitlines()
                try:
                    spec.meta, spec.body_start = parse_front_matter(text)
                except FrontMatterError as exc:
                    self.error(spec.rel, f"front matter: {exc}")
                    spec.body_start = 0
                self.specs.append(spec)
                self.collect_items(spec)
        self.collect_system_ids()

    def collect_items(self, spec: Spec) -> None:
        unit = spec.meta.get("unit_code")
        for n, line in enumerate(spec.lines, 1):
            m = HEAD_RE.match(line)
            q = Q_ROW_RE.match(line)
            if m:
                ident = m.group(1)
                im = ITEM_ID_RE.match(ident)
                if not im:
                    self.error(f"{spec.rel}:{n}", f"item ID {ident} is not <KIND>-<UNIT>-<NNN> with KIND in {' '.join(ITEM_KINDS)}")
                    continue
                if unit and im.group(2) != unit:
                    self.error(f"{spec.rel}:{n}", f"item {ident} does not use the spec's unit_code {unit}")
                if not m.group(2).strip().startswith("·"):
                    self.error(f"{spec.rel}:{n}", f"item heading must be '#### {ident} · <title>'")
            elif q:
                ident = q.group(1)
                if unit and q.group(2) != unit:
                    self.error(f"{spec.rel}:{n}", f"question {ident} does not use the spec's unit_code {unit}")
            else:
                continue
            if ident in self.defined:
                self.error(f"{spec.rel}:{n}", f"{ident} is already defined in {self.defined[ident]}")
            else:
                self.defined[ident] = f"{spec.rel}:{n}"
                spec.items[ident] = n

    def collect_system_ids(self) -> None:
        for path in [self.spec_dir / "entities.md", *sorted((self.spec_dir / "areas").glob("*.md"))]:
            if not path.is_file():
                continue
            for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                if ENT_HEAD_RE.match(line):
                    self.entities.update(ENT_RE.findall(line.split(" · ", 1)[0]))
                q = Q_ROW_RE.match(line)
                if q and path.name == "entities.md":
                    if q.group(1) in self.defined:
                        self.error(f"{self.rel(path)}:{n}", f"{q.group(1)} is already defined in {self.defined[q.group(1)]}")
                    self.defined.setdefault(q.group(1), f"{self.rel(path)}:{n}")
        comp = self.spec_dir / "components.md"
        if comp.is_file():
            for line in comp.read_text(encoding="utf-8").splitlines():
                m = INV_ROW_RE.match(line)
                if m:
                    self.invariants.add(m.group(1))

    # -- per-spec checks -------------------------------------------------
    def check_meta(self, spec: Spec, units: dict[str, list[str]], reserved: dict[str, str]) -> None:
        meta, where = spec.meta, spec.rel
        if not meta:
            return
        for key in REQUIRED_KEYS:
            if key not in meta or meta[key] in ("", []):
                self.error(where, f"front matter: missing {key}")
        if meta.get("id") and meta["id"] != spec.path.stem:
            self.error(where, f"front matter: id {meta['id']} does not match the file name {spec.path.name}")
        kinds = PROGRAM_KINDS if spec.folder == "programs" else JOB_KINDS
        if meta.get("kind") and meta["kind"] not in kinds:
            self.error(where, f"front matter: kind {meta['kind']!r} is not one of {', '.join(sorted(kinds))} (for {spec.folder}/)")
        if meta.get("module") and meta["module"] not in MODULES:
            self.error(where, f"front matter: module {meta['module']!r} is not one of {', '.join(sorted(MODULES))}")
        if meta.get("spec_status") and meta["spec_status"] not in STATUSES:
            self.error(where, f"front matter: spec_status {meta['spec_status']!r} is not one of {', '.join(sorted(STATUSES))}")
        if meta.get("source_rev") and not re.fullmatch(r"[0-9a-f]{7,40}", str(meta["source_rev"])):
            self.error(where, f"front matter: source_rev {meta['source_rev']!r} is not a commit hash")
        unit = meta.get("unit_code")
        if unit:
            if not isinstance(unit, str) or not UNIT_RE.match(unit):
                self.error(where, f"front matter: unit_code {unit!r} must be 3-6 capital letters or digits, starting with a letter")
            elif len(units.get(unit, [])) > 1:
                self.error(where, f"front matter: unit_code {unit} is also used by {', '.join(u for u in units[unit] if u != where)}")
            if spec.id in reserved and reserved[spec.id] != unit:
                self.error(where, f"front matter: unit_code {unit} differs from the code reserved for {spec.id} in areas/README.md ({reserved[spec.id]})")
            if spec.folder == "jobs" and isinstance(unit, str) and not unit.startswith("J"):
                self.error(where, f"front matter: a job spec's unit_code starts with J ({unit})")
        sources = meta.get("sources")
        if not isinstance(sources, list):
            if sources not in (None, "", []):
                self.error(where, "front matter: sources must be a list")
            return
        for i, src in enumerate(sources):
            if not isinstance(src, dict) or "path" not in src or "role" not in src:
                self.error(where, f"front matter: sources[{i}] needs path and role")
                continue
            path = self.root / str(src["path"])
            if not path.is_file():
                self.error(where, f"front matter: source {src['path']} does not exist")
                continue
            if i == 0:
                spec.primary = path
            digest = src.get("sha256")
            if not digest:
                if "lines" not in src:
                    self.warn(where, f"front matter: source {src['path']} has no sha256")
                continue
            if not re.fullmatch(r"[0-9a-f]{8,64}", str(digest)):
                self.error(where, f"front matter: sha256 of {src['path']} is not a hex prefix")
                continue
            actual = hashlib.sha256(path.read_bytes()).hexdigest()
            if not actual.startswith(str(digest)):
                self.error(where, f"front matter: {src['path']} changed since the spec was written (sha256 {digest}, now {actual[:len(str(digest))]}); re-check the spec and update the hash")

    def check_sections(self, spec: Spec) -> None:
        found = [(int(m.group(1)), n) for n, line in enumerate(spec.lines, 1) if (m := SECTION_RE.match(line))]
        numbers = [s for s, _ in found]
        if numbers != list(range(1, SECTIONS + 1)):
            missing = sorted(set(range(1, SECTIONS + 1)) - set(numbers))
            detail = f"missing {', '.join(map(str, missing))}" if missing else f"found {numbers}"
            self.error(spec.rel, f"sections must be '## 1.' to '## {SECTIONS}.' in order ({detail})")

    def check_item_sources(self, spec: Spec) -> None:
        for ident, n in spec.items.items():
            kind = ident.split("-", 1)[0]
            if kind not in NEEDS_SOURCE:
                continue
            body = []
            for line in spec.lines[n:]:
                if line.startswith("#"):
                    break
                body.append(line)
            if not any("**Source:**" in line for line in body):
                self.warn(f"{spec.rel}:{n}", f"{ident} has no **Source:** line")

    def check_references(self, rel: str, lines: list[str], start: int = 0) -> None:
        for n, line in enumerate(lines, 1):
            if n <= start:
                continue
            for m in REF_RE.finditer(line):
                prefix, first, last = m.group(1), int(m.group(2)), m.group(3)
                for k in range(first, int(last) + 1 if last else first + 1):
                    ident = f"{prefix}-{k:03d}"
                    self.counts["references"] += 1
                    if ident not in self.defined:
                        self.error(f"{rel}:{n}", f"reference {ident} is not defined in any spec")
            for m in ENT_RE.finditer(line):
                if m.group(1) not in self.entities:
                    self.error(f"{rel}:{n}", f"entity {m.group(1)} is not in entities.md or an areas/ proposal")
            for m in INV_RE.finditer(line):
                if m.group(1) not in self.invariants:
                    self.error(f"{rel}:{n}", f"invariant {m.group(1)} is not in components.md")

    def check_citations(self, rel: str, lines: list[str], primary: Path | None, start: int = 0) -> None:
        for n, line in enumerate(lines, 1):
            if n <= start:
                continue
            for span in SPAN_RE.findall(line):
                m = CITE_RE.match(span.strip())
                if not m:
                    continue
                where = f"{rel}:{n}"
                self.counts["citations"] += 1
                if m.group("path"):
                    path, problem = self.resolve(m.group("path"))
                    if path is None:
                        self.error(where, f"citation `{span}`: {problem}")
                        continue
                elif primary is None:
                    self.error(where, f"citation `{span}` has no file and this document has no primary source")
                    continue
                else:
                    path = primary
                count = self.line_count(path)
                for part in m.group("ranges").split(","):
                    a, _, b = part.strip().partition("-")
                    lo, hi = int(a), int(b or a)
                    if lo < 1 or hi < lo:
                        self.error(where, f"citation `{span}`: range {part.strip()} is not ascending")
                    elif hi > count:
                        self.error(where, f"citation `{span}`: {self.rel(path)} has {count} lines")

    def check_coverage(self, spec: Spec) -> None:
        primary = spec.primary
        if primary is None:
            return
        suffix = primary.suffix.lower()
        if suffix in COBOL_EXT:
            units, pre = cobol_paragraphs(primary)
            noun = "paragraph"
        elif suffix in JCL_EXT:
            units, pre = jcl_steps(primary), None
            noun = "step"
        else:
            units, pre = [], None
            noun = None
        start = next((n for n, line in enumerate(spec.lines, 1) if COVERAGE_RE.match(line)), None)
        spec.units = len(units)
        if start is None:
            if noun:
                self.error(spec.rel, f"no '## 16. Paragraph coverage' section ({len(units)} {noun}s to cover)")
            return
        rows: dict[str, list[tuple[int, int, int]]] = {}
        free: list[tuple[int, int, int]] = []
        count = self.line_count(primary)
        for n in range(start + 1, len(spec.lines) + 1):
            line = spec.lines[n - 1]
            if line.startswith("## "):
                break
            if not line.startswith("|") or re.match(r"^\|\s*:?-", line):
                continue
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if len(cells) < 3:
                continue
            name_cell, range_cell, items_cell = cells[0], cells[1], cells[2]
            rm = RANGE_RE.match(range_cell.replace(" ", ""))
            if not rm:
                if name_cell.lower() not in ("paragraph", "step", "unit", "name"):
                    self.error(f"{spec.rel}:{n}", f"coverage row: lines {range_cell!r} is not N-M")
                continue
            lo, hi = int(rm.group(1)), int(rm.group(2) or rm.group(1))
            if hi < lo or hi > count:
                self.error(f"{spec.rel}:{n}", f"coverage row: lines {range_cell} outside {self.rel(primary)} (1-{count})")
            if not items_cell:
                self.error(f"{spec.rel}:{n}", "coverage row: the items cell is empty")
            nm = re.fullmatch(r"`([A-Za-z0-9-]+)`", name_cell)
            if nm:
                rows.setdefault(nm.group(1).upper(), []).append((n, lo, hi))
            elif name_cell.startswith("("):
                free.append((n, lo, hi))
            else:
                self.error(f"{spec.rel}:{n}", f"coverage row: first cell must be `NAME` or (description), not {name_cell!r}")
        if noun is None:
            return
        known = {name for name, _, _ in units}
        starts = {(name, lo) for name, lo, _ in units}
        matched: dict[tuple[str, int], list[tuple[int, int]]] = {}
        for name, found in rows.items():
            if name not in known:
                self.error(f"{spec.rel}:{found[0][0]}", f"coverage row for {name}, which is not a {noun} of {self.rel(primary)}")
                continue
            for n, glo, ghi in found:
                if (name, glo) in starts:
                    matched.setdefault((name, glo), []).append((n, ghi))
                else:
                    expected = ", ".join(str(lo) for nm, lo, _ in units if nm == name)
                    self.error(f"{spec.rel}:{n}", f"{noun} {name} starts at line {expected}, not {glo}")
        covered = 0
        for name, lo, hi in units:
            got = matched.get((name, lo))
            if not got:
                self.error(spec.rel, f"{noun} {name} ({self.rel(primary)}:{lo}-{hi}) has no coverage row")
                continue
            if len(got) > 1:
                self.error(f"{spec.rel}:{got[1][0]}", f"{noun} {name} at line {lo} has more than one coverage row")
            n, ghi = got[0]
            if ghi != hi:
                self.error(f"{spec.rel}:{n}", f"{noun} {name} ends at line {hi}, not {ghi}")
            else:
                covered += 1
        if pre and not any(lo <= pre[0] <= hi for _, lo, hi in free):
            self.error(spec.rel, f"the code before the first paragraph ({self.rel(primary)}:{pre[0]}-{pre[1]}) has no coverage row, e.g. '| (procedure division main line) | {pre[0]}-{pre[1]} | ... |'")
        spec.covered = covered

    # -- inventory -------------------------------------------------------
    def in_scope(self) -> list[Path]:
        app = self.root / "app"
        if not app.is_dir():
            return []
        out = []
        for path in sorted(app.rglob("*")):
            if not path.is_file() or "data" in path.relative_to(app).parts:
                continue
            parent = path.parent.name.lower()
            suffix = path.suffix.lower()
            if suffix in COBOL_EXT or suffix == ".asm" or suffix in (".csd",):
                out.append(path)
            elif parent in ("jcl", "proc") and suffix in JCL_EXT:
                out.append(path)
            elif parent == "scheduler":
                out.append(path)
        return out

    # -- driver ----------------------------------------------------------
    def reserved_codes(self) -> dict[str, str]:
        path = self.spec_dir / "areas" / "README.md"
        if not path.is_file():
            return {}
        reserved = {}
        for line in path.read_text(encoding="utf-8").splitlines():
            for m in RESERVED_RE.finditer(line):
                reserved[m.group(1)] = m.group(2)
        return reserved

    def run(self, only: set[str] | None, require_all: bool) -> dict:
        self.load()
        units: dict[str, list[str]] = {}
        for spec in self.specs:
            if spec.meta.get("unit_code"):
                units.setdefault(str(spec.meta["unit_code"]), []).append(spec.rel)
        reserved = self.reserved_codes()
        selected = [s for s in self.specs if only is None or s.id in only]
        if only:
            for name in sorted(only - {s.id for s in self.specs}):
                self.error("check_spec", f"no spec named {name} in programs/ or jobs/")
        for spec in selected:
            self.check_meta(spec, units, reserved)
            self.check_sections(spec)
            self.check_item_sources(spec)
            self.check_references(spec.rel, spec.lines, spec.body_start)
            self.check_citations(spec.rel, spec.lines, spec.primary, spec.body_start)
            self.check_coverage(spec)
        if only is None:
            system = [self.spec_dir / n for n in ("entities.md", "components.md", "interactions.md")]
            system += sorted((self.spec_dir / "areas").glob("*.md")) + sorted((self.spec_dir / "evidence").glob("*.md"))
            for path in system:
                if path.is_file() and path.name != "README.md":
                    lines = path.read_text(encoding="utf-8").splitlines()
                    self.check_references(self.rel(path), lines)
                    self.check_citations(self.rel(path), lines, None)
        by_primary = {self.rel(s.primary): s for s in self.specs if s.primary}
        inventory = []
        for path in self.in_scope():
            rel = self.rel(path)
            spec = by_primary.get(rel)
            inventory.append({"source": rel, "spec": spec.rel if spec else None,
                              "status": spec.meta.get("spec_status") if spec else None,
                              "units": spec.units if spec else None,
                              "covered": spec.covered if spec else None})
            if spec is None and require_all and only is None:
                self.error(rel, "in-scope source has no spec")
        return {
            "specs": [{"spec": s.rel, "id": s.id, "status": s.meta.get("spec_status"),
                       "primary": self.rel(s.primary) if s.primary else None,
                       "items": len(s.items), "units": s.units, "covered": s.covered} for s in selected],
            "inventory": inventory,
            "counts": dict(self.counts),
            "errors": self.errors,
            "warnings": self.warnings,
        }


def summary(report: dict) -> list[str]:
    out = []
    specs = report["specs"]
    statuses = {}
    for s in specs:
        statuses[s["status"]] = statuses.get(s["status"], 0) + 1
    out.append("SPECS: " + str(len(specs)) + (" (" + ", ".join(f"{k} {v}" for k, v in sorted(statuses.items(), key=str)) + ")" if specs else ""))
    units = sum(s["units"] for s in specs)
    covered = sum(s["covered"] for s in specs)
    out.append(f"COVERAGE: {covered}/{units} paragraphs and steps of specified sources mapped")
    inv = report["inventory"]
    done = [i for i in inv if i["spec"]]
    out.append(f"INVENTORY: {len(done)}/{len(inv)} in-scope sources under app/ have a spec")
    c = report["counts"]
    out.append(f"CHECKED: {c['citations']} source citations, {c['references']} item references")
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check the CardDemo NL specs (docs/modernization/spec).")
    parser.add_argument("--program", action="append", metavar="NAME",
                        help="check only this spec (id, repeatable); references still resolve against all specs")
    parser.add_argument("--require-all", action="store_true",
                        help="also fail when an in-scope source under app/ has no spec")
    parser.add_argument("--inventory", action="store_true",
                        help="list every in-scope source with its spec and coverage")
    parser.add_argument("--json", type=Path, metavar="FILE", help="write the report as JSON")
    parser.add_argument("--root", type=Path, default=ROOT, help="repository root (default: this repository)")
    parser.add_argument("--spec-dir", type=Path, default=None,
                        help="spec folder (default: ROOT/docs/modernization/spec)")
    args = parser.parse_args(argv)

    spec_dir = args.spec_dir or args.root / "docs" / "modernization" / "spec"
    if not spec_dir.is_dir():
        print(f"check_spec: {spec_dir} is not a directory", file=sys.stderr)
        return 2
    checker = Checker(args.root, spec_dir)
    only = {p.upper() for p in args.program} if args.program else None
    report = checker.run(only, args.require_all)
    for line in report["errors"]:
        print(f"ERROR {line}")
    for line in report["warnings"]:
        print(f"warning {line}")
    if args.inventory:
        for item in report["inventory"]:
            detail = f"{item['spec']} [{item['status']}] {item['covered']}/{item['units']}" if item["spec"] else "-"
            print(f"  {item['source']:<60} {detail}")
    for line in summary(report):
        print(line)
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, indent=2) + "\n")
    status = f"{len(report['errors'])} error(s), {len(report['warnings'])} warning(s)"
    print(f"check_spec: {'OK' if not report['errors'] else 'FAILED'} ({status})")
    return 1 if report["errors"] else 0


if __name__ == "__main__":
    sys.exit(main())
