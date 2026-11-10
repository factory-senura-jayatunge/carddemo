"""Run settings shared by the DAG files: dates, templates and the decisions
that choose which cycle tasks run. DAG files import only Airflow and this
package, so these helpers live here."""

from __future__ import annotations

import calendar
import datetime
import json
import os

import pendulum

# DAGs never catch up; the start date only has to be in the past.
START_DATE = pendulum.datetime(2022, 7, 1, tz="UTC")

# The business date is the conf/param override when given, else the run's
# logical date. batch-service keeps one cycle per business date.
BUSINESS_DATE = "{{ params.businessDate or ds }}"
# A run ID that is safe as a directory name (var/reports/<job>/<runId>/).
RUN_ID = "{{ run_id | replace(':', '') | replace('+', '') }}"

INTEREST_CHOICES = ["always", "month_end", "never"]
STATEMENT_CHOICES = ["every_cycle", "with_interest"]
PURGE_CHOICES = ["after_cycle", "before_posting"]

POOLS_FILE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "pools.json")


def batch_pool_slots() -> int:
    with open(POOLS_FILE, encoding="utf-8") as f:
        return json.load(f)["carddemo_batch"]["slots"]


def business_date(context) -> datetime.date:
    value = (context.get("params") or {}).get("businessDate") or context["ds"]
    return datetime.date.fromisoformat(str(value))


def interest_due(context) -> bool:
    """params.interest: always, never, or month_end (the last day of the
    business date's month)."""
    choice = context["params"]["interest"]
    if choice == "always":
        return True
    if choice == "never":
        return False
    day = business_date(context)
    return day.day == calendar.monthrange(day.year, day.month)[1]


def cycle_interest(context) -> bool:
    """Whether the run's cycle charges interest, as register_cycle decided."""
    registered = context["ti"].xcom_pull(task_ids="register_cycle") or {}
    return bool(registered.get("interest"))


def statements_due(context) -> bool:
    if context["params"]["statements"] == "every_cycle":
        return True
    return cycle_interest(context)


def purge_before_posting(context) -> bool:
    return context["params"]["purge_position"] == "before_posting"


def purge_after_cycle(context) -> bool:
    return context["params"]["purge_position"] == "after_cycle"


def inbound_file(kind: str, context) -> str:
    var_dir = os.environ.get("CARDDEMO_VAR_DIR", "var")
    return os.path.join(var_dir, "inbound", kind, f"{business_date(context).isoformat()}.txt")


def transaction_type_input_present(context) -> bool:
    """Batch type maintenance runs only when its input file was delivered (D-BAT-07)."""
    return os.path.exists(inbound_file("trantype", context))
