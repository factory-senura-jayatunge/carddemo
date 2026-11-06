#!/usr/bin/env python3
"""Writes lookups.json, the three lists of app/cpy/CSLKPCDY.cpy the account edits use.

    usStateCodes                 VALID-US-STATE-CODE           (56)
    usStateZipPrefixes           VALID-US-STATE-ZIP-CD2-COMBO  (240, state + first two ZIP digits)
    nanpGeneralPurposeAreaCodes  VALID-GENERAL-PURP-CODE       (410)

Java (RuleTable) and the TypeScript mirror read the same file. Run from anywhere:

    python3 modernization/platform/validation/src/main/rules/gen_lookups.py [--check]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve()
REPO = HERE.parents[6]
COPYBOOK = REPO / "app/cpy/CSLKPCDY.cpy"
OUT = HERE.parents[1] / "resources/org/carddemo/platform/validation/lookups.json"
LISTS = {
    "usStateCodes": ("VALID-US-STATE-CODE", 56),
    "usStateZipPrefixes": ("VALID-US-STATE-ZIP-CD2-COMBO", 240),
    "nanpGeneralPurposeAreaCodes": ("VALID-GENERAL-PURP-CODE", 410),
}


def values_of(text: str, condition: str) -> list[str]:
    """The literals of one 88-level VALUES clause, in copybook order (comment lines skipped)."""
    lines = [l for l in text.splitlines() if not (len(l) > 6 and l[6] in "*/")]
    body = "\n".join(lines)
    m = re.search(r"\b88\s+" + re.escape(condition) + r"\s+VALUES?\b(.*?)\.\s*$", body, re.S | re.M)
    if not m:
        sys.exit(f"{COPYBOOK}: 88 {condition} not found")
    return re.findall(r"'([^']*)'", m.group(1))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="fail when the committed file differs")
    args = ap.parse_args()
    text = COPYBOOK.read_text(encoding="latin-1")
    lists = {}
    for name, (condition, count) in LISTS.items():
        values = values_of(text, condition)
        if len(values) != count or len(set(values)) != count:
            sys.exit(f"{condition}: {len(values)} values ({len(set(values))} distinct), expected {count}")
        lists[name] = values
    body = json.dumps({"source": "app/cpy/CSLKPCDY.cpy", "lists": lists}, indent=1) + "\n"
    if args.check:
        same = OUT.is_file() and OUT.read_text(encoding="utf-8") == body
        print("lookups.json: " + ("OK" if same else "differs; rerun without --check"))
        return 0 if same else 1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(body, encoding="utf-8")
    print("lookups.json: " + ", ".join(f"{k} {len(v)}" for k, v in lists.items()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
