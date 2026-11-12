#!/usr/bin/env python3
"""Regenerate the pending-authorization golden file for the legacy-format tests.

pauth-hd-unload.json: the elementary fields of the IMS segment copybooks
CIPAUSMY (PAUTSUM0) and CIPAUDTY (PAUTDTL1), parsed here with their usage
(DISPLAY, COMP-3, COMP) and OCCURS (expanded as NAME-1 .. NAME-n), and what
the EBCDIC HD unload sample AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat holds,
decoded here independently of the Java reader: record and segment counts,
the account IDs of the roots in file order with their number of children,
the sums of every signed numeric field, value counts of the status codes,
the range of the inverted key dates, and for the card numbers only a count
and a short CRC-32 of the sorted list (no card number is written).

Usage: python3 gen_pauth.py   (Python 3.11+, from any directory)
"""
import json
import os
import re
import zlib
from collections import Counter
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", "..", "..", ".."))
MODULE = os.path.join(ROOT, "app", "app-authorization-ims-db2-mq")
SAMPLE = os.path.join(MODULE, "data", "EBCDIC", "AWS.M2.CARDDEMO.IMSDATA.DBPAUTP0.dat")
OUT = os.path.join(HERE, "..", "resources", "golden", "pauth-hd-unload.json")

SEGMENTS = {"PAUTSUM0": "CIPAUSMY", "PAUTDTL1": "CIPAUDTY"}


def pic_digits(pic):
    expanded = re.sub(r"([X9])\((\d+)\)", lambda m: m.group(1) * int(m.group(2)), pic.upper())
    signed = expanded.startswith("S")
    body = expanded.lstrip("S")
    int_part, _, frac = body.partition("V")
    return len(int_part) + len(frac), "X" not in body, signed, len(frac)


def parse(copybook):
    path = os.path.join(MODULE, "cpy", copybook + ".cpy")
    fields, offset = [], 0
    with open(path, encoding="ascii") as fh:
        for raw in fh:
            line = raw.rstrip("\n")[6:72]
            if not line.strip() or line[0] in "*/":
                continue
            m = re.match(r"\s*(\d\d)\s+([A-Z0-9-]+)(.*)\.\s*$", line[1:].upper())
            if not m:
                continue
            _, name, rest = m.groups()
            pic = re.search(r"PIC\s+(\S+)", rest)
            if not pic:
                continue
            digits, numeric, signed, scale = pic_digits(pic.group(1))
            if "COMP-3" in rest:
                usage, length = "COMP-3", digits // 2 + 1
            elif re.search(r"\bCOMP\b", rest):
                usage, length = "COMP", 2 if digits <= 4 else 4 if digits <= 9 else 8
            else:
                usage, length = "DISPLAY", digits
            occurs = re.search(r"OCCURS\s+(\d+)", rest)
            names = [f"{name}-{i}" for i in range(1, int(occurs.group(1)) + 1)] if occurs else [name]
            for n in names:
                if n != "FILLER":
                    fields.append({"name": n, "offset": offset, "length": length, "usage": usage,
                                   "numeric": numeric, "signed": signed, "scale": scale})
                offset += length
    return {"length": offset, "fields": fields}


def packed(b, scale):
    h = b.hex()
    value = int(h[:-1])
    if h[-1] in "bd":
        value = -value
    return Decimal(value).scaleb(-scale)


def decode(layout, data):
    out = {}
    for f in layout["fields"]:
        b = data[f["offset"]:f["offset"] + f["length"]]
        if f["usage"] == "COMP-3":
            out[f["name"]] = packed(b, f["scale"])
        elif f["usage"] == "COMP":
            out[f["name"]] = Decimal(int.from_bytes(b, "big", signed=f["signed"])).scaleb(-f["scale"])
        else:
            out[f["name"]] = b.decode("cp037")
    return out


def sample(layouts):
    with open(SAMPLE, "rb") as fh:
        data = fh.read()
    records, p = [], 0
    while p < len(data):
        n = int.from_bytes(data[p:p + 2], "big")
        assert data[p + 2:p + 4] == b"\0\0", p
        records.append(data[p:p + n])
        p += n
    assert p == len(data)
    assert len(records[0]) == 88 and len(records[-1]) == 88
    roots, names = [], Counter()
    for r in records[1:-1]:
        name = r[10:18].decode("cp037")
        layout = layouts[SEGMENTS[name]]
        seg_len = int.from_bytes(r[8:10], "big")
        assert seg_len == layout["length"] and len(r) == 39 + seg_len + 1, (name, len(r))
        names[name] += 1
        seg = r[39:39 + seg_len]
        if name == "PAUTSUM0":
            blank = seg[0:6] == b"\x40" * 6
            roots.append({"blank": blank, "values": None if blank else decode(layout, seg), "children": []})
        else:
            roots[-1]["children"].append(decode(layout, seg))
    kept = [r for r in roots if not r["blank"]]
    details = [d for r in kept for d in r["children"]]

    def sums(rows, copybook):
        return {f["name"]: str(sum((row[f["name"]] for row in rows), start=Decimal(0)))
                for f in layouts[copybook]["fields"] if f["numeric"] and f["signed"]}

    cards = sorted({d["PA-CARD-NUM"] for d in details})
    dates = sorted(99999 - int(d["PA-AUTH-DATE-9C"]) for d in details)
    return {
        "file": os.path.basename(SAMPLE),
        "records": len(records),
        "segments": dict(names),
        "blankRoots": [{"position": i, "children": len(r["children"])} for i, r in enumerate(roots) if r["blank"]],
        "roots": [{"acctId": int(r["values"]["PA-ACCT-ID"]), "children": len(r["children"])} for r in kept],
        "summarySums": sums([r["values"] for r in kept], "CIPAUSMY"),
        "detailSums": sums(details, "CIPAUDTY"),
        "distinctCards": len(cards),
        "cardsCrc32": format(zlib.crc32("\n".join(cards).encode("ascii")) & 0xFFFFFFFF, "08x"),
        "respCodes": dict(Counter(d["PA-AUTH-RESP-CODE"] for d in details)),
        "matchStatus": dict(Counter(d["PA-MATCH-STATUS"] for d in details)),
        "fraud": dict(Counter(d["PA-AUTH-FRAUD"] for d in details)),
        "keyDateYyddd": {"min": dates[0], "max": dates[-1]},
    }


def main():
    layouts = {cb: parse(cb) for cb in SEGMENTS.values()}
    golden = {"layouts": layouts, "segments": SEGMENTS, "sample": sample(layouts)}
    with open(OUT, "w", newline="\n") as fh:
        json.dump(golden, fh, indent=2, sort_keys=True)
        fh.write("\n")
    print("wrote", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
