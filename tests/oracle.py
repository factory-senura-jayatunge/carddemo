"""Independent model of the CardDemo record layouts and sample data.

Layouts come from parsing the copybooks in app/cpy (not from the programs or
from tools/carddemo.py), and zoned decimals are decoded with the mainframe
overpunch convention, so tests can compare program output with expectations
computed straight from the source data.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from decimal import ROUND_DOWN, Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CPY = ROOT / "app" / "cpy"
DATA = ROOT / "app" / "data" / "ASCII"

_POS = {c: d for d, c in enumerate("{ABCDEFGHI")}
_NEG = {c: d for d, c in enumerate("}JKLMNOPQR")}


@dataclass(frozen=True)
class Field:
    name: str
    offset: int
    length: int
    numeric: bool
    signed: bool
    scale: int


def _pic_info(pic: str) -> tuple[int, bool, bool, int]:
    pic = pic.upper()
    expanded = re.sub(r"([X9])\((\d+)\)", lambda m: m.group(1) * int(m.group(2)), pic)
    signed = expanded.startswith("S")
    body = expanded.lstrip("S")
    int_part, _, frac = body.partition("V")
    numeric = "X" not in body
    return len(int_part) + len(frac), numeric, signed, len(frac)


def parse_copybook(name: str) -> dict[str, Field]:
    """Elementary DISPLAY fields of a simple record copybook (no OCCURS/COMP)."""
    text = (CPY / f"{name}.cpy").read_text()
    fields: dict[str, Field] = {}
    offset = 0
    for raw in text.splitlines():
        line = raw[6:72] if len(raw) > 6 else ""
        if not line.strip() or line[0] in "*/":
            continue
        m = re.match(r"\s*(\d\d)\s+([A-Z0-9-]+)(.*)\.\s*$", line[1:].upper())
        if not m:
            continue
        level, fname, rest = m.groups()
        if "REDEFINES" in rest or "OCCURS" in rest:
            raise ValueError(f"{name}: {fname} uses REDEFINES/OCCURS, not supported by this parser")
        pic = re.search(r"PIC\s+(\S+)", rest)
        if not pic:
            continue
        length, numeric, signed, scale = _pic_info(pic.group(1))
        if fname != "FILLER":
            fields[fname] = Field(fname, offset, length, numeric, signed, scale)
        offset += length
    fields["__LENGTH__"] = Field("__LENGTH__", 0, offset, False, False, 0)
    return fields


def zoned(text: str, scale: int = 0) -> Decimal:
    last = text[-1]
    if last in _POS:
        digit, sign = _POS[last], 1
    elif last in _NEG:
        digit, sign = _NEG[last], -1
    else:
        digit, sign = int(last), 1
    value = Decimal(int(text[:-1] + str(digit))) * sign
    return value.scaleb(-scale)


def to_zoned(value: Decimal, length: int, scale: int) -> str:
    units = int((abs(value) * (10 ** scale)).to_integral_value())
    digits = str(units).rjust(length, "0")
    if len(digits) > length:
        raise ValueError(f"{value} does not fit in {length} digits")
    table = "}JKLMNOPQR" if value < 0 else "{ABCDEFGHI"
    return digits[:-1] + table[int(digits[-1])]


class Record:
    """A fixed-length record viewed through a copybook layout."""

    def __init__(self, layout: dict[str, Field], data: bytes | str):
        self.layout = layout
        self.text = data.decode("latin-1") if isinstance(data, bytes) else data
        self.text = self.text.ljust(layout["__LENGTH__"].length)

    def raw(self, name: str) -> str:
        f = self.layout[name]
        return self.text[f.offset:f.offset + f.length]

    def __getitem__(self, name: str):
        f = self.layout[name]
        raw = self.raw(name)
        if f.numeric and f.signed:
            return zoned(raw, f.scale)
        if f.numeric:
            return Decimal(int(raw)).scaleb(-f.scale) if f.scale else int(raw)
        return raw.rstrip()


def build_record(layout: dict[str, Field], **values) -> bytes:
    return set_fields(layout, b" " * layout["__LENGTH__"].length, **values)


def set_fields(layout: dict[str, Field], data: bytes, **values) -> bytes:
    """Copy of a record with the named fields replaced (numbers are encoded per PICTURE)."""
    buf = list(data.decode("latin-1").ljust(layout["__LENGTH__"].length))
    for name, value in values.items():
        f = layout[name]
        if f.numeric and f.signed:
            text = to_zoned(Decimal(value), f.length, f.scale)
        elif f.numeric:
            text = str(int(Decimal(value) * (10 ** f.scale))).rjust(f.length, "0")
        else:
            text = str(value).ljust(f.length)
        if len(text) != f.length:
            raise ValueError(f"{name}: {value!r} does not fit in {f.length}")
        buf[f.offset:f.offset + f.length] = list(text)
    return "".join(buf).encode("latin-1")


def sample_records(filename: str) -> list[str]:
    out = []
    for raw in (DATA / filename).read_bytes().decode("latin-1").split("\n"):
        line = raw.rstrip("\r")
        if line:
            out.append(line)
    return out


ACCOUNT = parse_copybook("CVACT01Y")
CARD = parse_copybook("CVACT02Y")
XREF = parse_copybook("CVACT03Y")
CUSTOMER = parse_copybook("CVCUS01Y")
TCATBAL = parse_copybook("CVTRA01Y")
DISCGRP = parse_copybook("CVTRA02Y")
TRANTYPE = parse_copybook("CVTRA03Y")
TRANCATG = parse_copybook("CVTRA04Y")
TRAN = parse_copybook("CVTRA05Y")
DALYTRAN = parse_copybook("CVTRA06Y")


# CBTRN02C's WS-TEMP-BAL is S9(11)V99. It holds CYC-CREDIT - CYC-DEBIT (both
# S9(10)V99) + DALYTRAN-AMT (S9(09)V99), so every possible value fits; at the
# original S9(09)V99 the high digits of a large sum were dropped.
WS_TEMP_BAL_DIGITS = 11


def cobol_store(value: Decimal, int_digits: int, decimals: int = 2) -> Decimal:
    """Store value in a signed numeric DISPLAY field without ON SIZE ERROR:
    digits beyond the field's integer part are dropped, the sign is kept."""
    q = abs(value).quantize(Decimal(1).scaleb(-decimals), rounding=ROUND_DOWN)
    kept = q % (Decimal(10) ** int_digits)
    return -kept if value < 0 else kept


