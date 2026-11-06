#!/usr/bin/env python3
"""Spec-only reimplementation of CBTRN03C (job TRANREPT, transaction detail report).

Usage: python3 tranrept_from_spec.py --data DIR --out DIR
Standard library only. Item IDs refer to docs/modernization/spec/programs/CBTRN03C.md.
"""
import argparse
import os
import sys

LINE_LEN = 133
PAGE_SIZE = 20
TOTAL_MOD = 10 ** 11  # S9(09)V99 held as cents

POS_OVERPUNCH = {'{': 0, 'A': 1, 'B': 2, 'C': 3, 'D': 4, 'E': 5, 'F': 6, 'G': 7, 'H': 8, 'I': 9}
NEG_OVERPUNCH = {'}': 0, 'J': 1, 'K': 2, 'L': 3, 'M': 4, 'N': 5, 'O': 6, 'P': 7, 'Q': 8, 'R': 9}


class Abend(Exception):
    pass


def load_records(path, reclen):
    """Text file -> list of fixed-length records (input contract)."""
    recs = []
    with open(path, 'rb') as fh:
        data = fh.read().decode('latin-1')
    for line in data.split('\n'):
        if line.endswith('\r'):
            line = line[:-1]
        if line.strip(' ') == '':
            continue
        recs.append(line[:reclen].ljust(reclen))
    return recs


def load_ksds(path, reclen, keylen):
    table = {}
    for rec in load_records(path, reclen):
        # A KSDS holds one record per key; the first loaded wins.
        table.setdefault(rec[:keylen], rec)
    return table


def digit_value(ch):
    if '0' <= ch <= '9':
        return ord(ch) - 48
    v = ord(ch) & 0x0F
    return v if v <= 9 else 0


def decode_zoned(field):
    """Return (magnitude_in_units, negative) for a zoned field with trailing overpunch."""
    mag = 0
    for ch in field[:-1]:
        mag = mag * 10 + digit_value(ch)
    last = field[-1]
    neg = False
    if last in POS_OVERPUNCH:
        d = POS_OVERPUNCH[last]
    elif last in NEG_OVERPUNCH:
        d = NEG_OVERPUNCH[last]
        neg = True
    else:
        d = digit_value(last)
    return mag * 10 + d, neg


class Total:
    """decimal(11,2) accumulator without size-error handling (NUM-TRPT-001, NUM-POST-003)."""

    def __init__(self):
        self.mag = 0
        self.neg = False

    def value(self):
        return -self.mag if self.neg else self.mag

    def add(self, mag, neg):
        s = self.value() + (-mag if neg else mag)
        if s == 0:
            self.mag, self.neg = 0, False
            return
        sneg = s < 0
        self.mag = abs(s) % TOTAL_MOD
        self.neg = sneg  # sign kept even when truncation leaves zero

    def add_total(self, other):
        if other.mag == 0 and not other.neg:
            return
        self.add(other.mag, other.neg)

    def reset(self):
        self.mag, self.neg = 0, False


def edit_amount(mag, neg, plus_sign):
    """Edit pattern -ZZZ,ZZZ,ZZZ.ZZ or +ZZZ,ZZZ,ZZZ.ZZ (NUM-TRPT-002)."""
    if mag == 0:
        return ' ' * 15
    digits = '%011d' % mag
    intpart, dec = digits[:9], digits[9:]
    out = []
    started = False
    for i, ch in enumerate(intpart):
        if i in (3, 6):
            out.append(',' if started else ' ')
        if ch != '0' or started:
            started = True
            out.append(ch)
        else:
            out.append(' ')
    if neg:
        sign = '-'
    else:
        sign = '+' if plus_sign else ' '
    return sign + ''.join(out) + '.' + dec


def display_signed(mag, neg):
    """GnuCOBOL DISPLAY of S9(09)V99: 11 digits then the sign (section 4.3)."""
    return '%011d' % (mag % TOTAL_MOD) + ('-' if neg else '+')


def display_tran_amt(field):
    mag, neg = decode_zoned(field)
    return display_signed(mag, neg)


def pad(s, n):
    return s[:n].ljust(n)


RULE = '-' * LINE_LEN


def header_lines(start, end):
    name = (pad('DALYREPT', 38) + pad('Daily Transaction Report', 41)
            + 'Date Range: ' + pad(start, 10) + ' to ' + pad(end, 10))
    col = (pad('Transaction ID', 17) + pad('Account ID', 12) + pad('Transaction Type', 19)
           + pad('Tran Category', 35) + pad('Tran Source', 14) + ' '
           + pad('        Amount', 16))
    return [pad(name, LINE_LEN), ' ' * LINE_LEN, pad(col, LINE_LEN), RULE]


def total_line(label, total):
    return pad(label + edit_amount(total.mag, total.neg, True), LINE_LEN)


def status_line(status):
    # ERR-TRPT-005 / ERR-POST-007 for the numeric statuses used here.
    return 'FILE STATUS IS: NNNN' + '00' + status


