"""The traceability matrix and the gate of make trace."""
from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass, field

from .ledger import Ledger, covering_switch, gate_units, partial_problem, requires_test
from .reports import Result, is_valid_id
from .spec import Item, Spec

BOTH = ("LEGACY", "FIXED")
DECIDED = (None, "FIXED")


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
    # Only for an invariant with a partial deviation: one obligation per unit of gate_units.
    units: dict[str, dict] = field(default_factory=dict)

    def as_dict(self) -> dict:
        out = {
            "id": self.id, "unit": self.unit, "kind": self.kind, "title": self.title,
            "fidelity": self.fidelity, "classes": list(self.classes), "in_scope": self.in_scope,
            "switch": self.switch, "deviation": self.deviation, "excluded": self.excluded,
            "requirement": self.requirement, "status": self.status, "reason": self.reason,
            "tests": [t.as_dict() for t in self.tests],
        }
        if self.units:
            out["units"] = self.units
        return out


@dataclass
class Matrix:
    rows: dict[str, Row]
    gaps: list[tuple[str, str]]
    unknown: dict[str, list[Result]]
    results: list[Result]
    scope: list[str]
    gate_units: list[str] = field(default_factory=list)
    ungated: list[tuple[str, str]] = field(default_factory=list)
    selected: bool = False
    planned: list[tuple[str, str]] = field(default_factory=list)
    declared: list[str] | None = None

    @property
    def ok(self) -> bool:
        return not self.gaps


def _switch_tested(entry: dict | None) -> bool:
    return entry is not None and (entry.get("code_switch") is True or covering_switch(entry) is not None)


def _requirement(row: Row) -> str:
    if requires_test(row.deviation):
        # A partial deviation: the retained part is tested in the decided mode, which is the fidelity
        # entry's value when the item has one (a legacy-valued item keeps legacy behaviour), else fixed.
        if _switch_tested(row.switch):
            return "both-modes"
        if row.switch is not None:
            return f"ledger-mode:{str(row.switch.get('value', 'legacy')).upper()}"
        return "decided-mode"
    if _switch_tested(row.switch):
        return "both-modes"
    if row.switch is None and "defect-decide" in row.classes:
        return "both-modes"
    if row.switch is not None and "defect-decide" in row.classes:
        return f"ledger-mode:{str(row.switch.get('value', 'legacy')).upper()}"
    if not row.classes or "must-preserve" in row.classes:
        return "passing-test"
    return "none"


def _check(requirement: str, tests: list[Result], switch: dict | None = None) -> tuple[str, str]:
    """The status and reason of a requirement other than none, from the tests that count for it."""
    failed = [t for t in tests if t.status == "failed"]
    passed = [t for t in tests if t.status == "passed"]
    if failed:
        return "gap", f"{len(failed)} failing test{'s' if len(failed) > 1 else ''}"
    if requirement == "both-modes":
        missing = [m for m in BOTH if not any(t.mode == m for t in passed)]
        if missing:
            reason = "no passing test in " + " or ".join(m.lower() for m in missing) + " mode"
            cover = covering_switch(switch)
            if cover:
                reason += f" (covered by the switch {cover}: its tests must cite this item too)"
            return "gap", reason
    elif requirement == "decided-mode":
        if not any(t.mode in DECIDED for t in passed):
            return "gap", "no passing test in the decided mode (fixed, or a test without a mode)"
    elif requirement.startswith("ledger-mode:"):
        mode = requirement.split(":", 1)[1]
        if not any(t.mode in (None, mode) for t in passed):
            return "gap", f"no passing test in {mode.lower()} mode"
    elif not passed:
        return "gap", "no passing test"
    return "covered", ""


def _evaluate(row: Row) -> None:
    if row.excluded is not None:
        row.requirement, row.status, row.reason = "none", "excluded", row.excluded
        return
    if row.deviation is not None and not requires_test(row.deviation):
        row.requirement, row.status = "none", "deviation"
        row.reason = f"{row.deviation.get('kind')}: {row.deviation.get('target', '')}"
        return
    row.requirement = _requirement(row)
    if row.requirement == "none":
        row.status = "not-required"
        return
    row.status, row.reason = _check(row.requirement, row.tests, row.switch)
    if row.status == "gap" and requires_test(row.deviation):
        row.reason += " (partial deviation: the retained part stays required)"


