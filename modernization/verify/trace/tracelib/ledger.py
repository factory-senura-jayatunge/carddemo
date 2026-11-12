"""The decision ledger (modernization/ledger/*.toml): loading it, and the checks of make verify-ledger.

Fields of the three files: modernization/verify/trace/README.md.
"""
from __future__ import annotations

import datetime as dt
import re
import tomllib
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

from .code import Code
from .spec import Spec

SCHEMA = 1
VALUES = ("legacy", "fixed")
SWITCH_FIELDS = {"value", "code_switch", "setting", "decision", "approved_by", "approved_on", "note"}
SWITCH_REQUIRED = ("value", "code_switch", "decision", "approved_by", "approved_on")
DEVIATION_FIELDS = {"kind", "decision", "group", "legacy", "target", "scenarios", "retained", "requires_test",
                    "gate_units", "gate_excluded", "source", "note", "approved_by", "approved_on"}
NOT_STRINGS = {"approved_on", "code_switch", "scenarios", "retained", "requires_test", "gate_units", "gate_excluded"}
COMPONENTS_DOC = "components.md"
DEVIATION_REQUIRED = ("kind", "decision", "target", "approved_by", "approved_on")
DEVIATION_KINDS = ("architecture-dependent", "security-c1", "incidental-ui", "decision")
UNIT_FIELDS = {"spec", "excluded", "note"}
FILES = {"fidelity.toml": "switch", "deviations.toml": "deviation", "scope.toml": "unit"}
COVERED_RE = re.compile(r"\bCovered by the switch ((?:BR|VAL|FLOW|SE|ERR|NUM|DEF)-[A-Z0-9]+-\d{3})\b")


def covering_switch(entry: dict | None) -> str | None:
    """The switch named by a note "Covered by the switch <ID>": the item has no switch of its own, and
    the tests of that switch cite it in both modes."""
    note = (entry or {}).get("note")
    m = COVERED_RE.search(note) if isinstance(note, str) else None
    return m.group(1) if m else None


def decided_fix(entry: dict) -> bool:
    """An entry that records a catalog decision changing an item the spec does not class defect-decide
    (a must-preserve check that the decision fixes). Such an item has no legacy mode, so the entry can
    only be fixed, without a switch. The entry leaves the trace requirement of the item's class unchanged."""
    decision = entry.get("decision")
    return (entry.get("value") == "fixed" and entry.get("code_switch") is False and isinstance(decision, str)
            and decision.strip() not in ("", "none") and covering_switch(entry) is None)


def requires_test(entry: dict | None) -> bool:
    """A partial deviation: the item keeps outcomes of its own (retained), so trace still requires a
    passing test of it in the decided mode, as if the item had no entry."""
    return (entry or {}).get("requires_test") is True


PARTIAL_FIELDS = ("retained", "requires_test", "gate_units", "gate_excluded")


def partial_problem(entry: dict | None) -> str | None:
    """Why trace cannot read the partial fields of a deviation, or None. verify-ledger reports every
    problem; trace checks only enough to fail closed instead of exempting the item as a whole."""
    e = entry or {}
    if not any(k in e for k in PARTIAL_FIELDS):
        return None
    if e.get("requires_test") is not True:
        return "the entry has partial fields but requires_test is not true"
    retained = e.get("retained")
    if not (isinstance(retained, list) and retained and all(isinstance(s, str) and s.strip() for s in retained)):
        return "retained is not a non-empty list of statements"
    if "gate_units" in e and not gate_units(e):
        return "gate_units is not a non-empty list of unit codes"
    return None


def gate_units(entry: dict | None) -> tuple[str, ...]:
    """The units that each owe a test of a partial deviation of an invariant (the units of its components)."""
    units = (entry or {}).get("gate_units")
    return tuple(units) if isinstance(units, list) and all(isinstance(u, str) for u in units) else ()


@dataclass
class Ledger:
    dir: Path
    switches: dict[str, dict] = field(default_factory=dict)
    deviations: dict[str, dict] = field(default_factory=dict)
    units: dict[str, dict] = field(default_factory=dict)
    problems: list[str] = field(default_factory=list)

    def excluded(self, unit: str) -> dict[str, str]:
        ex = self.units.get(unit, {}).get("excluded", {})
        return ex if isinstance(ex, dict) else {}


