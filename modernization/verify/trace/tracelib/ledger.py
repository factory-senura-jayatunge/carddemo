"""The decision ledger (modernization/ledger/*.toml): loading it, and the checks of make verify-ledger.

Fields of the three files: modernization/verify/trace/README.md.
"""
from __future__ import annotations

import datetime as dt
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
DEVIATION_FIELDS = {"kind", "decision", "group", "legacy", "target", "scenarios", "source", "note",
                    "approved_by", "approved_on"}
DEVIATION_REQUIRED = ("kind", "decision", "target", "approved_by", "approved_on")
DEVIATION_KINDS = ("architecture-dependent", "security-c1", "incidental-ui", "decision")
UNIT_FIELDS = {"spec", "excluded", "note"}
FILES = {"fidelity.toml": "switch", "deviations.toml": "deviation", "scope.toml": "unit"}


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
    for key in sorted(allowed & set(e) - {"approved_on", "code_switch", "scenarios"}):
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
        elif item is not None and not item.defect_decide:
            problems.append(f"{where}: the spec classes it {item.fidelity or 'without a fidelity class'!r}; only defect-decide items and questions have a value")
        if "value" in e and e["value"] not in VALUES:
            problems.append(f"{where}: value must be legacy or fixed")
        if "code_switch" in e and not isinstance(e["code_switch"], bool):
            problems.append(f"{where}: code_switch must be true or false")
        if "setting" in e and e.get("value") != "fixed":
            problems.append(f"{where}: setting needs value = \"fixed\"")
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
    for row in arch_rows:
        for ident in row:
            if not spec.defined(ident):
                problems.append(f"architecture decisions.md: {ident} is not an ID the spec defines")
            elif ledger.deviations.get(ident, {}).get("kind") != "architecture-dependent":
                problems.append(f"deviations.toml: {ident}: architecture-dependent item has no entry of kind \"architecture-dependent\"")
    return problems


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
            + f"), {len(ledger.units)} units in scope")
    if code is not None:
        text += f"; code: {len(code.switches)} switches, {len(code.config)} configured"
    return text
