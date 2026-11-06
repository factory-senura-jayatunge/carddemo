"""Fidelity switches declared in the Java code and the modes the application configuration sets.

A switch is declared as FidelitySwitch.of("DEF-BILL-010", ...) or new FidelitySwitch("DEF-BILL-010", ...),
or read as switches.mode("DEF-BILL-010") / fixed(...) / isFixed(...) / isLegacy(...), with the item ID
as a string literal. The configured mode is carddemo.fidelity.<ITEM-ID> in the
default profile of a main application.yml, application.yaml or application.properties.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

SWITCH_RE = re.compile(
    r"(?:FidelitySwitch\s*\.\s*of|new\s+FidelitySwitch)\s*\(\s*\"([^\"]*)\""
    r"|\.\s*(?:mode|fixed|isFixed|isLegacy)\s*\(\s*\"((?:BR|VAL|FLOW|SE|ERR|NUM|DEF|Q)-[A-Z][A-Z0-9]{2,5}-\d{3})\""
)
CONFIG_NAMES = ("application.yml", "application.yaml", "application.properties")
PREFIX = "carddemo.fidelity."
SKIP_DIRS = {"target", "build", "node_modules", ".git", "test", "testFixtures"}


@dataclass
class Code:
    switches: dict[str, list[str]] = field(default_factory=dict)
    config: dict[str, list[tuple[str, str]]] = field(default_factory=dict)
    files: int = 0


def normalize_key(key: str) -> str:
    """Same mapping as SpecItemIds.normalizeKey: relaxed binding may lower-case the key or use . or _."""
    k = key.strip()
    if k.startswith("[") and k.endswith("]"):
        k = k[1:-1]
    return k.upper().replace(".", "-").replace("_", "-")


def _walk(top: Path):
    if not top.is_dir():
        return
    for path in sorted(top.iterdir()):
        if path.is_dir():
            if path.name not in SKIP_DIRS and not path.name.startswith("."):
                yield from _walk(path)
        else:
            yield path


def _unquote(s: str) -> str:
    s = s.strip()
    if len(s) >= 2 and s[0] == s[-1] and s[0] in "\"'":
        return s[1:-1]
    return s


def yaml_scalars(text: str) -> list[tuple[str, str, int]]:
    """(dotted key, value, line) of every scalar of the default-profile documents.

    Only the block-mapping subset Spring configuration uses: nested "key:" lines, "key: value"
    scalars, comments and "---" document separators. A document that activates a profile is skipped.
    """
    docs: list[list[tuple[int, str]]] = [[]]
    for n, raw in enumerate(text.splitlines(), 1):
        if re.match(r"^---[ \t]*$", raw):
            docs.append([])
        else:
            docs[-1].append((n, raw))
    out: list[tuple[str, str, int]] = []
    for doc in docs:
        rows: list[tuple[str, str, int]] = []
        stack: list[tuple[int, str]] = []
        profiled = False
        for n, raw in doc:
            if raw.lstrip().startswith("#"):
                continue
            line = re.sub(r"\s+#.*$", "", raw).rstrip()
            if not line.strip() or line.lstrip().startswith("- "):
                continue
            m = re.match(r"^(\s*)(\"[^\"]*\"|'[^']*'|[^:]+?)\s*:(?:\s+(.*))?$", line)
            if not m:
                continue
            indent, key, value = len(m.group(1)), _unquote(m.group(2)), m.group(3)
            while stack and stack[-1][0] >= indent:
                stack.pop()
            dotted = ".".join([k for _, k in stack] + [key])
            if value is None or value.strip() in ("", "|", ">"):
                stack.append((indent, key))
                continue
            value = _unquote(value)
            if dotted in ("spring.config.activate.on-profile", "spring.profiles"):
                profiled = True
            rows.append((dotted, value, n))
        if not profiled:
            out.extend(rows)
    return out

def properties_scalars(text: str) -> list[tuple[str, str, int]]:
    out = []
    for n, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line[0] in "#!":
            continue
        m = re.match(r"^([^=:\s]+)\s*[=:]\s*(.*)$", line)
        if m:
            out.append((m.group(1), m.group(2).strip(), n))
    return out


def scan(top: Path, root: Path | None = None) -> Code:
    """Every switch declared under top/**/src/main/java and every carddemo.fidelity key in main resources."""
    root = root or top
    code = Code()
    for path in _walk(top):
        parts = path.parts
        rel = path.relative_to(root).as_posix() if path.is_relative_to(root) else str(path)
        if path.suffix == ".java" and "main" in parts:
            text = path.read_text(encoding="utf-8", errors="replace")
            code.files += 1
            for m in SWITCH_RE.finditer(text):
                line = text.count("\n", 0, m.start()) + 1
                code.switches.setdefault(m.group(1) or m.group(2), []).append(f"{rel}:{line}")
        elif path.name in CONFIG_NAMES and "main" in parts:
            text = path.read_text(encoding="utf-8", errors="replace")
            rows = properties_scalars(text) if path.suffix == ".properties" else yaml_scalars(text)
            for key, value, line in rows:
                if key.startswith(PREFIX):
                    ident = normalize_key(key[len(PREFIX):])
                    code.config.setdefault(ident, []).append((f"{rel}:{line}", value))
    return code