def _read(path: Path, problems: list[str]) -> dict:
    try:
        data = tomllib.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        problems.append(f"{path.name}: missing")
        return {}
    except tomllib.TOMLDecodeError as e:
        problems.append(f"{path.name}: not valid TOML: {e}")
        return {}
    if data.get("schema") != SCHEMA:
        problems.append(f"{path.name}: schema must be {SCHEMA}")
    for key in sorted(set(data) - {"schema", FILES[path.name]}):
        problems.append(f"{path.name}: unknown top-level key {key!r}")
    raw = data.get(FILES[path.name], {})
    if not isinstance(raw, dict):
        problems.append(f"{path.name}: {FILES[path.name]} must be a table")
        return {}
    out = {}
    for ident, value in raw.items():
        if isinstance(value, dict):
            out[ident] = value
        else:
            problems.append(f"{path.name}: {ident}: must be a table [{FILES[path.name]}.\"{ident}\"]")
    return out


def load(directory: Path) -> Ledger:
    ledger = Ledger(directory)
    p = ledger.problems
    ledger.switches = _read(directory / "fidelity.toml", p)
    ledger.deviations = _read(directory / "deviations.toml", p)
    ledger.units = _read(directory / "scope.toml", p)
    return ledger


def _common(where: str, e: dict, allowed: set[str], required: tuple[str, ...], decisions: set[str],
            problems: list[str]) -> None:
    for key in sorted(set(e) - allowed):
        problems.append(f"{where}: unknown field {key!r}")
    for key in required:
        if key not in e:
            problems.append(f"{where}: {key} missing")
    for key in sorted(allowed & set(e) - NOT_STRINGS):
        if not isinstance(e[key], str) or (key in required and not e[key].strip()):
            problems.append(f"{where}: {key} must be a non-empty string")
    decision = e.get("decision")
    if isinstance(decision, str) and decision != "none" and decision not in decisions:
        problems.append(f"{where}: decision {decision} is not in the decision catalog")
    if "approved_on" in e:
        on = e["approved_on"]
        if isinstance(on, str):
            try:
                on = dt.date.fromisoformat(on)
            except ValueError:
                pass
        if not isinstance(on, dt.date) or isinstance(on, dt.datetime):
            problems.append(f"{where}: approved_on must be a date such as \"2026-11-06\"")


def check_switches(ledger: Ledger, spec: Spec, decisions: set[str]) -> list[str]:
    problems: list[str] = []
    for ident, e in ledger.switches.items():
        where = f"fidelity.toml: {ident}"
        _common(where, e, SWITCH_FIELDS, SWITCH_REQUIRED, decisions, problems)
        item = spec.items.get(ident)
        if item is None and ident not in spec.questions:
            problems.append(f"{where}: not an item the spec defines")
        elif item is not None and not item.defect_decide and not decided_fix(e):
            problems.append(f"{where}: the spec classes it {item.fidelity or 'without a fidelity class'!r}; only defect-decide items "
                            "and questions have a value, and other items only a decided fix "
                            "(value = \"fixed\", code_switch = false, a catalog decision)")
        if "value" in e and e["value"] not in VALUES:
            problems.append(f"{where}: value must be legacy or fixed")
        if "code_switch" in e and not isinstance(e["code_switch"], bool):
            problems.append(f"{where}: code_switch must be true or false")
        if "setting" in e and e.get("value") != "fixed":
            problems.append(f"{where}: setting needs value = \"fixed\"")
        cover = covering_switch(e)
        if cover is not None:
            if e.get("code_switch") is not False:
                problems.append(f"{where}: covered by the switch {cover}, so code_switch must be false")
            if ledger.switches.get(cover, {}).get("code_switch") is not True:
                problems.append(f"{where}: covered by {cover}, but {cover} has no entry with code_switch = true")
        dev = ledger.deviations.get(ident)
        if e.get("code_switch") is True and dev is not None and dev.get("kind") == "architecture-dependent":
            problems.append(f"{where}: code_switch = true, but deviations.toml records it as architecture-dependent (no legacy mode)")
    for ident, item in sorted(spec.items.items()):
        if item.defect_decide and ident not in ledger.switches and ident not in ledger.deviations:
            problems.append(f"fidelity.toml: {ident}: defect-decide item ({item.file}:{item.line}) has no entry here or in deviations.toml")
    return problems


