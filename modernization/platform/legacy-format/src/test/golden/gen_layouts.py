#!/usr/bin/env python3
"""Regenerate the copybook and sample-data golden files for the legacy-format tests.

copybook-layouts.json: every elementary field of the record copybooks, as
tests/oracle.py parses them from app/cpy (offset, length, numeric, signed,
scale), so the Java layouts are checked against the copybooks themselves.

sample-expectations.json: per sample file, the record count, a CRC-32 of the
sorted key list, the first and last key (except for files keyed by card
number) and the sum of every signed numeric field, decoded by tests/oracle.py.
No other field values are written, only counts, checksums and totals. The
checksum is a short CRC-32 rather than a long digest because secret scanners
take long hex strings for credentials.

Usage: python3 gen_layouts.py   (Python 3.11+, from any directory)
"""
import json
import os
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tests"))
import oracle  # noqa: E402

OUT = os.path.join(HERE, "..", "resources", "golden")

COPYBOOKS = ["CVACT01Y", "CVACT02Y", "CVACT03Y", "CVCUS01Y", "CVTRA01Y", "CVTRA02Y",
             "CVTRA03Y", "CVTRA04Y", "CVTRA05Y", "CVTRA06Y", "CSUSR01Y"]

# file, copybook, key length (the KSDS KEYS(n 0) of its JCL; 0 for sequential files)
SAMPLES = [
    ("acctdata.txt", "CVACT01Y", 11),
    ("custdata.txt", "CVCUS01Y", 9),
    ("carddata.txt", "CVACT02Y", 16),
    ("cardxref.txt", "CVACT03Y", 16),
    ("tcatbal.txt", "CVTRA01Y", 17),
    ("trantype.txt", "CVTRA03Y", 2),
    ("trancatg.txt", "CVTRA04Y", 6),
    ("discgrp.txt", "CVTRA02Y", 16),
    ("dailytran.txt", "CVTRA06Y", 0),
]

# Keyed by card number (PII): no key is written, only the checksum.
CARD_KEYED = {"carddata.txt", "cardxref.txt"}


def layouts():
    out = {}
    for name in COPYBOOKS:
        fields = oracle.parse_copybook(name)
        out[name] = {
            "length": fields["__LENGTH__"].length,
            "fields": [
                {"name": f.name, "offset": f.offset, "length": f.length,
                 "numeric": f.numeric, "signed": f.signed, "scale": f.scale}
                for k, f in fields.items() if k != "__LENGTH__"
            ],
        }
    return out


def samples():
    out = {}
    for filename, copybook, key_len in SAMPLES:
        layout = oracle.parse_copybook(copybook)
        length = layout["__LENGTH__"].length
        lines = oracle.sample_records(filename)
        records = [oracle.Record(layout, line) for line in lines]
        entry = {"copybook": copybook, "count": len(records),
                 "padded": sum(1 for line in lines if len(line) < length)}
        if key_len:
            keys = sorted(r.text[:key_len] for r in records)
            entry["keyLength"] = key_len
            entry["distinctKeys"] = len(set(keys))
            entry["keysCrc32"] = format(zlib.crc32("\n".join(keys).encode("latin-1")) & 0xFFFFFFFF, "08x")
            if filename not in CARD_KEYED:
                entry["firstKey"] = keys[0]
                entry["lastKey"] = keys[-1]
            entry["inKeyOrder"] = [r.text[:key_len] for r in records] == keys
        sums = {}
        for fname, f in layout.items():
            if fname != "__LENGTH__" and f.numeric and f.signed:
                sums[fname] = str(sum((r[fname] for r in records), start=oracle.Decimal(0)))
        entry["sums"] = sums
        out[filename] = entry
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, data in (("copybook-layouts.json", layouts()), ("sample-expectations.json", samples())):
        path = os.path.join(OUT, name)
        with open(path, "w", newline="\n") as fh:
            json.dump(data, fh, indent=2, sort_keys=True)
            fh.write("\n")
        print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    main()