def _evaluate_invariant(row: Row, items: dict[str, Item], ledger: Ledger) -> None:
    """An invariant with a partial deviation: every unit of gate_units in scope owes its own passing
    test, one that cites the invariant together with an item of that unit. The unit's test runs in
    both modes when the unit has a code switch or the co-cited item is tested through one."""
    row.requirement = "per-unit"
    owners = gate_units(row.deviation)
    if not owners:
        row.status, row.reason = "gap", "partial deviation of an invariant without gate_units (run make verify-ledger)"
        return

    def unit_items(t: Result, unit: str) -> list[str]:
        return [i for i in t.items if i in items and items[i].unit == unit]

    # A failing test that names no owning unit cannot be placed, so it counts against every one.
    stray_failed = [t for t in row.tests if t.status == "failed" and not any(unit_items(t, u) for u in owners)]
    for unit in owners:
        if unit not in ledger.units:
            row.units[unit] = {"requirement": "none", "status": "out-of-scope", "reason": "", "tests": []}
            continue
        tests = [t for t in row.tests if unit_items(t, unit)]
        cited = {i for t in tests for i in unit_items(t, unit)}
        both = (any(_switch_tested(e) for i, e in ledger.switches.items() if i in items and items[i].unit == unit)
                or any(_switch_tested(ledger.switches.get(i)) for i in cited))
        requirement = "both-modes" if both else "decided-mode"
        status, reason = _check(requirement, tests + stray_failed)
        failing = any(t.status == "failed" for t in tests + stray_failed)
        if status == "gap" and not failing and not any(t.status == "passed" for t in tests):
            reason = f"no passing test that cites {row.id} together with an item of {unit}"
            elsewhere = [t for t in row.tests if t.status == "passed" and t not in tests]
            if elsewhere:
                reason += f" ({len(elsewhere)} passing test{'s' if len(elsewhere) > 1 else ''} cite it for other units or none)"
        row.units[unit] = {"requirement": requirement, "status": status, "reason": reason, "tests": [t.name for t in tests]}
    scoped = {u: r for u, r in row.units.items() if r["status"] != "out-of-scope"}
    if not scoped:
        row.status, row.reason = "out-of-scope", ""
    elif all(r["status"] == "covered" for r in scoped.values()):
        row.status, row.reason = "covered", ""
    else:
        row.status = "gap"
        row.reason = "; ".join(f"{u}: {r['reason']}" for u, r in scoped.items() if r["status"] == "gap")


def _id_unit(ident: str) -> str:
    parts = ident.split("-")
    return parts[1] if len(parts) == 3 else ""


def switch_ids(row: Row) -> list[str]:
    """The switch a row stands for: its own (code_switch = true) and the one that covers it."""
    ids = [row.id] if row.switch is not None and row.switch.get("code_switch") is True else []
    cover = covering_switch(row.switch)
    return ids + ([cover] if cover else [])


def build(spec: Spec, ledger: Ledger, results: list[Result], arch_items: set[str],
          units: list[str] | None = None, declared: set[str] | None = None) -> Matrix:
    """units: gate only the gaps of these units (a subset of scope.toml); None gates every unit in
    scope plus the code switches outside it that are live (see declared). An undefined ID counts for
    a selected unit when it looks like one of its IDs or when a test that cites it also cites an
    item of the unit. The obligation of each unit of an invariant's partial deviation is gated with
    that unit. A deviation whose partial fields trace cannot read is a gap in any unit.

    declared: the switch IDs the main code declares or reads (tracelib/code.py). A code switch of a
    unit outside scope.toml, or an item covered by one, is live when the code declares that switch,
    the switch's unit is in scope or a test cites either; otherwise its gap is listed as planned and
    not gated. None treats every switch as live."""
    scope = sorted(ledger.units)
    selected = None if units is None else set(units)
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
    ungated: list[tuple[str, str]] = []
    planned: list[tuple[str, str]] = []

    def unit_of(ident: str) -> str:
        return rows[ident].unit if ident in rows else _id_unit(ident)

    def live(row: Row) -> bool:
        # A switch that no code declares has nothing to test yet. It is live once its unit is in
        # scope, the code declares it, or a test cites it (code may read it by a non-literal ID),
        # so a switch that exists anywhere cannot drop out of the gate.
        if row.in_scope or declared is None or row.tests:
            return True
        return any(i in declared or unit_of(i) in ledger.units or (i in rows and rows[i].tests)
                   for i in switch_ids(row))

    for ident, row in sorted(rows.items(), key=lambda kv: (kv[1].unit, kv[0])):
        problem = partial_problem(row.deviation)
        if problem:
            # Gated whatever the unit or selection: the entry's scope cannot be trusted either.
            row.requirement, row.status = "valid-entry", "gap"
            row.reason = f"malformed partial deviation: {problem} (run make verify-ledger)"
            gaps.append((ident, row.reason))
            continue
        if row.kind in ("INV", "PINV") and requires_test(row.deviation):
            _evaluate_invariant(row, spec.items, ledger)
            row.in_scope = row.status != "out-of-scope"
            if not row.units and row.status == "gap":
                gaps.append((ident, row.reason))
            for unit, res in row.units.items():
                if res["status"] == "gap":
                    (gaps if selected is None or unit in selected else ungated).append((ident, f"{unit}: {res['reason']}"))
            continue
        # An item covered by a switch is tested by that switch's tests, so it is gated wherever the switch is.
        code_switch = _switch_tested(row.switch)
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
            if not live(row):
                row.status = "planned"
                planned.append((ident, row.reason))
                continue
            (gaps if selected is None or row.unit in selected else ungated).append((ident, row.reason))

    def touches_selection(ident: str, rs: list[Result]) -> bool:
        if _id_unit(ident) in selected:
            return True
        return any(i in rows and rows[i].unit in selected for r in rs for i in r.items)

    for ident, rs in sorted(unknown.items()):
        gap = (ident, f"cited by {len(rs)} test{'s' if len(rs) > 1 else ''} but not defined by the spec ({rs[0].name})")
        (gaps if selected is None or touches_selection(ident, rs) else ungated).append(gap)
    return Matrix(rows, gaps, dict(unknown), results, scope, sorted(selected) if selected is not None else scope, ungated,
                  selected is not None, planned, sorted(declared) if declared is not None else None)


def unit_summary(matrix: Matrix) -> dict[str, Counter]:
    out: dict[str, Counter] = defaultdict(Counter)
    for row in matrix.rows.values():
        if row.in_scope:
            out[row.unit][row.status] += 1
    return out
