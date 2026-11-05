"""Build an edge-case READACCT data directory from the sample data.

Usage: python3 gen_stress.py OUT_DIR
Targets: amounts whose sign byte is a plain digit, negative amounts and
negative zero, zero / negative-zero / negative cycle debits (the 2525.00
default), blank, undashed, short and odd reissue dates, text in the ZIP and
group fields, extra accounts, and an input file that is not in key order.
"""
import random
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[5]
SRC = REPO / "app" / "data" / "ASCII"
POS, NEG = "{ABCDEFGHI", "}JKLMNOPQR"
AMOUNTS = {"bal": 12, "limit": 24, "cash": 36, "cyc_credit": 78, "cyc_debit": 90}


def zoned(cents: int, digits: int = 12, plain: bool = False, negzero: bool = False) -> str:
    s = str(abs(cents)).rjust(digits, "0")[-digits:]
    if plain:
        return s
    return s[:-1] + (NEG if cents < 0 or negzero else POS)[int(s[-1])]


def put(r: str, pos: int, value: str) -> str:
    return r[:pos] + value + r[pos + len(value):]


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
    reissue = ["          ", "20250520  ", "2025/05/20", "25-05-20  ", "2025-5-2  ", "ABCDEFGHIJ"]
    for i, a in enumerate(accts):
        n = int(a[:11])
        if n % 3 == 0:                                   # plain-digit sign bytes
            for name in ("bal", "cash", "cyc_credit"):
                a = put(a, AMOUNTS[name], zoned(rnd.randint(0, 9_999_999), plain=True))
        if n % 4 == 0:
            a = put(a, AMOUNTS["bal"], zoned(-rnd.randint(1, 99_999_999)))
        if n % 5 == 0:
            a = put(a, AMOUNTS["limit"], zoned(0, negzero=True))
        debit = n % 6
        if debit == 0:
            a = put(a, AMOUNTS["cyc_debit"], zoned(0))
        elif debit == 1:
            a = put(a, AMOUNTS["cyc_debit"], zoned(0, negzero=True))
        elif debit == 2:
            a = put(a, AMOUNTS["cyc_debit"], zoned(-rnd.randint(1, 999_999)))
        elif debit == 3:
            a = put(a, AMOUNTS["cyc_debit"], zoned(rnd.randint(1, 999_999), plain=True))
        elif debit == 4:
            a = put(a, AMOUNTS["cyc_debit"], zoned(999_999_999_999))
        if n % 7 == 0:
            a = put(a, 68, reissue[(n // 7) % len(reissue)])
        if n % 8 == 0:
            a = put(a, 102, "12345-6789")
            a = put(a, 112, "GRP-" + str(n).rjust(6, "0"))
        accts[i] = a
    extra = accts[0][11:]
    for n in (99, 12345678901, 99999999999):
        accts.append(str(n).rjust(11, "0") + extra)
    rnd.shuffle(accts)
    (out / "acctdata.txt").write_text("".join(a.rstrip() + "\n" for a in accts), encoding="latin-1")
    print("accounts", len(accts))


if __name__ == "__main__":
    main()
