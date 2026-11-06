#!/usr/bin/env python3
"""Spec-only reimplementation of CBTRN01C (job TRANVALD)."""
import argparse
import os
import sys

DALY_LEN, CUST_LEN, XREF_LEN, CARD_LEN, ACCT_LEN = 350, 500, 50, 150, 300


class Abend(Exception):
    pass


def read_records(path, reclen):
    recs = []
    with open(path, "rb") as fh:
        for raw in fh.read().decode("latin-1").split("\n"):
            if raw.endswith("\r"):
                raw = raw[:-1]
            if raw == "":
                continue
            recs.append(raw[:reclen].ljust(reclen))
    return recs


def load_indexed(path, reclen, kstart, klen):
    table = {}
    for rec in read_records(path, reclen):
        table.setdefault(rec[kstart:kstart + klen], rec)
    return table


def status_text(status):
    # ERR-TVAL-004 / ERR-POST-007
    if status.isdigit() and status[0] != "9":
        return "FILE STATUS IS: NNNN" + "00" + status
    return "FILE STATUS IS: NNNN" + status[0] + "%03d" % ord(status[1])


def run(data, out_lines):
    disp = out_lines.append

    def fail(msg, status):
        disp(msg)
        disp(status_text(status))
        disp("ABENDING PROGRAM")
        raise Abend()

    disp("START OF EXECUTION OF PROGRAM CBTRN01C")

    def path(name):
        return os.path.join(data, name)

    # Opens in order: DALYTRAN, CUSTFILE, XREFFILE, CARDFILE, ACCTFILE, TRANFILE.
    if not os.path.isfile(path("dailytran.txt")):
        fail("ERROR OPENING DAILY TRANSACTION FILE", "35")
    daily = read_records(path("dailytran.txt"), DALY_LEN)
    if not os.path.isfile(path("custdata.txt")):
        fail("ERROR OPENING CUSTOMER FILE", "35")
    if not os.path.isfile(path("cardxref.txt")):
        fail("ERROR OPENING CROSS REF FILE", "35")
    xref = load_indexed(path("cardxref.txt"), XREF_LEN, 0, 16)
    if not os.path.isfile(path("carddata.txt")):
        fail("ERROR OPENING CARD FILE", "35")
    if not os.path.isfile(path("acctdata.txt")):
        fail("ERROR OPENING ACCOUNT FILE", "35")
    acct = load_indexed(path("acctdata.txt"), ACCT_LEN, 0, 11)
    # TRANFILE exists as an empty indexed file; its open always succeeds.

    for rec in daily:
        disp(rec)  # BR-TVAL-001
        card_num = rec[262:278]
        tran_id = rec[0:16]
        x = xref.get(card_num)  # BR-TVAL-002
        if x is None:  # BR-TVAL-004
            disp("INVALID CARD NUMBER FOR XREF")
            disp("CARD NUMBER " + card_num
                 + " COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-" + tran_id)
            continue
        x_card, x_cust, x_acct = x[0:16], x[16:25], x[25:36]
        disp("SUCCESSFUL READ OF XREF")
        disp("CARD NUMBER: " + x_card)
        disp("ACCOUNT ID : " + x_acct)
        disp("CUSTOMER ID: " + x_cust)
        if x_acct in acct:  # BR-TVAL-003
            disp("SUCCESSFUL READ OF ACCOUNT FILE")
        else:
            disp("INVALID ACCOUNT NUMBER FOUND")
            disp("ACCOUNT " + x_acct + " NOT FOUND")

    # Closes cannot fail locally (ERR-TVAL-001 close branch not reachable).
    disp("END OF EXECUTION OF PROGRAM CBTRN01C")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    lines = []
    rc, err = 0, ""
    try:
        run(a.data, lines)
    except Abend:
        rc, err = 16, "CEE3ABD: USER ABEND U0999\n"
    with open(os.path.join(a.out, "sysout.txt"), "wb") as fh:
        fh.write("".join(l + "\n" for l in lines).encode("latin-1"))
    with open(os.path.join(a.out, "rc.txt"), "w") as fh:
        fh.write("%d\n" % rc)
    with open(os.path.join(a.out, "stderr.txt"), "w") as fh:
        fh.write(err)
    return 0


if __name__ == "__main__":
    sys.exit(main())