def check_deviations(ledger: Ledger, spec: Spec, decisions: set[str], arch_rows: list[list[str]]) -> list[str]:
    problems: list[str] = []
    for ident, e in ledger.deviations.items():
        where = f"deviations.toml: {ident}"
        _common(where, e, DEVIATION_FIELDS, DEVIATION_REQUIRED, decisions, problems)
        if not spec.defined(ident):
            problems.append(f"{where}: not an ID the spec defines")
        if "kind" in e and e["kind"] not in DEVIATION_KINDS:
            problems.append(f"{where}: kind must be one of {', '.join(DEVIATION_KINDS)}")
        scenarios = e.get("scenarios", [])
        if not isinstance(scenarios, list):
            problems.append(f"{where}: scenarios must be a list")
            scenarios = []
        for scenario in scenarios:
            parts = scenario.split() if isinstance(scenario, str) else []
            if len(parts) != 2 or parts[1] not in spec.scenarios.get(parts[0], set()):
                problems.append(f"{where}: scenario {scenario!r} is not a scenario of a spec (form: \"COSGN00C S10\")")
        _check_partial(where, ident, e, spec, problems)
    for row in arch_rows:
        for ident in row:
            if not spec.defined(ident):
                problems.append(f"architecture decisions.md: {ident} is not an ID the spec defines")
            elif ledger.deviations.get(ident, {}).get("kind") != "architecture-dependent":
                problems.append(f"deviations.toml: {ident}: architecture-dependent item has no entry of kind \"architecture-dependent\"")
    return problems


def _string_list(where: str, key: str, value, problems: list[str]) -> list[str]:
    if not isinstance(value, list) or not value or not all(isinstance(s, str) and s.strip() for s in value):
        problems.append(f"{where}: {key} must be a non-empty list of non-empty strings")
        return []
    seen = [s.strip() for s in value]
    for dup in sorted({s for s in seen if seen.count(s) > 1}):
        problems.append(f"{where}: {key} lists {dup!r} more than once")
    return seen


def _check_partial(where: str, ident: str, e: dict, spec: Spec, problems: list[str]) -> None:
    """retained, requires_test, gate_units and gate_excluded: the fields of a partial deviation."""
    partial = e.get("requires_test") is True
    if "requires_test" in e and not partial:
        problems.append(f"{where}: requires_test must be true; a whole-item deviation leaves it out")
    if "retained" in e:
        _string_list(where, "retained", e["retained"], problems)
        if not partial:
            problems.append(f"{where}: retained needs requires_test = true, or the retained parts go untested")
    elif partial:
        problems.append(f"{where}: requires_test = true needs retained, the parts the item keeps, as testable statements")
    if partial and ident in spec.questions:
        problems.append(f"{where}: requires_test on a question; trace never requires a test of a question")
    invariant = ident in spec.invariants or ident in spec.proposed_invariants
    if partial and invariant and "gate_units" not in e:
        problems.append(f"{where}: an invariant with requires_test = true needs gate_units, the units of its components")
    if "gate_excluded" in e and "gate_units" not in e:
        problems.append(f"{where}: gate_excluded needs gate_units")
    if "gate_units" not in e:
        return
    if not invariant:
        problems.append(f"{where}: gate_units is only for an invariant; an item is gated with its own unit")
    if not partial:
        problems.append(f"{where}: gate_units needs requires_test = true")
    units = _string_list(where, "gate_units", e["gate_units"], problems)
    for unit in units:
        if unit not in spec.units:
            problems.append(f"{where}: gate_units names {unit!r}, which is not the unit code of a spec")
    excluded = e.get("gate_excluded", {})
    if not isinstance(excluded, dict):
        problems.append(f"{where}: gate_excluded must be a table of unit = reason")
        excluded = {}
    for unit, reason in excluded.items():
        if not isinstance(reason, str) or not reason.strip():
            problems.append(f"{where}: gate_excluded {unit} needs the reason the ruling gives")
        if unit in units:
            problems.append(f"{where}: {unit} is both in gate_units and in gate_excluded")
    if not invariant:
        return
    components = spec.invariant_units.get(ident, ())
    if not components:
        problems.append(f"{where}: {COMPONENTS_DOC} names no component of {ident} with a unit, so gate_units cannot be checked")
        return
    missing = [u for u in components if u not in units and u not in excluded]
    extra = [u for u in dict.fromkeys(units + list(excluded)) if u not in components]
    if missing:
        problems.append(f"{where}: gate_units leaves out {', '.join(missing)}, units of its components in {COMPONENTS_DOC}; "
                        "a unit is left out only through gate_excluded with the ruling's reason")
    if extra:
        problems.append(f"{where}: {', '.join(extra)} is not a unit of its components in {COMPONENTS_DOC} "
                        f"({', '.join(components)})")


