#!/usr/bin/env python3
"""Static checks for the CardDemo fixed-format COBOL, copybook and JCL sources.

Standard library only (Python 3.10+). Paths, rules and thresholds are read from
cobol-lint.json at the repository root; docs/linting.md documents every rule.

Subcommands:
  check     lint + cobc syntax check (the CI and pre-commit entry point)
  lint      static rules only
  syntax    `cobc -fsyntax-only` for programs GnuCOBOL can parse
  fix       normalise CRLF, tabs and trailing whitespace in the files given
  baseline  rewrite the baseline file from the current findings
  report    findings per rule plus complexity and tech-debt metrics
  rules     list every rule with its configured severity and thresholds

Exit codes: 0 = no new findings, 1 = new findings (or `fix --check` would
change something), 2 = usage, configuration or tool error.
"""
from __future__ import annotations

import argparse
import collections
import concurrent.futures
import fnmatch
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

RULES = {
    # format (fixed-format physical layout)
    "crlf-line-ending": "File uses CRLF line endings instead of LF.",
    "tab-character": "Tab character in a fixed-format source.",
    "trailing-whitespace": "Trailing whitespace that does not pad the line to the full record length.",
    "line-too-long": "Line is longer than the record length.",
    "text-past-column-72": "Columns 73-80 hold text that is not a sequence/identification tag; the compiler ignores it.",
    "invalid-indicator": "Column 7 holds a character that is not a valid indicator.",
    "sequence-area-text": "Columns 1-6 hold something other than blanks or a sequence number.",
    "continuation-area-a": "A continuation line ('-' in column 7) has text in Area A (columns 8-11).",
    "missing-final-newline": "File does not end with a newline.",
    # structure
    "unresolved-copy": "COPY / EXEC SQL INCLUDE names a member that is not in the repository or the external list.",
    "unterminated-exec": "EXEC block without a matching END-EXEC.",
    "duplicate-paragraph": "Paragraph or section name is defined more than once in a program.",
    "stop-run-in-cics": "STOP RUN in a CICS program; use EXEC CICS RETURN or GOBACK.",
    # naming
    "program-id-mismatch": "PROGRAM-ID differs from the source member name.",
    "program-name-format": "Program member name is not a valid 1-8 character z/OS member name.",
    "program-prefix": "Program prefix does not match its runtime (CO = CICS online, CB = batch).",
    "copybook-name-format": "Copybook member name is not a valid 1-8 character z/OS member name.",
    "copybook-prefix": "Copybook name does not use one of the project's copybook prefixes.",
    "bms-copybook-name": "Symbolic-map copybook in cpy-bms has no matching BMS mapset source.",
    "paragraph-naming": "Paragraph name breaks the naming rules or the program's numbered-paragraph style.",
    # complexity and size
    "paragraph-complexity": "Paragraph cyclomatic complexity exceeds the threshold.",
    "program-complexity": "Program cyclomatic complexity exceeds the threshold.",
    "source-size": "Source exceeds the maximum line count or byte size.",
    "large-file": "Tracked file exceeds the maximum size for any file in the repository.",
    # dead and duplicate code
    "unreferenced-paragraph": "Paragraph is never PERFORMed, GO TO'd, referenced or reached by fall-through.",
    "unreachable-code": "Statement follows an unconditional GOBACK, STOP RUN, GO TO, EXIT PROGRAM or CICS RETURN/XCTL.",
    "duplicate-block": "Block of normalised lines duplicates code elsewhere.",
    # modularity and dependencies
    "batch-uses-cics-copybook": "Batch program copies a CICS or BMS copybook.",
    "module-boundary": "Source depends on a copybook or program owned by another module.",
    "unused-copybook": "Copybook is not copied or included by any source.",
    # tech debt
    "tech-debt-marker": "TODO/FIXME/XXX/HACK marker without an issue reference such as TODO(#123).",
    # performance
    "n-plus-one": "Database call runs once per row inside a FETCH/READ loop.",
    # data handling
    "sensitive-display": "DISPLAY writes a sensitive (PII / card / credential) field to the job log.",
    # JCL
    "jcl-statement-format": "JCL line is not a //, //* or /* statement and is not in-stream data.",
    "jcl-name-field": "JCL name field is not a valid 1-8 character name.",
    # cobc
    "cobc-syntax": "GnuCOBOL reports a syntax error.",
    "cobc-warning": "GnuCOBOL reports a warning.",
}

VERBS = {
    "ACCEPT", "ADD", "ALTER", "CALL", "CANCEL", "CLOSE", "COMPUTE", "CONTINUE",
    "DELETE", "DISPLAY", "DIVIDE", "EVALUATE", "EXIT", "GO", "GOBACK", "IF",
    "INITIALIZE", "INSPECT", "MERGE", "MOVE", "MULTIPLY", "NEXT", "OPEN",
    "PERFORM", "READ", "RELEASE", "RETURN", "REWRITE", "SEARCH", "SET", "SORT",
    "START", "STOP", "STRING", "SUBTRACT", "UNSTRING", "WRITE",
}
SCOPE_END = {"END-IF": "IF", "END-EVALUATE": "EVALUATE", "END-PERFORM": "PERFORM", "END-SEARCH": "SEARCH"}
PHRASE_END = {
    "END-READ", "END-WRITE", "END-REWRITE", "END-DELETE", "END-START", "END-RETURN",
    "END-CALL", "END-COMPUTE", "END-ADD", "END-SUBTRACT", "END-MULTIPLY", "END-DIVIDE",
    "END-STRING", "END-UNSTRING", "END-ACCEPT", "END-DISPLAY",
}
NOT_PARAGRAPH = {"EXIT", "GOBACK", "CONTINUE", "ELSE", "DECLARATIVES", "STOP", "END-EXEC"} | set(SCOPE_END) | PHRASE_END
FIGURATIVE = {
    "SPACE", "SPACES", "ZERO", "ZEROS", "ZEROES", "LOW-VALUE", "LOW-VALUES",
    "HIGH-VALUE", "HIGH-VALUES", "QUOTE", "QUOTES", "ALL", "NULL", "NULLS",
}
WORD_RE = re.compile(r"[A-Za-z0-9$#@_]+(?:-+[A-Za-z0-9$#@_]+)*")
# XXX is also a PICTURE string ("PIC XXX") and a format mask, so it only counts as a
# marker at the start of a comment or when followed by ':' or '('.
MARKER_RE = re.compile(r"(?<![\w-])(TODO|FIXME|HACK)(?![\w-])|^\s*(XXX)(?![\w-])|(?<![\w-])(XXX)(?=\s*[:(])")


class ConfigError(Exception):
    pass


# --------------------------------------------------------------------------- model

@dataclass
class Finding:
    path: str
    line: int
    rule: str
    severity: str
    message: str
    fingerprint: str = ""
    baselined: bool = False

    def render(self) -> str:
        return f"{self.path}:{self.line}: {self.severity} [{self.rule}] {self.message}"


@dataclass
class Tok:
    text: str
    kind: str  # word | lit | period | punct
    line: int
    col: int
    first: bool = False
    exec: int = -1


@dataclass
class ExecBlock:
    kind: str
    verb: str
    line: int
    start: int
    end: int
    terminated: bool = True

    @property
    def desc(self) -> str:
        return f"EXEC {self.kind} {self.verb}".strip()


@dataclass
class Para:
    name: str | None
    line: int
    header: int
    body: int
    end: int = 0
    is_section: bool = False
    section: str | None = None


@dataclass
class ParaInfo:
    decisions: int = 0
    statements: int = 0
    performs: list = field(default_factory=list)  # (target, thru, line, loop, k)
    gotos: list = field(default_factory=list)  # (target, line, k)
    unreachable: list = field(default_factory=list)  # (line, verb, terminal_line)
    displays: list = field(default_factory=list)  # (line, [(name, qualified)])
    drivers: list = field(default_factory=list)  # (k, line, desc)
    queries: list = field(default_factory=list)  # (k, line, desc)
    calls: list = field(default_factory=list)  # (target, line)
    inline_loops: list = field(default_factory=list)  # (start_k, end_k, line)
    stop_runs: list = field(default_factory=list)
    falls_through: bool = True


def module_of(rel: str) -> str:
    parts = rel.split("/")
    if len(parts) > 2 and parts[0] == "app" and parts[1].startswith("app-"):
        return parts[1]
    return "base"


