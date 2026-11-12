#!/usr/bin/env python3
"""Writes lookups.json, the lookup lists of the copybooks that the rule table's edits use.

    usStateCodes                 CSLKPCDY VALID-US-STATE-CODE           (56)
    usStateZipPrefixes           CSLKPCDY VALID-US-STATE-ZIP-CD2-COMBO  (240, state + first two ZIP digits)
    nanpGeneralPurposeAreaCodes  CSLKPCDY VALID-GENERAL-PURP-CODE       (410)
    userTypes                    COCOM01Y CDEMO-USRTYP-ADMIN, CDEMO-USRTYP-USER (2: A, U)

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
OUT = HERE.parents[1] / "resources/org/carddemo/platform/validation/lookups.json"
LOOKUPS = "app/cpy/CSLKPCDY.cpy"
COMMAREA = "app/cpy/COCOM01Y.cpy"
LISTS = {
    "usStateCodes": (LOOKUPS, ("VALID-US-STATE-CODE",), 56),
    "usStateZipPrefixes": (LOOKUPS, ("VALID-US-STATE-ZIP-CD2-COMBO",), 240),
    "nanpGeneralPurposeAreaCodes": (LOOKUPS, ("VALID-GENERAL-PURP-CODE",), 410),
    "userTypes": (COMMAREA, ("CDEMO-USRTYP-ADMIN", "CDEMO-USRTYP-USER"), 2),
}


def values_of(text: str, condition: str, copybook: str) -> list[str]:
    """The literals of one 88-level VALUES clause, in copybook order (comment lines skipped)."""
    lines = [l for l in text.splitlines() if not (len(l) > 6 and l[6] in "*/")]
    body = "\n".join(lines)
    m = re.search(r"\b88\s+" + re.escape(condition) + r"\s+VALUES?\b(.*?)\.\s*$", body, re.S | re.M)
    if not m:
        sys.exit(f"{copybook}: 88 {condition} not found")
    return re.findall(r"'([^']*)'", m.group(1))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="fail when the committed file differs")
    args = ap.parse_args()
    lists = {}
    for name, (copybook, conditions, count) in LISTS.items():
        text = (REPO / copybook).read_text(encoding="latin-1")
        values = [v for condition in conditions for v in values_of(text, condition, copybook)]
        if len(values) != count or len(set(values)) != count:
            sys.exit(f"{name}: {len(values)} values ({len(set(values))} distinct), expected {count}")
        lists[name] = values
    sources = sorted({copybook for copybook, _, _ in LISTS.values()}, key=[LOOKUPS, COMMAREA].index)
    body = json.dumps({"source": ", ".join(sources), "lists": lists}, indent=1) + "\n"
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
