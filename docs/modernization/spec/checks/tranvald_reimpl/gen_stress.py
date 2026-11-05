"""Build an edge-case TRANVALD data directory from the sample data.

Usage: python3 gen_stress.py OUT_DIR
Targets: cards missing from the cross-reference, cross-references whose
account is missing, records in a different order, a repeated transaction,
and cross-references added before, between and after the sample keys.
"""
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[5]
SRC = REPO / "app" / "data" / "ASCII"


def recs(name, lrecl):
    return [l.rstrip("\r").ljust(lrecl) for l in (SRC / name).read_text(encoding="latin-1").split("\n") if l.strip()]


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    for f in SRC.iterdir():
        shutil.copy(f, out / f.name)
    daily = list(reversed(recs("dailytran.txt", 350)))
    xref = recs("cardxref.txt", 50)
    # cross-references whose account does not exist (accounts 1-50 exist)
    orphans = [("0000000000000001", "00000009998"), ("5555555555555555", "00000000099"),
               ("9999999999999999", "99999999999")]
    for card, acct in orphans:
        xref.append(card + "000000077" + acct + " " * 14)
    xref.sort(key=lambda r: r[:16])
    set_card = lambda r, c: r[:262] + c + r[278:]
    for i, (card, _) in zip((0, 57, 299), orphans):
        daily[i] = set_card(daily[i], card)
    for i in (5, 6, 120, 250):                       # unknown cards
        daily[i] = set_card(daily[i], "4000" + str(i).rjust(12, "0"))
    daily[200] = set_card(daily[200], " " * 16)     # blank card number
    daily.insert(100, daily[99])                     # the same transaction twice
    (out / "dailytran.txt").write_text("\n".join(daily) + "\n", encoding="latin-1")
    (out / "cardxref.txt").write_text("\n".join(x.rstrip() for x in xref) + "\n", encoding="latin-1")
    print("daily", len(daily), "xref", len(xref))


if __name__ == "__main__":
    main()