class Source:
    def __init__(self, rel: str, kind: str, tab_width: int = 8):
        self.rel = rel
        self.kind = kind  # program | copybook | jcl
        self.path = ROOT / rel
        self.raw = self.path.read_bytes()
        text = self.raw.decode("latin-1")
        parts = text.split("\n")
        self.final_newline = text.endswith("\n") or text == ""
        if parts and parts[-1] == "":
            parts.pop()
        self.crlf_lines = [i for i, line in enumerate(parts, 1) if line.endswith("\r")]
        self.lines = [line[:-1] if line.endswith("\r") else line for line in parts]
        # Structural analysis sees tabs expanded the way the compiler does (-ftab-width);
        # the physical-layout rules keep working on the raw lines.
        self.code_lines = [line.expandtabs(tab_width) if "\t" in line else line for line in self.lines]
        self.member = Path(rel).stem.upper()
        self.module = module_of(rel)
        self._cob = None

    @property
    def cob(self) -> "CobolModel":
        if self._cob is None:
            self._cob = CobolModel(self)
        return self._cob

    def fingerprint(self, rule: str, line: int) -> str:
        if line <= 0 or line > len(self.lines):
            text = "<file>"
        elif self.kind == "jcl":
            text = " ".join(self.lines[line - 1][:72].split())
        else:
            text = " ".join(self.lines[line - 1][6:72].split())
        return hashlib.sha1(f"{rule}\0{text}".encode("latin-1")).hexdigest()[:12]


class CobolModel:
    """Token stream and structure of one fixed-format COBOL source."""

    def __init__(self, src: Source):
        self.src = src
        self.toks: list[Tok] = []
        self.comments: list[tuple[int, str]] = []
        self.execs: list[ExecBlock] = []
        self._tokenize()
        self._group_exec()
        self.copies = self._copies()
        self.program_id = self._program_id()
        self.proc_start = self._proc_start()
        self.paras: list[Para] = self._paragraphs() if self.proc_start is not None else []
        self._items_cache = None

    # -- lexing
    def _tokenize(self) -> None:
        lit_q = None
        lit_buf: list[str] = []
        lit_pos = (0, 0, False)

        def emit(text, kind, ln, col, first):
            self.toks.append(Tok(text, kind, ln, col, first))

        for ln, line in enumerate(self.src.code_lines, 1):
            ind = line[6] if len(line) > 6 else " "
            if ind in "*/":
                self.comments.append((ln, line[7:72]))
                continue
            code = line[7:72]
            n = len(code)
            i = 0
            first = True
            if lit_q is not None:
                if ind == "-":
                    q = code.find(lit_q)
                    i = q + 1 if q >= 0 else n
                else:
                    emit("".join(lit_buf), "lit", *lit_pos)
                    lit_q = None
            while i < n:
                if lit_q is not None:
                    j = code.find(lit_q, i)
                    if j < 0:
                        lit_buf.append(code[i:])
                        i = n
                        break
                    if j + 1 < n and code[j + 1] == lit_q:
                        lit_buf.append(code[i:j + 1])
                        i = j + 2
                        continue
                    lit_buf.append(code[i:j])
                    emit("".join(lit_buf), "lit", *lit_pos)
                    lit_q = None
                    i = j + 1
                    continue
                c = code[i]
                if c.isspace() or c in ",;":
                    i += 1
                    continue
                if c in "'\"":
                    lit_q, lit_buf, lit_pos = c, [], (ln, 8 + i, first)
                    first = False
                    i += 1
                    continue
                if code.startswith("*>", i):
                    self.comments.append((ln, code[i + 2:]))
                    break
                m = WORD_RE.match(code, i)
                if m:
                    emit(m.group(0).upper(), "word", ln, 8 + i, first)
                    i = m.end()
                elif c == ".":
                    nxt = code[i + 1] if i + 1 < n else " "
                    emit(".", "period" if nxt.isspace() else "punct", ln, 8 + i, first)
                    i += 1
                else:
                    emit(c, "punct", ln, 8 + i, first)
                    i += 1
                first = False
        if lit_q is not None:
            emit("".join(lit_buf), "lit", *lit_pos)

    def _group_exec(self) -> None:
        toks = self.toks
        i = 0
        while i < len(toks):
            t = toks[i]
            if t.kind == "word" and t.text in ("EXEC", "EXECUTE"):
                kind = toks[i + 1].text if i + 1 < len(toks) else ""
                verb = toks[i + 2].text if i + 2 < len(toks) and toks[i + 2].kind == "word" else ""
                j = i + 1
                terminated = False
                while j < len(toks):
                    if toks[j].kind == "word" and toks[j].text == "END-EXEC":
                        terminated = True
                        break
                    if toks[j].kind == "word" and toks[j].text in ("EXEC", "EXECUTE"):
                        j -= 1
                        break
                    j += 1
                j = min(j, len(toks) - 1)
                idx = len(self.execs)
                self.execs.append(ExecBlock(kind, verb, t.line, i, j, terminated))
                for k in range(i, j + 1):
                    toks[k].exec = idx
                i = j + 1
            else:
                i += 1

    def word(self, i: int) -> str:
        if 0 <= i < len(self.toks) and self.toks[i].kind == "word":
            return self.toks[i].text
        return ""

    def _copies(self) -> list[tuple[str, int, str]]:
        out = []
        for i, t in enumerate(self.toks):
            if t.kind == "word" and t.text == "COPY" and t.exec < 0 and i + 1 < len(self.toks):
                nxt = self.toks[i + 1]
                if nxt.kind in ("word", "lit"):
                    out.append((Path(nxt.text.strip()).stem.upper(), t.line, "COPY"))
        for b in self.execs:
            if b.kind == "SQL" and b.verb == "INCLUDE" and b.start + 3 <= b.end:
                name = self.toks[b.start + 3].text
                out.append((Path(name.strip()).stem.upper(), b.line, "EXEC SQL INCLUDE"))
        return out

    def _program_id(self) -> tuple[str, int] | None:
        for i, t in enumerate(self.toks):
            if t.kind == "word" and t.text == "PROGRAM-ID":
                j = i + 1
                while j < len(self.toks) and self.toks[j].kind in ("period", "punct"):
                    j += 1
                if j < len(self.toks):
                    return self.toks[j].text.upper(), self.toks[j].line
        return None

    def _proc_start(self) -> int | None:
        for i, t in enumerate(self.toks):
            if t.kind == "word" and t.text == "PROCEDURE" and self.word(i + 1) == "DIVISION":
                j = i + 2
                while j < len(self.toks) and self.toks[j].kind != "period":
                    j += 1
                return j + 1
        return None

    def _paragraphs(self) -> list[Para]:
        paras = [Para(None, self.toks[self.proc_start - 1].line if self.proc_start else 0,
                      self.proc_start, self.proc_start)]
        section = None
        toks = self.toks
        for i in range(self.proc_start, len(toks)):
            t = toks[i]
            if t.exec >= 0 or t.kind != "word" or not t.first or t.col > 11 or t.text in NOT_PARAGRAPH:
                continue
            nxt = toks[i + 1] if i + 1 < len(toks) else None
            if nxt is None:
                continue
            if nxt.kind == "period":
                paras.append(Para(t.text, t.line, i, i + 2, section=section))
            elif nxt.text == "SECTION" and i + 2 < len(toks) and toks[i + 2].kind == "period":
                section = t.text
                paras.append(Para(t.text, t.line, i, i + 3, is_section=True, section=t.text))
        for a, b in zip(paras, paras[1:]):
            a.end = b.header
        paras[-1].end = len(toks)
        return paras

    def items(self, start: int, end: int) -> list:
        out = []
        for i in range(start, end):
            t = self.toks[i]
            if t.exec >= 0:
                b = self.execs[t.exec]
                if b.start == i:
                    out.append(b)
            else:
                out.append(t)
        return out

    def proc_words(self) -> collections.Counter:
        c = collections.Counter()
        if self.proc_start is None:
            return c
        headers = {p.header for p in self.paras if p.name}
        for i in range(self.proc_start, len(self.toks)):
            t = self.toks[i]
            if t.kind == "word" and i not in headers:
                c[t.text] += 1
        return c

    def has_exec(self, *kinds: str) -> bool:
        return any(b.kind in kinds for b in self.execs)

    def data_items(self) -> list[tuple[int, str, int]]:
        """(level, name, line) of data description entries outside the procedure division."""
        out = []
        end = self.proc_start if self.proc_start is not None else len(self.toks)
        toks = self.toks
        for i in range(0, min(end, len(toks)) - 1):
            t = toks[i]
            if t.kind != "word" or not t.first or t.exec >= 0 or not t.text.isdigit():
                continue
            level = int(t.text)
            if not (1 <= level <= 49 or level in (66, 77, 88)):
                continue
            nxt = toks[i + 1]
            name = nxt.text if nxt.kind == "word" else "FILLER"
            out.append((level, name, t.line))
        return out


