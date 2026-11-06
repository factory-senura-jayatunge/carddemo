#!/usr/bin/env python3
"""Build the CBEXPORT stress scenarios from the sample data (deterministic).

Scenarios written under OUTDIR:
  stress/        sample files after POSTTRAN, plus records with edge values
                 (negative, negative zero, maximum, plain-digit sign byte, key
                 extremes, letters in text keys), input records shuffled
  empty/         all five input files present and empty
  missing-card/  CARDDATA.ps absent: the fifth OPEN fails
  missing-cust/  CUSTDATA.ps absent: the first OPEN fails
Usage: python3 gen_stress.py SAMPLE_SCENARIO OUTDIR
SAMPLE_SCENARIO holds the five .ps files (for example the sample day after POSTTRAN).
"""
from __future__ import annotations

import random
import sys
from pathlib import Path

POS = "{ABCDEFGHI"
NEG = "}JKLMNOPQR"
LRECL = {"CUSTDATA": 500, "ACCTDATA": 300, "CARDXREF": 50, "TRANSACT": 350, "CARDDATA": 150}


def zoned(value_cents: int, digits: int, negative_zero: bool = False) -> bytes:
    s = f"{abs(value_cents):0{digits}d}"
    if len(s) != digits:
        raise ValueError(value_cents)
    neg = value_cents < 0 or negative_zero
    return (s[:-1] + (NEG if neg else POS)[int(s[-1])]).encode()


def put(rec: bytes, pos: int, data: bytes) -> bytes:
    """pos is 1-based."""
    return rec[:pos - 1] + data + rec[pos - 1 + len(data):]


def records(path: Path, lrecl: int) -> list[bytes]:
    d = path.read_bytes()
    return [d[i:i + lrecl] for i in range(0, len(d), lrecl)]


def write(dirp: Path, name: str, recs: list[bytes]) -> None:
    dirp.mkdir(parents=True, exist_ok=True)
    (dirp / f"{name}.ps").write_bytes(b"".join(r.ljust(LRECL[name], b" ") for r in recs))


def main() -> int:
    sample, out = Path(sys.argv[1]), Path(sys.argv[2])
    rnd = random.Random(20261105)
    data = {n: records(sample / f"{n}.ps", l) for n, l in LRECL.items()}

    cust = data["CUSTDATA"]
    cust[0] = put(cust[0], 330, b"999")                           # FICO 999 (pos 330-332)
    cust[1] = put(cust[1], 330, b"000")                           # FICO 000
    cust[2] = put(cust[2], 280, b"999999999")                    # SSN max (pos 280-288)
    big = put(cust[3], 1, b"999999999")
    big = put(big, 10, b"Mary Ann".ljust(25))                     # first name with a space
    big = put(big, 35, b" ".ljust(25))                            # blank middle name
    big = put(big, 330, b"850")
    zero = put(cust[4], 1, b"000000000")
    zero = put(zero, 333, b"FILLER-NOT-EXPORTED".ljust(168))      # non-blank FILLER
    cust += [big, zero]

    acct = data["ACCTDATA"]
    acct[0] = put(acct[0], 13, zoned(-123454, 12))               # balance -1234.54
    acct[1] = put(acct[1], 13, zoned(999999999999, 12))          # +9,999,999,999.99
    acct[1] = put(acct[1], 91, zoned(-999999999999, 12))         # cycle debit minimum
    acct[1] = put(acct[1], 79, zoned(999999999999, 12))          # cycle credit maximum
    acct[2] = put(acct[2], 13, zoned(0, 12, negative_zero=True))  # negative zero in each kind
    acct[2] = put(acct[2], 37, zoned(0, 12, negative_zero=True))
    acct[2] = put(acct[2], 91, zoned(0, 12, negative_zero=True))
    acct[2] = put(acct[2], 25, zoned(0, 12, negative_zero=True))
    acct[2] = put(acct[2], 79, zoned(0, 12, negative_zero=True))
    acct[3] = put(acct[3], 13, b"000000001234")                  # plain digit in the sign byte
    acct[3] = put(acct[3], 25, b"000000005000")
    acct[3] = put(acct[3], 91, b"000000000001")
    acct[4] = put(acct[4], 25, zoned(-50000, 12))                # negative credit limit
    acct[4] = put(acct[4], 37, zoned(-1, 12))                    # cash limit -0.01
    acct[4] = put(acct[4], 91, zoned(-1, 12))
    acct[5] = put(acct[5], 123, b"Z" * 178)                      # non-blank FILLER
    top = put(acct[6], 1, b"99999999999")
    top = put(top, 12, b"N")
    acct.append(top)

    xref = data["CARDXREF"]
    xref.append(b"9999999999999999" + b"999999999" + b"99999999999")
    xref.append(b"ABCD000000000001" + b"000000000" + b"00000000000")
    xref.append(b"0000000000000000" + b"000000001" + b"00000000001" + b"XREF-FILLER-1")

    tran = data["TRANSACT"]
    tran[0] = put(tran[0], 133, zoned(99999999999, 11))          # 999,999,999.99
    tran[1] = put(tran[1], 133, zoned(-99999999999, 11))
    tran[2] = put(tran[2], 133, zoned(0, 11, negative_zero=True))
    tran[3] = put(tran[3], 133, b"00000000500")                  # plain digit sign byte
    tran[4] = put(tran[4], 144, b"999999999")
    tran[5] = put(tran[5], 144, b"000000000")
    tran[6] = put(tran[6], 331, b"TRAN-FILLER-NONBLANK")
    special = put(tran[7], 1, b"TESTID0000000001")
    special = put(special, 19, b"9999")
    tran.append(special)

    card = data["CARDDATA"]
    card[0] = put(card[0], 28, b"999")                           # CVV 999
    card[1] = put(card[1], 28, b"000")
    card[2] = put(card[2], 92, b"C" * 59)                        # non-blank FILLER
    card.append(put(put(card[3], 1, b"9999999999999999"), 17, b"99999999999"))
    card.append(put(card[4], 1, b"ABCD000000000001"))

    for recs in data.values():
        rnd.shuffle(recs)
    for name, recs in data.items():
        write(out / "stress", name, recs)
        write(out / "empty", name, [])
        if name != "CARDDATA":
            write(out / "missing-card", name, recs)
        if name != "CUSTDATA":
            write(out / "missing-cust", name, recs)
    print(f"scenarios in {out}: stress, empty, missing-card, missing-cust")
    return 0


if __name__ == "__main__":
    sys.exit(main())
