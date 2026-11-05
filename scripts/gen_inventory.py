#!/usr/bin/env python3
"""Generate docs/inventory.md from the CardDemo sources.

The inventory lists the COBOL and assembler programs of each module, the
CICS transactions, mapsets and files defined in the CSD files, the
programs each JCL job runs, and which programs use each copybook. It is
derived only from files in the repository, so it can be regenerated at
any time.

Usage:
    python3 scripts/gen_inventory.py          # rewrite docs/inventory.md
    python3 scripts/gen_inventory.py --check  # exit 1 if it is stale
"""

from __future__ import annotations

import argparse
import difflib
import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUTPUT = ROOT / "docs" / "inventory.md"
CORE = "core"
SAMPLES = "samples"

COPYBOOK_DIRS = {
    "cpy": "COBOL copybook",
    "cpy-bms": "BMS symbolic map",
    "dcl": "DB2 DCLGEN",
}

NAME = r"[A-Z0-9#@$][A-Z0-9#@$-]*"
COPY_RE = re.compile(r"(?<![\w-])COPY\s+['\"]?(" + NAME + r")['\"]?")
INCLUDE_RE = re.compile(r"\bEXEC\s+SQL\s+INCLUDE\s+(" + NAME + r")")
CALL_RE = re.compile(r"(?<![\w-])CALL\s+['\"](" + NAME + r")['\"]")
PROGRAM_ID_RE = re.compile(r"\bPROGRAM-ID\.?\s+['\"]?(" + NAME + r")")
FEATURES = (
    ("CICS", re.compile(r"\bEXEC\s+CICS\b")),
    ("DB2", re.compile(r"\bEXEC\s+SQL\b")),
    ("IMS", re.compile(r"\bCBLTDLI\b|\bAIBTDLI\b|\bEXEC\s+DLI\b")),
    ("MQ", re.compile(r"\bCALL\s+['\"]MQ[A-Z]+['\"]")),
)
FUNCTION_RE = re.compile(r"^\*\s*FUNCTION\s*:\s*(.*)$", re.I)
HEADER_KEY_RE = re.compile(r"^\*\s*[A-Za-z][A-Za-z ]*:")

CSD_DEFINE_RE = re.compile(r"^DEFINE\s+([A-Z0-9]+)\(([^)]*)\)(.*)$", re.I)
CSD_ATTR_RE = re.compile(r"\b([A-Z0-9]+)\(([^()]*)\)")

JOB_RE = re.compile(r"^//(\S+)\s+JOB\b")
EXEC_RE = re.compile(r"^//(\S*)\s+EXEC\s+(\S+)")
PROC_STMT_RE = re.compile(r"^//(\S+)\s+PROC\b")
IMS_PARM_RE = re.compile(
    r"PARM=\(?'?(?:BMP|DLI|DBB|ULU|UDR|MPP)\s*,\s*(" + NAME + r")")
RUN_PROGRAM_RE = re.compile(r"\bRUN\s+PROGRAM\s*\(\s*(" + NAME + r")\s*\)")
SYSTSIN_MEMBER_RE = re.compile(
    r"^//SYSTSIN\s+DD\s+.*\bDSN=[^,(\s]+\((" + NAME + r")\)", re.M)
BMS_RE = re.compile(r"^(" + NAME + r")\s+(DFHMSD|DFHMDI)\b")


@dataclass
class Program:
    name: str
    module: str
    path: str
    language: str
    program_id: str = ""
    function: str = ""
    features: list = field(default_factory=list)
    copies: list = field(default_factory=list)
    calls: list = field(default_factory=list)


@dataclass
class CsdDef:
    kind: str
    name: str
    path: str
    module: str
    attrs: dict


@dataclass
class Job:
    path: str
    module: str
    name: str
    steps: list
    programs: list


@dataclass
class Mapset:
    name: str
    module: str
    path: str
    maps: list


@dataclass
class Copybook:
    name: str
    module: str
    path: str
    kind: str


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def module_of(path: Path) -> str:
    parts = path.relative_to(ROOT).parts
    if parts[0] == "samples":
        return SAMPLES
    if len(parts) > 2 and parts[1].startswith("app-"):
        return parts[1]
    return CORE


def read_lines(path: Path) -> list:
    return path.read_text(encoding="utf-8", errors="replace").splitlines()


