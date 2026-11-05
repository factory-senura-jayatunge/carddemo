#!/usr/bin/env python3
"""Reimplementation of CBEXPORT (customer data export) from its NL spec.

Usage:
    python3 cbexport_from_spec.py --data SCENARIO_DIR --out OUT_DIR \
        --clock "YYYY-MM-DD HH:MM:SS"
"""
import argparse
import os
import sys

REC_LEN_EXPORT = 500

# (dataset file, record length, key length, open-error name, read-error name)
INPUTS = [
    ("CUSTDATA.ps", 500, 9, "CUSTOMER-INPUT"),
    ("ACCTDATA.ps", 300, 11, "ACCOUNT-INPUT"),
    ("CARDXREF.ps", 50, 16, "XREF-INPUT"),
    ("TRANSACT.ps", 350, 16, "TRANSACTION-INPUT"),
    ("CARDDATA.ps", 150, 16, "CARD-INPUT"),
]


class Abend(Exception):
    pass


# ---------------------------------------------------------------- numerics

def _low(b):
    return b & 0x0F


def _sign_byte(b):
    """Last byte of a signed zoned field -> (last digit nibble, negative).

    ASCII overpunch table of NUM-EXPT-003; anything else counts as positive
    with its low nibble as the digit when 0-9, else 0 (NUM-EXPT-006).
    """
    if b == 0x7B:  # '{'
        return 0, False
    if 0x41 <= b <= 0x49:  # 'A'-'I'
        return b - 0x40, False
    if b == 0x7D:  # '}'
        return 0, True
    if 0x4A <= b <= 0x52:  # 'J'-'R'
        return b - 0x49, True
    if 0x30 <= b <= 0x39:
        return b - 0x30, False
    lo = b & 0x0F
    return (lo if lo <= 9 else 0), False


def signed_nibbles(field):
    """Digit nibbles (non-sign bytes keep their low 4 bits) and sign."""
    nibs = [_low(b) for b in field[:-1]]
    last, neg = _sign_byte(field[-1])
    nibs.append(last)
    return nibs, neg


def pack_nibbles(nibs, sign_nibble):
    allnibs = list(nibs) + [sign_nibble]
    if len(allnibs) % 2:
        allnibs.insert(0, 0)
    out = bytearray()
    for i in range(0, len(allnibs), 2):
        out.append((allnibs[i] << 4) | allnibs[i + 1])
    return bytes(out)


def signed_zoned_to_packed(field):
    nibs, neg = signed_nibbles(field)
    return pack_nibbles(nibs, 0xD if neg else 0xC)


def unsigned_zoned_to_packed(field):
    return pack_nibbles([_low(b) for b in field], 0xF)


def accumulate(nibs):
    v = 0
    for n in nibs:
        v = v * 10 + n
    return v


def unsigned_zoned_to_binary(field, size):
    v = accumulate(_low(b) for b in field)
    return (v % (1 << (8 * size))).to_bytes(size, "big")


def signed_zoned_to_binary(field, size):
    nibs, neg = signed_nibbles(field)
    v = accumulate(nibs)
    if neg:
        v = -v
    return (v % (1 << (8 * size))).to_bytes(size, "big")


# ---------------------------------------------------------- record builders

def header(rtype, timestamp, seq):
    return (rtype + timestamp + seq.to_bytes(4, "big") + b"0001" + b"NORTH")


def build_customer(r):
    d = (unsigned_zoned_to_binary(r[0:9], 4)       # EXP-CUST-ID 41-44
         + r[9:329]                                # 45-364 byte copy
         + unsigned_zoned_to_packed(r[329:332]))   # FICO 365-366
    return d


def build_account(r):
    d = (r[0:11]                                   # ACCT-ID 41-51
         + r[11:12]                                # status 52
         + signed_zoned_to_packed(r[12:24])        # CURR-BAL 53-59
         + r[24:36]                                # CREDIT-LIMIT 60-71
         + signed_zoned_to_packed(r[36:48])        # CASH-CREDIT-LIMIT 72-78
         + r[48:78]                                # three dates 79-108
         + r[78:90]                                # CYC-CREDIT 109-120
         + signed_zoned_to_binary(r[90:102], 8)    # CYC-DEBIT 121-128
         + r[102:122])                             # ZIP, GROUP-ID 129-148
    return d


def build_xref(r):
    return (r[0:16]                                # card 41-56
            + r[16:25]                             # cust id 57-65
            + unsigned_zoned_to_binary(r[25:36], 8))  # acct id 66-73


