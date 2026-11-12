"""Run settings shared by the DAG files: dates, parameter choices and the
decisions that choose which cycle tasks run. DAG files import only Airflow
and this package, so these helpers live here."""

from __future__ import annotations

import datetime
import json
import os

import pendulum
from airflow.sdk.exceptions import AirflowFailException

# DAGs never catch up; the start date only has to be in the past.
START_DATE = pendulum.datetime(2022, 7, 1, tz="UTC")

INTEREST_CHOICES = ["always", "month_end", "never"]
STATEMENT_CHOICES = ["every_cycle", "with_interest"]
PURGE_CHOICES = ["after_cycle", "before_posting"]

POOLS_FILE = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "pools.json")


def batch_pool_slots() -> int:
    with open(POOLS_FILE, encoding="utf-8") as f:
        return json.load(f)["carddemo_batch"]["slots"]


def business_date(context) -> datetime.date:
    """params.businessDate when given, else the run's logical date; a manual
    run without a logical date takes the date it was triggered for."""
    value = (context.get("params") or {}).get("businessDate") or context.get("ds")
    if not value:
        run = context.get("dag_run")
        run_after = getattr(run, "run_after", None)
        if run_after is None:
            raise ValueError("the run has no logical date; trigger it with the param businessDate")
        value = run_after.date().isoformat()
    return datetime.date.fromisoformat(str(value)[:10])


def cycle_interest(context) -> bool:
    """Whether the run's cycle charges interest: the boolean that
    batch-service stored when it resolved the run's interest policy."""
    registered = context["ti"].xcom_pull(task_ids="register_cycle") or {}
    return registered.get("interest") is True


def statements_due(context) -> bool:
    if context["params"]["statements"] == "every_cycle":
        return True
    return cycle_interest(context)


def purge_before_posting(context) -> bool:
    return context["params"]["purge_position"] == "before_posting"


def purge_after_cycle(context) -> bool:
    return context["params"]["purge_position"] == "after_cycle"


def inbound_file(kind: str, context) -> str:
    """<CARDDEMO_VAR_DIR>/inbound/<kind>/<businessDate>.txt. stack.py gives
    every Airflow process the absolute setting; without it the task fails, so
    a missing file is never assumed because of the working directory."""
    var_dir = os.environ.get("CARDDEMO_VAR_DIR", "")
    if not os.path.isabs(var_dir):
        raise AirflowFailException("CARDDEMO_VAR_DIR is unset or relative")
    return os.path.join(var_dir, "inbound", kind, f"{business_date(context).isoformat()}.txt")


def transaction_type_input_present(context) -> bool:
    """Batch type maintenance runs only when its input file was delivered (D-BAT-07)."""
    return os.path.exists(inbound_file("trantype", context))
