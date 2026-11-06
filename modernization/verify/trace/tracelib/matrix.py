"""The traceability matrix and the gate of make trace."""
from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass, field

from .ledger import Ledger
from .reports import Result, is_valid_id
from .spec import Spec

BOTH = ("LEGACY", "FIXED")


@dataclass
class Row:
    id: str
    unit: str
    kind: str
    title: str
    fidelity: str
    classes: tuple[str, ...]
    in_scope: bool
    switch: dict | None
    deviation: dict | None
    excluded: str | None
    tests: list[Result] = field(default_factory=list)
    requirement: str = "none"
    status: str = "not-required"
    reason: str = ""

    def as_dict(self) -> dict:
        return {
            "id": self.id, "unit": self.unit, "kind": self.kind, "title": self.title,
            "fidelity": self.fidelity, "classes": list(self.classes), "in_scope": self.in_scope,
            "switch": self.switch, "deviation": self.deviation, "excluded": self.excluded,
            "requirement": self.requirement, "status": self.status, "reason": self.reason,
            "tests": [t.as_dict() for t in self.tests],
        }


@dataclass
class Matrix:
    rows: dict[str, Row]
    gaps: list[tuple[str, str]]
    unknown: dict[str, list[Result]]
    results: list[Result]
    scope: list[str]

    @property
    def ok(self) -> bool:
        return not self.gaps


def _requirement(row: Row) -> str:
    if row.switch is not None and row.switch.get("code_switch") is True:
        return "both-modes"
    if row.switch is None and "defect-decide" in row.classes:
        return "both-modes"
    if row.switch is not None and "defect-decide" in row.classes:
        return f"ledger-mode:{str(row.switch.get('value', 'legacy')).upper()}"
    if not row.classes or "must-preserve" in row.classes:
        return "passing-test"
    return "none"


def _evaluate(row: Row) -> None:
    if row.excluded is not None:
        row.requirement, row.status, row.reason = "none", "excluded", row.excluded
        return
    if row.deviation is not None:
        row.requirement, row.status = "none", "deviation"
        row.reason = f"{row.deviation.get('kind')}: {row.deviation.get('target', '')}"
        return
    row.requirement = _requirement(row)
    if row.requirement == "none":
        row.status = "not-required"
        return
    failed = [t for t in row.tests if t.status == "failed"]
    passed = [t for t in row.tests if t.status == "passed"]
    if failed:
        row.status, row.reason = "gap", f"{len(failed)} failing test{'s' if len(failed) > 1 else ''}"
        return
    if row.requirement == "both-modes":
        missing = [m for m in BOTH if not any(t.mode == m for t in passed)]
        if missing:
            row.status = "gap"
            row.reason = "no passing test in " + " or ".join(m.lower() for m in missing) + " mode"
            return
    elif row.requirement.startswith("ledger-mode:"):
        mode = row.requirement.split(":", 1)[1]
        if not any(t.mode in (None, mode) for t in passed):
            row.status, row.reason = "gap", f"no passing test in {mode.lower()} mode"
            return
    elif not passed:
        row.status, row.reason = "gap", "no passing test"
        return
    row.status, row.reason = "covered", ""


def build(spec: Spec, ledger: Ledger, results: list[Result], arch_items: set[str]) -> Matrix:
    scope = sorted(ledger.units)
    cited: dict[str, list[Result]] = defaultdict(list)
    unknown: dict[str, list[Result]] = defaultdict(list)
    for r in results:
        for ident in dict.fromkeys(r.items):
            if is_valid_id(ident) and spec.defined(ident):
                cited[ident].append(r)
            else:
                unknown[ident].append(r)
    rows: dict[str, Row] = {}
    for ident, item in spec.items.items():
        in_scope = item.unit in ledger.units
        rows[ident] = Row(ident, item.unit, item.kind, item.title, item.fidelity, item.classes, in_scope,
                          ledger.switches.get(ident), ledger.deviations.get(ident),
                          ledger.excluded(item.unit).get(ident) if in_scope else None, cited.get(ident, []))
    others = set(spec.questions) | spec.invariants | spec.proposed_invariants
    for ident in sorted(others):
        if ident in ledger.switches or ident in ledger.deviations or ident in cited:
            unit = ident.split("-")[1] if ident.startswith("Q-") else "invariants"
            rows[ident] = Row(ident, unit, ident.split("-")[0], "", "", (), unit in ledger.units,
                              ledger.switches.get(ident), ledger.deviations.get(ident), None, cited.get(ident, []))
    gaps: list[tuple[str, str]] = []
    for ident, row in sorted(rows.items(), key=lambda kv: (kv[1].unit, kv[0])):
        code_switch = row.switch is not None and row.switch.get("code_switch") is True
        if row.kind in ("Q", "INV", "PINV") and not code_switch:
            row.requirement, row.status = "none", ("deviation" if row.deviation else "not-required")
            continue
        if not row.in_scope and not code_switch:
            row.status = "out-of-scope"
            continue
        _evaluate(row)
        if row.in_scope and ident in arch_items and row.deviation is None:
            row.status, row.reason = "gap", "architecture-dependent item without a deviation"
        if row.status == "gap":
            gaps.append((ident, row.reason))
    for ident, rs in sorted(unknown.items()):
        gaps.append((ident, f"cited by {len(rs)} test{'s' if len(rs) > 1 else ''} but not defined by the spec ({rs[0].name})"))
    return Matrix(rows, gaps, dict(unknown), results, scope)


def unit_summary(matrix: Matrix) -> dict[str, Counter]:
    out: dict[str, Counter] = defaultdict(Counter)
    for row in matrix.rows.values():
        if row.in_scope:
            out[row.unit][row.status] += 1
    return out
