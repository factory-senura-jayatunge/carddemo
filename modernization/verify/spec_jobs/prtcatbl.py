"""BR-JPCAT-001 / NUM-JPCAT-001 / DEF-JPCAT-001, -002."""
from __future__ import annotations

from decimal import Decimal
import re

from .common import Finding, InvalidFixture, completed, unchanged


def expected(rows: list[dict], mode: str) -> bytes:
    if mode not in ("legacy", "fixed"):
        raise InvalidFixture("MODE")
    if any(not isinstance(r["cat_cd"], str) or not re.fullmatch(r"[0-9]{4}", r["cat_cd"]) for r in rows):
        raise InvalidFixture("KEY_LAYOUT")
    lines = []
    for row in sorted(rows, key=lambda r: (r["acct_id"], r["type_cd"], r["cat_cd"])):
        amount = Decimal(str(row["balance"]))
        if not amount.is_finite() or amount != amount.quantize(Decimal(".01")) or abs(amount) > Decimal("999999999.99"):
            raise InvalidFixture("BALANCE_RANGE")
        acct, code, category = row["acct_id"], row["type_cd"], row["cat_cd"]
        if len(acct) != 11 or not acct.isascii() or not acct.isdigit() or len(code) != 2:
            raise InvalidFixture("KEY_LAYOUT")
        sign = "-" if mode == "fixed" and amount < 0 else " "
        try:
            line = f"{acct} {code} {category} {abs(amount):012.2f}{sign}       \n".encode("ascii")
        except UnicodeError:
            raise InvalidFixture("KEY_ENCODING") from None
        if len(line) != 41:
            raise InvalidFixture("LINE_LENGTH")
        lines.append(line)
    return b"".join(lines)


def validate(case: str, rows: list[dict], mode: str, output: bytes, status: str,
             before_hashes: dict, after_hashes: dict, window_open: bool) -> list[Finding]:
    findings = completed(case, status) + unchanged(before_hashes, after_hashes, case, "core.tcat_bal")
    if window_open:
        findings.append(Finding(case, "NO_WINDOW", "ops.batch_window"))
    wanted = expected(rows, mode)
    if output != wanted:
        # Deliberately never show either the actual or expected line.
        count = max(len(output), len(wanted))
        offset = next((i for i in range(count) if output[i:i+1] != wanted[i:i+1]), 0)
        findings.append(Finding(case, "LISTING_BYTES", "PRTCATBL.txt", offset // 41 + 1, str(offset % 41 + 1)))
    return findings


def compare_modes(case: str, rows: list[dict], legacy: bytes, fixed: bytes) -> list[Finding]:
    """Also checks both files, so an identical defect cannot pass."""
    if legacy == expected(rows, "legacy") and fixed == expected(rows, "fixed"):
        return []
    return [Finding(case, "MODE_DIFFERENCES", "PRTCATBL.txt")]