# --------------------------------------------------------------------------- statement scan

class Scanner:
    """Walks one paragraph's statements, tracking scope so that unconditional
    transfers can be told apart from transfers inside IF/EVALUATE branches."""

    def __init__(self, model: CobolModel, para: Para, para_names: set[str], cfg: dict):
        self.m = model
        self.items = model.items(para.body, para.end)
        self.names = para_names
        self.cfg = cfg
        self.info = ParaInfo()
        self.ignore_unreachable = set(cfg["rules"].get("unreachable-code", {}).get("ignore_verbs", []))

    def w(self, k: int) -> str:
        if 0 <= k < len(self.items):
            it = self.items[k]
            if isinstance(it, Tok) and it.kind == "word":
                return it.text
        return ""

    def run(self) -> ParaInfo:
        info = self.info
        stack = [{"kind": "ROOT", "term": None, "rep": False, "tline": 0}]

        def pop():
            e = stack.pop()
            if e["kind"] == "PERFORM" and e.get("loop"):
                info.inline_loops.append((e["k"], k, e["line"]))

        def pop_until(*kinds):
            # A terminator with no matching open scope (END-STRING after a STRING that had
            # no ON OVERFLOW phrase, for example) must not unwind the enclosing IFs.
            if not any(e["kind"] in kinds for e in stack[1:]):
                return
            while len(stack) > 1 and stack[-1]["kind"] not in kinds:
                pop()

        def pop_through(kind):
            pop_until(kind)
            if len(stack) > 1 and stack[-1]["kind"] == kind:
                pop()

        def reset_top():
            stack[-1]["term"] = None
            stack[-1]["rep"] = False

        def stmt(line, verb):
            info.statements += 1
            top = stack[-1]
            if top["term"] and not top["rep"] and verb not in self.ignore_unreachable:
                info.unreachable.append((line, verb, top["tline"]))
                top["rep"] = True

        def terminal(kind, line):
            stack[-1]["term"] = kind
            stack[-1]["tline"] = line

        items = self.items
        k = 0
        while k < len(items):
            it = items[k]
            if isinstance(it, ExecBlock):
                stmt(it.line, it.desc)
                cls = classify_exec(it, self.cfg)
                if cls == "driver":
                    info.drivers.append((k, it.line, it.desc))
                elif cls == "query":
                    info.queries.append((k, it.line, it.desc))
                if it.kind == "CICS" and it.verb in ("RETURN", "XCTL", "ABEND"):
                    terminal("stop", it.line)
                k += 1
                continue
            t = it
            if t.kind == "period":
                while len(stack) > 1:
                    pop()
                k += 1
                continue
            if t.kind != "word":
                k += 1
                continue
            w = t.text
            nxt = self.w(k + 1)
            prev = self.w(k - 1)
            if w == "ELSE":
                pop_until("IF")
                reset_top()
            elif w == "WHEN":
                pop_until("EVALUATE", "SEARCH")
                reset_top()
                if nxt != "OTHER":
                    info.decisions += 1
            elif w in SCOPE_END:
                pop_through(SCOPE_END[w])
            elif w in PHRASE_END:
                pop_through("PHRASE")
            elif self._phrase_start(w, nxt, prev):
                if stack[-1]["kind"] == "PHRASE":
                    reset_top()
                else:
                    stack.append({"kind": "PHRASE", "term": None, "rep": False, "tline": 0})
            elif w in ("AND", "OR", "UNTIL"):
                info.decisions += 1
            elif w in VERBS and not (w == "NEXT" and nxt != "SENTENCE"):
                stmt(t.line, w)
                if w == "IF":
                    info.decisions += 1
                    stack.append({"kind": "IF", "term": None, "rep": False, "tline": 0})
                elif w in ("EVALUATE", "SEARCH"):
                    stack.append({"kind": w, "term": None, "rep": False, "tline": 0})
                elif w == "PERFORM":
                    self._perform(k, t, stack)
                elif w == "GOBACK":
                    terminal("stop", t.line)
                elif w == "STOP" and nxt == "RUN":
                    info.stop_runs.append(t.line)
                    terminal("stop", t.line)
                elif w == "EXIT" and nxt == "PROGRAM":
                    terminal("stop", t.line)
                elif w == "EXIT" and nxt in ("PARAGRAPH", "SECTION"):
                    terminal("exit", t.line)
                elif w == "NEXT":
                    terminal("exit", t.line)
                elif w == "GO":
                    targets, depending = self._goto_targets(k)
                    for name in targets:
                        info.gotos.append((name, t.line, k))
                    if targets and not depending:
                        terminal("stop", t.line)
                elif w == "DISPLAY":
                    info.displays.append((t.line, self._display_operands(k)))
                elif w == "READ":
                    info.drivers.append((k, t.line, "READ"))
                elif w == "CALL":
                    it2 = items[k + 1] if k + 1 < len(items) else None
                    if isinstance(it2, Tok) and it2.kind == "lit":
                        info.calls.append((it2.text.strip().upper(), t.line))
            k += 1
        while len(stack) > 1:
            pop()
        info.falls_through = stack[0]["term"] != "stop"
        return info

    @staticmethod
    def _phrase_start(w: str, nxt: str, prev: str) -> bool:
        if w == "AT" and nxt in ("END", "END-OF-PAGE", "EOP"):
            return True
        if w == "INVALID":
            return prev != "NOT"
        if w == "ON" and nxt in ("SIZE", "EXCEPTION", "OVERFLOW"):
            return prev != "NOT"
        if w == "SIZE" and nxt == "ERROR" and prev not in ("ON", "NOT"):
            return True
        if w in ("EXCEPTION", "OVERFLOW") and prev not in ("ON", "NOT"):
            return True
        if w == "NOT" and nxt in ("AT", "INVALID", "ON", "SIZE", "EXCEPTION", "OVERFLOW"):
            return True
        return False

    def _perform(self, k: int, t: Tok, stack: list) -> None:
        nxt_it = self.items[k + 1] if k + 1 < len(self.items) else None
        nxt = self.w(k + 1)
        inline = (
            not isinstance(nxt_it, Tok) or nxt_it.kind != "word"
            or nxt in ("UNTIL", "VARYING", "WITH", "TEST", "FOREVER")
            or self.w(k + 2) == "TIMES" or nxt in VERBS
        )
        if inline:
            loop = self._loop_clause(k + 1)
            stack.append({"kind": "PERFORM", "term": None, "rep": False, "tline": 0,
                          "loop": loop, "k": k, "line": t.line})
            return
        thru = None
        j = k + 2
        if self.w(j) in ("THRU", "THROUGH"):
            thru = self.w(j + 1) or None
            j += 2
        self.info.performs.append((nxt, thru, t.line, self._loop_clause(j), k))

    def _loop_clause(self, j: int) -> bool:
        while j < len(self.items):
            it = self.items[j]
            if isinstance(it, ExecBlock) or it.kind == "period":
                return False
            if it.kind == "word":
                if it.text in ("UNTIL", "VARYING"):
                    return True
                if it.text in VERBS or it.text in SCOPE_END or it.text in ("ELSE", "WHEN"):
                    return False
            j += 1
        return False

    def _goto_targets(self, k: int) -> tuple[list[str], bool]:
        j = k + 1
        if self.w(j) == "TO":
            j += 1
        names = []
        while j < len(self.items):
            it = self.items[j]
            if not isinstance(it, Tok) or it.kind != "word":
                break
            if it.text == "DEPENDING":
                return names, True
            if it.text in VERBS or it.text in SCOPE_END or it.text in ("ELSE", "WHEN"):
                break
            names.append(it.text)
            j += 1
        return names, False

    def _display_operands(self, k: int) -> list[str]:
        ops = []
        j = k + 1
        skip_next = False
        while j < len(self.items):
            it = self.items[j]
            if isinstance(it, ExecBlock) or it.kind == "period":
                break
            if it.kind == "word":
                w = it.text
                if (w in VERBS or w in SCOPE_END or w in PHRASE_END
                        or w in ("ELSE", "WHEN", "UPON", "WITH", "NOT", "AT", "ON")):
                    break
                if skip_next:
                    skip_next = False
                elif w in ("OF", "IN", "FUNCTION"):
                    skip_next = True
                elif w not in FIGURATIVE and not w.isdigit():
                    ops.append(w)
            j += 1
        return ops


