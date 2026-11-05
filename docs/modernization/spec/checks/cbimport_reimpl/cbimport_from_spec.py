#!/usr/bin/env python3
"""CBIMPORT reimplemented from its natural-language specification.

Usage: python3 cbimport_from_spec.py --data SCENARIO_DIR --out OUT_DIR
                                     --clock "YYYY-MM-DD HH:MM:SS"
"""
import argparse
import os
import sys
import time

REC_LEN = 500
KEY_OFF, KEY_LEN = 27, 4

OUT_FILES = [
    # (DD, file name, open-error name, write-error text)
    ("CUSTOUT", "CUSTOUT.ps", "CUSTOMER-OUTPUT", "customer"),
    ("ACCTOUT", "ACCTOUT.ps", "ACCOUNT-OUTPUT", "account"),
    ("XREFOUT", "XREFOUT.ps", "XREF-OUTPUT", "xref"),
    ("TRNXOUT", "TRNXOUT.ps", "TRANSACTION-OUTPUT", "transaction"),
    ("CARDOUT", "CARDOUT.ps", "CARD-OUTPUT", "card"),
    ("ERROUT", "ERROUT.ps", "ERROR-OUTPUT", None),
]

POS_OVERPUNCH = "{ABCDEFGHI"
NEG_OVERPUNCH = "}JKLMNOPQR"


class Abend(Exception):
    pass


# ---------------------------------------------------------------- numerics

def ubin_to_digits(b, n):
    """NUM-IMPT-001: unsigned big-endian binary -> n digits, V mod 10^n."""
    v = int.from_bytes(b, "big", signed=False)
    return ("%0*d" % (n, v % (10 ** n))).encode("ascii")


def overpunch(digit_chars, negative):
    """NUM-IMPT-007: fold the sign into the last digit character.

    digit_chars is a list of 1-char strings; a last 'digit' that is not
    0-9 (invalid packed half-byte, NUM-IMPT-006) takes the digit-0 sign."""
    last = digit_chars[-1]
    d = ord(last) - 0x30 if "0" <= last <= "9" else 0
    table = NEG_OVERPUNCH if negative else POS_OVERPUNCH
    return ("".join(digit_chars[:-1]) + table[d]).encode("ascii")


def sbin_to_zoned(b, n):
    """NUM-IMPT-002: signed big-endian binary -> zoned S9(n), low n digits."""
    i = int.from_bytes(b, "big", signed=True)
    digits = list("%0*d" % (n, abs(i) % (10 ** n)))
    return overpunch(digits, i < 0)


def nibbles(b):
    out = []
    for byte in b:
        out.append(byte >> 4)
        out.append(byte & 0x0F)
    return out


def packed_to_zoned(b, ndigits):
    """NUM-IMPT-003/006: signed packed -> zoned with ndigits digits.

    Any leading half-byte beyond ndigits (even precision pad) is ignored."""
    nb = nibbles(b)
    sign = nb[-1]
    digs = nb[-1 - ndigits:-1]
    chars = [chr(0x30 + h) for h in digs]
    return overpunch(chars, sign == 0x0D)


def packed_unsigned_digits(b, ndigits):
    """NUM-IMPT-004/006: packed -> unsigned digits; sign half-byte ignored."""
    nb = nibbles(b)
    digs = nb[-1 - ndigits:-1]
    return bytes(0x30 + h for h in digs)


# ---------------------------------------------------------------- mappings

def f(rec, pos, length):
    """Export field by 1-based position."""
    return rec[pos - 1:pos - 1 + length]


def build(size, parts):
    out = bytearray(b" " * size)
    for pos, data in parts:
        out[pos - 1:pos - 1 + len(data)] = data
    assert len(out) == size
    return bytes(out)


def map_customer(r):
    return build(500, [
        (1, ubin_to_digits(f(r, 41, 4), 9)),
        (10, f(r, 45, 25)),
        (35, f(r, 70, 25)),
        (60, f(r, 95, 25)),
        (85, f(r, 120, 50)),
        (135, f(r, 170, 50)),
        (185, f(r, 220, 50)),
        (235, f(r, 270, 2)),
        (237, f(r, 272, 3)),
        (240, f(r, 275, 10)),
        (250, f(r, 285, 15)),
        (265, f(r, 300, 15)),
        (280, f(r, 315, 9)),
        (289, f(r, 324, 20)),
        (309, f(r, 344, 10)),
        (319, f(r, 354, 10)),
        (329, f(r, 364, 1)),
        (330, packed_unsigned_digits(f(r, 365, 2), 3)),
    ])


