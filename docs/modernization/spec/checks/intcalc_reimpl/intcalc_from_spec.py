#!/usr/bin/env python3
"""Spec-only reimplementation of CBACT04C (job INTCALC, monthly interest).

Built from docs/modernization/spec/programs/CBACT04C.md and the entity
catalog only. Usage: python3 intcalc_from_spec.py --data DIR --out DIR
"""
import argparse
import datetime
import os
import sys

BUSINESS_DATE = datetime.date(2022, 7, 6)
DEFAULT_PARM = "2022071800"

POS_SIGN = "{ABCDEFGHI"
NEG_SIGN = "}JKLMNOPQR"


class Abend(Exception):
    pass


def decode_zoned(field):
    """Zoned decimal with overpunch sign in the last byte -> integer of units
    of the last digit (cents for V99 fields)."""
    value = 0
    for ch in field[:-1]:
        value = value * 10 + (ord(ch) & 0x0F)
    last = field[-1]
    negative = False
    if last in POS_SIGN:
        digit = POS_SIGN.index(last)
    elif last in NEG_SIGN:
        digit = NEG_SIGN.index(last)
        negative = True
    else:
        digit = ord(last) & 0x0F
    value = value * 10 + digit
    return -value if negative else value


def encode_zoned(value, length, negative_zero=False):
    """Integer (implied decimals) -> zoned text of `length` digits, sign folded
    into the last byte. Values that do not fit keep their low-order digits and
    their sign (no size-error handling)."""
    negative = value < 0
    mag = abs(value) % (10 ** length)
    if mag == 0:
        negative = negative_zero and negative
    digits = str(mag).rjust(length, "0")
    table = NEG_SIGN if negative else POS_SIGN
    return digits[:-1] + table[int(digits[-1])]


def store(value, length):
    """Store a computed result into a S9(n) field: a true zero is '{', an
    overflow that leaves zero digits keeps the sign (NUM-POST-003)."""
    return encode_zoned(value, length, negative_zero=(value != 0))


def load_records(path, reclen):
    recs = []
    with open(path, "rb") as fh:
        data = fh.read().decode("latin-1")
    for line in data.split("\n"):
        if line.endswith("\r"):
            line = line[:-1]
        if line == "":
            continue
        recs.append(line[:reclen].ljust(reclen))
    return recs


def load_ksds(path, reclen, keyslice):
    """Load an indexed file: one record per key (first occurrence kept),
    returned as a dict and the sorted list of keys."""
    table = {}
    for rec in load_records(path, reclen):
        key = rec[keyslice]
        if key not in table:
            table[key] = rec
    return table, sorted(table)


def file_status_text(status):
    if status.isdigit() and status[0] != "9":
        return "FILE STATUS IS: NNNN" + "00" + status
    return "FILE STATUS IS: NNNN" + status[0] + str(ord(status[1]) % 256).rjust(3, "0")[-3:]


def timestamp():
    now = datetime.datetime.now()
    hh = now.microsecond // 10000
    return "%s-%02d.%02d.%02d.%02d0000" % (
        BUSINESS_DATE.isoformat(), now.hour, now.minute, now.second, hh)