def run(data_dir):
    sysout = []
    report = []
    state = {'rc': 0, 'stderr': ''}

    def abend():
        sysout.append('ABENDING PROGRAM')
        state['rc'] = 16
        state['stderr'] = 'CEE3ABD: USER ABEND U0999\n'
        raise Abend()

    def open_input(name, err_msg):
        path = os.path.join(data_dir, name)
        if not os.path.isfile(path):
            sysout.append(err_msg)
            sysout.append(status_line('35'))
            abend()
        return path

    try:
        sysout.append('START OF EXECUTION OF PROGRAM CBTRN03C')
        tran_path = open_input('tranfile.txt', 'ERROR OPENING TRANFILE')
        tranfile = load_records(tran_path, 350)
        # TRANREPT opened for output here; lines accumulate in `report`.
        xref = load_ksds(open_input('cardxref.txt', 'ERROR OPENING CROSS REF FILE'), 50, 16)
        ttype = load_ksds(open_input('trantype.txt', 'ERROR OPENING TRANSACTION TYPE FILE'), 60, 2)
        tcatg = load_ksds(open_input('trancatg.txt', 'ERROR OPENING TRANSACTION CATG FILE'), 60, 6)
        dp_path = os.path.join(data_dir, 'dateparm.txt')
        dateparm = load_records(dp_path, 80) if os.path.isfile(dp_path) else []

        start_date = end_date = ' ' * 10
        eof = False
        if dateparm:
            rec = dateparm[0]
            start_date, end_date = rec[0:10], rec[11:21]
            sysout.append('Reporting from ' + start_date + ' to ' + end_date)
        else:
            eof = True  # ERR-TRPT-002 status 10: the loop does not run

        # Working-storage records without VALUE start as binary zeros locally
        # (FLOW-TRPT-001), which compare below any start date, even a blank one.
        work = '\x00' * 350
        idx = 0
        first_time = True
        line_counter = 0
        curr_card = ' ' * 16
        page_total = Total()
        account_total = Total()
        grand_total = Total()
        # Used as is when the first card number is blank and no lookup ran (BR-TRPT-004).
        acct_id = '\x00' * 11
        type_desc = ' ' * 50
        cat_desc = ' ' * 50

        def write(line):
            report.append(line)

        def write_headers():
            nonlocal line_counter
            for ln in header_lines(start_date, end_date):
                write(ln)
            line_counter += 4

        def write_page_totals():
            nonlocal line_counter
            write(total_line('Page Total ' + '.' * 86, page_total))
            grand_total.add_total(page_total)
            page_total.reset()
            write(RULE)
            line_counter += 2

        def write_account_totals():
            nonlocal line_counter
            write(total_line('Account Total' + '.' * 84, account_total))
            account_total.reset()
            write(RULE)
            line_counter += 2

        while not eof:
            if idx < len(tranfile):
                work = tranfile[idx]
                idx += 1
            else:
                eof = True  # work record keeps the last transaction read
            proc_date = work[304:314]
            if not (proc_date >= start_date and proc_date <= end_date):
                break  # FLOW-TRPT-001 / DEF-TRPT-001: silent exit, no totals
            if not eof:
                sysout.append(work)
                card = work[262:278]
                if card != curr_card:
                    if not first_time:
                        write_account_totals()
                    curr_card = card
                    xrec = xref.get(card)
                    if xrec is None:
                        sysout.append('INVALID CARD NUMBER : ' + card)
                        sysout.append(status_line('23'))
                        abend()
                    acct_id = xrec[25:36]
                    type_cd = work[16:18]
                else:
                    type_cd = work[16:18]
                trec = ttype.get(type_cd)
                if trec is None:
                    sysout.append('INVALID TRANSACTION TYPE : ' + type_cd)
                    sysout.append(status_line('23'))
                    abend()
                type_desc = trec[2:52]
                cat_key = type_cd + work[18:22]
                crec = tcatg.get(cat_key)
                if crec is None:
                    sysout.append('INVALID TRAN CATG KEY : ' + cat_key)
                    sysout.append(status_line('23'))
                    abend()
                cat_desc = crec[6:56]
                # 1100-WRITE-TRANSACTION-REPORT (BR-TRPT-005, BR-TRPT-003)
                if first_time:
                    first_time = False
                    write_headers()
                if line_counter % PAGE_SIZE == 0:
                    write_page_totals()
                    write_headers()
                amt_field = work[132:143]
                mag, neg = decode_zoned(amt_field)
                page_total.add(mag, neg)
                account_total.add(mag, neg)
                detail = (work[0:16] + ' ' + acct_id + ' ' + type_cd + '-' + type_desc[:15]
                          + ' ' + work[18:22] + '-' + cat_desc[:29] + ' ' + work[22:32]
                          + '    ' + edit_amount(mag, neg and mag != 0, False))
                write(pad(detail, LINE_LEN))
                line_counter += 1
            else:
                sysout.append('TRAN-AMT ' + display_tran_amt(work[132:143]))
                sysout.append('WS-PAGE-TOTAL' + display_signed(page_total.mag, page_total.neg))
                if not first_time:
                    write_account_totals()
                write_page_totals()
                write(total_line('Grand Total' + '.' * 86, grand_total))
        sysout.append('END OF EXECUTION OF PROGRAM CBTRN03C')
    except Abend:
        pass
    return report, sysout, state['rc'], state['stderr']


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--data', required=True)
    ap.add_argument('--out', required=True)
    args = ap.parse_args(argv)
    os.makedirs(args.out, exist_ok=True)
    report, sysout, rc, err = run(args.data)
    with open(os.path.join(args.out, 'TRANREPT.txt'), 'wb') as fh:
        fh.write(''.join(ln + '\n' for ln in report).encode('latin-1'))
    with open(os.path.join(args.out, 'sysout.txt'), 'wb') as fh:
        fh.write(''.join(ln + '\n' for ln in sysout).encode('latin-1'))
    with open(os.path.join(args.out, 'rc.txt'), 'w') as fh:
        fh.write('%d\n' % rc)
    with open(os.path.join(args.out, 'stderr.txt'), 'w') as fh:
        fh.write(err)
    if err:
        sys.stderr.write(err)
    return 0


if __name__ == '__main__':
    sys.exit(main())
