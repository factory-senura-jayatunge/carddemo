"""Test results that cite spec items, read from the reports the test runners write.

- spec-items JSONL (platform/fidelity's SpecItemExtension, one line per JUnit test):
  {"class", "method", "displayName", "items", "status", "mode"}
- JUnit XML (Surefire, Vitest's junit reporter, pytest --junitxml): item IDs come from <property>
  elements (any property whose name is spec, spec_item, specItem, items or tag, or that starts
  with "spec") and from "@<ID>" tags in the test name.
- Playwright JSON reporter: item IDs from "@<ID>" tags in the titles and from the tags list.

The mode of a result is LEGACY or FIXED when the runner records it (the JSONL mode field, a mode
property, an "@legacy"/"@fixed" tag or a "[LEGACY]"/"[FIXED]" parameter in the name), else None.
"""
from __future__ import annotations

import json
import os
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

from .spec import ID_RE

LOOSE_ID_RE = re.compile(r"(?<![\w-])((?:BR|VAL|FLOW|SE|ERR|NUM|DEF|Q)-[A-Za-z0-9]+-\d+|P?INV-[a-z0-9-]*\d+)(?![\w-])")
TAG_RE = re.compile(r"@((?:BR|VAL|FLOW|SE|ERR|NUM|DEF|Q)-[A-Za-z0-9]+-\d+|P?INV-[a-z0-9-]*\d+)(?![\w-])")
MODE_RE = re.compile(r"@(legacy|fixed)\b|\[(LEGACY|FIXED)\]|\bmode[=:]\s*(legacy|fixed)\b", re.I)
PROPERTY_NAMES = {"spec", "spec_item", "specitem", "specitems", "items", "item", "tag", "tags"}


@dataclass
class Result:
    name: str
    source: str
    status: str  # passed | failed | skipped
    mode: str | None
    items: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        return {"name": self.name, "source": self.source, "status": self.status, "mode": self.mode}


def _status(raw: str | None) -> str:
    s = (raw or "").lower()
    if s in ("successful", "passed", "expected", "flaky", "pass"):
        return "passed"
    if s in ("disabled", "skipped", "aborted", "pending", "todo", "skip"):
        return "skipped"
    return "failed"


def _mode(*texts: str | None) -> str | None:
    for t in texts:
        if not t:
            continue
        m = MODE_RE.search(t)
        if m:
            return next(g for g in m.groups() if g).upper()
    return None


def _ids(text: str) -> list[str]:
    return list(dict.fromkeys(LOOSE_ID_RE.findall(text)))


def read_jsonl(path: Path, rel: str) -> tuple[list[Result], list[str]]:
    results, problems = [], []
    for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as e:
            problems.append(f"{rel}:{n}: not JSON: {e}")
            continue
        name = f"{row.get('class')}#{row.get('method')}"
        if row.get("displayName") and row.get("displayName") != f"{row.get('method')}()":
            name += f" {row['displayName']}"
        mode = row.get("mode")
        results.append(Result(name, rel, _status(row.get("status")), mode.upper() if isinstance(mode, str) else _mode(row.get("displayName")),
                              [i for i in row.get("items", []) if isinstance(i, str)]))
    return results, problems


def read_junit(path: Path, rel: str) -> tuple[list[Result], list[str]]:
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as e:
        return [], [f"{rel}: not XML: {e}"]
    results = []
    for case in root.iter("testcase"):
        name = case.get("name", "")
        classname = case.get("classname", "")
        items: list[str] = list(TAG_RE.findall(name))
        mode = _mode(name)
        for prop in case.iter("property"):
            pname = (prop.get("name") or "").lower()
            value = prop.get("value") if prop.get("value") is not None else (prop.text or "")
            if pname in PROPERTY_NAMES or pname.startswith("spec"):
                items += _ids(value)
            elif pname == "mode" and value.strip().lower() in ("legacy", "fixed"):
                mode = value.strip().upper()
        if not items:
            continue
        if case.find("failure") is not None or case.find("error") is not None:
            status = "failed"
        elif case.find("skipped") is not None:
            status = "skipped"
        else:
            status = "passed"
        full = f"{classname}#{name}" if classname else name
        results.append(Result(full, rel, status, mode, list(dict.fromkeys(items))))
    return results, []


def _playwright_specs(suite: dict, titles: list[str]):
    here = titles + ([suite["title"]] if suite.get("title") else [])
    for spec in suite.get("specs", []):
        yield here, spec
    for child in suite.get("suites", []):
        yield from _playwright_specs(child, here)


def read_playwright(data: dict, rel: str) -> list[Result]:
    results = []
    for suite in data.get("suites", []):
        for titles, spec in _playwright_specs(suite, []):
            title = " › ".join(titles[1:] + [spec.get("title", "")]) if len(titles) > 1 else spec.get("title", "")
            text = " ".join(titles + [spec.get("title", "")])
            tags = [t.lstrip("@") for t in spec.get("tags", []) if isinstance(t, str)]
            items = list(dict.fromkeys(TAG_RE.findall(text) + [t for t in tags if LOOSE_ID_RE.fullmatch(t)]))
            if not items:
                continue
            mode = _mode(text, " ".join("@" + t for t in tags))
            for test in spec.get("tests", []):
                status = test.get("status")
                if status is None:
                    runs = test.get("results", [])
                    status = runs[-1].get("status") if runs else "skipped"
                status = {"unexpected": "failed", "timedOut": "failed", "interrupted": "failed"}.get(status, status)
                project = test.get("projectName") or ""
                name = f"{spec.get('file', '')}: {title}" + (f" [{project}]" if project else "")
                results.append(Result(name, rel, _status(status), mode, items))
    return results


def read(path: Path, root: Path) -> tuple[list[Result], list[str]]:
    rel = path.relative_to(root).as_posix() if path.is_relative_to(root) else str(path)
    if path.suffix == ".jsonl":
        return read_jsonl(path, rel)
    if path.suffix == ".xml":
        return read_junit(path, rel)
    if path.suffix == ".json":
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            return [], [f"{rel}: not JSON: {e}"]
        if isinstance(data, dict) and "suites" in data:
            return read_playwright(data, rel), []
        return [], []
    return [], []


SKIP_DIRS = {"node_modules", ".git", "var", ".venv", "venv", "__pycache__", "dist"}
REPORT_DIRS = (
    ("spec-items", (".jsonl",)),
    ("test-results", (".xml", ".json", ".jsonl")),
    ("playwright-report", (".json",)),
    ("surefire-reports", (".xml",)),
    ("failsafe-reports", (".xml",)),
)


def discover(top: Path) -> list[Path]:
    """Every report file under a spec-items, test-results, playwright-report, surefire-reports or
    failsafe-reports directory below top (node_modules, .git and var are skipped)."""
    found: list[Path] = []
    for dirpath, dirnames, filenames in os.walk(top):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        parts = Path(dirpath).relative_to(top).parts
        suffixes = next((sfx for name, sfx in REPORT_DIRS if name in parts), None)
        if suffixes is None:
            continue
        found += [Path(dirpath) / f for f in sorted(filenames) if Path(f).suffix in suffixes]
    return found

def is_valid_id(ident: str) -> bool:
    return ID_RE.fullmatch(ident) is not None