def classify_exec(b: ExecBlock, cfg: dict) -> str | None:
    rule = cfg.get("rules", {}).get("n-plus-one", {})
    key = f"{b.kind} {b.verb}"
    if key in rule.get("row_drivers", []):
        return "driver"
    if key in rule.get("per_row_queries", []):
        return "query"
    return None


# --------------------------------------------------------------------------- linter

class Linter:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.rules_cfg = cfg["rules"]
        self.findings: list[Finding] = []
        self.sources: dict[str, Source] = {}
        self.metrics: dict = {"paragraph_complexity": [], "program_complexity": [], "markers": collections.Counter()}
        self._discover()
        self.copybooks = {}
        for s in self.sources.values():
            if s.kind == "copybook":
                self.copybooks.setdefault(s.member, []).append(s)
        self.programs = {s.member: s for s in self.sources.values() if s.kind == "program"}
        self._infos: dict[str, dict] = {}

    # -- plumbing
    def opt(self, rule: str, key: str, default=None):
        return self.rules_cfg.get(rule, {}).get(key, default)

    def enabled(self, rule: str) -> bool:
        return self.rules_cfg.get(rule, {}).get("enabled", True)

    def add(self, src: Source | str, line: int, rule: str, message: str, fingerprint: str | None = None) -> None:
        if not self.enabled(rule):
            return
        rel = src if isinstance(src, str) else src.rel
        if fingerprint is None:
            if isinstance(src, Source):
                fingerprint = src.fingerprint(rule, line)
            else:
                fingerprint = hashlib.sha1(f"{rule}\0{message}".encode()).hexdigest()[:12]
        sev = self.rules_cfg.get(rule, {}).get("severity", "error")
        self.findings.append(Finding(rel, max(line, 1), rule, sev, message, fingerprint))

    def _discover(self) -> None:
        paths = self.cfg["paths"]
        excludes = paths.get("exclude", [])
        for kind, key in (("program", "programs"), ("copybook", "copybooks"), ("jcl", "jcl")):
            exts = {e.lower() for e in paths[key]["extensions"]}
            for pat in paths[key]["globs"]:
                for p in sorted(ROOT.glob(pat)):
                    rel = p.relative_to(ROOT).as_posix()
                    if not p.is_file() or p.suffix.lower() not in exts or rel in self.sources:
                        continue
                    if any(fnmatch.fnmatch(rel, ex) for ex in excludes):
                        continue
                    self.sources[rel] = Source(rel, kind, self.cfg.get("tab_width", 8))

    def is_generated(self, rel: str) -> bool:
        return any(fnmatch.fnmatch(rel, g) for g in self.cfg["paths"].get("generated", []))

    def resolve(self, member: str) -> list[Source]:
        return self.copybooks.get(member, [])

    # -- run
    def run(self) -> list[Finding]:
        for src in self.sources.values():
            self.physical(src)
            self.size(src)
            if src.kind == "jcl":
                self.jcl(src)
            else:
                self.cobol_common(src)
        for src in self.sources.values():
            if src.kind == "program":
                self.program(src)
        self.naming()
        self.dependencies()
        self.duplicates()
        self.large_files()
        self.findings.sort(key=lambda f: (f.path, f.line, f.rule))
        return self.findings

    # -- physical layout
    def physical(self, src: Source) -> None:
        rec = self.cfg["record_length"]
        pad_ok = self.opt("trailing-whitespace", "allow_record_padding", True)
        if src.crlf_lines:
            self.add(src, src.crlf_lines[0], "crlf-line-ending",
                     f"{len(src.crlf_lines)} of {len(src.lines)} lines end in CRLF; files must use LF "
                     f"(CRLF breaks the mainframe upload/compile)", fingerprint="file")
        if not src.final_newline:
            self.add(src, len(src.lines), "missing-final-newline", "file does not end with a newline")
        jcl_instream = self.jcl_instream_lines(src) if src.kind == "jcl" else set()
        for ln, line in enumerate(src.lines, 1):
            if "\t" in line:
                self.add(src, ln, "tab-character",
                         f"tab at column {line.index(chr(9)) + 1}; column positions depend on the editor's tab width")
            if len(line) > rec:
                self.add(src, ln, "line-too-long", f"line is {len(line)} characters; records are {rec}")
            stripped = line.rstrip(" \t")
            if stripped != line and not (pad_ok and len(line) == rec):
                self.add(src, ln, "trailing-whitespace",
                         f"{len(line) - len(stripped)} trailing whitespace characters (line length {len(line)})")
            if src.kind == "jcl":
                if ln in jcl_instream or line.startswith("//*"):
                    continue
            else:
                ind = line[6] if len(line) > 6 else " "
                if ind in "*/":
                    pass
                elif ind not in self.opt("invalid-indicator", "allowed", " *-/Dd"):
                    self.add(src, ln, "invalid-indicator", f"column 7 holds {ind!r}")
                seq = line[:6]
                if seq.strip() and not seq.strip().isdigit():
                    self.add(src, ln, "sequence-area-text", f"columns 1-6 hold {seq!r}")
                if ind == "-" and line[7:11].strip():
                    self.add(src, ln, "continuation-area-a", "continuation line has text in columns 8-11")
                if ind in "*/":
                    continue
            ident = line[72:80]
            if ident.strip():
                if not re.fullmatch(r"[A-Za-z0-9@#$]{1,8} *", ident):
                    self.add(src, ln, "text-past-column-72",
                             f"columns 73-80 hold {ident!r}; text there is ignored by the compiler")

    def size(self, src: Source) -> None:
        max_lines = self.opt("source-size", "max_lines")
        max_bytes = self.opt("source-size", "max_bytes")
        if max_lines and len(src.lines) > max_lines:
            self.add(src, 1, "source-size", f"{len(src.lines)} lines exceeds max_lines={max_lines}", fingerprint="lines")
        if max_bytes and len(src.raw) > max_bytes:
            self.add(src, 1, "source-size", f"{len(src.raw)} bytes exceeds max_bytes={max_bytes}", fingerprint="bytes")

    # -- JCL
    @staticmethod
    def jcl_instream_lines(src: Source) -> set[int]:
        out = set()
        dlm = None
        for ln, line in enumerate(src.lines, 1):
            if dlm is not None:
                if line.startswith(dlm):
                    dlm = None
                    continue
                if dlm == "/*" and line.startswith("//"):
                    dlm = None
                else:
                    out.add(ln)
                    continue
            if line.startswith("//") and not line.startswith("//*"):
                stmt = line[2:71]
                if re.search(r"\sDD\s+(\*|DATA)(\s|,|$)", " " + stmt):
                    m = re.search(r"DLM=(?:'([^']{2})'|([^\s,]{2}))", stmt)
                    dlm = (m.group(1) or m.group(2)) if m else "/*"
        return out

    def jcl(self, src: Source) -> None:
        instream = self.jcl_instream_lines(src)
        name_re = re.compile(self.opt("jcl-name-field", "pattern", r"[A-Z@#$][A-Z0-9@#$]{0,7}"))
        if not name_re.fullmatch(src.member):
            self.add(src, 1, "jcl-name-field", f"member name {src.member!r} is not 1-8 alphanumeric/national characters",
                     fingerprint="file")
        for ln, line in enumerate(src.lines, 1):
            if ln in instream or line.startswith("//*") or line.startswith("/*"):
                continue
            if not line.startswith("//"):
                what = "blank line" if not line.strip() else f"{line[:20]!r}"
                self.add(src, ln, "jcl-statement-format",
                         f"{what} outside in-stream data; JCL statements start with // or /*")
                continue
            name = line[2:].split(" ", 1)[0] if len(line) > 2 and line[2] != " " else ""
            if name:
                for part in name.split("."):
                    if not name_re.fullmatch(part):
                        self.add(src, ln, "jcl-name-field", f"name field {name!r} is not 1-8 alphanumeric/national characters")
                        break
        for ln, text in self.jcl_comments(src, instream):
            self.marker(src, ln, text)

    @staticmethod
    def jcl_comments(src: Source, instream: set[int]):
        for ln, line in enumerate(src.lines, 1):
            if ln not in instream and line.startswith("//*"):
                yield ln, line[3:72]

    # -- COBOL shared (programs and copybooks)
    def cobol_common(self, src: Source) -> None:
        m = src.cob
        for b in m.execs:
            if not b.terminated:
                self.add(src, b.line, "unterminated-exec", f"{b.desc} has no END-EXEC before the next EXEC or end of file")
        external = {x.upper() for x in self.cfg["external_copybooks"]["members"]}
        for member, line, via in m.copies:
            if not self.resolve(member) and member not in external:
                self.add(src, line, "unresolved-copy", f"{via} {member}: no such member in the repository "
                         f"and not listed in external_copybooks")
        for ln, text in m.comments:
            self.marker(src, ln, text)

    def marker(self, src: Source, ln: int, text: str) -> None:
        ref = re.compile(self.opt("tech-debt-marker", "reference_pattern", r"\((#\d+|[A-Z][A-Z0-9]+-\d+)\)"))
        for mt in MARKER_RE.finditer(text):
            word = mt.group(1) or mt.group(2) or mt.group(3)
            self.metrics["markers"][word] += 1
            if not ref.match(text[mt.end():]):
                self.add(src, ln, "tech-debt-marker", f"{word} without an issue reference; write it as {word}(#123)")

    # -- programs
    def para_infos(self, src: Source) -> list[tuple[Para, ParaInfo]]:
        if src.rel not in self._infos:
            m = src.cob
            names = {p.name for p in m.paras if p.name}
            self._infos[src.rel] = [(p, Scanner(m, p, names, self.cfg).run()) for p in m.paras]
        return self._infos[src.rel]

    def program(self, src: Source) -> None:
        m = src.cob
        if m.proc_start is None:
            return
        infos = self.para_infos(src)
        is_cics = m.has_exec("CICS")
        # complexity
        p_max = self.opt("paragraph-complexity", "max")
        total = 1
        for p, info in infos:
            cc = 1 + info.decisions
            total += info.decisions
            label = p.name or "(procedure division entry)"
            self.metrics["paragraph_complexity"].append((cc, src.rel, label, p.line))
            if p_max and cc > p_max:
                self.add(src, p.line, "paragraph-complexity",
                         f"{label} has cyclomatic complexity {cc} (max {p_max})")
        self.metrics["program_complexity"].append((total, src.rel))
        g_max = self.opt("program-complexity", "max")
        if g_max and total > g_max:
            self.add(src, m.proc_start and m.toks[m.proc_start - 1].line, "program-complexity",
                     f"program cyclomatic complexity {total} (max {g_max})", fingerprint="file")
        seen_names: dict[str, int] = {}
        for p in m.paras:
            if not p.name:
                continue
            key = (p.section if not p.is_section else None, p.name)
            if key in seen_names:
                self.add(src, p.line, "duplicate-paragraph",
                         f"{p.name} is already defined at line {seen_names[key]}; PERFORM/GO TO {p.name} is ambiguous")
            else:
                seen_names[key] = p.line
        # STOP RUN in CICS
        if is_cics:
            for _, info in infos:
                for line in info.stop_runs:
                    self.add(src, line, "stop-run-in-cics", "STOP RUN ends the CICS region task; use EXEC CICS RETURN")
        self.dead_code(src, infos)
        self.n_plus_one(src, infos)
        self.sensitive(src, infos)

    def _range(self, m: CobolModel, start: str, thru: str | None) -> list[str]:
        named = [p for p in m.paras if p.name]
        idx = {p.name: i for i, p in enumerate(named)}
        if start not in idx:
            return []
        i = idx[start]
        if named[i].is_section and not thru:
            out = [named[i].name]
            for p in named[i + 1:]:
                if p.is_section:
                    break
                out.append(p.name)
            return out
        j = idx.get(thru, i) if thru else i
        return [p.name for p in named[i:j + 1]]

    def dead_code(self, src: Source, infos) -> None:
        m = src.cob
        refs = m.proc_words()
        reached = set()
        for p, info in infos:
            for target, thru, *_ in info.performs:
                reached.update(self._range(m, target, thru))
        named = [(p, i) for p, i in infos if p.name]
        # main-line fall-through: from the entry point and every GO TO target
        starts = {0}
        pos = {p.name: n for n, (p, _) in enumerate(infos) if p.name}
        for p, info in infos:
            for target, *_ in info.gotos:
                if target in pos:
                    starts.add(pos[target])
        if infos and infos[0][1].statements == 0 and len(infos) > 1:
            starts.add(1)
        for s in starts:
            n = s
            while n < len(infos):
                p, info = infos[n]
                if p.name:
                    reached.add(p.name)
                if not info.falls_through:
                    break
                n += 1
        ignore_exit = self.opt("unreferenced-paragraph", "ignore_exit_only_paragraphs", False)
        for p, info in named:
            if p.name in reached or refs.get(p.name):
                continue
            if ignore_exit and self._exit_only(m, p):
                continue
            kind = "section" if p.is_section else "paragraph"
            self.add(src, p.line, "unreferenced-paragraph",
                     f"{kind} {p.name} is never performed, GO TO'd or reached by fall-through")
        for p, info in infos:
            for line, verb, tline in info.unreachable:
                self.add(src, line, "unreachable-code",
                         f"{verb} can never run: it follows an unconditional transfer at line {tline}")

    @staticmethod
    def _exit_only(m: CobolModel, p: Para) -> bool:
        words = [t.text for t in m.toks[p.body:p.end] if t.kind == "word"]
        return words == ["EXIT"]

    def n_plus_one(self, src: Source, infos) -> None:
        m = src.cob
        by_name = {p.name: info for p, info in infos if p.name}

        def closure(names: list[str]) -> set[str]:
            seen, todo = set(), list(names)
            while todo:
                n = todo.pop()
                if n in seen or n not in by_name:
                    continue
                seen.add(n)
                info = by_name[n]
                for target, thru, *_ in info.performs:
                    todo.extend(self._range(m, target, thru))
                for target, *_ in info.gotos:
                    todo.append(target)
            return seen

        reported = set()

        def check(loop_line, drivers, queries):
            if not drivers or not queries:
                return
            d = drivers[0]
            for q in queries:
                if q[1] in reported:
                    continue
                reported.add(q[1])
                self.add(src, q[1], "n-plus-one",
                         f"{q[2]} runs once per row: the loop at line {loop_line} iterates {d[2]} (line {d[1]}); "
                         f"use a join, cursor or set-based statement")

        for p, info in infos:
            for target, thru, line, loop, _k in info.performs:
                if not loop:
                    continue
                body = closure(self._range(m, target, thru))
                drivers = [d for n in body for d in by_name[n].drivers]
                queries = [q for n in body for q in by_name[n].queries]
                check(line, sorted(drivers, key=lambda x: x[1]), sorted(queries, key=lambda x: x[1]))
            for start, end, line in info.inline_loops:
                drivers = [d for d in info.drivers if start < d[0] < end]
                queries = [q for q in info.queries if start < q[0] < end]
                called = []
                for target, thru, _l, _loop, k in info.performs:
                    if start < k < end:
                        called.extend(self._range(m, target, thru))
                for target, _l, k in info.gotos:
                    if start < k < end:
                        called.append(target)
                for n in closure(called):
                    drivers += by_name[n].drivers
                    queries += by_name[n].queries
                check(line, sorted(drivers, key=lambda x: x[1]), sorted(queries, key=lambda x: x[1]))

    # -- sensitive data
    def _expanded_items(self, m: CobolModel, seen: set[str]) -> list[tuple[int, str]]:
        """Data items of a source with COPY members spliced in at their position."""
        items = [(t.line, (lvl, name)) for lvl, name, t_line in m.data_items() for t in [Tok("", "", t_line, 0)]]
        copies = [(line, member) for member, line, via in m.copies if via == "COPY"]
        end_line = m.toks[m.proc_start].line if m.proc_start is not None and m.proc_start < len(m.toks) else 10 ** 9
        merged = [(line, 0, it) for line, it in items]
        for line, member in copies:
            if line >= end_line or member in seen:
                continue
            for cb in self.resolve(member)[:1]:
                sub = self._expanded_items(cb.cob, seen | {member})
                merged.append((line, 1, sub))
        merged.sort(key=lambda x: (x[0], x[1]))
        out = []
        for _line, is_copy, it in merged:
            if is_copy:
                out.extend(it)
            else:
                out.append(it)
        return out

    def sensitive_names(self, src: Source) -> dict[str, str]:
        pats = [re.compile(p) for p in self.opt("sensitive-display", "field_patterns", [])]
        items = self._expanded_items(src.cob, set())
        out: dict[str, str] = {}
        stack: list[tuple[int, str]] = []
        for level, name in items:
            if level == 88:
                continue
            if level in (1, 66, 77):
                stack = []
            while stack and stack[-1][0] >= level:
                stack.pop()
            if name != "FILLER" and any(p.search(name) for p in pats):
                out.setdefault(name, f"field {name}")
                for _lvl, parent in stack:
                    if parent != "FILLER":
                        out.setdefault(parent, f"group {parent}, which contains {name}")
            if level not in (66, 77):
                stack.append((level, name))
        return out

    def sensitive(self, src: Source, infos) -> None:
        if not self.enabled("sensitive-display"):
            return
        names = None
        for _p, info in infos:
            for line, ops in info.displays:
                if names is None:
                    names = self.sensitive_names(src)
                for op in ops:
                    if op in names:
                        self.add(src, line, "sensitive-display",
                                 f"DISPLAY writes {names[op]} to the job log/console; mask it or log a reference key")
                        break

    # -- naming
    def naming(self) -> None:
        name_re = re.compile(self.opt("program-name-format", "pattern", r"[A-Z@#$][A-Z0-9@#$]{0,7}"))
        cb_re = re.compile(self.opt("copybook-name-format", "pattern", r"[A-Z@#$][A-Z0-9@#$]{0,7}"))
        online = self.opt("program-prefix", "online_prefix", "CO")
        batch = self.opt("program-prefix", "batch_prefix", "CB")
        prefixes = tuple(self.opt("copybook-prefix", "prefixes", []))
        suffixes = tuple(self.opt("copybook-prefix", "suffixes", []))
        cb_exceptions = set(self.opt("copybook-prefix", "exceptions", []))
        numbered = re.compile(self.opt("paragraph-naming", "numbered_pattern", r"\d{4}[A-Z]?-"))
        share = self.opt("paragraph-naming", "numbered_share", 0.75)
        max_len = self.opt("paragraph-naming", "max_length", 30)
        for src in self.sources.values():
            if src.kind == "program":
                if not name_re.fullmatch(src.member):
                    self.add(src, 1, "program-name-format", f"member name {src.member!r} is not 1-8 characters A-Z, 0-9, @#$", fingerprint="file")
                pid = src.cob.program_id
                if pid and pid[0] != src.member:
                    self.add(src, pid[1], "program-id-mismatch", f"PROGRAM-ID {pid[0]} but the member is {src.member}")
                if src.cob.has_exec("CICS") and not src.member.startswith(online):
                    hint = f" (the {batch} prefix is for batch programs)" if src.member.startswith(batch) else ""
                    self.add(src, 1, "program-prefix",
                             f"CICS program {src.member} should start with {online}{hint}", fingerprint="file")
                paras = [p for p in src.cob.paras if p.name and not p.is_section]
                for p in paras:
                    if "_" in p.name or len(p.name) > max_len:
                        self.add(src, p.line, "paragraph-naming", f"{p.name}: paragraph names use hyphens and at most {max_len} characters")
                if paras:
                    num = [p for p in paras if numbered.match(p.name)]
                    if len(num) / len(paras) >= share:
                        for p in paras:
                            if not numbered.match(p.name):
                                self.add(src, p.line, "paragraph-naming",
                                         f"{p.name} is not numbered, but {len(num)}/{len(paras)} paragraphs in this program "
                                         f"use the NNNN-NAME style")
            elif src.kind == "copybook":
                if not cb_re.fullmatch(src.member):
                    self.add(src, 1, "copybook-name-format", f"member name {src.member!r} is not 1-8 characters A-Z, 0-9, @#$", fingerprint="file")
                if "/cpy-bms/" in "/" + src.rel:
                    bms_dir = src.path.parent.parent / "bms"
                    maps = {p.stem.upper() for p in bms_dir.glob("*") if p.suffix.lower() == ".bms"} if bms_dir.is_dir() else set()
                    if src.member not in maps:
                        self.add(src, 1, "bms-copybook-name", f"no BMS mapset {src.member}.bms in {bms_dir.relative_to(ROOT).as_posix()}", fingerprint="file")
                elif not (src.member.startswith(prefixes) or src.member.endswith(suffixes) or src.member in cb_exceptions):
                    self.add(src, 1, "copybook-prefix",
                             f"{src.member} does not start with one of {', '.join(prefixes)}", fingerprint="file")

    # -- dependencies and module boundaries
    def dependencies(self) -> None:
        cics_cb = {x.upper() for x in self.opt("batch-uses-cics-copybook", "cics_copybooks", [])}
        used = set()
        for src in self.sources.values():
            if src.kind == "jcl":
                continue
            m = src.cob
            is_batch = src.kind == "program" and not m.has_exec("CICS")
            for member, line, via in m.copies:
                used.add(member)
                targets = self.resolve(member)
                if is_batch:
                    if member in cics_cb:
                        self.add(src, line, "batch-uses-cics-copybook", f"batch program copies CICS copybook {member}")
                    elif targets and all("/cpy-bms/" in t.rel for t in targets):
                        self.add(src, line, "batch-uses-cics-copybook", f"batch program copies BMS symbolic map {member}")
                if targets:
                    self._boundary(src, line, f"{via} {member}", {t.module for t in targets})
            if src.kind == "program":
                for _p, info in self.para_infos(src):
                    for target, line in info.calls:
                        if target in self.programs:
                            self._boundary(src, line, f"CALL '{target}'", {self.programs[target].module})
        for member, srcs in self.copybooks.items():
            if member not in used:
                for s in srcs:
                    self.add(s, 1, "unused-copybook", f"no source copies or includes {member}", fingerprint="file")

    def _boundary(self, src: Source, line: int, what: str, owners: set[str]) -> None:
        allowed = {src.module, "base"}
        if owners & allowed:
            return
        owner = ", ".join(sorted(owners))
        if src.module == "base":
            msg = f"{what}: base application depends on extension module {owner}"
        else:
            msg = f"{what}: module {src.module} depends on another extension module ({owner})"
        self.add(src, line, "module-boundary", msg)

    # -- duplicates
    def duplicates(self) -> None:
        if not self.enabled("duplicate-block"):
            return
        window = self.opt("duplicate-block", "min_lines", 12)
        scope = self.opt("duplicate-block", "scope", "procedure")
        trivial = {x.upper() for x in self.opt("duplicate-block", "ignore_lines", [])}
        seqs = {}
        for rel, src in sorted(self.sources.items()):
            if src.kind == "jcl" or self.is_generated(rel):
                continue
            m = src.cob
            if scope == "procedure":
                if src.kind != "program" or m.proc_start is None or m.proc_start >= len(m.toks):
                    continue
                first_line = m.toks[m.proc_start].line
            else:
                first_line = 1
            seq = []
            for ln, line in enumerate(src.code_lines, 1):
                if ln < first_line:
                    continue
                ind = line[6] if len(line) > 6 else " "
                if ind in "*/":
                    continue
                norm = " ".join(line[7:72].split()).upper()
                if not norm or norm in trivial:
                    continue
                seq.append((norm, ln))
            seqs[rel] = seq
        first_seen: dict[int, tuple[str, int]] = {}
        for rel, seq in seqs.items():
            cur = None
            blocks = []
            for i in range(len(seq) - window + 1):
                key = hash(tuple(x[0] for x in seq[i:i + window]))
                hit = first_seen.get(key)
                if hit is None:
                    first_seen[key] = (rel, i)
                    continue
                if hit[0] == rel and i - hit[1] < window:
                    continue
                if cur and cur["end"] == i - 1:
                    cur["end"] = i
                else:
                    cur = {"start": i, "end": i, "orig": hit}
                    blocks.append(cur)
            for b in blocks:
                start_ln = seq[b["start"]][1]
                end_ln = seq[b["end"] + window - 1][1]
                o_rel, o_i = b["orig"]
                o_ln = seqs[o_rel][o_i][1]
                n = b["end"] - b["start"] + window
                self.add(self.sources[rel], start_ln, "duplicate-block",
                         f"{n} normalised lines (to line {end_ln}) duplicate {o_rel}:{o_ln}")

    # -- large files anywhere in the repository
    def tracked_files(self) -> list[str]:
        try:
            out = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z"], capture_output=True, check=True)
            return [p for p in out.stdout.decode("utf-8", "replace").split("\0") if p]
        except (OSError, subprocess.CalledProcessError):
            return [p.relative_to(ROOT).as_posix() for p in ROOT.rglob("*") if p.is_file() and ".git" not in p.parts]

    def large_files(self, files: list[tuple[str, int]] | None = None) -> None:
        limit = self.opt("large-file", "max_bytes")
        if not limit:
            return
        if files is None:
            files = []
            for rel in self.tracked_files():
                p = ROOT / rel
                if p.is_file():
                    files.append((rel, p.stat().st_size))
        for rel, size in files:
            if size > limit:
                self.add(rel, 1, "large-file", f"{size} bytes exceeds max_bytes={limit}", fingerprint="file")


