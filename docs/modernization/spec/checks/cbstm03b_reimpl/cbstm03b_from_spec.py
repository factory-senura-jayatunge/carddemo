#!/usr/bin/env python3
"""Spec-only reimplementation of CBSTM03B (statement file-access subroutine)
together with the COBOL test driver that calls it once per request line."""
import argparse
import os
import sys

AREA_LEN = 1040
OFF_DD, OFF_OPER, OFF_RC, OFF_KEY, OFF_KEYLN, OFF_DATA = 0, 8, 9, 11, 36, 40
DATA_LEN = 1000

LOW2 = b"\x00\x00"  # NUM-STIO-003: status field before the file's first I/O

WHITE = set(b"\t\n\x0b\x0c\r ")
DIGITS = set(b"0123456789")

# DD name -> (data file, record length, key length, sequential?)
FILES = {
    b"TRNXFILE": ("TRXFL.ps", 350, 32, True),
    b"XREFFILE": ("CARDXREF.ps", 50, 16, True),
    b"CUSTFILE": ("CUSTDATA.ps", 500, 9, False),
    b"ACCTFILE": ("ACCTDATA.ps", 300, 11, False),
}


class IndexedFile:
    def __init__(self, path, reclen, keylen, sequential):
        self.path = path
        self.reclen = reclen
        self.keylen = keylen
        self.sequential = sequential
        self.status = LOW2
        self.is_open = False
        self.records = None  # sorted list of (key, record)
        self.index = None    # key -> record
        self.pos = 0
        self.at_end = False

    def _load(self):
        with open(self.path, "rb") as f:
            raw = f.read()
        index = {}
        n = len(raw) // self.reclen
        for i in range(n):
            rec = raw[i * self.reclen:(i + 1) * self.reclen]
            key = rec[:self.keylen]
            # Loading an indexed file: a duplicate key is rejected, the first stays.
            if key not in index:
                index[key] = rec
        self.index = index
        self.records = sorted(index.items())

    def open_input(self):
        if self.is_open:
            self.status = b"41"
            return
        if not os.path.isfile(self.path):
            self.status = b"35"
            return
        self._load()
        self.is_open = True
        self.pos = 0
        self.at_end = False
        self.status = b"00"

    def close(self):
        if not self.is_open:
            self.status = b"42"
            return
        self.is_open = False
        self.status = b"00"

    def read_next(self):
        if not self.is_open:
            self.status = b"47"
            return None
        if self.at_end:
            self.status = b"46"
            return None
        if self.pos >= len(self.records):
            self.at_end = True
            self.status = b"10"
            return None
        rec = self.records[self.pos][1]
        self.pos += 1
        self.status = b"00"
        return rec

    def read_key(self, key):
        if not self.is_open:
            self.status = b"47"
            return None
        rec = self.index.get(key)
        if rec is None:
            self.status = b"23"
            return None
        self.status = b"00"
        return rec


def acct_key(s):
    """NUM-STIO-002: text moved to an unsigned PIC 9(11) item (GnuCOBOL)."""
    i = 0
    while i < len(s) and s[i] in WHITE:
        i += 1
    if i < len(s) and s[i] in b"+-":
        i += 1
    t = s[i:]
    dot = t.find(b".")
    if dot >= 0:
        t = t[:dot]
    n = sum(1 for c in t if c in DIGITS)
    if n == 0:
        return b"0" * 11
    if n > 11:
        drop = n - 11
        seen = 0
        j = 0
        while seen < drop:
            if t[j] in DIGITS:
                seen += 1
            j += 1
        t = t[j:]
    last = max(k for k, c in enumerate(t) if c in DIGITS)
    span = t[:last + 1]
    for c in span:
        if not (c in DIGITS or c in WHITE or c == 0x2C):
            return b"0" * 11
    digits = bytes(c for c in span if c in DIGITS)
    return digits.rjust(11, b"0")


def keyln_value(area):
    """Value of the S9(4) zoned field, sign overpunched in the last byte."""
    b = area[OFF_KEYLN:OFF_KEYLN + 4]
    val = 0
    neg = False
    for k, c in enumerate(b):
        if k == 3:
            if c == 0x7B:          # '{'  +0
                d = 0
            elif 0x41 <= c <= 0x49:  # 'A'-'I' +1..+9
                d = c - 0x40
            elif c == 0x7D:        # '}'  -0
                d, neg = 0, True
            elif 0x4A <= c <= 0x52:  # 'J'-'R' -1..-9
                d, neg = c - 0x49, True
            else:
                d = c & 0x0F if 0x30 <= c <= 0x39 else 0
        else:
            d = c & 0x0F if 0x30 <= c <= 0x39 else 0
        val = val * 10 + d
    return -val if neg else val


def store_keyln(area, value):
    """Driver moves a non-negative number into the S9(4) field (-fsign=EBCDIC)."""
    value = value % 10000
    s = b"%04d" % value
    last = s[3] - 0x30
    area[OFF_KEYLN:OFF_KEYLN + 4] = s[:3] + (b"{" if last == 0 else bytes([0x40 + last]))


def key_text(area, length):
    # DEF-STIO-001: the reference modification is not bounded by the 25-byte key.
    if length <= 0:
        return b""
    return bytes(area[OFF_KEY:min(OFF_KEY + length, AREA_LEN)])


def cbstm03b(area, files):
    dd = bytes(area[OFF_DD:OFF_DD + 8])
    f = files.get(dd)
    if f is None:
        return
    oper = area[OFF_OPER]
    rec = None
    if oper == ord("O"):
        f.open_input()
    elif f.sequential and oper == ord("R"):
        rec = f.read_next()
    elif not f.sequential and oper == ord("K"):
        kl = keyln_value(area)
        text = key_text(area, kl)
        if dd == b"CUSTFILE":
            key = text[:9].ljust(9, b" ")
        else:
            key = acct_key(text)
        rec = f.read_key(key)
    elif oper == ord("C"):
        f.close()
    if rec is not None:
        area[OFF_DATA:OFF_DATA + DATA_LEN] = rec.ljust(DATA_LEN, b" ")
    area[OFF_RC:OFF_RC + 2] = f.status


def parse_keyln(field):
    v = 0
    for c in field:
        v = v * 10 + (c - 0x30 if 0x30 <= c <= 0x39 else 0)
    return v


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    files = {dd: IndexedFile(os.path.join(args.data, name), rl, kl, seq)
             for dd, (name, rl, kl, seq) in FILES.items()}

    with open(os.path.join(args.data, "requests.txt"), "rb") as fh:
        raw = fh.read()
    lines = raw.split(b"\n")
    if lines and lines[-1] == b"":
        lines.pop()

    area = bytearray(b" " * AREA_LEN)
    store_keyln(area, 0)
    out = []
    for line in lines:
        if line.endswith(b"\r"):
            line = line[:-1]
        line = line[:38].ljust(38, b" ")
        area[OFF_DD:OFF_DD + 8] = line[0:8]
        area[OFF_OPER] = line[8]
        store_keyln(area, parse_keyln(line[9:13]))
        area[OFF_KEY:OFF_KEY + 25] = line[13:38]
        cbstm03b(area, files)
        out.append(b"RC=" + bytes(area[OFF_RC:OFF_RC + 2]) + b"|"
                   + bytes(area[OFF_DATA:OFF_DATA + DATA_LEN]) + b"\n")

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "calls.txt"), "wb") as fh:
        fh.write(b"".join(out))
    with open(os.path.join(args.out, "rc.txt"), "w", encoding="latin-1") as fh:
        fh.write("0\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