def files_in(dirname: str, suffixes: tuple, roots=("app",)) -> list:
    found = []
    for top in roots:
        for path in (ROOT / top).rglob("*"):
            if (path.is_file() and path.parent.name.lower() == dirname
                    and path.suffix.lower() in suffixes):
                found.append(path)
    return sorted(found, key=rel)


def cobol_code(lines: list) -> str:
    """Return the code area (columns 8-72) of non-comment lines."""
    code = []
    for line in lines:
        if len(line) < 7 or line[6] in "*/":
            continue
        text = line[7:72]
        cut = text.find("*>")
        if cut >= 0:
            text = text[:cut]
        code.append(text)
    return "\n".join(code).upper()


def header_function(lines: list) -> str:
    """Return the text of the 'Function :' field in a program header."""
    comments = [line[6:72].rstrip() for line in lines[:40]
                if len(line) > 6 and line[6] == "*"]
    for i, comment in enumerate(comments):
        match = FUNCTION_RE.match(comment)
        if not match:
            continue
        parts = [match.group(1)]
        for follow in comments[i + 1:]:
            body = follow[1:].strip()
            if (not body or set(body) == {"*"} or HEADER_KEY_RE.match(follow)
                    or not follow[1:].startswith("    ")):
                break
            parts.append(body)
        text = " ".join(p.strip().rstrip("*").strip() for p in parts)
        text = re.sub(r"\s+", " ", text).strip()
        return text[:1].upper() + text[1:].lower() if text.isupper() else text
    return ""


def load_programs() -> dict:
    programs = {}
    for path in files_in("cbl", (".cbl",)):
        lines = read_lines(path)
        code = cobol_code(lines)
        pid = PROGRAM_ID_RE.search(code)
        prog = Program(
            name=path.stem.upper(), module=module_of(path), path=rel(path),
            language="COBOL", program_id=pid.group(1) if pid else "",
            function=header_function(lines),
            features=[n for n, rx in FEATURES if rx.search(code)],
            copies=sorted(set(COPY_RE.findall(code))
                          | set(INCLUDE_RE.findall(code))),
            calls=sorted(set(CALL_RE.findall(code))),
        )
        programs[prog.name] = prog
    for path in files_in("asm", (".asm",)):
        lines = read_lines(path)
        code = "\n".join(l[:71] for l in lines if not l.startswith("*"))
        csect = re.search(r"^(" + NAME + r")\s+(?:CSECT|START)\b",
                          code.upper(), re.M)
        prog = Program(
            name=path.stem.upper(), module=module_of(path), path=rel(path),
            language="Assembler", program_id=csect.group(1) if csect else "",
        )
        programs[prog.name] = prog
    return programs


def load_copybooks() -> dict:
    books = defaultdict(list)
    for dirname, kind in COPYBOOK_DIRS.items():
        for path in files_in(dirname, (".cpy", ".dcl")):
            book = Copybook(path.stem.upper(), module_of(path), rel(path),
                            kind)
            books[book.name].append(book)
    return books


def load_csd() -> list:
    defs = []
    for path in files_in("csd", (".csd",)):
        current = None
        for line in read_lines(path):
            stripped = line.strip()
            match = CSD_DEFINE_RE.match(stripped)
            if match:
                current = [match.group(1).upper(), match.group(2).upper(),
                           match.group(3)]
                defs.append((path, current))
            elif current is not None:
                current[2] += " " + stripped
    result = []
    for path, (kind, name, text) in defs:
        attrs = {k.upper(): v.strip() for k, v in CSD_ATTR_RE.findall(text)}
        result.append(CsdDef(kind, name, rel(path), module_of(path), attrs))
    return result


def load_mapsets() -> dict:
    mapsets = {}
    for path in files_in("bms", (".bms",)):
        name, maps = path.stem.upper(), []
        for line in read_lines(path):
            match = BMS_RE.match(line.upper())
            if match and match.group(2) == "DFHMSD":
                name = match.group(1)
            elif match:
                maps.append(match.group(1))
        mapsets[name] = Mapset(name, module_of(path), rel(path), maps)
    return mapsets