# --------------------------------------------------------------------------- cobc syntax check

def syntax_check(cfg: dict, linter: Linter, only: set[str] | None, allow_missing: bool) -> tuple[list[Finding], list[str], list[tuple[str, str]], str | None]:
    """Returns (findings, checked, skipped, problem)."""
    scfg = cfg["syntax"]
    cobc = shutil.which(scfg.get("cobc", "cobc"))
    if not cobc:
        msg = "cobc not found on PATH; the GnuCOBOL syntax check did not run"
        return [], [], [], msg
    skip_kinds = set(scfg.get("skip_exec_kinds", ["CICS", "SQL", "DLI"]))
    base_dirs = sorted({s.path.parent for s in linter.sources.values() if s.kind == "copybook" and s.module == "base"})
    jobs, skipped = [], []
    for rel, src in sorted(linter.sources.items()):
        if src.kind != "program" or (only is not None and rel not in only):
            continue
        kinds = sorted({b.kind for b in src.cob.execs} & skip_kinds)
        if kinds:
            skipped.append((rel, "contains EXEC " + "/".join(kinds) + " (GnuCOBOL has no precompiler for it)"))
            continue
        own = sorted({s.path.parent for s in linter.sources.values() if s.kind == "copybook" and s.module == src.module})
        dirs = own + [d for d in base_dirs if d not in own]
        cmd = [cobc, "-fsyntax-only", f"-std={scfg.get('std', 'ibm')}", f"-ftab-width={cfg.get('tab_width', 8)}"]
        cmd += scfg.get("flags", []) + scfg.get("per_program_flags", {}).get(rel, [])
        for d in dirs:
            cmd += ["-I", str(d)]
        cmd.append(str(src.path))
        jobs.append((rel, cmd))
    line_re = re.compile(r"^(.*?):(\d+): (error|warning): (.*)$")
    findings: list[Finding] = []
    seen: set[tuple[str, str, str, str]] = set()

    def run(job):
        rel, cmd = job
        p = subprocess.run(cmd, capture_output=True, text=True, cwd=ROOT, errors="replace")
        return rel, p.returncode, p.stdout + p.stderr

    sev = {r: cfg["rules"].get(r, {}).get("severity", "error") for r in ("cobc-syntax", "cobc-warning")}
    with concurrent.futures.ThreadPoolExecutor(max_workers=os.cpu_count() or 4) as ex:
        for rel, rc, out in ex.map(run, jobs):
            errors = 0
            for raw in out.splitlines():
                mt = line_re.match(raw)
                if not mt:
                    continue
                path, ln, level, msg = mt.groups()
                try:
                    frel = Path(path).resolve().relative_to(ROOT).as_posix()
                except ValueError:
                    frel = path
                rule = "cobc-syntax" if level == "error" else "cobc-warning"
                errors += level == "error"
                if not cfg["rules"].get(rule, {}).get("enabled", True):
                    continue
                # a copybook problem is reported once, not once per program that copies it
                key = (frel, ln, level, msg)
                if key in seen:
                    continue
                seen.add(key)
                via = f" (compiling {rel})" if frel != rel else ""
                fp = hashlib.sha1(f"{rule}\0{msg}".encode()).hexdigest()[:12]
                findings.append(Finding(frel, int(ln), rule, sev[rule], msg + via, fp))
            if rc != 0 and errors == 0:
                fp = hashlib.sha1(f"cobc-syntax\0rc{rc}".encode()).hexdigest()[:12]
                findings.append(Finding(rel, 1, "cobc-syntax", sev["cobc-syntax"], f"cobc exited with {rc}: {out.strip()[:200]}", fp))
    return findings, [j[0] for j in jobs], skipped, None


