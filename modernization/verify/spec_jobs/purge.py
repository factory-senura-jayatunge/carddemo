"""D-33/D-34, BR-AUPG-002/-003/-006: pure before-state purge oracle."""
from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Mapping

from .common import Finding, InvalidFixture, completed, unchanged, require_integer, equal_scalar

TOTALS = ("approved_auth_cnt", "approved_auth_amt", "declined_auth_cnt", "declined_auth_amt")
AMOUNTS = ("approved_auth_amt", "declined_auth_amt", "credit_balance")


def day(value) -> date:
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    return date.fromisoformat(str(value)[:10])


def cutoff(business_date: str | date, expiry_days: int = 5) -> date:
    try:
        if isinstance(expiry_days, bool) or not isinstance(expiry_days, int) or expiry_days < 1:
            raise ValueError
        return day(business_date) - timedelta(days=expiry_days)
    except (ValueError, OverflowError, TypeError):
        raise InvalidFixture("INVALID_PURGE_PARAMETERS") from None


@dataclass(frozen=True)
class Result:
    summaries: dict
    details: dict
    messages: dict
    outbox: dict
    totals: dict


def expected(summaries: Mapping, details: Mapping, messages: Mapping, outbox: Mapping,
             *, business_date: str | date, database_utc_date: str | date,
             expiry_days: int = 5) -> Result:
    expires = cutoff(business_date, expiry_days)
    try:
        message_expiry = min(expires, day(database_utc_date) - timedelta(days=expiry_days))
    except OverflowError:
        message_expiry = None
    sums, kept = deepcopy(dict(summaries)), deepcopy(dict(details))
    totals = dict(summaries_read=len(sums),
                  details_read=sum(d["acct_id"] in sums for d in details.values()),
                  summaries_deleted=0, details_deleted=0, summaries_nonzero_deleted=0)
    for row in sums.values():
        for column in AMOUNTS:
            row[column] = Decimal(str(row[column]))
        for column in ("approved_auth_cnt", "declined_auth_cnt"):
            row[column] = require_integer(row[column])
    for key, row in details.items():
        if row["acct_id"] not in sums:
            raise InvalidFixture("ORPHAN_DETAIL")
        if day(row["processed_at"]) > expires or row["auth_fraud"] == "F":
            continue
        summary = sums[row["acct_id"]]
        if row["auth_resp_code"] == "00":
            amount = Decimal(str(row["approved_amt"]))
            summary["approved_auth_cnt"] -= 1
            summary["approved_auth_amt"] -= amount
            if row["match_status"] == "P":
                summary["credit_balance"] -= amount
        else:
            summary["declined_auth_cnt"] -= 1
            summary["declined_auth_amt"] -= Decimal(str(row["transaction_amt"]))
        del kept[key]
        totals["details_deleted"] += 1
    remaining_accounts = {row["acct_id"] for row in kept.values()}
    for key, row in list(sums.items()):
        if key not in remaining_accounts:
            totals["summaries_deleted"] += 1
            totals["summaries_nonzero_deleted"] += int(any(
                Decimal(str(row.get(c, 0))) != 0 for c in (*TOTALS, "credit_balance", "cash_balance")))
            del sums[key]
    msgs = {k: deepcopy(v) for k, v in messages.items()
            if message_expiry is None or day(v["received_at"]) > message_expiry}
    outs = {k: deepcopy(v) for k, v in outbox.items()
            if message_expiry is None or v["sent_at"] is None or day(v["created_at"]) > message_expiry}
    return Result(sums, kept, msgs, outs, totals)


def validate(case: str, wanted: Result, actual: Result, *, status: str,
             fraud_before: dict, fraud_after: dict, window_open: bool = False,
             cycle_completed: bool = True) -> list[Finding]:
    findings = completed(case, status) + unchanged(fraud_before, fraud_after, case, "pauth.auth_fraud")
    if window_open or not cycle_completed:
        findings.append(Finding(case, "PURGE_CYCLE_ORDER", "ops.batch_window"))
    for name in ("summaries", "details", "messages", "outbox", "totals"):
        expected, found = getattr(wanted, name), getattr(actual, name)
        same = expected == found
        if name == "totals":
            same = expected.keys() == found.keys() and all(equal_scalar(found[k], v) for k, v in expected.items())
        elif name == "summaries":
            same = same and all(equal_scalar(found[k].get(c), row[c]) for k, row in expected.items()
                                for c in ("approved_auth_cnt", "declined_auth_cnt") if c in row)
        if not same:
            findings.append(Finding(case, "PURGE_" + name.upper(), "pauth" if name != "totals" else "execution_context"))
    return findings


def validate_invalid(case: str, *, status: str, exit_description: str,
                     before: Mapping, after: Mapping) -> list[Finding]:
    findings = unchanged(before, after, case, "pauth")
    if status != "FAILED" or "INVALID" not in exit_description.upper() or "PARAM" not in exit_description.upper():
        findings.append(Finding(case, "INVALID_PARAMETER_FAILURE", "execution"))
    return findings
