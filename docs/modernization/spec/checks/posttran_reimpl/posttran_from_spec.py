#!/usr/bin/env python3
"""POSTTRAN (CBTRN02C) reimplemented from the natural-language spec only.

Usage: python3 posttran_from_spec.py [--data DIR] --out DIR
"""
import argparse
import datetime
import os
import sys
from decimal import Decimal

BUSINESS_DATE = "2022-07-06"

# Zoned decimal overpunch (format.md glossary, NUM-POST-001).
POS_OVERPUNCH = "{ABCDEFGHI"
NEG_OVERPUNCH = "}JKLMNOPQR"

REASON_TEXT = {
    100: "INVALID CARD NUMBER FOUND",
    101: "ACCOUNT RECORD NOT FOUND",
    102: "OVERLIMIT TRANSACTION",
    103: "TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
}


class Abend(Exception):
    pass


def zoned_decode(field, scale=2):
    digits = field[:-1]
    last = field[-1]
    if last in POS_OVERPUNCH:
        sign, d = 1, POS_OVERPUNCH.index(last)
    elif last in NEG_OVERPUNCH:
        sign, d = -1, NEG_OVERPUNCH.index(last)
    elif last.isdigit():
        sign, d = 1, int(last)
    else:
        raise ValueError("bad zoned sign byte %r in %r" % (last, field))
    if not digits.isdigit() and digits != "":
        raise ValueError("bad zoned digits %r" % field)
    cents = int(digits + str(d)) * sign
    value = Decimal(cents).scaleb(-scale)
    if value == 0:
        value = Decimal(0).scaleb(-scale)  # negative zero is zero
    return value


def zoned_encode(value, length, scale=2):
    units = int((value.scaleb(scale)).to_integral_value())
    neg = units < 0
    mag = abs(units) % (10 ** length)  # NUM-POST-003: keep low-order digits
    if mag == 0:
        neg = False
    s = str(mag).zfill(length)
    table = NEG_OVERPUNCH if neg else POS_OVERPUNCH
    return s[:-1] + table[int(s[-1])]


def read_records(path, reclen, pad=False):
    with open(path, "rb") as fh:
        data = fh.read().decode("latin-1")
    lines = data.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    recs = []
    for ln in lines:
        if ln.endswith("\r"):
            ln = ln[:-1]
        if len(ln) != reclen:
            if pad and len(ln) < reclen:
                ln = ln.ljust(reclen)
            else:
                raise ValueError("%s: record length %d, expected %d" % (path, len(ln), reclen))
        recs.append(ln)
    return recs


def load_ksds(path, reclen, keylen, pad=False):
    table = {}
    dups = 0
    for rec in read_records(path, reclen, pad):
        key = rec[:keylen]
        if key in table:
            dups += 1
            continue
        table[key] = rec
    return table, dups


