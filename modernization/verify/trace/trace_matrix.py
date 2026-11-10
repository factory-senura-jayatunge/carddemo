#!/usr/bin/env python3
"""make trace: the rule-to-test traceability matrix and its gate.

Reads the spec items, the ledger (modernization/ledger/*.toml) and the test reports, writes
build/trace/matrix.md and matrix.json, and passes when, for the units in scope.toml, every
must-preserve or unclassified item that is not excluded has a passing test, every defect-decide
item has passing tests in the ledger's mode (both modes for a code switch or an undecided item) or
a deviation, every architecture-dependent item has a deviation, no cited item has a failing test,
and no test cites an ID the spec does not define. A code switch of another unit, and an item it
covers, needs passing tests in both modes too once the main code declares or reads that switch or
a test cites it; until then it is listed as planned (not gated, not delivered) and gates as soon
as that happens or its unit enters scope.toml. Incidental items and questions need no test. With
--unit, only the gaps of the named units count for the gate and the exit status; the matrix still
shows every unit.
Tests that write an ID in brackets ("[FLOW-BILL-002]") instead of as a tag ("@FLOW-BILL-002") are
reported, not counted. With --manifest, only the reports that one run wrote are read (see
tracelib/manifest.py); a missing, stale or empty required report is a gap.
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

from tracelib import catalog, code, ledger, matrix, reports, spec  # noqa: E402
from tracelib import manifest as run_manifest  # noqa: E402

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
    return f"for the units in scope: {', '.join(m.scope) or 'none'}, and the code switches the code declares"


def _rel(p: Path, root: Path) -> str:
    return p.relative_to(root).as_posix() if p.is_relative_to(root) else str(p)


def _run_info(man: run_manifest.Manifest, outside: list[dict], root: Path) -> dict:
    return {
        "run": man.run, "manifest": man.source, "started": man.started_text, "started_ns": man.started_ns,
        "reports": [{"path": e.rel, "required": e.required, "missing": e.missing,
                     "current": [_rel(f, root) for f in e.current], "stale": [_rel(f, root) for f in e.stale]}
                    for e in man.entries],
        "outside": outside,
    }


def _run_md(info: dict) -> list[str]:
    out = ["## Reports of this run", "",
           f"Run `{info['run']}`, started {info['started']}, manifest `{info['manifest']}`. Only the current files "
           "below were read: files at a listed path that the run did not write are stale and not read.", "",
           "| Path | Required | Current | Stale (not read) |", "| :-- | :-- | --: | --: |"]
    for e in info["reports"]:
        cur = "missing" if e["missing"] else str(len(e["current"]))
        out.append(f"| `{e['path']}` | {'yes' if e['required'] else 'no'} | {cur} | {len(e['stale'])} |")
    stale = [s for e in info["reports"] for s in e["stale"]]
    if stale:
        out += ["", "Stale files: " + ", ".join(f"`{s}`" for s in stale) + "."]
    out.append("")
    if info["outside"]:
        out += ["## Reports outside this run (not read)", "",
                "These report files are on disk but not in the run's manifest, so this gate does not read them. "
                "`make trace` without a manifest reads every one of them.", "",
                "| Report | Results citing items | Failed |", "| :-- | --: | --: |"]
        out += [f"| `{o['path']}` | {'unreadable' if o['unreadable'] else o['results']} | {o['failed']} |" for o in info["outside"]]
        out.append("")
    return out


def render_md(m: matrix.Matrix, sources: list[str], generated: str, run_info: dict | None = None) -> str:
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
    if m.planned:
        out += ["## Planned code switches (not gated, not delivered)", "",
                "Code switches of units outside scope.toml, and items covered by one, that no main code declares or "
                "reads and no test cites yet. Nothing implements them, so they are not part of this gate and not "
                "delivered. Each one gates as soon as the code declares the switch (or the switch that covers the "
                "item), a test cites either, or its unit enters scope.toml.", "", "| Item | Unit | Switch | Missing |", "| :-- | :-- | :-- | :-- |"]
        out += [f"| {g} | {m.rows[g].unit} | {', '.join(matrix.switch_ids(m.rows[g]))} | {_md_escape(r)} |" for g, r in m.planned]
        out.append("")
    if run_info:
        out += _run_md(run_info)
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
        out += [f"| {r.id} | {_md_escape(_ledger_cell(r))} | {_tests_cell(r)} | {r.status}{': ' + _md_escape(r.reason) if r.reason and r.status in ('gap', 'planned') else ''} |" for r in switches]
        out.append("")
    cited_out = sorted({r.unit for r in m.rows.values() if not r.in_scope and r.tests})
    if cited_out:
        out += ["## Cited items of other units", "", "| Item | Tests |", "| :-- | :-- |"]
        out += [f"| {r.id} | {_tests_cell(r)} |" for r in sorted(m.rows.values(), key=lambda r: r.id) if not r.in_scope and r.tests]
        out.append("")
    out += ["## Report files", ""] + [f"- `{s}`" for s in sources] + ([] if sources else ["- none found"])
    return "\n".join(out) + "\n"


def render_json(m: matrix.Matrix, sources: list[str], generated: str, run_info: dict | None = None) -> str:
    data = {
        "schema": 1,
        "generated": generated,
        "gate": {"ok": m.ok, "units": m.gate_units, "gaps": [{"id": g, "reason": r} for g, r in m.gaps],
                 "ungated_gaps": [{"id": g, "reason": r} for g, r in m.ungated],
                 "planned": [{"id": g, "reason": r} for g, r in m.planned],
                 "declared_switches": m.declared},
        "scope": m.scope,
        "reports": sources,
        "summary": {u: dict(c) for u, c in sorted(matrix.unit_summary(m).items())},
        "items": [r.as_dict() for r in sorted(m.rows.values(), key=lambda r: (r.unit, r.id))],
        "unknown_citations": {i: [t.as_dict() for t in ts] for i, ts in sorted(m.unknown.items())},
        "uncounted_bracketed": [dict(r.as_dict(), ids=r.uncounted) for r in _uncounted(m)],
    }
    if run_info:
        data["run"] = run_info
    return json.dumps(data, indent=1, ensure_ascii=False) + "\n"


def run(root: Path, ledger_dir: Path, report_paths: list[Path], out_dir: Path, out=None,
        units: list[str] | None = None, manifest_path: Path | None = None) -> int:
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
    man = None
    run_gaps: list[str] = []
    if manifest_path is not None:
        man, problems = run_manifest.load(manifest_path, root)
        if man is None:
            for p in problems:
                print(f"  {p}", file=out)
            print("trace: FAILED (the run manifest is not valid)", file=out)
            return 1
        run_gaps = run_manifest.select(man)
        files = list(dict.fromkeys(f for e in man.entries for f in e.current))
    else:
        files = []
        for p in report_paths:
            files += reports.discover(p) if p.is_dir() else [p]
    results: list[reports.Result] = []
    read_problems: list[str] = []
    sources: list[str] = []
    citing: dict[Path, int] = {}
    for f in files:
        rs, probs = reports.read(f, root)
        read_problems += probs
        citing[f] = sum(1 for x in rs if x.items)
        if rs:
            results += rs
            sources.append(_rel(f, root))
    outside: list[dict] = []
    if man is not None:
        for e in man.entries:
            if e.required and e.current and not any(citing.get(f) for f in e.current):
                run_gaps.append(f"required report {e.rel} of {man.run} has no test result citing an item")
        listed = set(files) | {f for e in man.entries for f in e.stale}
        on_disk = [f for top in report_paths for f in (reports.discover(top) if top.is_dir() else [top])]
        for f in dict.fromkeys(on_disk):
            if f in listed:
                continue
            rs, probs = reports.read(f, root)
            outside.append({"path": _rel(f, root), "results": sum(1 for x in rs if x.items),
                            "failed": sum(1 for x in rs if x.items and x.status == "failed"), "unreadable": bool(probs)})
    arch = {i for row in catalog.architecture_dependent(root) for i in row}
    scanned = code.scan(root / "modernization", root)
    # No main Java at all means the code was not found, not that it declares nothing: gate every switch.
    declared = set(scanned.switches) if scanned.files else None
    m = matrix.build(s, led, results, arch, units, declared)
    m.gaps += [("report", p) for p in read_problems + run_gaps]
    run_info = _run_info(man, outside, root) if man is not None else None
    generated = dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat()
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "matrix.md").write_text(render_md(m, sources, generated, run_info), encoding="utf-8")
    (out_dir / "matrix.json").write_text(render_json(m, sources, generated, run_info), encoding="utf-8")
    for ident, reason in m.gaps[:50]:
        print(f"  {ident}: {reason}", file=out)
    if len(m.gaps) > 50:
        print(f"  ... {len(m.gaps) - 50} more in {out_dir / 'matrix.md'}", file=out)
    if m.ungated:
        print(f"  ({len(m.ungated)} gap{'s' if len(m.ungated) != 1 else ''} of units outside the gate, listed in {out_dir / 'matrix.md'})", file=out)
    if m.planned:
        n = len(m.planned)
        print(f"  ({n} planned code switch{'es' if n != 1 else ''} or covered item{'s' if n != 1 else ''} outside scope.toml "
              f"that no code declares and no test cites yet: not gated, not delivered, listed in {out_dir / 'matrix.md'})", file=out)
    failing_outside = [o for o in (run_info or {}).get("outside", []) if o["failed"] or o["unreadable"]]
    if failing_outside:
        n = len(failing_outside)
        print(f"  warning: {n} report{'s' if n != 1 else ''} outside this run {'have' if n != 1 else 'has'} failing or "
              f"unreadable results (not read here; make trace still reads {'them' if n != 1 else 'it'}):", file=out)
        for o in failing_outside[:10]:
            print(f"    {o['path']}: {'unreadable' if o['unreadable'] else str(o['failed']) + ' failed'}", file=out)
        if n > 10:
            print(f"    ... {n - 10} more in {out_dir / 'matrix.md'}", file=out)
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
                    help="gate only this unit of scope.toml (repeatable; default: every unit in scope and every code "
                         "switch the code declares)")
    ap.add_argument("--manifest", type=Path, metavar="FILE",
                    help="read only the reports this run manifest lists and the run wrote (see tracelib/manifest.py); "
                         "a missing, stale or empty required report is a gap")
    args = ap.parse_args(argv)
    if args.manifest is not None and args.reports:
        ap.error("--manifest and --reports cannot be combined: the manifest names the reports")
    root = args.root.resolve()
    return run(root, args.ledger or root / "modernization/ledger",
               args.reports if args.reports else [root / "modernization"],
               args.out or root / "modernization/build/trace", units=args.unit, manifest_path=args.manifest)


if __name__ == "__main__":
    sys.exit(main())
