"""Independent ENT-AUTH-HD-UNLOAD decoder, no target codec or exporter."""
from __future__ import annotations

from datetime import date, datetime, timedelta
from decimal import Decimal
import re

from modernization.verify.spec_jobs.common import InvalidFixture, require_integer
from modernization.verify.spec_jobs.common import Finding


def packed(raw: bytes, digits: int, scale: int = 0) -> Decimal:
    nibbles = [n for byte in raw for n in (byte >> 4, byte & 15)]
    if not raw or len(nibbles) != (digits + 2) // 2 * 2:
        raise InvalidFixture("PACKED_LENGTH")
    sign = nibbles.pop()
    if sign not in (10, 11, 12, 13, 14, 15):
        raise InvalidFixture("PACKED_SIGN")
    padding = len(nibbles) - digits
    if padding and nibbles[:padding] != [0] * padding:
        raise InvalidFixture("PACKED_PADDING")
    nibbles = nibbles[padding:]
    if any(n > 9 for n in nibbles):
        raise InvalidFixture("PACKED_DIGIT")
    number = Decimal("".join(str(n) for n in nibbles)).scaleb(-scale)
    return -number if sign in (11, 13) else number


def timestamp(date_9c: int, time_9c: int) -> str:
    try:
        yy, ordinal = divmod(99999 - date_9c, 1000)
        if not 0 <= yy <= 99 or ordinal < 1:
            raise ValueError
        actual = date(2000 + yy, 1, 1) + timedelta(days=ordinal - 1)
        if actual.year != 2000 + yy:
            raise ValueError
        clock = 999999999 - time_9c
        hh, clock = divmod(clock, 10000000)
        mm, clock = divmod(clock, 100000)
        ss, millis = divmod(clock, 1000)
        result = datetime(actual.year, actual.month, actual.day, hh, mm, ss, millis * 1000)
    except (ValueError, OverflowError):
        raise InvalidFixture("AUTH_TIMESTAMP") from None
    return result.isoformat(timespec="milliseconds")


def text(raw: bytes, start: int, length: int) -> str:
    return raw[start - 1:start - 1 + length].decode("cp037").rstrip(" ")


def amount(raw: bytes, start: int, length: int, digits: int = 11) -> Decimal:
    return packed(raw[start - 1:start - 1 + length], digits, 2)


def summary(raw: bytes) -> dict:
    account = int(packed(raw[:6], 11))
    customer = text(raw, 7, 9)
    if account < 0 or not re.fullmatch(r"[0-9]{9}", customer):
        raise InvalidFixture("SUMMARY_KEY")
    return dict(acct_id=f"{account:011d}", cust_id=customer,
                credit_limit=amount(raw, 27, 6), cash_limit=amount(raw, 33, 6),
                credit_balance=amount(raw, 39, 6), cash_balance=amount(raw, 45, 6),
                approved_auth_cnt=int.from_bytes(raw[50:52], "big", signed=True),
                declined_auth_cnt=int.from_bytes(raw[52:54], "big", signed=True),
                approved_auth_amt=amount(raw, 55, 6), declined_auth_amt=amount(raw, 61, 6))


DETAIL_TEXT = {
    "orig_date": (9, 6), "orig_time": (15, 6), "card_num": (21, 16), "auth_type": (37, 4),
    "card_expiry_date": (41, 4), "message_type": (45, 6), "message_source": (51, 6),
    "auth_id_code": (57, 6), "auth_resp_code": (63, 2), "auth_resp_reason": (65, 4),
    "processing_code": (69, 6), "merchant_category_code": (89, 4), "acqr_country_code": (93, 3),
    "pos_entry_mode": (96, 2), "merchant_id": (98, 15), "merchant_name": (113, 22),
    "merchant_city": (135, 13), "merchant_state": (148, 2), "merchant_zip": (150, 9),
    "transaction_id": (159, 15), "match_status": (174, 1), "auth_fraud": (175, 1),
}
VARIABLE_TEXT = {"merchant_name", "merchant_city", "merchant_zip"}