# --------------------------------------------------------------------------- baseline

def load_baseline(cfg: dict) -> dict:
    path = ROOT / cfg["baseline"]
    if not path.exists():
        return {}
    return json.loads(path.read_text()).get("findings", {})


def apply_baseline(findings: list[Finding], baseline: dict) -> int:
    budget = collections.Counter()
    for path, rules in baseline.items():
        for rule, fps in rules.items():
            for fp, n in fps.items():
                budget[(path, rule, fp)] += n
    for f in findings:
        key = (f.path, f.rule, f.fingerprint)
        if budget[key] > 0:
            budget[key] -= 1
            f.baselined = True
    return sum(v for v in budget.values() if v > 0)


def write_baseline(cfg: dict, findings: list[Finding]) -> Path:
    data: dict = {}
    for f in findings:
        fps = data.setdefault(f.path, {}).setdefault(f.rule, {})
        fps[f.fingerprint] = fps.get(f.fingerprint, 0) + 1
    path = ROOT / cfg["baseline"]
    doc = {
        "_comment": "Known findings accepted when the linter was introduced. Generated by "
                    "`python3 scripts/cobol_lint.py baseline`; keys are path -> rule -> line fingerprint -> count. "
                    "check/lint fail only on findings not listed here. Fixing code never requires editing this file; "
                    "regenerate it to drop entries that no longer occur.",
        "version": 1,
        "total": len(findings),
        "findings": {p: {r: dict(sorted(v.items())) for r, v in sorted(rs.items())} for p, rs in sorted(data.items())},
    }
    path.write_text(json.dumps(doc, indent=1, sort_keys=False) + "\n")
    return path


