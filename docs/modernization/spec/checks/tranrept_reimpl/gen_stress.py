"""Build edge-case TRANREPT data sets from the sample data.

Usage: python3 gen_stress.py OUT_DIR
Writes OUT_DIR/case-*/, each a complete data directory with its own
tranfile.txt (the sorted TRANFILE) and dateparm.txt:
  case-main      many cards, bounds of the range, negative and zero totals,
                 an overflowing grand total, an out-of-range record late in
                 the file (the report stops there)
  case-allin     the same records without the out-of-range one (end-of-file
                 totals, page breaks across account totals)
  case-lastout   the last record is out of range (no totals at all)
  case-empty     empty TRANFILE
  case-reversed  start date after end date
  case-blankdates  empty TRANFILE with blank DATEPARM dates (the blank work
                 record is in range, so the end-of-file totals are written)
  case-blankcard the first record has a blank card number (no card break, no
                 cross-reference read before the first detail)
  case-badcat    a category missing from TRANCATG (abend after some lines)
  case-badcard   a card missing from CARDXREF (abend)
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


def with_ts(r: str, day: str) -> str:
    ts = day + "-12.00.00.000000"
    return r[:278] + ts + ts + r[330:]


def with_amt(r: str, cents: int) -> str:
    return r[:132] + zoned(cents, 11) + r[143:]


def write_case(out: Path, name: str, tran: list[str], dateparm: str) -> None:
    d = out / name
    d.mkdir(parents=True, exist_ok=True)
    for f in SRC.iterdir():
        shutil.copy(f, d / f.name)
    (d / "tranfile.txt").write_text("".join(t + "\n" for t in tran), encoding="latin-1")
    (d / "dateparm.txt").write_text(dateparm + "\n")


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    rnd = random.Random(20220706)
    cards = {x[:16] for x in recs("cardxref.txt", 50)}
    cats = {c[:6] for c in recs("trancatg.txt", 60)}
    daily = [r for r in recs("dailytran.txt", 350) if r[262:278] in cards and r[16:22] in cats]
    days = ["2022-03-01", "2022-03-15", "2022-04-30", "2022-05-31", "2022-06-30"]
    tran = []
    for i, r in enumerate(daily):
        r = with_ts(r, days[i % len(days)])
        c = rnd.random()
        if c < 0.1:
            r = with_amt(r, 0)
        elif c < 0.35:
            r = with_amt(r, -rnd.randint(1, 500000))
        elif c < 0.4:
            r = with_amt(r, rnd.randint(1, 99))
        tran.append(r)
    tran.sort(key=lambda r: r[262:278])
    # a card whose amounts sum to zero (account total prints as spaces)
    groups = {}
    for k, r in enumerate(tran):
        groups.setdefault(r[262:278], []).append(k)
    zero_card = next(ks for ks in groups.values() if len(ks) >= 2)
    for n, k in enumerate(zero_card):
        tran[k] = with_amt(tran[k], (12345, -12345)[n] if n < 2 else 0)
    # two huge amounts: the page and grand totals exceed 999,999,999.99 and keep the low digits
    tran[40] = with_amt(tran[40], 99_000_000_000)
    tran[41] = with_amt(tran[41], 98_765_432_100)
    allin = list(tran)
    late = with_ts(tran[230], "2022-07-15")
    main_tran = tran[:230] + [late] + tran[230:]
    parm = "2022-03-01 2022-06-30"
    write_case(out, "case-main", main_tran, parm)
    write_case(out, "case-allin", allin, parm)
    write_case(out, "case-lastout", allin[:-1] + [with_ts(allin[-1], "2021-12-31")], parm)
    write_case(out, "case-empty", [], parm)
    write_case(out, "case-reversed", allin[:20], "2022-06-30 2022-03-01")
    write_case(out, "case-blankdates", [], " " * 21 + "*")
    write_case(out, "case-blankcard", [allin[0][:262] + " " * 16 + allin[0][278:]] + allin[:12], parm)
    bad = list(allin[:60])
    bad[45] = bad[45][:16] + "01" + "0099" + bad[45][22:]
    write_case(out, "case-badcat", bad, parm)
    bad = list(allin[:60])
    j = next(k for k in range(30, 60) if bad[k][262:278] != bad[k - 1][262:278])
    bad = bad[:j] + [bad[j][:262] + "0000000000000000" + bad[j][278:]] + bad[j:]
    write_case(out, "case-badcard", bad, parm)
    print("cases written to", out)


if __name__ == "__main__":
    main()