def map_account(r):
    return build(300, [
        (1, f(r, 41, 11)),
        (12, f(r, 52, 1)),
        (13, packed_to_zoned(f(r, 53, 7), 12)),
        (25, f(r, 60, 12)),
        (37, packed_to_zoned(f(r, 72, 7), 12)),
        (49, f(r, 79, 10)),
        (59, f(r, 89, 10)),
        (69, f(r, 99, 10)),
        (79, f(r, 109, 12)),
        (91, sbin_to_zoned(f(r, 121, 8), 12)),
        (103, f(r, 129, 10)),
        (113, f(r, 139, 10)),
    ])


def map_xref(r):
    return build(50, [
        (1, f(r, 41, 16)),
        (17, f(r, 57, 9)),
        (26, ubin_to_digits(f(r, 66, 8), 11)),
    ])


def map_tran(r):
    return build(350, [
        (1, f(r, 41, 16)),
        (17, f(r, 57, 2)),
        (19, f(r, 59, 4)),
        (23, f(r, 63, 10)),
        (33, f(r, 73, 100)),
        (133, packed_to_zoned(f(r, 173, 6), 11)),
        (144, ubin_to_digits(f(r, 179, 4), 9)),
        (153, f(r, 183, 50)),
        (203, f(r, 233, 50)),
        (253, f(r, 283, 10)),
        (263, f(r, 293, 16)),
        (279, f(r, 309, 26)),
        (305, f(r, 335, 26)),
    ])


def map_card(r):
    return build(150, [
        (1, f(r, 41, 16)),
        (17, ubin_to_digits(f(r, 57, 8), 11)),
        (28, ubin_to_digits(f(r, 65, 2), 3)),
        (31, f(r, 67, 50)),
        (81, f(r, 117, 10)),
        (91, f(r, 127, 1)),
    ])


# type byte -> (output index, mapper, counter name)
DISPATCH = {
    b"C": (0, map_customer, "cust"),
    b"A": (1, map_account, "acct"),
    b"X": (2, map_xref, "xref"),
    b"T": (3, map_tran, "tran"),
    b"D": (4, map_card, "card"),
}


# ---------------------------------------------------------------- clock

