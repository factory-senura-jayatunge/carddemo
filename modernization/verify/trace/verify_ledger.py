#!/usr/bin/env python3
"""make verify-ledger: check modernization/ledger/*.toml against the spec, the decision catalog and the code.

Passes when every switch the code declares or reads has a fidelity entry with code_switch = true
and no main configuration overrides the ledger's value; every fidelity entry names a defect-decide
item or a question the spec defines, and every deviation an ID the spec defines, each with a
decision, approved_by and approved_on; every defect-decide item has a fidelity entry or a deviation;
and every architecture-dependent item has a deviation of that kind. A code switch the code does not
declare yet is a warning. Prints the problems, then
"verify-ledger: OK" or "verify-ledger: FAILED (<reason>)". Exit status 0 or 1.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from tracelib import catalog, code, ledger, spec  # noqa: E402

REPO = HERE.parents[2]


def run(root: Path, ledger_dir: Path, code_dir: Path | None, out=sys.stdout) -> int:
    led = ledger.load(ledger_dir)
    problems = list(led.problems)
    try:
        s = spec.load(root)
    except Exception as e:  # the spec checker's own errors are make spec-check's job
        print(f"verify-ledger: FAILED (cannot load the spec: {e})", file=out)
        return 1
    decisions = catalog.decisions(root)
    if not decisions:
        problems.append(f"{catalog.CATALOG}: no decisions found")
    problems += ledger.check_switches(led, s, decisions)
    problems += ledger.check_deviations(led, s, decisions, catalog.architecture_dependent(root))
    problems += ledger.check_scope(led, s)
    scanned = code.scan(code_dir, root) if code_dir is not None else None
    warnings: list[str] = []
    if scanned is not None:
        code_problems, warnings = ledger.check_code(led, scanned)
        problems += code_problems
    for p in problems:
        print(f"  {p}", file=out)
    for wmsg in warnings:
        print(f"  warning: {wmsg}", file=out)
    print(f"ledger: {ledger.summary(led, scanned)}", file=out)
    if problems:
        print(f"verify-ledger: FAILED ({len(problems)} problem{'s' if len(problems) != 1 else ''})", file=out)
        return 1
    print("verify-ledger: OK", file=out)
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--root", type=Path, default=REPO, help="repository root (default: this checkout)")
    ap.add_argument("--ledger", type=Path, help="ledger directory (default: <root>/modernization/ledger)")
    ap.add_argument("--code", type=Path, help="code to scan for switches (default: <root>/modernization)")
    ap.add_argument("--no-code", action="store_true", help="skip the checks against the code")
    args = ap.parse_args(argv)
    root = args.root.resolve()
    ledger_dir = args.ledger or root / "modernization/ledger"
    code_dir = None if args.no_code else (args.code or root / "modernization")
    return run(root, ledger_dir, code_dir)


if __name__ == "__main__":
    sys.exit(main())
