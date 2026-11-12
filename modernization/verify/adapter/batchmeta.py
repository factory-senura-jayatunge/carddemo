"""Execution metadata from batch-service's Spring Batch repository (schema batch).

Everything is read for one JOB_EXECUTION_ID only. When a step name occurs more
than once in that execution, the step execution with the highest
STEP_EXECUTION_ID wins for counts, status and context; earlier executions of
the same job instance (restarts) are never consulted.
"""
from __future__ import annotations

from typing import Any, Mapping, Sequence

from . import javaser

COUNT_COLUMNS = ("READ_COUNT", "WRITE_COUNT", "COMMIT_COUNT", "ROLLBACK_COUNT", "FILTER_COUNT",
                 "READ_SKIP_COUNT", "WRITE_SKIP_COUNT", "PROCESS_SKIP_COUNT")
STEP_STATUSES = frozenset({"COMPLETED", "STARTING", "STARTED", "STOPPING", "STOPPED", "FAILED",
                           "ABANDONED", "UNKNOWN"})


class MetadataError(ValueError):
    """A repository answer that does not have the expected shape; value-free."""


def _execution_id(value) -> int:
    # The batch sequences start at 0, so 0 is a valid execution ID.
    if type(value) is not int or not 0 <= value <= 2 ** 63 - 1:
        raise MetadataError("EXECUTION_ID")
    return value


def steps_sql(job_execution_id: int) -> str:
    job = _execution_id(job_execution_id)
    return ("SELECT s.STEP_EXECUTION_ID, s.STEP_NAME, s.STATUS, "
            + ", ".join(f"s.{c}" for c in COUNT_COLUMNS) +
            ", c.SHORT_CONTEXT, c.SERIALIZED_CONTEXT "
            "FROM batch.BATCH_STEP_EXECUTION s "
            "LEFT JOIN batch.BATCH_STEP_EXECUTION_CONTEXT c ON c.STEP_EXECUTION_ID = s.STEP_EXECUTION_ID "
            f"WHERE s.JOB_EXECUTION_ID = {job} ORDER BY s.STEP_EXECUTION_ID")


def job_sql(job_execution_id: int) -> str:
    job = _execution_id(job_execution_id)
    return ("SELECT e.JOB_EXECUTION_ID, e.JOB_INSTANCE_ID, e.STATUS, e.EXIT_CODE, e.EXIT_MESSAGE, "
            "c.SHORT_CONTEXT, c.SERIALIZED_CONTEXT "
            "FROM batch.BATCH_JOB_EXECUTION e "
            "LEFT JOIN batch.BATCH_JOB_EXECUTION_CONTEXT c ON c.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID "
            f"WHERE e.JOB_EXECUTION_ID = {job}")


def _context(row: Mapping) -> dict:
    short, serialized = row.get("SHORT_CONTEXT"), row.get("SERIALIZED_CONTEXT")
    if short is None and serialized is None:
        return {}
    return javaser.decode(javaser.stored_text(short, serialized))


def latest_steps(rows: Sequence[Mapping]) -> dict[str, Mapping]:
    latest: dict[str, Mapping] = {}
    seen = set()
    for row in rows:
        step_id, name = row.get("STEP_EXECUTION_ID"), row.get("STEP_NAME")
        _execution_id(step_id)
        if not isinstance(name, str) or not name or step_id in seen:
            raise MetadataError("STEP_ROW")
        seen.add(step_id)
        if name not in latest or step_id > latest[name]["STEP_EXECUTION_ID"]:
            latest[name] = row
    return latest


def steps(rows: Sequence[Mapping]) -> tuple[dict, dict, dict]:
    """(step_counts, step_statuses, contexts) by step name, from steps_sql's rows."""
    counts, statuses, contexts = {}, {}, {}
    for name, row in latest_steps(rows).items():
        values = {}
        for column in COUNT_COLUMNS:
            value = row.get(column)
            if type(value) is not int or value < 0:
                raise MetadataError("STEP_COUNT")
            values[column] = value
        status = row.get("STATUS")
        if status not in STEP_STATUSES:
            raise MetadataError("STEP_STATUS")
        counts[name], statuses[name] = values, status
        contexts[name] = _context(row)
    return counts, statuses, contexts


def job(rows: Sequence[Mapping], job_execution_id: int) -> dict[str, Any]:
    """job_sql's single row: status, IDs, exit description and the decoded job context."""
    wanted = _execution_id(job_execution_id)
    if len(rows) != 1 or type(rows[0].get("JOB_EXECUTION_ID")) is not int or rows[0]["JOB_EXECUTION_ID"] != wanted:
        raise MetadataError("JOB_ROW")
    row = rows[0]
    status, exit_message = row.get("STATUS"), row.get("EXIT_MESSAGE")
    if status not in STEP_STATUSES or not (exit_message is None or isinstance(exit_message, str)):
        raise MetadataError("JOB_ROW")
    return {"status": status, "execution_id": row["JOB_EXECUTION_ID"],
            "job_instance_id": _execution_id(row.get("JOB_INSTANCE_ID")),
            "exit_description": exit_message or "", "job_context": _context(row)}