class Run:
    def __init__(self, data_dir, out_dir):
        self.data_dir = data_dir
        self.out_dir = out_dir
        self.sysout = []
        self.transact = []
        self.accounts = None
        self.account_keys = None
        self.rc = 0
        self.stderr = ""

    def display(self, line):
        self.sysout.append(line)

    def abend(self):
        self.display("ABENDING PROGRAM")
        self.stderr = "CEE3ABD: USER ABEND U0999\n"
        self.rc = 16
        raise Abend()

    def io_error(self, message, status):
        self.display(message)
        self.display(file_status_text(status))
        self.abend()

    def open_ksds(self, name, reclen, keyslice, message, xref=False):
        path = os.path.join(self.data_dir, name)
        if not os.path.isfile(path):
            status = "35"
            if xref:
                self.display(message + status)
                self.display(file_status_text(status))
                self.abend()
            self.io_error(message, status)
        return load_ksds(path, reclen, keyslice)

    def read_parm(self):
        path = os.path.join(self.data_dir, "parm.txt")
        if not os.path.isfile(path):
            return DEFAULT_PARM
        with open(path, "rb") as fh:
            text = fh.read().decode("latin-1")
        first = text.split("\n", 1)[0]
        return first.rstrip("\r\n")

    def main(self):
        parm_date = self.read_parm()[:10].ljust(10)
        # One clock reading for the whole run, so every interest transaction
        # carries the same timestamp in both fields (NUM-INTC-005).
        ts = timestamp()
        self.display("START OF EXECUTION OF PROGRAM CBACT04C")

        tcat, tcat_keys = self.open_ksds(
            "tcatbal.txt", 50, slice(0, 17),
            "ERROR OPENING TRANSACTION CATEGORY BALANCE")
        xref, xref_keys = self.open_ksds(
            "cardxref.txt", 50, slice(0, 16),
            "ERROR OPENING CROSS REF FILE", xref=True)
        # Alternate index XREF-ACCT-ID: first card in card-number order.
        xref_by_acct = {}
        for key in xref_keys:
            rec = xref[key]
            xref_by_acct.setdefault(rec[25:36], rec)
        disc, _ = self.open_ksds(
            "discgrp.txt", 50, slice(0, 16),
            "ERROR OPENING DALY REJECTS FILE")
        self.accounts, self.account_keys = self.open_ksds(
            "acctdata.txt", 300, slice(0, 11),
            "ERROR OPENING ACCOUNT MASTER FILE")

        last_acct = " " * 11
        first_time = True
        total_int = 0
        tranid_suffix = 0
        acct_rec = None
        xref_rec = None

        for key in tcat_keys:
            rec = tcat[key]
            self.display(rec)
            acct_id = rec[0:11]
            type_cd = rec[11:13]
            cat_cd = rec[13:17]
            bal = decode_zoned(rec[17:28])

            if acct_id != last_acct:
                if not first_time:
                    self.update_account(acct_rec, total_int)
                else:
                    first_time = False
                total_int = 0
                last_acct = acct_id
                acct_rec = self.accounts.get(acct_id)
                if acct_rec is None:
                    self.display("ACCOUNT NOT FOUND: " + acct_id)
                    self.io_error("ERROR READING ACCOUNT FILE", "23")
                xref_rec = xref_by_acct.get(acct_id)
                if xref_rec is None:
                    self.display("ACCOUNT NOT FOUND: " + acct_id)
                    self.io_error("ERROR READING XREF FILE", "23")

            group = acct_rec[112:122]
            drec = disc.get(group + type_cd + cat_cd)
            if drec is None:
                self.display("DISCLOSURE GROUP RECORD MISSING")
                self.display("TRY WITH DEFAULT GROUP CODE")
                drec = disc.get("DEFAULT".ljust(10) + type_cd + cat_cd)
                if drec is None:
                    self.io_error("ERROR READING DEFAULT DISCLOSURE GROUP", "23")
            rate = decode_zoned(drec[16:22])

            if rate != 0:
                exact_num = bal * rate
                monthly = abs(exact_num) // 120000
                if exact_num < 0:
                    monthly = -monthly
                monthly_field = store(monthly, 11)
                monthly = decode_zoned(monthly_field)
                total_int = decode_zoned(store(total_int + monthly, 11))

                tranid_suffix = (tranid_suffix + 1) % 1000000
                tran = (
                    parm_date + str(tranid_suffix).rjust(6, "0")
                    + "01"
                    + "0005"
                    + "System".ljust(10)
                    + ("Int. for a/c " + acct_rec[0:11]).ljust(100)
                    + monthly_field
                    + "000000000"
                    + " " * 110
                    + xref_rec[0:16]
                    + ts
                    + ts
                    + " " * 20
                )
                assert len(tran) == 350
                self.transact.append(tran)

        if not first_time:
            self.update_account(acct_rec, total_int)

        self.display("END OF EXECUTION OF PROGRAM CBACT04C")

    def update_account(self, acct_rec, total_int):
        cur = decode_zoned(acct_rec[12:24])
        new_bal = store(cur + total_int, 12)
        zero = encode_zoned(0, 12)
        updated = acct_rec[:12] + new_bal + acct_rec[24:78] + zero + zero + acct_rec[102:]
        assert len(updated) == 300
        self.accounts[updated[0:11]] = updated

    def write_outputs(self):
        os.makedirs(self.out_dir, exist_ok=True)

        def put(name, text):
            with open(os.path.join(self.out_dir, name), "wb") as fh:
                fh.write(text.encode("latin-1"))

        put("TRANSACT.txt", "".join(t + "\n" for t in self.transact))
        if self.accounts is not None:
            put("ACCTDATA.after.txt",
                "".join(self.accounts[k] + "\n" for k in sorted(self.accounts)))
        else:
            src = os.path.join(self.data_dir, "acctdata.txt")
            if os.path.isfile(src):
                table, keys = load_ksds(src, 300, slice(0, 11))
                put("ACCTDATA.after.txt", "".join(table[k] + "\n" for k in keys))
            else:
                put("ACCTDATA.after.txt", "")
        put("sysout.txt", "".join(line + "\n" for line in self.sysout))
        put("rc.txt", "%d\n" % self.rc)
        put("stderr.txt", self.stderr)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args(argv)
    run = Run(args.data, args.out)
    try:
        run.main()
    except Abend:
        pass
    run.write_outputs()
    return 0


if __name__ == "__main__":
    sys.exit(main())