class Clock:
    def __init__(self, text):
        date, tod = text.strip().split()
        y, mo, d = date.split("-")
        hh, mm, ss = tod.split(":")
        self.ymd = "%04d%02d%02d" % (int(y), int(mo), int(d))
        self.hms = "%02d%02d%02d" % (int(hh), int(mm), int(ss))

    def current_date(self):
        """FUNCTION CURRENT-DATE (21 chars). Date and time are pinned;
        hundredths and UTC offset come from the wall clock / process TZ."""
        now = time.time()
        hund = int(now * 100) % 100
        off = time.localtime(now).tm_gmtoff
        if off is None:
            tz = "00000"
        else:
            sign = "+" if off >= 0 else "-"
            off = abs(int(off)) // 60
            tz = "%s%02d%02d" % (sign, off // 60, off % 60)
        return "%s%s%02d%s" % (self.ymd, self.hms, hund, tz)


# ---------------------------------------------------------------- program

def load_export(path):
    """Open EXPFILE and return records in ascending key order, or None
    when the dataset does not exist (status 35)."""
    if not os.path.isfile(path):
        return None
    try:
        with open(path, "rb") as fh:
            data = fh.read()
    except OSError:
        return None
    recs = []
    seen = set()
    for i in range(0, len(data), REC_LEN):
        rec = data[i:i + REC_LEN]
        if len(rec) < REC_LEN:
            rec = rec + b" " * (REC_LEN - len(rec))
        key = rec[KEY_OFF:KEY_OFF + KEY_LEN]
        if key in seen:
            # A KSDS holds one record per key; the load keeps the first.
            continue
        seen.add(key)
        recs.append(rec)
    recs.sort(key=lambda r: r[KEY_OFF:KEY_OFF + KEY_LEN])
    return recs


def run(data_dir, out_dir, clock_text):
    os.makedirs(out_dir, exist_ok=True)
    clock = Clock(clock_text)
    stdout = []
    stderr = []
    handles = []
    rc = 0

    def display(line):
        stdout.append(line)

    def c9(v):
        return "%09d" % (v % 1000000000)

    counts = dict(read=0, cust=0, acct=0, xref=0, tran=0, card=0,
                  err=0, unk=0)
    try:
        display("CBIMPORT: Starting Customer Data Import")
        cd = [clock.current_date() for _ in range(6)]
        imp_date = "%s-%s-%s" % (cd[0][0:4], cd[1][4:6], cd[2][6:8])
        imp_time = "%s:%s:%s" % (cd[3][8:10], cd[4][10:12], cd[5][12:14])

        recs = load_export(os.path.join(data_dir, "EXPORT.ps"))
        if recs is None:
            display("ERROR: Cannot open EXPORT-INPUT, Status: 35")
            raise Abend()
        for dd, name, oname, _ in OUT_FILES:
            try:
                handles.append(open(os.path.join(out_dir, name), "wb"))
            except OSError:
                display("ERROR: Cannot open %s, Status: 30" % oname)
                raise Abend()

        display("CBIMPORT: Import Date: " + imp_date)
        display("CBIMPORT: Import Time: " + imp_time)

        for rec in recs:
            counts["read"] += 1
            t = rec[0:1]
            if t in DISPATCH:
                idx, mapper, cname = DISPATCH[t]
                out = mapper(rec)
                try:
                    handles[idx].write(out)
                except OSError:
                    display("ERROR: Writing %s record, Status: 30"
                            % OUT_FILES[idx][3])
                    raise Abend()
                counts[cname] += 1
            else:
                counts["unk"] += 1
                seq = int.from_bytes(rec[KEY_OFF:KEY_OFF + KEY_LEN], "big")
                ts = clock.current_date().encode("ascii")
                err = (ts.ljust(26, b" ") + b"|" + t + b"|"
                       + ("%07d" % (seq % 10000000)).encode("ascii") + b"|"
                       + b"Unknown record type encountered".ljust(50, b" ")
                       + b" " * 43)
                err = err.ljust(132, b" ")
                try:
                    handles[5].write(err)
                except OSError:
                    display("ERROR: Writing error record, Status: 30")
                counts["err"] += 1

        display("CBIMPORT: Import validation completed")
        display("CBIMPORT: No validation errors detected")
        for h in handles:
            try:
                h.close()
            except OSError:
                pass
        handles = []
        display("CBIMPORT: Import completed")
        display("CBIMPORT: Total Records Read: " + c9(counts["read"]))
        display("CBIMPORT: Customers Imported: " + c9(counts["cust"]))
        display("CBIMPORT: Accounts Imported: " + c9(counts["acct"]))
        display("CBIMPORT: XRefs Imported: " + c9(counts["xref"]))
        display("CBIMPORT: Transactions Imported: " + c9(counts["tran"]))
        display("CBIMPORT: Cards Imported: " + c9(counts["card"]))
        display("CBIMPORT: Errors Written: " + c9(counts["err"]))
        display("CBIMPORT: Unknown Record Types: " + c9(counts["unk"]))
        rc = 0
    except Abend:
        display("CBIMPORT: ABENDING PROGRAM")
        stderr.append("CEE3ABD: USER ABEND U0000")
        rc = 16
    finally:
        for h in handles:
            try:
                h.close()
            except OSError:
                pass

    def write_text(name, lines):
        with open(os.path.join(out_dir, name), "w", encoding="ascii",
                  newline="\n") as fh:
            for line in lines:
                fh.write(line + "\n")

    write_text("stdout.txt", stdout)
    write_text("stderr.txt", stderr)
    with open(os.path.join(out_dir, "rc.txt"), "w") as fh:
        fh.write("%d\n" % rc)
    return rc


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--clock", default="2022-07-06 12:00:00")
    a = ap.parse_args(argv)
    run(a.data, a.out, a.clock)
    return 0


if __name__ == "__main__":
    sys.exit(main())
