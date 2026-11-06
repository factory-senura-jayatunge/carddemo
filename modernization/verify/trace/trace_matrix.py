#!/usr/bin/env python3
"""make trace: the rule-to-test traceability matrix and its gate.

Reads the spec items, the ledger (modernization/ledger/*.toml) and the test reports, writes
build/trace/matrix.md and matrix.json, and passes when, for the units in scope.toml, every
must-preserve or unclassified item that is not excluded has a passing test, every defect-decide
item has passing tests in the ledger's mode (both modes for a code switch or an undecided item) or
a deviation, every architecture-dependent item has a deviation, no cited item has a failing test,
and no test cites an ID the spec does not define. Every code switch needs passing tests in both
modes, whatever its unit. Incidental items and questions need no test. With --unit, only the gaps
of the named units count for the gate and the exit status; the matrix still shows every unit.
Tests that write an ID in brackets ("[FLOW-BILL-002]") instead of as a tag ("@FLOW-BILL-002") are
reported, not counted.
Ends with "trace: OK" or "trace: FAILED (<reason>)"; exit status 0 or 1.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from tracelib import catalog, ledger, matrix, reports, spec  # noqa: E402

REPO = HERE.parents[2]
STATUS_ORDER = ("gap", "covered", "deviation", "excluded", "not-required")


def _md_escape(text: str) -> str:
    return text.replace("|", "\\|").replace("\n", " ")


def _tests_cell(row: matrix.Row) -> str:
    if not row.tests:
        return "-"
    counts: dict[str, int] = {}
    for t in row.tests:
        key = t.status + (f" {t.mode.lower()}" if t.mode else "")
        counts[key] = counts.get(key, 0) + 1
    return ", ".join(f"{n} {k}" for k, n in sorted(counts.items()))


def _ledger_cell(row: matrix.Row) -> str:
    parts = []
    if row.switch is not None:
        parts.append(f"{row.switch.get('value')}" + (" (switch)" if row.switch.get("code_switch") else "")
                     + f" {row.switch.get('decision', '')}".rstrip())
    if row.deviation is not None:
        parts.append(f"deviation {row.deviation.get('kind')} {row.deviation.get('decision', '')}".rstrip())
    return "; ".join(parts) or "-"


def _citing(m: matrix.Matrix) -> int:
    return sum(1 for r in m.results if r.items)


def _uncounted(m: matrix.Matrix) -> list[reports.Result]:
    return [r for r in m.results if r.uncounted]


def _uncounted_line(n: int) -> str:
    return f"{n} test{'s carry' if n != 1 else ' carries'} bracketed IDs that are not counted (cite an item as @<ID>)"


def _gate_label(m: matrix.Matrix) -> str:
    if m.selected:
        return f"for the selected units: {', '.join(m.gate_units)}"
    return f"for the units in scope: {', '.join(m.scope) or 'none'}"


def render_md(m: matrix.Matrix, sources: list[str], generated: str) -> str:
    out = ["# Traceability matrix", "",
           f"Generated {generated} by `make -C modernization trace` from the spec, `ledger/*.toml` and "
           f"{len(sources)} test report file{'s' if len(sources) != 1 else ''} ({_citing(m)} test results citing items).", ""]
    out.append(f"**Gate: {'OK' if m.ok else f'FAILED, {len(m.gaps)} gaps'}** {_gate_label(m)}.")
    out.append("")
    if m.gaps:
        out += ["## Gaps", "", "| Item | Reason |", "| :-- | :-- |"]
        out += [f"| {g} | {_md_escape(r)} |" for g, r in m.gaps]
        out.append("")
    if m.ungated:
        out += ["## Gaps of units outside the gate", "", "| Item | Reason |", "| :-- | :-- |"]
        out += [f"| {g} | {_md_escape(r)} |" for g, r in m.ungated]
        out.append("")
    uncounted = _uncounted(m)
    if uncounted:
        out += ["## Bracketed IDs not counted", "", _uncounted_line(len(uncounted)) + ".", "",
                "| Test | Bracketed IDs | Report |", "| :-- | :-- | :-- |"]
        out += [f"| {_md_escape(r.name)} | {', '.join(r.uncounted)} | `{r.source}` |" for r in uncounted]
        out.append("")
    summary = matrix.unit_summary(m)
    out += ["## Units in scope", "", "| Unit | " + " | ".join(STATUS_ORDER) + " |", "| :-- |" + " --: |" * len(STATUS_ORDER)]
    for unit in m.scope:
        out.append(f"| {unit} | " + " | ".join(str(summary[unit][s]) for s in STATUS_ORDER) + " |")
    out.append("")
    for unit in m.scope:
        rows = sorted((r for r in m.rows.values() if r.unit == unit and r.in_scope), key=lambda r: r.id)
        out += [f"### {unit}", "", "| Item | Title | Fidelity | Ledger | Tests | Status |", "| :-- | :-- | :-- | :-- | :-- | :-- |"]
        for r in rows:
            status = r.status + (f": {r.reason}" if r.reason and r.status in ("gap", "excluded") else "")
            out.append(f"| {r.id} | {_md_escape(r.title)} | {_md_escape(r.fidelity or '-')} | {_md_escape(_ledger_cell(r))} | "
                       f"{_tests_cell(r)} | {_md_escape(status)} |")
        out.append("")
    switches = sorted((r for r in m.rows.values() if r.switch and r.switch.get("code_switch") is True), key=lambda r: r.id)
    if switches:
        out += ["## Code switches", "", "| Switch | Ledger | Tests | Status |", "| :-- | :-- | :-- | :-- |"]
        out += [f"| {r.id} | {_md_escape(_ledger_cell(r))} | {_tests_cell(r)} | {r.status}{': ' + _md_escape(r.reason) if r.reason and r.status == 'gap' else ''} |" for r in switches]
        out.append("")
    cited_out = sorted({r.unit for r in m.rows.values() if not r.in_scope and r.tests})
    if cited_out:
        out += ["## Cited items of other units", "", "| Item | Tests |", "| :-- | :-- |"]
        out += [f"| {r.id} | {_tests_cell(r)} |" for r in sorted(m.rows.values(), key=lambda r: r.id) if not r.in_scope and r.tests]
        out.append("")
    out += ["## Report files", ""] + [f"- `{s}`" for s in sources] + ([] if sources else ["- none found"])
    return "\n".join(out) + "\n"


def render_json(m: matrix.Matrix, sources: list[str], generated: str) -> str:
    data = {
        "schema": 1,
        "generated": generated,
        "gate": {"ok": m.ok, "units": m.gate_units, "gaps": [{"id": g, "reason": r} for g, r in m.gaps],
                 "ungated_gaps": [{"id": g, "reason": r} for g, r in m.ungated]},
        "scope": m.scope,
        "reports": sources,
        "summary": {u: dict(c) for u, c in sorted(matrix.unit_summary(m).items())},
        "items": [r.as_dict() for r in sorted(m.rows.values(), key=lambda r: (r.unit, r.id))],
        "unknown_citations": {i: [t.as_dict() for t in ts] for i, ts in sorted(m.unknown.items())},
        "uncounted_bracketed": [dict(r.as_dict(), ids=r.uncounted) for r in _uncounted(m)],
    }
    return json.dumps(data, indent=1, ensure_ascii=False) + "\n"


def run(root: Path, ledger_dir: Path, report_paths: list[Path], out_dir: Path, out=None,
        units: list[str] | None = None) -> int:
    out = out or sys.stdout
    led = ledger.load(ledger_dir)
    try:
        s = spec.load(root)
    except Exception as e:
        print(f"trace: FAILED (cannot load the spec: {e})", file=out)
        return 1
    if led.problems:
        for p in led.problems:
            print(f"  {p}", file=out)
        print("trace: FAILED (the ledger does not load; run make verify-ledger)", file=out)
        return 1
    if units is not None:
        units = list(dict.fromkeys(u.upper() for u in units))
        unknown_units = [u for u in units if u not in led.units]
        if unknown_units:
            print(f"trace: FAILED (--unit {', '.join(unknown_units)} not in scope.toml; "
                  f"units in scope: {', '.join(sorted(led.units)) or 'none'})", file=out)
            return 1
    files: list[Path] = []
    for p in report_paths:
        files += reports.discover(p) if p.is_dir() else [p]
    results: list[reports.Result] = []
    read_problems: list[str] = []
    sources: list[str] = []
    for f in files:
        rs, probs = reports.read(f, root)
        read_problems += probs
        if rs:
            results += rs
            sources.append(f.relative_to(root).as_posix() if f.is_relative_to(root) else str(f))
    arch = {i for row in catalog.architecture_dependent(root) for i in row}
    m = matrix.build(s, led, results, arch, units)
    m.gaps += [("report", p) for p in read_problems]
    generated = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "matrix.md").write_text(render_md(m, sources, generated), encoding="utf-8")
    (out_dir / "matrix.json").write_text(render_json(m, sources, generated), encoding="utf-8")
    for ident, reason in m.gaps[:50]:
        print(f"  {ident}: {reason}", file=out)
    if len(m.gaps) > 50:
        print(f"  ... {len(m.gaps) - 50} more in {out_dir / 'matrix.md'}", file=out)
    if m.ungated:
        print(f"  ({len(m.ungated)} gap{'s' if len(m.ungated) != 1 else ''} of units outside the gate, listed in {out_dir / 'matrix.md'})", file=out)
    uncounted = _uncounted(m)
    if uncounted:
        print(f"  warning: {_uncounted_line(len(uncounted))}:", file=out)
        for r in uncounted[:10]:
            print(f"    {r.name}: {', '.join(r.uncounted)}", file=out)
        if len(uncounted) > 10:
            print(f"    ... {len(uncounted) - 10} more in {out_dir / 'matrix.md'}", file=out)
    summary = matrix.unit_summary(m)
    per_unit = ", ".join(f"{u} {summary[u]['covered']} covered/{summary[u]['gap']} gaps" for u in m.gate_units)
    print(f"matrix: {out_dir / 'matrix.md'} ({_citing(m)} results from {len(sources)} reports; {per_unit})", file=out)
    if m.gaps:
        print(f"trace: FAILED ({len(m.gaps)} gap{'s' if len(m.gaps) != 1 else ''})", file=out)
        return 1
    print("trace: OK", file=out)
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", type=Path, default=REPO, help="repository root (default: this checkout)")
    ap.add_argument("--ledger", type=Path, help="ledger directory (default: <root>/modernization/ledger)")
    ap.add_argument("--reports", type=Path, nargs="*",
                    help="report files or directories to search (default: <root>/modernization)")
    ap.add_argument("--out", type=Path, help="output directory (default: <root>/modernization/build/trace)")
    ap.add_argument("--unit", action="append", metavar="UNIT",
                    help="gate only this unit of scope.toml (repeatable; default: every unit in scope and every code switch)")
    args = ap.parse_args(argv)
    root = args.root.resolve()
    return run(root, args.ledger or root / "modernization/ledger",
               args.reports if args.reports else [root / "modernization"],
               args.out or root / "modernization/build/trace", units=args.unit)


if __name__ == "__main__":
    sys.exit(main())