def check_scope(ledger: Ledger, spec: Spec) -> list[str]:
    problems: list[str] = []
    for unit, u in ledger.units.items():
        where = f"scope.toml: {unit}"
        for key in sorted(set(u) - UNIT_FIELDS):
            problems.append(f"{where}: unknown field {key!r}")
        if unit not in spec.units:
            problems.append(f"{where}: not the unit code of a spec")
        elif u.get("spec") != spec.units[unit]:
            problems.append(f"{where}: spec must be {spec.units[unit]}")
        excluded = u.get("excluded", {})
        if not isinstance(excluded, dict):
            problems.append(f"{where}: excluded must be a table of item ID = reason")
            continue
        for ident, reason in excluded.items():
            item = spec.items.get(ident)
            if item is None or item.unit != unit:
                problems.append(f"{where}: excluded {ident} is not an item of unit {unit}")
            if not isinstance(reason, str) or not reason.strip():
                problems.append(f"{where}: excluded {ident} needs a reason")
    return problems


def check_code(ledger: Ledger, code: Code) -> tuple[list[str], list[str]]:
    """Problems, and warnings that do not fail the check."""
    problems: list[str] = []
    warnings: list[str] = []
    for ident, places in sorted(code.switches.items()):
        e = ledger.switches.get(ident)
        if e is None:
            problems.append(f"{places[0]}: switch {ident} has no entry in fidelity.toml")
        elif e.get("code_switch") is not True:
            problems.append(f"{places[0]}: switch {ident} is in the code, but fidelity.toml has code_switch = false")
    for ident, e in sorted(ledger.switches.items()):
        if e.get("code_switch") is True and ident not in code.switches:
            warnings.append(f"fidelity.toml: {ident}: code_switch = true, but no code declares the switch yet")
    for ident, settings in sorted(code.config.items()):
        e = ledger.switches.get(ident)
        for where, value in settings:
            value = value.strip().lower()
            if value not in VALUES:
                problems.append(f"{where}: carddemo.fidelity.{ident}: {value!r} is not legacy or fixed")
            elif e is None:
                problems.append(f"{where}: carddemo.fidelity.{ident} is set, but fidelity.toml has no entry for it")
            elif value != e.get("value"):
                problems.append(f"{where}: carddemo.fidelity.{ident} is {value}, fidelity.toml says {e.get('value')}")
    return problems, warnings


def summary(ledger: Ledger, code: Code | None) -> str:
    values = Counter(e.get("value") for e in ledger.switches.values())
    in_code = sum(1 for e in ledger.switches.values() if e.get("code_switch") is True)
    kinds = Counter(e.get("kind") for e in ledger.deviations.values())
    text = (f"{len(ledger.switches)} fidelity entries ({values['fixed']} fixed, {values['legacy']} legacy; "
            f"{in_code} code switches), {len(ledger.deviations)} deviations ("
            + ", ".join(f"{n} {k}" for k, n in sorted(kinds.items(), key=lambda kv: str(kv[0])))
            + f"; {sum(1 for e in ledger.deviations.values() if requires_test(e))} partial with a retained part to test)"
            + f", {len(ledger.units)} units in scope")
    if code is not None:
        text += f"; code: {len(code.switches)} switches, {len(code.config)} configured"
    return text