def jcl_steps(lines: list) -> list:
    """Return (step name, EXEC operand, step text) for each EXEC."""
    stmts = [(i, line[:72]) for i, line in enumerate(lines)
             if not line.startswith("//*")]
    execs = [(i, EXEC_RE.match(text)) for i, text in stmts]
    execs = [(i, m) for i, m in execs if m]
    steps = []
    for n, (i, match) in enumerate(execs):
        end = execs[n + 1][0] if n + 1 < len(execs) else len(lines)
        body = "\n".join(l[:72] for l in lines[i:end]
                         if not l.startswith("//*")).upper()
        steps.append((match.group(1), match.group(2).upper(), body))
    return steps


def ctl_text(member: str, module: str) -> str:
    """Return a control-card member from the module's ctl/ directory."""
    for path in files_in("ctl", (".ctl",)):
        if path.stem.upper() == member and module_of(path) in (module, CORE):
            return "\n".join(read_lines(path)).upper()
    return ""


def step_targets(operand: str, body: str, module: str = CORE) -> list:
    """Return the programs or procedures one EXEC statement runs."""
    first = operand.split(",")[0]
    if first.startswith("PGM="):
        pgm = first[4:]
        targets = [pgm]
        if pgm == "DFSRRC00":
            targets += IMS_PARM_RE.findall(body)
        elif pgm.startswith("IKJEFT"):
            targets += RUN_PROGRAM_RE.findall(body)
            for member in SYSTSIN_MEMBER_RE.findall(body):
                targets += RUN_PROGRAM_RE.findall(ctl_text(member, module))
        return targets
    proc = first[5:] if first.startswith("PROC=") else first
    return ["proc:" + proc]


def load_procs() -> dict:
    procs = {}
    for path in files_in("proc", (".prc",), roots=("app", "samples")):
        lines = read_lines(path)
        targets = []
        for _, operand, body in jcl_steps(lines):
            targets += step_targets(operand, body, module_of(path))
        stmt = next((m.group(1) for m in map(PROC_STMT_RE.match, lines)
                     if m), "")
        procs[path.stem.upper()] = (rel(path), targets, stmt)
    return procs


def load_jobs() -> list:
    jobs = []
    for path in files_in("jcl", (".jcl",), roots=("app", "samples")):
        lines = read_lines(path)
        name = next((m.group(1) for m in map(JOB_RE.match, lines) if m), "")
        steps = jcl_steps(lines)
        targets = []
        for _, operand, body in steps:
            targets += step_targets(operand, body, module_of(path))
        jobs.append(Job(rel(path), module_of(path), name, steps, targets))
    return jobs


def expand(targets: list, procs: dict) -> list:
    """Replace proc:NAME targets with the programs that proc runs."""
    out = []
    for target in targets:
        if target.startswith("proc:") and target[5:] in procs:
            out += expand(procs[target[5:]][1], procs)
        elif not target.startswith("proc:"):
            out.append(target)
    return out


def link(path: str) -> str:
    return f"[`{path.split('/')[-1]}`](../{path})"


def cell(values) -> str:
    return ", ".join(values) if values else "-"


def table(headers: list, rows: list) -> list:
    out = ["| " + " | ".join(headers) + " |",
           "| " + " | ".join(":--" for _ in headers) + " |"]
    for row in rows:
        out.append("| " + " | ".join(str(c).replace("|", "\\|")
                                     for c in row) + " |")
    return out


def module_order(module: str) -> tuple:
    return ({CORE: 0, SAMPLES: 2}.get(module, 1), module)


def module_title(module: str) -> str:
    if module == CORE:
        return "Core application (`app/`)"
    if module == SAMPLES:
        return "Samples (`samples/`)"
    return f"Optional module `app/{module}/`"


def resolve(name: str, module: str, books: dict):
    candidates = books.get(name, [])
    for want in (module, CORE):
        for book in candidates:
            if book.module == want:
                return book
    return candidates[0] if candidates else None


