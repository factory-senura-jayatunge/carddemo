"""VAL-JDISC-001, D-67 and SE-JDISC-003, independent of the loader."""
from __future__ import annotations

from decimal import Decimal
import re

from .common import Finding, InvalidFixture, completed, unchanged
from .layouts import oracle

LAYOUT = oracle.DISCGRP
FIELDS = {"group_id": "DIS-ACCT-GROUP-ID", "type_cd": "DIS-TRAN-TYPE-CD",
          "cat_cd": "DIS-TRAN-CAT-CD", "int_rate": "DIS-INT-RATE"}


class InputError(InvalidFixture):
    def __init__(self, line: int, rule: str):
        self.line, self.rule = line, rule
        super().__init__(f"discgrp.txt line {line}: {rule}")


def lines(raw: bytes) -> list[bytes]:
    # Split LF only, not bytes.splitlines() which treats some record data as
    # line breaks. An optional CR belongs only to a CRLF line ending.
    result = raw.split(b"\n")
    if result[-1] == b"":
        result.pop()
    return [line[:-1] if line.endswith(b"\r") else line for line in result]


def parse(raw: bytes) -> list[dict]:
    rows, previous = [], None
    for number, line in enumerate(lines(raw), 1):
        if len(line) != 50:
            raise InputError(number, "RECORD_LENGTH")
        record = oracle.Record(LAYOUT, line)
        if not re.fullmatch(r"[0-9]{4}", record.raw("DIS-TRAN-CAT-CD")):
            raise InputError(number, "CATEGORY_DIGITS")
        if not re.fullmatch(r"[0-9]{5}[0-9{ABCDEFGHI}JKLMNOPQR]", record.raw("DIS-INT-RATE")):
            raise InputError(number, "RATE_ENCODING")
        if line[22:] != b"0" * 28:
            raise InputError(number, "FILLER_CONSTANT")
        # Stored CHAR codes are compared as padded bytes under BIN2.
        key = line[:16]
        if previous is not None and key <= previous:
            raise InputError(number, "ASCENDING_UNIQUE_KEY")
        previous = key
        rows.append(dict(group_id=record.raw("DIS-ACCT-GROUP-ID").rstrip(" "),
                         type_cd=record.raw("DIS-TRAN-TYPE-CD"), cat_cd=record.raw("DIS-TRAN-CAT-CD"),
                         int_rate=record["DIS-INT-RATE"]))
    if not any(row["group_id"] == "DEFAULT" for row in rows):
        raise InputError(0, "DEFAULT_GROUP_REQUIRED")
    return rows


def encode(row: dict) -> bytes:
    values = {field: row[column] for column, field in FIELDS.items()}
    return oracle.build_record(LAYOUT, **values)[:22] + b"0" * 28


def validate(case: str, source: bytes, actual: list[dict], *, status: str,
             source_after: bytes, observations: tuple[dict, ...] = (),
             old_hashes: dict | None = None, new_hashes: dict | None = None) -> list[Finding]:
    findings = completed(case, status)
    wanted = parse(source)
    # Compare normalised SQL projection, not adapter-specific metadata columns.
    normal = lambda rows: sorted(
        [(r["group_id"].rstrip(" "), r["type_cd"], str(r["cat_cd"]).zfill(4), Decimal(str(r["int_rate"])))
         for r in rows])
    if normal(wanted) != normal(actual):
        findings.append(Finding(case, "DISCLOSURE_ROWS", "ref.disclosure_group"))
    by_key = {(r["group_id"].rstrip(" "), r["type_cd"], str(r["cat_cd"]).zfill(4)): r for r in actual}
    for number, line in enumerate(lines(source), 1):
        r = wanted[number - 1]
        key = (r["group_id"], r["type_cd"], r["cat_cd"])
        try:
            encoded = encode(by_key[key])
        except (KeyError, ValueError, TypeError):
            findings.append(Finding(case, "REENCODE", "discgrp.txt", number))
            continue
        if encoded != line:
            findings.append(Finding(case, "REENCODE", "discgrp.txt", number))
    if source_after != source:
        findings.append(Finding(case, "INPUT_UNCHANGED", "discgrp.txt"))
    if case == "snapshot":
        if not observations or old_hashes is None or new_hashes is None:
            findings.append(Finding(case, "SNAPSHOT_OBSERVATIONS_REQUIRED", "ref.disclosure_group"))
        elif any(view != old_hashes and view != new_hashes for view in observations):
            findings.append(Finding(case, "SNAPSHOT_ATOMICITY", "ref.disclosure_group"))
    return findings


def validate_rejected(case: str, *, status: str, before: dict, after: dict,
                      exit_description: str = "") -> list[Finding]:
    findings = unchanged(before, after, case, "ref.disclosure_group")
    wanted = "BATCH_WINDOW_REQUIRED" if case == "no_window" else "FAILED"
    if status != wanted:
        findings.append(Finding(case, "RELOAD_REFUSED", "execution"))
    if case == "missing" and "INBOUND_FILE_MISSING" not in exit_description:
        findings.append(Finding(case, "MISSING_FILE_DESCRIPTION", "execution"))
    return findings