def build_tran(r):
    return (r[0:132]                               # 41-172
            + signed_zoned_to_packed(r[132:143])   # amount 173-178
            + unsigned_zoned_to_binary(r[143:152], 4)  # merchant id 179-182
            + r[152:330])                          # 183-360


def build_card(r):
    return (r[0:16]                                # card num 41-56
            + unsigned_zoned_to_binary(r[16:27], 8)    # acct id 57-64
            + unsigned_zoned_to_binary(r[27:30], 2)    # CVV 65-66
            + r[30:91])                            # 67-127


TYPES = [
    # letter, builder, processing text, per-type count label
    (b"C", build_customer, "customer", "Customers exported: "),
    (b"A", build_account, "account", "Accounts exported: "),
    (b"X", build_xref, "cross-reference", "Cross-references exported: "),
    (b"T", build_tran, "transaction", "Transactions exported: "),
    (b"D", build_card, "card", "Cards exported: "),
]


# --------------------------------------------------------------- data load

def load_ksds(path, rec_len, key_len):
    """Return records in ascending key order, or None if the dataset is
    absent. Duplicate keys keep the first occurrence; a short trailing
    record is padded with spaces (decisions recorded in notes.md)."""
    if not os.path.isfile(path):
        return None
    with open(path, "rb") as fh:
        data = fh.read()
    recs = {}
    for i in range(0, len(data), rec_len):
        rec = data[i:i + rec_len]
        if len(rec) < rec_len:
            rec = rec + b" " * (rec_len - len(rec))
        key = rec[:key_len]
        if key not in recs:
            recs[key] = rec
    return [recs[k] for k in sorted(recs)]


# -------------------------------------------------------------------- main

def run(data_dir, clock):
    out = []          # DISPLAY lines
    export = []       # export records written
    created = False

    date = clock[0:10]
    time = clock[11:19]
    timestamp = (date + " " + time + ".00").encode("ascii") + b"\x00" * 4
    assert len(timestamp) == 26

    def display(s):
        out.append(s)

    def abend():
        display("CBEXPORT: ABENDING PROGRAM")
        raise Abend()

    try:
        display("CBEXPORT: Starting Customer Data Export")
        files = []
        for fname, rec_len, key_len, name in INPUTS:
            recs = load_ksds(os.path.join(data_dir, fname), rec_len, key_len)
            if recs is None:
                display("ERROR: Cannot open %s, Status: 35" % name)
                abend()
            files.append(recs)
        created = True  # EXPFILE opened OUTPUT: the file now exists
        display("CBEXPORT: Export Date: " + date)
        display("CBEXPORT: Export Time: " + time)

        seq = 0
        counts = []
        for (letter, builder, kind, label), recs in zip(TYPES, files):
            display("CBEXPORT: Processing %s records" % kind)
            n = 0
            for r in recs:
                seq = (seq + 1) % 1000000000
                data = builder(r)
                data = data + b" " * (460 - len(data))
                rec = header(letter, timestamp, seq) + data
                assert len(rec) == REC_LEN_EXPORT
                export.append(rec)
                n = (n + 1) % 1000000000
            counts.append(n)
            display("CBEXPORT: %s%09d" % (label, n))

        total = sum(counts) % 1000000000
        display("CBEXPORT: Export completed")
        display("CBEXPORT: Customers Exported: %09d" % counts[0])
        display("CBEXPORT: Accounts Exported: %09d" % counts[1])
        display("CBEXPORT: XRefs Exported: %09d" % counts[2])
        display("CBEXPORT: Transactions Exported: %09d" % counts[3])
        display("CBEXPORT: Cards Exported: %09d" % counts[4])
        display("CBEXPORT: Total Records Exported: %09d" % total)
        return out, export, created, "", 0
    except Abend:
        return out, export, created, "CEE3ABD: USER ABEND U0000\n", 16


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--clock", required=True)
    a = ap.parse_args(argv)

    lines, export, created, stderr, rc = run(a.data, a.clock)
    os.makedirs(a.out, exist_ok=True)
    exp_path = os.path.join(a.out, "EXPORT.ps")
    if created:
        with open(exp_path, "wb") as fh:
            fh.write(b"".join(export))
    elif os.path.exists(exp_path):
        os.remove(exp_path)
    with open(os.path.join(a.out, "stdout.txt"), "w", newline="\n") as fh:
        fh.write("".join(l + "\n" for l in lines))
    with open(os.path.join(a.out, "stderr.txt"), "w", newline="\n") as fh:
        fh.write(stderr)
    with open(os.path.join(a.out, "rc.txt"), "w", newline="\n") as fh:
        fh.write("%d\n" % rc)
    return 0


if __name__ == "__main__":
    sys.exit(main())