def proc_timestamp():
    now = datetime.datetime.now()
    return "%s-%02d.%02d.%02d.%02d0000" % (
        BUSINESS_DATE, now.hour, now.minute, now.second, now.microsecond // 10000)


def main():
    repo = os.path.abspath(os.path.join(os.path.dirname(__file__), *[os.pardir] * 5))
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default=os.path.join(repo, "app", "data", "ASCII"))
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    sysout = []
    sysout.append("START OF EXECUTION OF PROGRAM CBTRN02C")

    daily = read_records(os.path.join(args.data, "dailytran.txt"), 350)
    # cardxref.txt lines are 36 bytes; the record is 50 (FILLER 37-50), so pad.
    xref, xref_dups = load_ksds(os.path.join(args.data, "cardxref.txt"), 50, 16, pad=True)
    acct, acct_dups = load_ksds(os.path.join(args.data, "acctdata.txt"), 300, 11)
    tcat, tcat_dups = load_ksds(os.path.join(args.data, "tcatbal.txt"), 50, 17)
    tran = {}
    rejects = []

    processed = 0
    rejected = 0
    reason_counts = {}
    new_tcat = 0
    posted = 0
    # DEF-POST-010: FILLER of the last TCATBAL record read or written;
    # before any, low-values.
    last_tcat_filler = "\x00" * 22
    rc = 0

    try:
        for drec in daily:
            processed += 1
            reason = 0
            card = drec[262:278]
            amt = zoned_decode(drec[132:143])
            orig_date = drec[278:288]

            xrec = xref.get(card)
            arec = None
            if xrec is None:
                reason = 100
            else:
                acct_id = xrec[25:36]
                arec = acct.get(acct_id)
                if arec is None:
                    reason = 101
                else:
                    credit_limit = zoned_decode(arec[24:36])
                    cyc_credit = zoned_decode(arec[78:90])
                    cyc_debit = zoned_decode(arec[90:102])
                    temp_bal = cyc_credit - cyc_debit + amt
                    if temp_bal > credit_limit:
                        reason = 102
                    exp_date = arec[58:68]
                    if exp_date < orig_date:
                        reason = 103

            if reason == 0:
                # SE-POST-001 / BR-POST-004
                key = acct_id + drec[16:18] + drec[18:22]
                trec = tcat.get(key)
                if trec is not None:
                    last_tcat_filler = trec[28:50]
                    bal = zoned_decode(trec[17:28]) + amt
                    tcat[key] = trec[:17] + zoned_encode(bal, 11) + trec[28:50]
                else:
                    sysout.append("TCATBAL record not found for key : %s.. Creating." % key)
                    tcat[key] = key + zoned_encode(amt, 11) + last_tcat_filler
                    new_tcat += 1
                # SE-POST-002 / BR-POST-005
                curr_bal = zoned_decode(arec[12:24]) + amt
                r = list(arec)
                r[12:24] = zoned_encode(curr_bal, 12)
                if amt >= 0:
                    r[78:90] = zoned_encode(cyc_credit + amt, 12)
                else:
                    r[90:102] = zoned_encode(cyc_debit + amt, 12)
                acct[acct_id] = "".join(r)
                # SE-POST-003 / BR-POST-006
                tid = drec[:16]
                trec_out = drec[:304] + proc_timestamp() + " " * 20
                if tid in tran:
                    sysout.append("ERROR WRITING TO TRANSACTION FILE")
                    sysout.append("FILE STATUS IS: NNNN0022")
                    raise Abend()
                tran[tid] = trec_out
                posted += 1
            else:
                rejected += 1
                reason_counts[reason] = reason_counts.get(reason, 0) + 1
                rejects.append(drec + "%04d" % reason + REASON_TEXT[reason].ljust(76))

        sysout.append("TRANSACTIONS PROCESSED :%09d" % processed)
        sysout.append("TRANSACTIONS REJECTED  :%09d" % rejected)
        rc = 4 if rejected > 0 else 0
        sysout.append("END OF EXECUTION OF PROGRAM CBTRN02C")
    except Abend:
        sysout.append("ABENDING PROGRAM")
        print("CEE3ABD: USER ABEND U0999", file=sys.stderr)
        rc = 16

    os.makedirs(args.out, exist_ok=True)

    def write(name, recs):
        with open(os.path.join(args.out, name), "wb") as fh:
            for rec in recs:
                fh.write(rec.encode("latin-1") + b"\n")

    write("DALYREJS.txt", rejects)
    write("TRANSACT.after.txt", [tran[k] for k in sorted(tran)])
    write("ACCTDATA.after.txt", [acct[k] for k in sorted(acct)])
    write("TCATBALF.after.txt", [tcat[k] for k in sorted(tcat)])
    write("sysout.txt", sysout)
    with open(os.path.join(args.out, "rc.txt"), "w") as fh:
        fh.write("%d\n" % rc)

    print("processed=%d posted=%d rejected=%d by_reason=%s new_tcat=%d tcat_total=%d rc=%d "
          "input_dups(xref,acct,tcat)=%d,%d,%d" % (
              processed, posted, rejected, dict(sorted(reason_counts.items())), new_tcat,
              len(tcat), rc, xref_dups, acct_dups, tcat_dups), file=sys.stderr)
    return rc


if __name__ == "__main__":
    sys.exit(main())
