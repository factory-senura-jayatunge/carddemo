"""Build an edge-case INTCALC data directory from the sample data.

Usage: python3 gen_stress.py OUT_DIR
Targets: disclosure groups (own group, ZEROAPR, unknown group -> DEFAULT),
several categories per account, zero and negative balances, truncation of
negative interest, large balances, a different PARM, non-zero cycle fields.
"""
import random
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[5]
SRC = REPO / "app" / "data" / "ASCII"
POS, NEG = "{ABCDEFGHI", "}JKLMNOPQR"


def zoned(cents: int, digits: int) -> str:
    s = str(abs(cents)).rjust(digits, "0")[-digits:]
    return s[:-1] + (NEG if cents < 0 else POS)[int(s[-1])]


def recs(name, lrecl):
    return [l.rstrip("\r").ljust(lrecl) for l in (SRC / name).read_text(encoding="latin-1").split("\n") if l.strip()]


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for f in SRC.iterdir():
        shutil.copy(f, out / f.name)
    rnd = random.Random(20220706)
    accts = recs("acctdata.txt", 300)
    groups = {1: "A000000000", 2: "ZEROAPR   ", 3: "NOSUCHGRP ", 5: "DEFAULT   ", 6: "A000000000", 7: "A000000000"}
    for i, a in enumerate(accts):
        n = int(a[:11])
        if n in groups:
            a = a[:112] + groups[n] + a[122:]
        if n % 4 == 0:      # non-zero cycle fields, reset by INTCALC
            a = a[:78] + zoned(rnd.randint(1, 99999), 12) + zoned(-rnd.randint(1, 99999), 12) + a[102:]
        if n == 8:
            a = a[:12] + zoned(-123456, 12) + a[24:]
        accts[i] = a
    (out / "acctdata.txt").write_text("\n".join(accts) + "\n", encoding="latin-1")

    tcat = recs("tcatbal.txt", 50)
    keys = ["010001", "010002", "010003", "010004", "020001", "030001", "040001", "040002",
            "050001", "060001", "060002", "070001"]
    existing = {r[:17]: r for r in tcat}
    filler = tcat[0][28:50]
    for n in range(1, 11):
        acct = str(n).rjust(11, "0")
        for k in keys:
            key = acct + k
            if key in existing:
                continue
            choice = rnd.random()
            if choice < 0.15:
                cents = 0
            elif choice < 0.35:
                cents = -rnd.randint(1, 200)          # small negatives truncate to 0.00 or -0.01
            elif choice < 0.45:
                cents = -rnd.randint(1000, 5000000)
            elif choice < 0.5:
                cents = rnd.randint(9_000_000_000, 99_999_999_999)
            else:
                cents = rnd.randint(1, 900000)
            existing[key] = key + zoned(cents, 11) + filler
    tcat = [existing[k] for k in sorted(existing)]
    (out / "tcatbal.txt").write_text("\n".join(tcat) + "\n", encoding="latin-1")
    (out / "parm.txt").write_text("2023013100\n")
    print("accounts", len(accts), "category balances", len(tcat))


if __name__ == "__main__":
    main()
