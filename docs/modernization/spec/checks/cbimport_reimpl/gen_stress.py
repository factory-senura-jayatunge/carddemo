#!/usr/bin/env python3
"""Build the CBIMPORT stress scenarios (deterministic).

Input: an export file of 500-byte records (for example the CBEXPORT stress
output). Scenarios written under OUTDIR:
  stress/   the export records renumbered so that record types are interleaved
            and sequence numbers have gaps, plus records with unknown types,
            large sequence numbers, packed and binary edge values, non-blank
            header and FILLER bytes
  empty/    EXPORT.ps present and empty
  missing/  no EXPORT.ps: the OPEN of EXPFILE fails
Usage: python3 gen_stress.py EXPORT_PS OUTDIR
"""
from __future__ import annotations

import random
import sys
from pathlib import Path


def put(rec: bytes, pos: int, data: bytes) -> bytes:
    return rec[:pos - 1] + data + rec[pos - 1 + len(data):]


def seq(rec: bytes, n: int) -> bytes:
    return put(rec, 28, n.to_bytes(4, "big"))


def main() -> int:
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    d = src.read_bytes()
    recs = [d[i:i + 500] for i in range(0, len(d), 500)]
    rnd = random.Random(5110)
    by_type = {t: [r for r in recs if r[:1] == t.encode()] for t in "CAXTD"}

    extra = []
    a = by_type["A"][0]
    extra.append(put(a, 41 + 12, bytes.fromhex("0000000000000d")))           # packed negative zero balance
    extra[-1] = put(extra[-1], 41 + 31, bytes.fromhex("0000000123456f"))      # cash limit, sign nibble F
    extra[-1] = put(extra[-1], 41 + 80, (-999999999999).to_bytes(8, "big", signed=True))
    extra[-1] = put(extra[-1], 41, b"00000000777")
    a2 = put(a, 41, b"00000000778")
    a2 = put(a2, 41 + 12, bytes.fromhex("9999999999999d"))                    # -9,999,999,999.99 packed
    a2 = put(a2, 41 + 80, (1).to_bytes(8, "big", signed=True))
    a2 = put(a2, 41 + 108, b"Q" * 352)                                       # non-blank FILLER in the data area
    extra.append(a2)
    t = by_type["T"][0]
    t1 = put(t, 41, b"9000000000000001")
    t1 = put(t1, 41 + 132, bytes.fromhex("00000012345f"))                    # unsigned-style packed amount
    t1 = put(t1, 41 + 138, (999999999).to_bytes(4, "big"))
    t1 = put(t1, 41 + 320, b"T" * 140)
    extra.append(t1)
    t2 = put(t, 41, b"9000000000000002")
    t2 = put(t2, 41 + 132, bytes.fromhex("00000000000d"))                    # packed negative zero amount
    extra.append(t2)
    c = by_type["C"][0]
    c1 = put(c, 41, (999999999).to_bytes(4, "big"))
    c1 = put(c1, 41 + 324, bytes.fromhex("000f"))                            # FICO 000
    c1 = put(c1, 2, b"HEADER IGNORED BY IMPORT!!")                          # header bytes are not used
    c1 = put(c1, 32, b"9999SOUTH")
    c1 = put(c1, 41 + 326, b"F" * 134)
    extra.append(c1)
    x = by_type["X"][0]
    extra.append(put(put(x, 41, b"7777777777777777"), 41 + 25, (0).to_bytes(8, "big")))
    dd = by_type["D"][0]
    extra.append(put(put(dd, 41, b"7777777777777777"), 41 + 24, (999).to_bytes(2, "big")))

    unknown = []
    for ty in (b"Z", b"c", b" ", b"\x00", b"9"):
        u = put(by_type["C"][1], 1, ty)
        unknown.append(u)

    body = recs + extra
    rnd.shuffle(body)
    numbered, n = [], 0
    for r in body:
        n += rnd.choice((1, 1, 1, 2, 7))
        numbered.append(seq(r, n))
    numbered += [seq(unknown[0], n + 1), seq(unknown[1], 12345678), seq(unknown[2], 99999999),
                 seq(unknown[3], 123456789), seq(unknown[4], 999999999)]
    rnd.shuffle(numbered)        # file order is irrelevant: the dataset is read in key order

    for name, content in (("stress", numbered), ("empty", [])):
        p = out / name
        p.mkdir(parents=True, exist_ok=True)
        (p / "EXPORT.ps").write_bytes(b"".join(content))
    (out / "missing").mkdir(parents=True, exist_ok=True)
    (out / "missing" / "EXPORT.ps").unlink(missing_ok=True)
    print(f"scenarios in {out}: stress ({len(numbered)} records), empty, missing")
    return 0


if __name__ == "__main__":
    sys.exit(main())
