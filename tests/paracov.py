"""Paragraph coverage for GnuCOBOL programs built with -ftrace.

GnuCOBOL has no line coverage. A program compiled with -ftrace and run with
COB_SET_TRACE=Y writes one line per program entry and paragraph/section
entered, for example:

    Program-Id:  CBACT02C         Paragraph: 1000-CARDFILE-GET-NEXT   Line:  92

Coverage is the share of (program entry + paragraphs) that at least one test
reached, measured over every CardDemo program in the build manifest.
"""
from __future__ import annotations

import re
from pathlib import Path

_LABEL = re.compile(r"^([A-Z0-9][A-Z0-9-]*)(\s+SECTION)?\s*\.\s*$", re.IGNORECASE)
_TRACE = re.compile(r"^Program-Id:\s+(\S+)\s+(Paragraph|Section|Entry):\s+(\S+)")
ENTRY = "(entry)"
# Labels cobc generates itself (for example the target of NEXT SENTENCE).
_GENERATED = re.compile(r"^L\$\d+$")


def paragraphs(source: Path) -> list[str]:
    """Paragraph and section names in the PROCEDURE DIVISION (fixed format)."""
    names, in_proc = [], False
    for raw in source.read_text(errors="replace").splitlines():
        if len(raw) < 8 or raw[6] in "*/":
            continue
        area = raw[7:72].rstrip()
        if not in_proc:
            if re.match(r"\s*PROCEDURE\s+DIVISION", area, re.IGNORECASE):
                in_proc = True
            continue
        if area[:1] == " ":
            continue
        m = _LABEL.match(area)
        if m and m.group(1).upper() not in ("END-IF", "EXIT", "GOBACK"):
            names.append(m.group(1).upper())
    return names


def inventory(programs: dict[str, Path]) -> dict[str, set[str]]:
    return {name: {ENTRY, *paragraphs(src)} for name, src in programs.items()}


def reached(trace_files: list[Path]) -> dict[str, set[str]]:
    hits: dict[str, set[str]] = {}
    for path in trace_files:
        for line in path.read_text(errors="replace").splitlines():
            m = _TRACE.match(line)
            if not m:
                continue
            program, kind, name = m.group(1).upper(), m.group(2), m.group(3).upper()
            if _GENERATED.match(name):
                continue
            hits.setdefault(program, set()).add(ENTRY if kind == "Entry" else name)
    return hits


def report(inv: dict[str, set[str]], hits: dict[str, set[str]]) -> dict:
    per_program, total, covered, unknown = {}, 0, 0, {}
    for program, units in sorted(inv.items()):
        got = hits.get(program, set())
        stray = got - units
        if stray:
            unknown[program] = sorted(stray)
        n_cov = len(units & got)
        per_program[program] = {
            "units": len(units),
            "covered": n_cov,
            "percent": round(100.0 * n_cov / len(units), 1),
            "missed": sorted(units - got),
        }
        total += len(units)
        covered += n_cov
    return {
        "total_units": total,
        "covered_units": covered,
        "percent": round(100.0 * covered / total, 1) if total else 0.0,
        "programs_exercised": sum(1 for p in per_program.values() if p["covered"]),
        "programs": per_program,
        "unknown_trace_names": unknown,
    }