def detail(raw: bytes, acct_id: str) -> dict:
    date_9c, time_9c = int(packed(raw[:3], 5)), int(packed(raw[3:8], 9))
    row = {name: (text(raw, *span) if name in VARIABLE_TEXT else
                  raw[span[0] - 1:span[0] - 1 + span[1]].decode("cp037"))
           for name, span in DETAIL_TEXT.items()}
    # A blank flag remains a space in the constrained CHAR(1) column.
    row["auth_fraud"] = row["auth_fraud"] or " "
    fraud_date = text(raw, 176, 8)
    try:
        if fraud_date:
            if not re.fullmatch(r"[0-9]{2}/[0-9]{2}/[0-9]{2}", fraud_date):
                raise ValueError
            parsed = date(2000 + int(fraud_date[6:]), int(fraud_date[:2]), int(fraud_date[3:5])).isoformat()
        else:
            parsed = None
    except ValueError:
        raise InvalidFixture("FRAUD_DATE") from None
    if row["auth_resp_code"] not in ("00", "05") or row["match_status"] not in ("P", "D", "E"):
        raise InvalidFixture("DETAIL_RESPONSE_OR_MATCH")
    if row["auth_fraud"] not in (" ", "F", "R") or not re.fullmatch(r"[0-9]{6}", row["processing_code"]) \
            or not re.fullmatch(r"[0-9]{2}", row["pos_entry_mode"]):
        raise InvalidFixture("DETAIL_FLAG_OR_DIGITS")
    row.update(acct_id=acct_id, auth_date_9c=date_9c, auth_time_9c=time_9c,
               processed_at=timestamp(date_9c, time_9c),
               transaction_amt=amount(raw, 75, 7, 12), approved_amt=amount(raw, 82, 7, 12),
               fraud_rpt_date=parsed, fraud_changed_by=None, matched_tran_pk=None, matched_tran_id=None,
               matched_cycle_id=None, matched_at=None)
    return row


def parse(raw: bytes) -> tuple[list[dict], list[dict]]:
    summaries, details = [], []
    offset, count, parent, skipped = 0, 0, None, False
    while offset < len(raw):
        if len(raw) - offset < 4:
            raise InvalidFixture("HD_DESCRIPTOR")
        length = int.from_bytes(raw[offset:offset + 2], "big")
        if length < 4 or offset + length > len(raw) or raw[offset + 2:offset + 4] != b"\0\0":
            raise InvalidFixture("HD_DESCRIPTOR")
        record = raw[offset:offset + length]
        offset += length
        count += 1
        if count == 1 or offset == len(raw):
            if length != 88 or record[8:16].decode("cp037") != "PAUTSUM0":
                raise InvalidFixture("HD_HEADER_TRAILER")
            continue
        name = record[10:18].decode("cp037")
        if record[-1:] != b"\0" or int.from_bytes(record[8:10], "big") != length - 40:
            raise InvalidFixture("HD_SEGMENT_LENGTH")
        if length == 140 and name == "PAUTSUM0":
            # The segment data starts after the 39-byte IMS prefix and RDW.
            body = record[39:139]
            if body[:6] == b"\x40" * 6:
                parent = None
                skipped = True
                continue
            row = summary(body)
            if summaries and summaries[-1]["acct_id"] >= row["acct_id"]:
                raise InvalidFixture("ASCENDING_SUMMARY")
            summaries.append(row)
            parent = row["acct_id"]
            skipped = False
        elif length == 240 and name == "PAUTDTL1":
            if skipped:
                continue
            if parent is None:
                raise InvalidFixture("HD_PARENT")
            details.append(detail(record[39:239], parent))
        else:
            raise InvalidFixture("HD_SEGMENT")
    if count < 2:
        raise InvalidFixture("HD_HEADER_TRAILER")
    keys = [(d["acct_id"], d["auth_date_9c"], d["auth_time_9c"]) for d in details]
    if len(keys) != len(set(keys)):
        raise InvalidFixture("DUPLICATE_DETAIL")
    return summaries, details


def to_packed(value, digits: int, scale: int, original: bytes) -> bytes:
    value = Decimal(str(value))
    integer = abs(value).scaleb(scale)
    if not value.is_finite() or integer != integer.to_integral_value() or integer >= 10 ** digits:
        raise InvalidFixture("PACKED_RANGE")
    original_sign = original[-1] & 15
    sign = (f"{original_sign:x}" if (value < 0) == (original_sign in (11, 13)) else
            "d" if value < 0 else "c")
    encoded = f"{int(integer):0{digits}d}" + sign
    if len(encoded) % 2:
        encoded = "0" + encoded
    return bytes.fromhex(encoded)


SUMMARY_AMOUNT = {"credit_limit": 27, "cash_limit": 33, "credit_balance": 39,
                  "cash_balance": 45, "approved_auth_amt": 55, "declined_auth_amt": 61}


