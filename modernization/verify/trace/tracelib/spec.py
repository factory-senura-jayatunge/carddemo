"""Item IDs, fidelity classes, units and scenarios of the natural-language spec.

The spec is read with the spec checker's own loader (docs/modernization/spec/tools/check_spec.py),
so an ID counts as defined here exactly when make spec-check accepts it.
"""
from __future__ import annotations

import importlib.util
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

FIDELITY_CLASSES = ("must-preserve", "incidental", "defect-decide")
ID_RE = re.compile(
    r"(?<![\w-])("
    r"(?:BR|VAL|FLOW|SE|ERR|NUM|DEF|Q)-[A-Z][A-Z0-9]{2,5}-\d{3}"
    r"|INV-\d{3}"
    r"|PINV-[a-z0-9]+(?:-[a-z0-9]+)*-\d{2}"
    r")(?![\w-])"
)
PINV_ROW_RE = re.compile(r"^\|\s*(PINV-[a-z0-9]+(?:-[a-z0-9]+)*-\d{2})\s*\|")
SCENARIO_ROW_RE = re.compile(r"^\|\s*(S\d+)\s*\|")
NEXT_HEAD_RE = re.compile(r"^#{1,4}\s")
FIDELITY_RE = re.compile(r"\*\*Fidelity:\*\*\s*([^\n]*)")


@dataclass
class Item:
    id: str
    kind: str
    unit: str
    spec: str
    file: str
    line: int
    title: str
    fidelity: str = ""
    classes: tuple[str, ...] = ()

    @property
    def primary(self) -> str:
        return self.classes[0] if self.classes else ""

    @property
    def defect_decide(self) -> bool:
        return "defect-decide" in self.classes


@dataclass
class Spec:
    root: Path
    items: dict[str, Item] = field(default_factory=dict)
    questions: dict[str, str] = field(default_factory=dict)
    invariants: set[str] = field(default_factory=set)
    proposed_invariants: set[str] = field(default_factory=set)
    entities: set[str] = field(default_factory=set)
    units: dict[str, str] = field(default_factory=dict)
    scenarios: dict[str, set[str]] = field(default_factory=dict)
    errors: list[str] = field(default_factory=list)

    def defined(self, ident: str) -> bool:
        return (ident in self.items or ident in self.questions or ident in self.invariants
                or ident in self.proposed_invariants or ident in self.entities)


def load_checker(root: Path):
    path = root / "docs/modernization/spec/tools/check_spec.py"
    spec = importlib.util.spec_from_file_location("carddemo_check_spec", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def classes_of(fidelity: str) -> tuple[str, ...]:
    found = sorted((fidelity.find(c), c) for c in FIDELITY_CLASSES if c in fidelity)
    return tuple(c for _, c in found)


def load(root: Path) -> Spec:
    root = root.resolve()
    check_spec = load_checker(root)
    checker = check_spec.Checker(root, root / "docs/modernization/spec")
    checker.load()
    result = Spec(root)
    result.invariants = set(checker.invariants)
    result.entities = set(checker.entities)
    for s in checker.specs:
        unit = str(s.meta.get("unit_code", ""))
        if unit:
            result.units.setdefault(unit, s.rel)
        result.scenarios[s.id] = {m.group(1) for line in s.lines if (m := SCENARIO_ROW_RE.match(line))}
        for ident, n in s.items.items():
            kind, iunit = ident.split("-")[0], ident.split("-")[1]
            if kind == "Q":
                result.questions[ident] = s.rel
                continue
            heading = s.lines[n - 1]
            title = heading.split("·", 1)[1].strip() if "·" in heading else ""
            j = n
            while j < len(s.lines) and not NEXT_HEAD_RE.match(s.lines[j]):
                j += 1
            body = "\n".join(s.lines[n:j])
            fm = FIDELITY_RE.search(body)
            fidelity = fm.group(1).split("· **Confidence", 1)[0].strip() if fm else ""
            result.items[ident] = Item(ident, kind, iunit, s.id, s.rel, n, title, fidelity, classes_of(fidelity))
    for ident, where in checker.defined.items():
        if ident.startswith("Q-") and ident not in result.questions:
            result.questions[ident] = where.split(":", 1)[0]
    areas = root / "docs/modernization/spec/areas"
    for path in sorted(areas.glob("*.md")):
        for line in path.read_text(encoding="utf-8").splitlines():
            m = PINV_ROW_RE.match(line)
            if m:
                result.proposed_invariants.add(m.group(1))
    result.errors = list(checker.errors)
    return result


def ids_in(text: str) -> list[str]:
    return ID_RE.findall(text)
