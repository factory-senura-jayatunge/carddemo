#!/usr/bin/env python3
"""Spec-only reimplementation of CBACT01C (job READACCT).

Usage: python3 readacct_from_spec.py --data DIR --out DIR
"""
import argparse
import os
import sys

REC_LEN = 300
KEY_LEN = 11
# (offset, length) of the S9(10)V99 zoned amounts in ENT-ACCOUNT
AMOUNTS = {
    "CURR-BAL": 12,
    "CREDIT-LIMIT": 24,
    "CASH-CREDIT-LIMIT": 36,
    "CURR-CYC-CREDIT": 78,
    "CURR-CYC-DEBIT": 90,
}
POS_OVER = "{ABCDEFGHI"
NEG_OVER = "}JKLMNOPQR"


def load_ksds(path):
    records = {}
    with open(path, "rb") as fh:
        for raw in fh.read().decode("latin-1").split("\n"):
            if raw.endswith("\r"):
                raw = raw[:-1]
            if raw == "":
                continue
            rec = raw[:REC_LEN].ljust(REC_LEN)
            key = rec[:KEY_LEN]
            # Loading a KSDS rejects a duplicate key; the first record stays.
            records.setdefault(key, rec)
    return [records[k] for k in sorted(records, key=lambda k: k.encode("latin-1"))]


def decode_zoned(field):
    """Return (digits:str of 12, negative:bool) for an S9(10)V99 field."""
    body, last = field[:-1], field[-1]
    if last in POS_OVER:
        return body + str(POS_OVER.index(last)), False
    if last in NEG_OVER:
        return body + str(NEG_OVER.index(last)), True
    if last.isdigit():
        return body + last, False
    # Not described by the spec: use the low nibble of the byte as the digit.
    return body + str((ord(last) & 0x0F) % 10), False


def encode_zoned(digits, negative):
    d = int(digits[-1]) if digits[-1].isdigit() else 0
    return digits[:-1] + (NEG_OVER[d] if negative else POS_OVER[d])


def digit_value(digits):
    v = 0
    for ch in digits:
        v = v * 10 + ((ord(ch) & 0x0F) % 10 if not ch.isdigit() else int(ch))
    return v


def pack(value_cents, negative):
    """PIC S9(10)V99 COMP-3: 12 digits + sign nibble in 7 bytes."""
    s = "%012d" % abs(value_cents)
    s = s[-12:]
    nibbles = [int(c) for c in s] + [0xD if (negative and value_cents != 0) else 0xC]
    nibbles = [0] + nibbles  # 13 nibbles + leading pad = 14 nibbles
    return bytes((nibbles[i] << 4) | nibbles[i + 1] for i in range(0, 14, 2))


def display_signed(digits, negative):
    return digits + ("-" if negative else "+")