def build() -> str:
    programs = load_programs()
    books = load_copybooks()
    csd = load_csd()
    mapsets = load_mapsets()
    procs = load_procs()
    jobs = load_jobs()

    csd_by_kind = defaultdict(list)
    for d in csd:
        csd_by_kind[d.kind].append(d)
    csd_programs = {d.name for d in csd_by_kind["PROGRAM"]}
    csd_mapsets = {d.name: d for d in csd_by_kind["MAPSET"]}

    run_by = defaultdict(set)
    for job in jobs:
        for target in expand(job.programs, procs):
            run_by[target].add(job.path.split("/")[-1])
    called_by = defaultdict(set)
    for prog in programs.values():
        for callee in prog.calls:
            called_by[callee].add(prog.name)
    started_by = defaultdict(set)
    for d in csd_by_kind["TRANSACTION"]:
        started_by[d.attrs.get("PROGRAM", "").upper()].add(d.name)

    users = defaultdict(set)
    nested = defaultdict(set)
    book_copies = {}
    for entries in books.values():
        for book in entries:
            code = cobol_code(read_lines(ROOT / book.path))
            book_copies[book.path] = sorted(set(COPY_RE.findall(code)))
    for prog in programs.values():
        for name in prog.copies:
            book = resolve(name, prog.module, books)
            users[book.path if book else name].add(prog.name)
    for path, names in book_copies.items():
        module = module_of(ROOT / path)
        for name in names:
            book = resolve(name, module, books)
            nested[book.path if book else name].add(path.split("/")[-1])

    def maps_of(prog: Program) -> list:
        return sorted(n for n in prog.copies
                      if (b := resolve(n, prog.module, books))
                      and b.kind == "BMS symbolic map")

    def kind_of(prog: Program) -> str:
        if "CICS" in prog.features:
            return "Online (CICS)"
        if prog.name in called_by and prog.name not in run_by:
            return "Subprogram"
        return "Batch"

    out = [
        "<!-- Generated by scripts/gen_inventory.py. Do not edit by hand. -->",
        "",
        "# CardDemo inventory",
        "",
        "This page is generated from the sources by "
        "`python3 scripts/gen_inventory.py`. CI runs "
        "`python3 scripts/gen_inventory.py --check`, which fails when this "
        "page no longer matches the sources. Regenerate it in the same change "
        "that adds, removes or renames a program, copybook, mapset, CSD "
        "definition or JCL job.",
        "",
        "How each column is derived:",
        "",
        "- **Kind**: *Online (CICS)* when the program contains `EXEC CICS`; "
        "*Subprogram* when another program calls it by literal name and no "
        "JCL runs it; otherwise *Batch*.",
        "- **Uses**: `EXEC CICS`, `EXEC SQL` (DB2), `CBLTDLI`/`AIBTDLI`/"
        "`EXEC DLI` (IMS) and `CALL 'MQ...'` (MQ) found in the code.",
        "- **Transactions** and **CSD files** come from the `DEFINE` "
        "statements in the `csd/` files.",
        "- **Mapsets** are the BMS symbolic-map copybooks (`cpy-bms/`) a "
        "program copies.",
        "- **Run by JCL** lists the jobs whose steps name the program in "
        "`EXEC PGM=`, in a `DFSRRC00` `PARM`, in an `IKJEFT01` `RUN PROGRAM`, "
        "or through a cataloged procedure.",
        "- Lines that are comments in COBOL (column 7) or JCL (`//*`) "
        "are ignored.",
        "",
        "## Summary",
        "",
    ]
    modules = sorted({p.module for p in programs.values()}
                     | {d.module for d in csd} | {j.module for j in jobs},
                     key=module_order)
    rows = []
    for module in modules:
        progs = [p for p in programs.values() if p.module == module]
        rows.append([
            module_title(module),
            sum(1 for p in progs if kind_of(p) == "Online (CICS)"),
            sum(1 for p in progs if kind_of(p) != "Online (CICS)"),
            sum(1 for d in csd_by_kind["TRANSACTION"] if d.module == module),
            sum(1 for m in mapsets.values() if m.module == module),
            sum(1 for j in jobs if j.module == module),
            sum(1 for e in books.values() for b in e if b.module == module),
        ])
    out += table(["Module", "Online programs", "Batch and subprograms",
                  "Transactions", "Mapsets", "JCL jobs", "Copybooks"], rows)

    out += ["", "## Programs", ""]
    for module in modules:
        progs = sorted((p for p in programs.values() if p.module == module),
                       key=lambda p: (kind_of(p) != "Online (CICS)", p.name))
        if not progs:
            continue
        out += [f"### {module_title(module)}", ""]
        rows = [[f"`{p.name}`", link(p.path), p.language, kind_of(p),
                 cell(p.features), p.function or "-",
                 cell(sorted(started_by.get(p.name, ()))),
                 cell(maps_of(p)),
                 cell(sorted(run_by.get(p.name, ()))),
                 cell(sorted(called_by.get(p.name, ())))]
                for p in progs]
        out += table(["Program", "Source", "Language", "Kind", "Uses",
                      "Function (from the header)", "Transactions",
                      "Mapsets", "Run by JCL", "Called by"], rows)
        out.append("")

    out += ["## CICS transactions", ""]
    rows = []
    for d in sorted(csd_by_kind["TRANSACTION"],
                    key=lambda d: (module_order(d.module), d.name)):
        pgm = d.attrs.get("PROGRAM", "").upper()
        prog = programs.get(pgm)
        rows.append([f"`{d.name}`", f"`{pgm}`" if pgm else "-",
                     prog.function if prog and prog.function else "-",
                     cell(maps_of(prog)) if prog else "-",
                     module_title(d.module), link(d.path)])
    out += table(["Transaction", "Program", "Program function", "Mapsets",
                  "Module", "CSD file"], rows)

    out += ["", "## Mapsets", ""]
    rows = []
    for name in sorted(set(mapsets) | set(csd_mapsets),
                       key=lambda n: (module_order(
                           (mapsets.get(n) or csd_mapsets[n]).module), n)):
        ms = mapsets.get(name)
        defined = csd_mapsets.get(name)
        progs = sorted(p.name for p in programs.values()
                       if name in maps_of(p))
        rows.append([f"`{name}`", link(ms.path) if ms else "-",
                     cell(ms.maps) if ms else "-",
                     link(defined.path) if defined else "-",
                     defined.attrs.get("DESCRIPTION", "-") if defined else "-",
                     cell(progs)])
    out += table(["Mapset", "BMS source", "Maps", "CSD file",
                  "CSD description", "Used by"], rows)

    out += ["", "## Other CSD definitions", ""]
    rows = []
    for d in sorted(csd, key=lambda d: (d.kind, d.name, d.path)):
        if d.kind in ("PROGRAM", "TRANSACTION", "MAPSET"):
            continue
        detail = [f"{k}({d.attrs[k]})" for k in
                  ("DSNAME", "DSNAME01", "PLAN", "ENTRY", "TRANSID", "TYPE")
                  if k in d.attrs]
        rows.append([d.kind, f"`{d.name}`", cell(detail),
                     d.attrs.get("DESCRIPTION", "-"), link(d.path)])
    out += table(["Type", "Name", "Key attributes", "Description",
                  "CSD file"], rows)

    out += ["", "## JCL jobs", "",
            "*Application programs* are programs whose source is in this "
            "repository. Everything else a step runs (IBM utilities, "
            "compilers, IMS and DB2 drivers) is listed under *Other "
            "programs*. Procedures are shown as `name (proc)`.", ""]
    for module in modules:
        mod_jobs = [j for j in jobs if j.module == module]
        if not mod_jobs:
            continue
        out += [f"### {module_title(module)}", ""]
        rows = []
        for job in mod_jobs:
            app, other = [], []
            for target in job.programs:
                if target.startswith("proc:"):
                    other.append(f"{target[5:]} (proc)")
                    app += [t for t in expand([target], procs)
                            if t in programs]
                elif target in programs:
                    app.append(target)
                else:
                    other.append(target)
            rows.append([link(job.path), f"`{job.name}`" if job.name else "-",
                         len(job.steps), cell(sorted(set(app))),
                         cell(sorted(set(other)))])
        out += table(["JCL", "Job", "Steps", "Application programs",
                      "Other programs"], rows)
        out.append("")

    if procs:
        out += ["### Cataloged procedures", ""]
        rows = []
        for name, (path, targets, stmt) in sorted(procs.items(),
                                                  key=lambda i: i[1][0]):
            shown = sorted({f"{t[5:]} (proc)" if t.startswith("proc:") else t
                            for t in targets})
            rows.append([link(path), f"`{name}`",
                         f"`{stmt}`" if stmt else "-", cell(shown)])
        out += table(["Procedure source", "Member", "PROC statement",
                      "Programs"], rows)
        out.append("")

    out += ["## Copybooks", ""]
    for module in modules:
        mod_books = sorted((b for e in books.values() for b in e
                            if b.module == module),
                           key=lambda b: (b.kind, b.name))
        if not mod_books:
            continue
        out += [f"### {module_title(module)}", ""]
        rows = [[f"`{b.name}`", link(b.path), b.kind,
                 cell(sorted(users.get(b.path, ()))),
                 cell(sorted(nested.get(b.path, ())))] for b in mod_books]
        out += table(["Copybook", "Source", "Kind", "Used by programs",
                      "Copied by copybooks"], rows)
        out.append("")

    external = sorted(n for n in set(users) | set(nested) if "/" not in n)
    out += ["### Copybooks referenced but not in the repository", "",
            "These names are copied or included by the programs below but "
            "have no source file in the repository. They must come from the "
            "compile-time `SYSLIB` (product libraries such as CICS, DB2 and "
            "MQ).", ""]
    out += table(["Copybook", "Used by"],
                 [[f"`{n}`", cell(sorted(users.get(n, set())
                                         | nested.get(n, set())))]
                  for n in external])

    findings = []
    for prog in sorted(programs.values(), key=lambda p: p.name):
        if prog.program_id and prog.program_id != prog.name:
            findings.append(f"`{prog.path}` declares `{prog.program_id}`, "
                            f"not `{prog.name}`.")
    for name, (path, _, stmt) in sorted(procs.items()):
        if stmt and stmt != name:
            findings.append(f"`{path}` (member `{name}`) has a PROC "
                            f"statement named `{stmt}`. EXEC uses the "
                            "member name.")
    for d in sorted(csd_by_kind["PROGRAM"], key=lambda d: d.name):
        if d.name not in programs:
            findings.append(f"CSD program `{d.name}` ({d.path}) has no "
                            "source in the repository.")
    for prog in sorted(programs.values(), key=lambda p: p.name):
        if "CICS" in prog.features and prog.name not in csd_programs:
            findings.append(f"CICS program `{prog.name}` has no "
                            "`DEFINE PROGRAM` in any CSD file.")
    for d in sorted(csd_by_kind["TRANSACTION"], key=lambda d: d.name):
        pgm = d.attrs.get("PROGRAM", "").upper()
        if pgm and pgm not in csd_programs:
            findings.append(f"Transaction `{d.name}` names program `{pgm}`, "
                            "which no CSD file defines.")
    for name in sorted(set(mapsets) - set(csd_mapsets)):
        findings.append(f"Mapset `{name}` has BMS source but no "
                        "`DEFINE MAPSET` in any CSD file.")
    for name in sorted(set(csd_mapsets) - set(mapsets)):
        findings.append(f"CSD mapset `{name}` has no BMS source.")
    for prog in sorted(programs.values(), key=lambda p: p.name):
        if (kind_of(prog) == "Batch" and prog.name not in run_by
                and prog.name not in called_by):
            findings.append(f"Batch program `{prog.name}` is not run by any "
                            "JCL in the repository.")
    for entries in books.values():
        for book in entries:
            if book.path not in users and book.path not in nested:
                findings.append(f"Copybook `{book.path}` is not copied by "
                                "any program or copybook.")
    for name, entries in sorted(books.items()):
        if len(entries) > 1:
            findings.append(f"Copybook name `{name}` exists more than once: "
                            + ", ".join(f"`{b.path}`" for b in entries) + ".")

    out += ["", "## Cross-reference findings", "",
            "Mismatches between the sources, the CSD files and the JCL, as "
            "found by the generator. They are facts about the current tree, "
            "not necessarily defects.", ""]
    out += [f"- {f}" for f in findings] or ["- None."]
    out.append("")
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true",
                        help="exit 1 if docs/inventory.md is out of date")
    args = parser.parse_args()
    content = build()
    if args.check:
        current = OUTPUT.read_text(encoding="utf-8") if OUTPUT.exists() else ""
        if current == content:
            print(f"{rel(OUTPUT)} is up to date.")
            return 0
        diff = list(difflib.unified_diff(
            current.splitlines(), content.splitlines(),
            f"{rel(OUTPUT)} (committed)", f"{rel(OUTPUT)} (generated)",
            lineterm=""))
        print("\n".join(diff[:200]), file=sys.stderr)
        if len(diff) > 200:
            print(f"... {len(diff) - 200} more diff lines", file=sys.stderr)
        print(f"{rel(OUTPUT)} is stale. Run: python3 scripts/gen_inventory.py",
              file=sys.stderr)
        return 1
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(content, encoding="utf-8")
    print(f"Wrote {rel(OUTPUT)}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