# --------------------------------------------------------------------------- fix (formatter)

def expand_tabs(line: str, width: int) -> tuple[str, bool]:
    """Expands tabs outside literals. Returns (line, tab_left_in_literal)."""
    out, col, quote, left = [], 0, None, False
    for i, c in enumerate(line):
        if quote is None and c in "'\"" and i >= 7:
            quote = c
        elif quote is not None and c == quote:
            quote = None
        if c == "\t" and quote is None:
            n = width - (col % width)
            out.append(" " * n)
            col += n
            continue
        if c == "\t":
            left = True
        out.append(c)
        col += 1
    return "".join(out), left


def fix_file(cfg: dict, path: Path, check_only: bool) -> list[str]:
    rel = path.resolve().relative_to(ROOT).as_posix()
    rec = cfg["record_length"]
    width = cfg.get("tab_width", 8)
    raw = path.read_bytes()
    text = raw.decode("latin-1")
    parts = text.split("\n")
    if parts and parts[-1] == "":
        parts.pop()
    notes = collections.Counter()
    new_lines = []
    for ln, line in enumerate(parts, 1):
        if line.endswith("\r"):
            line = line[:-1]
            notes["CRLF converted"] += 1
        if "\t" in line:
            expanded, left = expand_tabs(line, width)
            if left:
                notes[f"line {ln}: tab inside a literal left for manual review"] += 1
            if len(expanded.rstrip()) > 72 and len(line.rstrip()) <= 72 and not (len(line) > 6 and line[6] in "*/"):
                notes[f"line {ln}: tab expansion would push code past column 72; fix by hand"] += 1
            else:
                if expanded != line:
                    notes["tab expanded"] += 1
                line = expanded
        stripped = line.rstrip(" \t")
        if stripped != line and len(line) != rec:
            line = stripped
            notes["trailing whitespace removed"] += 1
        new_lines.append(line)
    new_text = "\n".join(new_lines) + ("\n" if new_lines else "")
    if text and not text.endswith("\n"):
        notes["final newline added"] += 1
    changed = new_text.encode("latin-1") != raw
    if changed and not check_only:
        path.write_bytes(new_text.encode("latin-1"))
    if not changed:
        return []
    return [f"{rel}: {k}" + (f" ({v} lines)" if not k.startswith("line ") and v > 1 else "") for k, v in notes.items()]


# --------------------------------------------------------------------------- CLI

def load_config(path: Path) -> dict:
    try:
        cfg = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as e:
        raise ConfigError(f"cannot read {path}: {e}")
    unknown = set(cfg.get("rules", {})) - set(RULES)
    missing = set(RULES) - set(cfg.get("rules", {}))
    if unknown:
        raise ConfigError(f"unknown rule ids in {path.name}: {', '.join(sorted(unknown))}")
    if missing:
        raise ConfigError(f"{path.name} must configure every rule; missing: {', '.join(sorted(missing))}")
    for key in ("paths", "record_length", "baseline", "external_copybooks", "syntax"):
        if key not in cfg:
            raise ConfigError(f"{path.name} lacks '{key}'")
    return cfg