def cobdatft_type2_to_type2(date10):
    out = [" "] * 10
    out[0:4] = date10[0:4]
    out[4:6] = date10[5:7]
    out[6:8] = date10[8:10]
    return "".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    src = os.path.join(a.data, "acctdata.txt")
    sysout = []
    outfile, arryfile, vbrcfile = [], [], []
    rc = 0
    stderr = []

    sysout.append("START OF EXECUTION OF PROGRAM CBACT01C")
    accounts = None
    try:
        accounts = load_ksds(src)
    except OSError:
        accounts = None
    if accounts is None:
        # ERR-ACRD-001: ACCTFILE open failure (status 35, file not found).
        sysout.append("ERROR OPENING ACCTFILE")
        sysout.append("FILE STATUS IS: NNNN0035")
        sysout.append("ABENDING PROGRAM")
        stderr.append("CEE3ABD: USER ABEND U0999")
        rc = 16
        original = []
    else:
        original = list(accounts)
        for rec in accounts:
            area = list(rec)
            dec = {}
            for name, off in AMOUNTS.items():
                digits, neg = decode_zoned(rec[off:off + 12])
                dec[name] = (digits, neg)
                # NUM-ACRD-004: a plain-digit sign byte is rewritten in the
                # record area with the positive overpunch.
                if rec[off + 11].isdigit():
                    area[off + 11] = POS_OVER[int(rec[off + 11])]
            acct_id = rec[0:11]
            status = rec[11]
            open_d, exp_d, reis_d = rec[48:58], rec[58:68], rec[68:78]
            group = rec[112:122]

            def z(name):
                return encode_zoned(*dec[name])

            def disp(name):
                return display_signed(*dec[name])

            sysout.append("ACCT-ID                 :" + acct_id)
            sysout.append("ACCT-ACTIVE-STATUS      :" + status)
            sysout.append("ACCT-CURR-BAL           :" + disp("CURR-BAL"))
            sysout.append("ACCT-CREDIT-LIMIT       :" + disp("CREDIT-LIMIT"))
            sysout.append("ACCT-CASH-CREDIT-LIMIT  :" + disp("CASH-CREDIT-LIMIT"))
            sysout.append("ACCT-OPEN-DATE          :" + open_d)
            sysout.append("ACCT-EXPIRAION-DATE     :" + exp_d)
            sysout.append("ACCT-REISSUE-DATE       :" + reis_d)
            sysout.append("ACCT-CURR-CYC-CREDIT    :" + disp("CURR-CYC-CREDIT"))
            sysout.append("ACCT-CURR-CYC-DEBIT     :" + disp("CURR-CYC-DEBIT"))
            sysout.append("ACCT-GROUP-ID           :" + group)
            sysout.append("-" * 49)

            # BR-ACRD-003/004/005
            dd, dneg = dec["CURR-CYC-DEBIT"]
            dval = digit_value(dd)
            if dval == 0:
                debit_packed = pack(252500, False)
            else:
                debit_packed = pack(dval, dneg)
            out = (acct_id + status + z("CURR-BAL") + z("CREDIT-LIMIT")
                   + z("CASH-CREDIT-LIMIT") + open_d + exp_d
                   + cobdatft_type2_to_type2(reis_d) + z("CURR-CYC-CREDIT")
                   ).encode("latin-1") + debit_packed + group.encode("latin-1")
            assert len(out) == 107
            outfile.append(out)

            # BR-ACRD-006 / NUM-ACRD-003
            bal = z("CURR-BAL").encode("latin-1")
            cleared = b"000000000000" + pack(0, False)
            arr = (acct_id.encode("latin-1")
                   + bal + pack(100500, False)
                   + bal + pack(152500, False)
                   + encode_zoned("000000102500", True).encode("latin-1")
                   + pack(250000, True)
                   + cleared + cleared + b"    ")
            assert len(arr) == 110
            arryfile.append(arr)

            # BR-ACRD-007
            v1 = acct_id + status
            v2 = acct_id + z("CURR-BAL") + z("CREDIT-LIMIT") + reis_d[0:4]
            sysout.append("VBRC-REC1:" + v1)
            sysout.append("VBRC-REC2:" + v2)
            vbrcfile.append(v1.encode("latin-1"))
            vbrcfile.append(v2.encode("latin-1"))

            # BR-ACRD-008
            sysout.append("".join(area))
        sysout.append("END OF EXECUTION OF PROGRAM CBACT01C")
        stderr.append("libcob: warning: implicit CLOSE of VBRC-FILE ('VBRCFILE')")
        stderr.append("libcob: warning: implicit CLOSE of ARRY-FILE ('ARRYFILE')")
        stderr.append("libcob: warning: implicit CLOSE of OUT-FILE ('OUTFILE')")

    def write_hex(name, recs):
        with open(os.path.join(a.out, name), "w", encoding="ascii", newline="\n") as fh:
            for r in recs:
                fh.write(r.hex() + "\n")

    write_hex("OUTFILE.hex", outfile)
    write_hex("ARRYFILE.hex", arryfile)
    write_hex("VBRCFILE.hex", vbrcfile)
    with open(os.path.join(a.out, "ACCTDATA.after.txt"), "wb") as fh:
        for r in original:
            fh.write(r.encode("latin-1") + b"\n")
    with open(os.path.join(a.out, "sysout.txt"), "wb") as fh:
        for line in sysout:
            fh.write(line.encode("latin-1") + b"\n")
    with open(os.path.join(a.out, "rc.txt"), "w", newline="\n") as fh:
        fh.write("%d\n" % rc)
    with open(os.path.join(a.out, "stderr.txt"), "w", newline="\n") as fh:
        for line in stderr:
            fh.write(line + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
