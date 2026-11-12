"""Live purge observations. Expected deletion and totals come only from purge.py."""
from __future__ import annotations

from datetime import timedelta
from dataclasses import replace
from decimal import Decimal
import re

from . import fixtures, purge
from .common import Finding, require_integer
from .session import Execution, Session

SUMMARY_COLUMNS = ("acct_id", "cust_id", "credit_limit", "cash_limit", "credit_balance",
                   "cash_balance", *purge.TOTALS)
CONTEXT_KEYS = ("summaries_read", "details_read", "summaries_deleted", "details_deleted",
                "summaries_nonzero_deleted")
PUBLIC_CONTEXT_KEYS = ("summariesRead", "detailsRead", "summariesDeleted", "detailsDeleted",
                       "summariesDeletedWithNonZeroTotals")


def indexed(rows, key):
    return {(require_integer(row[key]) if key in ("auth_id", "outbox_id") else row[key]): row for row in rows}


def state(session: Session) -> purge.Result:
    reader = session.as_login("fixture")
    summaries = indexed(reader.query("SELECT " + ",".join(SUMMARY_COLUMNS) +
                                      ",CONVERT(varchar(16),row_version,2) _row_version" +
                                      " FROM pauth.auth_summary ORDER BY acct_id"), "acct_id")
    # The non-changing summary columns are compared as values; ROWVERSION is
    # database metadata and necessarily changes when totals are updated.
    for row in summaries.values():
        for column in ("approved_auth_cnt", "declined_auth_cnt"):
            require_integer(row[column])
        for column in purge.AMOUNTS + ("credit_limit", "cash_limit", "cash_balance"):
            row[column] = Decimal(str(row[column]))
    details = indexed(reader.query(
        "SELECT auth_id,acct_id,processed_at,auth_fraud,auth_resp_code,approved_amt,transaction_amt,match_status "
        "FROM pauth.auth_detail ORDER BY auth_id"), "auth_id")
    messages = indexed(reader.query(
        "SELECT CONVERT(varchar(64),msg_key,2) msg_key,received_at FROM pauth.processed_message ORDER BY msg_key"), "msg_key")
    outbox = indexed(reader.query("SELECT outbox_id,created_at,sent_at FROM pauth.outbox ORDER BY outbox_id"), "outbox_id")
    for rows, table, key in ((details, "auth_detail", "auth_id"), (messages, "processed_message", "msg_key"),
                             (outbox, "outbox", "outbox_id")):
        digests = session.snapshot_digest("pauth." + table, (key,))
        for value, row in rows.items():
            # Binary msg keys use the adapter's native key form, not the
            # projection's rendering. A fixed hex expression is specified in
            # the adapter contract for this one non-surrogate key.
            digest_key = (value,)
            if digest_key not in digests and key == "msg_key":
                digest_key = (bytes.fromhex(value),)
            row["_digest"] = digests[digest_key]
    return purge.Result(summaries, details, messages, outbox, {})