def expected_posting(daily: list[Record], xref: dict[str, Record], accounts: dict[str, Record]):
    """Model CBTRN02C's validation (1500-*) and posting (2800-*) rules.

    Returns (posted daily records, [(daily record, reason code)], final account
    cycle balances as {acct_id: (cyc_credit, cyc_debit, curr_bal)}).
    """
    state = {k: [a["ACCT-CURR-CYC-CREDIT"], a["ACCT-CURR-CYC-DEBIT"], a["ACCT-CURR-BAL"]]
             for k, a in accounts.items()}
    posted, rejected = [], []
    for t in daily:
        card = t.raw("DALYTRAN-CARD-NUM")
        if card not in xref:
            rejected.append((t, 100))
            continue
        acct_id = xref[card].raw("XREF-ACCT-ID")
        if acct_id not in accounts:
            rejected.append((t, 101))
            continue
        acct, amt = accounts[acct_id], t["DALYTRAN-AMT"]
        credit, debit, bal = state[acct_id]
        reason = 0
        if not acct["ACCT-CREDIT-LIMIT"] >= cobol_store(credit - debit + amt, WS_TEMP_BAL_DIGITS):
            reason = 102
        if not acct.raw("ACCT-EXPIRAION-DATE") >= t.raw("DALYTRAN-ORIG-TS")[:10]:
            reason = 103
        if reason:
            rejected.append((t, reason))
            continue
        posted.append(t)
        state[acct_id][2] = bal + amt
        if amt >= 0:
            state[acct_id][0] = credit + amt
        else:
            state[acct_id][1] = debit + amt
    return posted, rejected, {k: tuple(v) for k, v in state.items()}

def expected_tcatbal(posted: list[Record], xref: dict[str, Record],
                     balances: dict[str, Decimal]) -> dict[str, Decimal]:
    """CBTRN02C 2700-*: add each posted amount to its account/type/category balance,
    creating the balance record when the key is new. Keys are the 17-byte TCATBAL key."""
    result = dict(balances)
    for t in posted:
        key = xref[t.raw("DALYTRAN-CARD-NUM")].raw("XREF-ACCT-ID") + t.raw("DALYTRAN-TYPE-CD") \
            + t.raw("DALYTRAN-CAT-CD")
        result[key] = result.get(key, Decimal(0)) + t["DALYTRAN-AMT"]
    return result


def unpack_comp3(data: bytes, scale: int = 0) -> Decimal:
    """Decode a packed-decimal (COMP-3) field: two digits per byte, sign in the last nibble."""
    nibbles = "".join(f"{b:02X}" for b in data)
    digits, sign = nibbles[:-1], nibbles[-1]
    if not digits.isdigit() or sign not in "ABCDEF":
        raise ValueError(f"not packed decimal: {data.hex()}")
    value = Decimal(int(digits)).scaleb(-scale)
    return -value if sign in "BD" else value


def edited_amount(text: str) -> Decimal:
    """Value of a report field edited with PIC -ZZZ,ZZZ,ZZZ.ZZ or +ZZZ,ZZZ,ZZZ.ZZ."""
    negative = "-" in text
    value = Decimal(text.replace("+", "").replace("-", "").replace(",", "").strip())
    return -value if negative else value

def sample(layout: dict[str, Field], filename: str) -> list[Record]:
    return [Record(layout, r) for r in sample_records(filename)]


def keyed(records: list[Record], key_field: str) -> dict[str, Record]:
    return {r.raw(key_field): r for r in records}


def records_of(layout: dict[str, Field], data: list[bytes]) -> list[Record]:
    return [Record(layout, r) for r in data]