def staged_files() -> list[str]:
    out = subprocess.run(["git", "-C", str(ROOT), "diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z"],
                         capture_output=True, check=True)
    return [p for p in out.stdout.decode("utf-8", "replace").split("\0") if p]


def staged_sizes(paths: list[str]) -> list[tuple[str, int]]:
    out = []
    for p in paths:
        r = subprocess.run(["git", "-C", str(ROOT), "cat-file", "-s", f":{p}"], capture_output=True, text=True)
        if r.returncode == 0:
            out.append((p, int(r.stdout.strip())))
    return out


def summarize(findings: list[Finding], baseline_used: bool) -> list[str]:
    total = collections.Counter(f.rule for f in findings)
    new = collections.Counter(f.rule for f in findings if not f.baselined)
    lines = []
    if not findings:
        return ["no findings"]
    width = max(len(r) for r in total)
    head = f"{'rule':<{width}}  {'total':>6}" + (f"  {'baselined':>9}  {'new':>5}" if baseline_used else "")
    lines.append(head)
    for rule in sorted(total):
        row = f"{rule:<{width}}  {total[rule]:>6}"
        if baseline_used:
            row += f"  {total[rule] - new[rule]:>9}  {new[rule]:>5}"
        lines.append(row)
    n_new = sum(new.values())
    lines.append(f"{'TOTAL':<{width}}  {len(findings):>6}" + (f"  {len(findings) - n_new:>9}  {n_new:>5}" if baseline_used else ""))
    return lines


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="cobol_lint.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", type=Path, default=None, help="config file (default: <root>/cobol-lint.json)")
    ap.add_argument("--root", type=Path, default=None, help="repository root to lint (default: this script's repository)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("check", "lint", "syntax", "report"):
        sp = sub.add_parser(name)
        sp.add_argument("paths", nargs="*", help="limit reported findings to these files (repo-relative or absolute)")
        sp.add_argument("--no-baseline", action="store_true", help="report every finding, including baselined ones")
        sp.add_argument("--rule", action="append", default=[], help="only report this rule (repeatable)")
        sp.add_argument("--staged", action="store_true", help="lint the files staged in git (pre-commit mode)")
        sp.add_argument("--allow-missing-cobc", action="store_true", help="warn instead of failing when cobc is absent")
        sp.add_argument("--quiet", action="store_true", help="print only the summary")
        sp.add_argument("--quiet-clean", action="store_true", help="print nothing when there are no new findings")
    sp = sub.add_parser("fix")
    sp.add_argument("paths", nargs="+", help="files to normalise in place")
    sp.add_argument("--check", action="store_true", help="report what would change; exit 1 if anything would")
    sub.add_parser("baseline", help="rewrite the baseline from the current lint + syntax findings")
    sub.add_parser("rules", help="list rules, severities and thresholds")
    args = ap.parse_args(argv)

    global ROOT
    if args.root is not None:
        ROOT = args.root.resolve()
    try:
        cfg = load_config(args.config or ROOT / "cobol-lint.json")
    except ConfigError as e:
        print(f"cobol_lint: {e}", file=sys.stderr)
        return 2

    if args.cmd == "rules":
        for rid, desc in RULES.items():
            rc = cfg["rules"][rid]
            params = {k: v for k, v in rc.items() if k not in ("enabled", "severity", "reason", "field_patterns")}
            state = rc.get("severity", "error") if rc.get("enabled", True) else "disabled"
            print(f"{rid:<26} {state:<8} {desc}" + (f"  {json.dumps(params)}" if params else ""))
        return 0

    if args.cmd == "fix":
        notes, missing = [], []
        for p in args.paths:
            path = Path(p) if Path(p).is_absolute() else Path.cwd() / p
            if not path.is_file():
                missing.append(p)
                continue
            notes += fix_file(cfg, path, args.check)
        for n in notes:
            print(("would fix " if args.check else "fixed ") + n)
        for p in missing:
            print(f"cobol_lint: no such file: {p}", file=sys.stderr)
        if missing:
            return 2
        return 1 if (args.check and notes) else 0

    linter = Linter(cfg)
    if args.cmd == "baseline":
        findings = linter.run()
        syn, _checked, _skipped, problem = syntax_check(cfg, linter, None, True)
        if problem:
            print(f"cobol_lint: {problem}; baseline not written", file=sys.stderr)
            return 2
        path = write_baseline(cfg, findings + syn)
        print(f"wrote {path.relative_to(ROOT)} with {len(findings) + len(syn)} findings")
        return 0

    only: set[str] | None = None
    syntax_only: set[str] | None = None
    if args.paths:
        only = set()
        for p in args.paths:
            ap_ = Path(p) if Path(p).is_absolute() else Path.cwd() / p
            try:
                only.add(ap_.resolve().relative_to(ROOT).as_posix())
            except ValueError:
                print(f"cobol_lint: {p} is outside the repository", file=sys.stderr)
                return 2
    if args.staged:
        staged = staged_files()
        only = set(staged) | (only or set())
    if only is not None:
        # a staged copybook re-checks every program that copies it
        syntax_only = {r for r in only if r in linter.sources and linter.sources[r].kind == "program"}
        changed_members = {linter.sources[r].member for r in only if r in linter.sources and linter.sources[r].kind == "copybook"}
        for rel, src in linter.sources.items():
            if src.kind == "program" and changed_members & {c[0] for c in src.cob.copies}:
                syntax_only.add(rel)

    findings: list[Finding] = []
    if args.cmd in ("check", "lint", "report"):
        findings += linter.run()
        if args.staged:
            linter.findings = []
            linter.large_files(staged_sizes(staged_files()))
            staged_large = linter.findings
            findings = [f for f in findings if f.rule != "large-file"] + staged_large
    problem = None
    checked, skipped = [], []
    if args.cmd in ("check", "syntax", "report"):
        syn, checked, skipped, problem = syntax_check(cfg, linter, syntax_only, args.allow_missing_cobc)
        findings += syn
    if only is not None:
        findings = [f for f in findings if f.path in only]
    if args.rule:
        bad = set(args.rule) - set(RULES)
        if bad:
            print(f"cobol_lint: unknown rule {', '.join(sorted(bad))}", file=sys.stderr)
            return 2
        findings = [f for f in findings if f.rule in args.rule]

    use_baseline = not args.no_baseline
    stale = apply_baseline(findings, load_baseline(cfg)) if use_baseline else 0
    findings.sort(key=lambda f: (f.path, f.line, f.rule))
    if args.quiet_clean and not any(not f.baselined for f in findings) and not (problem and not args.allow_missing_cobc):
        if problem:
            print(f"warning: {problem}", file=sys.stderr)
        return 0
    if not args.quiet:
        for f in findings:
            if not f.baselined:
                print(f.render())
    if args.cmd in ("check", "syntax", "report") and not problem:
        print(f"\ncobc syntax check: {len(checked)} programs compiled with -fsyntax-only, {len(skipped)} skipped")
        for rel, why in skipped:
            print(f"  skipped {rel}: {why}")
    print()
    for line in summarize(findings, use_baseline):
        print(line)
    if use_baseline and stale and only is None and not args.rule:
        print(f"note: {stale} baseline entries no longer occur; run `python3 scripts/cobol_lint.py baseline` to prune them")
    if args.cmd == "report":
        pc = sorted(linter.metrics["paragraph_complexity"], reverse=True)[:10]
        print("\nmost complex paragraphs:")
        for cc, rel, label, line in pc:
            print(f"  {cc:>4}  {rel}:{line} {label}")
        print("most complex programs:")
        for cc, rel in sorted(linter.metrics["program_complexity"], reverse=True)[:10]:
            print(f"  {cc:>4}  {rel}")
        markers = linter.metrics["markers"]
        print("tech-debt markers: " + (", ".join(f"{k}={v}" for k, v in sorted(markers.items())) or "none"))
        return 0
    rc = 1 if any(not f.baselined for f in findings) else 0
    if problem:
        if args.allow_missing_cobc or args.cmd == "lint":
            print(f"warning: {problem}", file=sys.stderr)
        else:
            print(f"error: {problem} (use --allow-missing-cobc to skip it)", file=sys.stderr)
            rc = 2
    return rc


if __name__ == "__main__":
    sys.exit(main())