def totals(execution: Execution) -> dict:
    # Exact public context names are declared by architecture, never learned
    # from observed job output. Missing/ambiguous keys fail the case.
    context = execution.contexts.get("purge", {})
    if not all(k in context for k in PUBLIC_CONTEXT_KEYS):
        raise ValueError("PURGE_CONTEXT_REQUIRED")
    result = {model: require_integer(context[public]) for model, public in zip(CONTEXT_KEYS, PUBLIC_CONTEXT_KEYS)}
    messages = execution.contexts.get("purgeMessages", {})
    if not all(k in context for k in ("fraudDetailsKept", "creditReleased")) or not all(
            k in messages for k in ("processedMessagesDeleted", "outboxRowsDeleted", "messagesBefore")):
        raise ValueError("PURGE_EXTRA_CONTEXT_REQUIRED")
    credit = context["creditReleased"]
    if not isinstance(credit, str) or not re.fullmatch(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?", credit):
        raise ValueError("PURGE_CREDIT_ENCODING")
    if result["summaries_read"] == 0 and credit != "0":
        raise ValueError("PURGE_EMPTY_CREDIT_ENCODING")
    result.update(fraud_details_kept=require_integer(context["fraudDetailsKept"]), credit_released=Decimal(credit),
                  processed_messages_deleted=require_integer(messages["processedMessagesDeleted"]),
                  outbox_rows_deleted=require_integer(messages["outboxRowsDeleted"]), messages_before=messages["messagesBefore"])
    return result


def expected_counters(before: purge.Result, wanted: purge.Result, business, utc, expiry) -> purge.Result:
    """Counter names/semantics were reconciled by the architect, not independently specified."""
    expires = purge.cutoff(business, expiry)
    try:
        capped = min(expires, purge.day(utc) - timedelta(days=expiry))
        bound = (capped + timedelta(days=1)).isoformat() + "T00:00"
    except OverflowError:
        bound = "none"
    deleted = before.details.keys() - wanted.details.keys()
    released = sum((Decimal(str(before.details[k]["approved_amt"])) for k in deleted
                    if before.details[k]["auth_resp_code"] == "00" and before.details[k]["match_status"] == "P"),
                   Decimal(0))
    extras = dict(fraud_details_kept=sum(d["auth_fraud"] == "F" and purge.day(d["processed_at"]) <= expires
                                        for d in before.details.values()),
                  credit_released=released, processed_messages_deleted=len(before.messages) - len(wanted.messages),
                  outbox_rows_deleted=len(before.outbox) - len(wanted.outbox), messages_before=bound)
    return replace(wanted, totals={**wanted.totals, **extras})


def exclude_updated_metadata(before: purge.Result, wanted: purge.Result, actual: purge.Result):
    """ROWVERSION stays checked on every summary not expected to be written."""
    deleted = before.details.keys() - wanted.details.keys()
    updated = {before.details[k]["acct_id"] for k in deleted}
    for key in updated:
        for result in (wanted, actual):
            if key in result.summaries:
                result.summaries[key].pop("_row_version", None)


def run(session: Session, case: str, mode: str) -> tuple[list[Finding], Execution]:
    if case in ("edges", "year_end"):
        session.fixture_execute(fixtures.purge_edges(case == "year_end"))
    before = state(session)
    if case.startswith("sample_") and (len(before.summaries), len(before.details)) != (21, 202):
        raise ValueError("PURGE_SAMPLE_COUNT")
    eligible = sorted({purge.day(d["processed_at"]) for d in before.details.values() if d["auth_fraud"] != "F"})
    if not eligible:
        raise ValueError("PURGE_SAMPLE_REQUIRED")
    expiry = 1 if case == "one" else 5
    if case == "sample_partial":
        business = eligible[len(eligible) // 2] + timedelta(days=expiry)
    else:
        business = eligible[-1] + timedelta(days=expiry)
    if case == "edges":
        business = purge.day("2022-07-06")
    elif case == "year_end":
        business = purge.day("2023-01-02")
    if case == "underflow":
        business, expiry = purge.day("0001-01-01"), 1
    elif case == "zero":
        expiry = 0
    cycle = require_integer(session.cycle(business.isoformat(), completed=True))
    params = {"cycleId": cycle, "businessDate": business.isoformat(), "attempt": 1,
              "commitInterval": int(case.rsplit("_", 1)[1]) if case.startswith("interval_") else 10}
    if case != "default":
        params["expiryDays"] = expiry
    utc = session.query("SELECT CONVERT(varchar(10),SYSUTCDATETIME(),23) utc_date")[0]["utc_date"]
    fraud_before = session.snapshot_digest("pauth.auth_fraud", ("auth_id",))
    if case in ("zero", "underflow"):
        execution = session.launch("purgePendingAuthorizationsJob", params).wait()
        problems = purge.validate_invalid(case, status=execution.status, exit_description=execution.exit_description,
                                          before=before.__dict__, after=state(session).__dict__)
        if session.snapshot_digest("pauth.auth_fraud", ("auth_id",)) != fraud_before:
            problems.append(Finding(case, "UNCHANGED", "pauth.auth_fraud"))
        return problems, execution
    wanted = purge.expected(before.summaries, before.details, before.messages, before.outbox,
                            business_date=business, database_utc_date=utc, expiry_days=expiry)
    wanted = expected_counters(before, wanted, business, utc, expiry)
    execution = session.launch("purgePendingAuthorizationsJob", params).wait()
    findings = []
    if case == "rerun":
        first = state(session)
        first_actual = purge.Result(first.summaries, first.details, first.messages, first.outbox, totals(execution))
        exclude_updated_metadata(before, wanted, first_actual)
        findings += purge.validate(case, wanted, first_actual, status=execution.status,
                                    fraud_before=fraud_before,
                                    fraud_after=session.snapshot_digest("pauth.auth_fraud", ("auth_id",)))
        before = state(session)
        wanted = purge.expected(before.summaries, before.details, before.messages, before.outbox,
                                business_date=business, database_utc_date=utc, expiry_days=expiry)
        wanted = expected_counters(before, wanted, business, utc, expiry)
        params["attempt"] = 2
        execution = session.launch("purgePendingAuthorizationsJob", params).wait()
    after = state(session)
    actual = purge.Result(after.summaries, after.details, after.messages, after.outbox, totals(execution))
    exclude_updated_metadata(before, wanted, actual)
    completed = session.query(f"SELECT status FROM batch.cycle WHERE cycle_id={cycle}")[0]["status"] == "COMPLETED"
    windows = session.query("SELECT window_id FROM ops.batch_window WHERE closed_at IS NULL")
    for row in windows:
        require_integer(row["window_id"])
    opened = bool(windows)
    findings += purge.validate(case, wanted, actual, status=execution.status, fraud_before=fraud_before,
                               fraud_after=session.snapshot_digest("pauth.auth_fraud", ("auth_id",)),
                               window_open=opened, cycle_completed=completed)
    return findings, execution