def encode_summary(row: dict, source: bytes) -> bytes:
    # Only the fields explicitly absent from the relational mapping are kept
    # from the source template, not synthesized from database values.
    raw = bytearray(source)
    raw[:6] = to_packed(row["acct_id"], 11, 0, source[:6])
    raw[6:15] = row["cust_id"].ljust(9).encode("cp037")
    for column, start in SUMMARY_AMOUNT.items():
        raw[start - 1:start + 5] = to_packed(row[column], 11, 2, source[start - 1:start + 5])
    for column, start in (("approved_auth_cnt", 50), ("declined_auth_cnt", 52)):
        raw[start:start + 2] = int(row[column]).to_bytes(2, "big", signed=True)
    return bytes(raw)


def encode_detail(row: dict, source: bytes) -> bytes:
    raw = bytearray(source)
    raw[:3] = to_packed(row["auth_date_9c"], 5, 0, source[:3])
    raw[3:8] = to_packed(row["auth_time_9c"], 9, 0, source[3:8])
    for column, (start, length) in DETAIL_TEXT.items():
        value = str(row[column]).ljust(length).encode("cp037")
        if len(value) != length:
            raise InvalidFixture("TEXT_LENGTH")
        raw[start - 1:start - 1 + length] = value
    for column, start in (("transaction_amt", 75), ("approved_amt", 82)):
        raw[start - 1:start + 6] = to_packed(row[column], 12, 2, source[start - 1:start + 6])
    fraud_date = row["fraud_rpt_date"]
    raw[175:183] = (date.fromisoformat(str(fraud_date)).strftime("%m/%d/%y") if fraud_date else " " * 8).encode("cp037")
    return bytes(raw)


def bodies(raw: bytes) -> tuple[list[bytes], list[bytes]]:
    summaries, details = [], []
    offset, skipped = 0, False
    while offset < len(raw):
        length = int.from_bytes(raw[offset:offset + 2], "big")
        record = raw[offset:offset + length]
        if length < 4 or offset + length > len(raw):
            raise InvalidFixture("HD_DESCRIPTOR")
        if length == 140:
            skipped = record[39:45] == b"\x40" * 6
            if not skipped:
                summaries.append(record[39:139])
        elif length == 240 and not skipped:
            details.append(record[39:239])
        offset += length
    return summaries, details


def validate(raw: bytes, actual_summaries: list[dict], actual_details: list[dict]) -> list[Finding]:
    expected_tables = parse(raw)
    source_bodies = bodies(raw)
    findings = []
    for table, wanted, actual, records, keys, encode in zip(
        ("pauth.auth_summary", "pauth.auth_detail"), expected_tables,
        (actual_summaries, actual_details), source_bodies,
        (("acct_id",), ("acct_id", "auth_date_9c", "auth_time_9c")), (encode_summary, encode_detail)):
        index = {tuple(r[c] for c in keys): r for r in actual}
        if len(actual) != len(wanted):
            findings.append(Finding("load", "ROW_COUNT", table))
        if set(index) != {tuple(r[c] for c in keys) for r in wanted} or len(index) != len(actual):
            findings.append(Finding("load", "KEY_SET", table))
        for number, (row, record) in enumerate(zip(wanted, records), 1):
            found = index.get(tuple(row[c] for c in keys))
            if found is None:
                findings.append(Finding("load", "MISSING_ROW", table, number))
                continue
            normal = dict(found)
            for column, expected in row.items():
                value = found.get(column)
                try:
                    if isinstance(expected, Decimal):
                        value = Decimal(str(value))
                    elif isinstance(expected, int):
                        value = require_integer(value)
                    elif column == "processed_at":
                        value = datetime.fromisoformat(str(value)).isoformat(timespec="milliseconds")
                    elif isinstance(expected, str):
                        value = str(value).rstrip(" ") if column in VARIABLE_TEXT else str(value)
                    equal = value == expected
                    normal[column] = value
                except (TypeError, ValueError, ArithmeticError):
                    equal = False
                if not equal:
                    findings.append(Finding("load", "COLUMN", table, number, column))
            try:
                equal = encode(normal, record) == record
            except (TypeError, ValueError, ArithmeticError, KeyError, UnicodeError, OverflowError):
                equal = False
            if not equal:
                findings.append(Finding("load", "REENCODE", table, number))
        amount_columns = {c for r in wanted for c, v in r.items() if isinstance(v, Decimal)}
        for column in amount_columns:
            try:
                equal = sum(r[column] for r in wanted) == sum(Decimal(str(r[column])) for r in actual)
            except (TypeError, ValueError, ArithmeticError, KeyError):
                equal = False
            if not equal:
                findings.append(Finding("load", "AMOUNT_SUM", table, column=column))
    return findings
