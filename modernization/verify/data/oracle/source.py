"""Source-to-column mapping derived from data-model.md and copybook layouts."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal
import hashlib
import re
import tomllib

from modernization.verify.spec_jobs.common import Finding, InvalidFixture, require_integer
from modernization.verify.spec_jobs.discgrp import lines
from modernization.verify.spec_jobs.layouts import oracle

# Independent built-in entry declared by DEF-ACUP-002. Do not read the
# implementation's resource: a changed resource must disagree with this rule.
SAMPLE_ACCOUNT_SHA = "c2a97b6a32dc4a87a7aafdf7f72e6712e560412d30b00c5526cca80fc9dfd260"


@dataclass(frozen=True)
class Table:
    filename: str
    layout: dict
    columns: dict[str, str]
    keys: tuple[str, ...]
    count: int
    filler: tuple[int, bytes] | None = None
    kept_filler: int | None = None


TABLES = {
    "core.account": Table("acctdata.txt", oracle.ACCOUNT, dict(
        acct_id="ACCT-ID", active_status="ACCT-ACTIVE-STATUS", curr_bal="ACCT-CURR-BAL",
        credit_limit="ACCT-CREDIT-LIMIT", cash_credit_limit="ACCT-CASH-CREDIT-LIMIT",
        open_date="ACCT-OPEN-DATE", expiration_date="ACCT-EXPIRAION-DATE", reissue_date="ACCT-REISSUE-DATE",
        curr_cyc_credit="ACCT-CURR-CYC-CREDIT", curr_cyc_debit="ACCT-CURR-CYC-DEBIT",
        addr_zip="ACCT-ADDR-ZIP", group_id="ACCT-GROUP-ID"), ("acct_id",), 50, kept_filler=122),
    "core.customer": Table("custdata.txt", oracle.CUSTOMER, dict(
        cust_id="CUST-ID", first_name="CUST-FIRST-NAME", middle_name="CUST-MIDDLE-NAME", last_name="CUST-LAST-NAME",
        addr_line_1="CUST-ADDR-LINE-1", addr_line_2="CUST-ADDR-LINE-2", addr_line_3="CUST-ADDR-LINE-3",
        state_cd="CUST-ADDR-STATE-CD", country_cd="CUST-ADDR-COUNTRY-CD", zip="CUST-ADDR-ZIP",
        phone_1="CUST-PHONE-NUM-1", phone_2="CUST-PHONE-NUM-2", ssn="CUST-SSN",
        govt_issued_id="CUST-GOVT-ISSUED-ID", dob="CUST-DOB-YYYY-MM-DD", eft_account_id="CUST-EFT-ACCOUNT-ID",
        pri_card_holder_ind="CUST-PRI-CARD-HOLDER-IND", fico_score="CUST-FICO-CREDIT-SCORE"), ("cust_id",), 50),
    "core.card": Table("carddata.txt", oracle.CARD, dict(
        card_num="CARD-NUM", acct_id="CARD-ACCT-ID", embossed_name="CARD-EMBOSSED-NAME",
        expiration_date="CARD-EXPIRAION-DATE", active_status="CARD-ACTIVE-STATUS"), ("card_num",), 50, kept_filler=91),
    "core.card_xref": Table("cardxref.txt", oracle.XREF, dict(
        card_num="XREF-CARD-NUM", cust_id="XREF-CUST-ID", acct_id="XREF-ACCT-ID"), ("card_num",), 50, filler=(36, b" ")),
    "core.tcat_bal": Table("tcatbal.txt", oracle.TCATBAL, dict(
        acct_id="TRANCAT-ACCT-ID", type_cd="TRANCAT-TYPE-CD", cat_cd="TRANCAT-CD", balance="TRAN-CAT-BAL"),
        ("acct_id", "type_cd", "cat_cd"), 50, kept_filler=28),
    "ref.tran_type": Table("trantype.txt", oracle.TRANTYPE, dict(
        type_cd="TRAN-TYPE", description="TRAN-TYPE-DESC"), ("type_cd",), 7, filler=(52, b"0")),
    "ref.tran_category": Table("trancatg.txt", oracle.TRANCATG, dict(
        type_cd="TRAN-TYPE-CD", cat_cd="TRAN-CAT-CD", description="TRAN-CAT-TYPE-DESC"),
        ("type_cd", "cat_cd"), 18, filler=(56, b"0")),
    "ref.disclosure_group": Table("discgrp.txt", oracle.DISCGRP, dict(
        group_id="DIS-ACCT-GROUP-ID", type_cd="DIS-TRAN-TYPE-CD", cat_cd="DIS-TRAN-CAT-CD", int_rate="DIS-INT-RATE"),
        ("group_id", "type_cd", "cat_cd"), 51, filler=(22, b"0")),
}
TEXT_DIGITS = {"acct_id", "cust_id", "ssn", "cat_cd"}
DATE_COLUMNS = {"open_date", "reissue_date", "dob"}


def account_layouts(raw: bytes, mode: str, manifest: bytes | None = None) -> dict[str, str]:
    """The mode never depends on a group value, path or filename."""
    if mode not in ("legacy", "fixed"):
        raise InvalidFixture("MODE")
    records = lines(raw)
    ids = [line[:11].decode("latin-1") for line in records]
    result = dict.fromkeys(ids, "CVACT01Y")
    if mode == "legacy":
        return result
    if manifest is None:
        if hashlib.sha256(raw).hexdigest() == SAMPLE_ACCOUNT_SHA:
            return dict.fromkeys(ids, "ACUP_UPDATE")
        return result
    try:
        if len(manifest) > 1024 * 1024:
            raise ValueError
        text = manifest.decode("utf-8")
        text = re.sub(r'"[^"\\\r\n]*"|#[^\r\n]*',
                      lambda m: "" if m[0].startswith("#") else m[0], text)
        string = r'"[^"\\\r\n]*"'
        array = r"\[\s*(?:" + string + r"\s*(?:,\s*" + string + r"\s*)*,?\s*)?\]"
        pair = r"[A-Za-z_][A-Za-z_0-9]*\s*=\s*(?:" + string + r"|[+-]?[0-9]+|" + array + r")"
        if not re.fullmatch(r"\s*(?:" + pair + r"[ \t]*(?:\r?\n|$)\s*)*", text):
            raise ValueError
        declaration = tomllib.loads(text)
        if set(declaration) - {"schema", "sha256", "default_layout", "other_layout_accounts"}:
            raise ValueError
        if type(declaration.get("schema")) is not int or declaration["schema"] != 1:
            raise ValueError
        if not isinstance(declaration.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", declaration["sha256"]):
            raise ValueError
        if declaration["sha256"] != hashlib.sha256(raw).hexdigest():
            raise ValueError
        default = declaration["default_layout"]
        overrides = declaration.get("other_layout_accounts", [])
        if not isinstance(overrides, list):
            raise ValueError
        result = dict.fromkeys(ids, default)
        seen = set()
        for acct in overrides:
            if not isinstance(acct, str) or not re.fullmatch(r"[0-9]{11}", acct) or acct in seen or acct not in result:
                raise ValueError
            result[acct] = "CVACT01Y" if default == "ACUP_UPDATE" else "ACUP_UPDATE"
            seen.add(acct)
        if any(layout not in ("CVACT01Y", "ACUP_UPDATE") for layout in result.values()):
            raise ValueError
    except (ValueError, KeyError, TypeError, UnicodeError, tomllib.TOMLDecodeError):
        raise InvalidFixture("ACCOUNT_MANIFEST") from None
    return result


def blank(text: str) -> bool:
    return text == " " * len(text) or text == "\0" * len(text)


def parse(table_name: str, raw: bytes, *, mode: str = "legacy", manifest: bytes | None = None) -> list[dict]:
    table = TABLES[table_name]
    layouts = account_layouts(raw, mode, manifest) if table_name == "core.account" else {}
    rows, previous = [], None
    for number, line in enumerate(lines(raw), 1):
        if table_name == "core.card_xref" and len(line) == 36:
            line = line.ljust(50, b" ")
        if len(line) != table.layout["__LENGTH__"].length:
            raise InvalidFixture(f"{table.filename} line {number}: RECORD_LENGTH")
        record = oracle.Record(table.layout, line)
        row = {}
        for column, field_name in table.columns.items():
            field = table.layout[field_name]
            text = record.raw(field_name)
            try:
                if field.numeric:
                    pattern = r"[0-9]+[0-9{ABCDEFGHI}JKLMNOPQR]" if field.signed else r"[0-9]+"
                    if not re.fullmatch(pattern, text):
                        raise ValueError
                    row[column] = text if column in TEXT_DIGITS else record[field_name]
                    if field.signed and row[column] == 0 and text[-1] in "}JKLMNOPQR":
                        raise ValueError
                else:
                    row[column] = text.rstrip(" ")
                if column in DATE_COLUMNS or (column == "expiration_date" and table_name == "core.account"):
                    if row[column] and not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", row[column]):
                        raise ValueError
                    row[column] = date.fromisoformat(row[column]).isoformat() if row[column] else None
            except (ValueError, ArithmeticError):
                raise InvalidFixture(f"{table.filename} line {number}: FIELD_ENCODING {column}") from None
        if table.kept_filler is not None:
            row["legacy_filler"] = line[table.kept_filler:].decode("latin-1")
        if table.filler is not None:
            offset, constant = table.filler
            if line[offset:] != constant * (len(line) - offset):
                raise InvalidFixture(f"{table.filename} line {number}: FILLER_CONSTANT")
        if table_name == "core.account":
            original = line[102:122].decode("latin-1")
            declared = layouts[row["acct_id"]]
            if declared == "ACUP_UPDATE":
                if not blank(original[10:]):
                    raise InvalidFixture(f"{table.filename} line {number}: DECLARED_LAYOUT")
                row["addr_zip"] = ""
                row["group_id"] = "" if blank(original[:10]) else original[:10].rstrip(" ")
            row["legacy_zip_group"] = original
            row["zip_group_origin"] = "M" if declared == "ACUP_UPDATE" else "R"
        key = tuple(str(row[column]).ljust(table.layout[table.columns[column]].length) for column in table.keys)
        if previous is not None and key <= previous:
            raise InvalidFixture(f"{table.filename} line {number}: ASCENDING_UNIQUE_KEY")
        previous = key
        rows.append(row)
    return rows


def encode(table_name: str, row: dict) -> bytes:
    table = TABLES[table_name]
    values = {field: row[column] if row[column] is not None else "" for column, field in table.columns.items()}
    # CVV is intentionally not checked. No oracle SQL path may unmask it.
    if table_name == "core.card":
        values["CARD-CVV-CD"] = 0
    raw = bytearray(oracle.build_record(table.layout, **values))
    if table.kept_filler is not None:
        raw[table.kept_filler:] = row["legacy_filler"].encode("latin-1")
    if table.filler is not None:
        offset, constant = table.filler
        raw[offset:] = constant * (len(raw) - offset)
    if table_name == "core.account" and row.get("legacy_zip_group") is not None:
        original = row["legacy_zip_group"]
        if row["zip_group_origin"] == "M":
            group, zip_code = ("" if blank(original[:10]) else original[:10].rstrip(" ")), ""
        else:
            zip_code, group = original[:10].rstrip(" "), original[10:].rstrip(" ")
        if row["addr_zip"] == zip_code and row["group_id"].rstrip(" ") == group:
            raw[102:122] = original.encode("latin-1")
    return bytes(raw)


def canonical(column: str, value):
    if isinstance(value, (Decimal, int)):
        return value
    if value is None:
        return None
    if column == "legacy_filler" or column == "legacy_zip_group":
        return value
    # Only SQL CHAR padding, not NUL or any other whitespace, is removable.
    return str(value).rstrip(" ")


def validate(table_name: str, source: bytes, actual: list[dict], *, mode="legacy", manifest=None) -> list[Finding]:
    table = TABLES[table_name]
    wanted = parse(table_name, source, mode=mode, manifest=manifest)
    findings = []
    if len(wanted) != len(actual):
        findings.append(Finding(mode, "ROW_COUNT", table.filename))
    index = {tuple(canonical(c, row[c]) for c in table.keys): row for row in actual}
    expected_keys = {tuple(canonical(c, row[c]) for c in table.keys) for row in wanted}
    if set(index) != expected_keys or len(index) != len(actual):
        findings.append(Finding(mode, "KEY_SET", table.filename))
    for number, (expected, source_line) in enumerate(zip(wanted, lines(source)), 1):
        key = tuple(canonical(c, expected[c]) for c in table.keys)
        actual_row = index.get(key)
        if actual_row is None:
            findings.append(Finding(mode, "MISSING_ROW", table.filename, number))
            continue
        normal = dict(actual_row)
        for column, value in expected.items():
            actual_value = actual_row.get(column)
            try:
                if isinstance(value, Decimal):
                    normal[column] = Decimal(str(actual_value))
                elif isinstance(value, int):
                    normal[column] = require_integer(actual_value)
                else:
                    normal[column] = canonical(column, actual_value)
                equal = normal[column] == canonical(column, value)
            except (ValueError, TypeError, ArithmeticError):
                equal = False
            if not equal:
                findings.append(Finding(mode, "COLUMN", table.filename, number, column))
        if table_name == "core.card_xref":
            source_line = source_line.ljust(50, b" ")
        elif table_name == "core.customer":
            source_line = source_line[:332] + b" " * 168
        try:
            encoded = encode(table_name, normal)
            if table_name == "core.card":
                # Compare both spans around the deliberately excluded CVV.
                same = encoded[:27] == source_line[:27] and encoded[30:] == source_line[30:]
            else:
                same = encoded == source_line
        except (ValueError, TypeError, KeyError, ArithmeticError, UnicodeError):
            same = False
        if not same:
            findings.append(Finding(mode, "REENCODE", table.filename, number))
    for column in {c for row in wanted for c, v in row.items() if isinstance(v, Decimal)}:
        try:
            equal = sum(row[column] for row in wanted) == sum(Decimal(str(row[column])) for row in actual)
        except (ValueError, KeyError, ArithmeticError):
            equal = False
        if not equal:
            findings.append(Finding(mode, "AMOUNT_SUM", table.filename, column=column))
    return findings
