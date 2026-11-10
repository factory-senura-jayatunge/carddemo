"""DAG structure: import, task order, trigger rules, parameters, schedules,
the batch pool and the jobs each task launches (architecture batch.md)."""

from __future__ import annotations

import json
import os

import pytest

from conftest import AIRFLOW_DIR

DAG_IDS = {
    "carddemo_daily_cycle",
    "carddemo_transaction_report",
    "carddemo_category_balance_listing",
    "carddemo_reference_refresh",
    "carddemo_data_load",
    "carddemo_export_masters",
    "carddemo_import_migration",
}
# DAGs that change data share the one-slot pool; the others only read.
CHANGES_DATA = {"carddemo_daily_cycle", "carddemo_reference_refresh", "carddemo_data_load",
                "carddemo_import_migration"}

DAILY_ORDER = [
    "register_cycle",
    "open_batch_window",
    "purge_pending_authorizations_before_posting",
    "archive_master",
    "ingest_daily_file",
    "post_daily_transactions",
    "calculate_interest",
    "generate_statements",
    "apply_retention",
    "close_batch_window",
    "purge_pending_authorizations",
]

JOBS = {
    "carddemo_daily_cycle": {
        "purge_pending_authorizations_before_posting": "purgePendingAuthorizationsJob",
        "archive_master": "archiveMasterJob",
        "ingest_daily_file": "ingestDailyFileJob",
        "post_daily_transactions": "postDailyTransactionsJob",
        "calculate_interest": "calculateInterestJob",
        "generate_statements": "statementJob",
        "apply_retention": "applyRetentionJob",
        "purge_pending_authorizations": "purgePendingAuthorizationsJob",
    },
    "carddemo_transaction_report": {"run_report": "transactionReportJob"},
    "carddemo_category_balance_listing": {"run_listing": "categoryBalanceListingJob"},
    "carddemo_reference_refresh": {
        "maintain_transaction_types": "transactionTypeMaintenanceJob",
        "reload_disclosure_groups": "disclosureGroupReloadJob",
    },
    "carddemo_data_load": {"load": "dataLoadJob"},
    "carddemo_export_masters": {"export_masters": "exportMastersJob"},
    "carddemo_import_migration": {"import_migration": "importMigrationJob"},
}


def linear_order(dag) -> list:
    """Task IDs in order when the DAG is one chain; fails otherwise."""
    roots = [t for t in dag.tasks if not t.upstream_task_ids]
    assert len(roots) == 1, f"{dag.dag_id}: roots {[t.task_id for t in roots]}"
    order, task = [], roots[0]
    while True:
        order.append(task.task_id)
        nxt = sorted(task.downstream_task_ids)
        if not nxt:
            return order
        assert len(nxt) == 1, f"{dag.dag_id}: {task.task_id} fans out to {nxt}"
        task = dag.get_task(nxt[0])


def test_no_import_errors(dagbag):
    assert dagbag.import_errors == {}


def test_exactly_the_specified_dags(dagbag):
    assert set(dagbag.dag_ids) == DAG_IDS


@pytest.mark.parametrize("dag_id", sorted(DAG_IDS))
def test_dag_serializes(dagbag, dag_id):
    """The scheduler works on the serialized DAG; a DAG that does not
    serialize parses but never runs."""
    from airflow.serialization.serialized_objects import DagSerialization

    data = DagSerialization.to_dict(dagbag.dags[dag_id])
    restored = DagSerialization.from_dict(data)
    assert set(restored.task_ids) == set(dagbag.dags[dag_id].task_ids)


def test_daily_cycle_order(dagbag):
    assert linear_order(dagbag.dags["carddemo_daily_cycle"]) == DAILY_ORDER


def test_daily_cycle_trigger_rules(dagbag):
    """Tasks after a task that may be skipped run unless something failed;
    close_batch_window never runs after a failure, so the window stays open."""
    dag = dagbag.dags["carddemo_daily_cycle"]
    none_failed = {"archive_master", "generate_statements", "apply_retention", "close_batch_window"}
    for task in dag.tasks:
        expected = "none_failed" if task.task_id in none_failed else "all_success"
        assert task.trigger_rule == expected, task.task_id


def test_daily_cycle_schedule_and_runs(dagbag):
    dag = dagbag.dags["carddemo_daily_cycle"]
    assert dag.timetable.expression == "0 0 * * *"
    assert dag.catchup is False
    assert dag.max_active_runs == 1


def test_daily_cycle_params(dagbag):
    params = dagbag.dags["carddemo_daily_cycle"].params
    expected = {
        "interest": ("always", ["always", "month_end", "never"]),
        "statements": ("every_cycle", ["every_cycle", "with_interest"]),
        "purge_position": ("after_cycle", ["after_cycle", "before_posting"]),
    }
    for name, (default, enum) in expected.items():
        param = params.get_param(name)
        assert param.value == default, name
        assert param.schema["enum"] == enum, name
    assert params.get_param("businessDate").value is None


def test_daily_cycle_tasks_use_the_cycle(dagbag):
    dag = dagbag.dags["carddemo_daily_cycle"]
    for task in dag.tasks:
        if task.task_id != "register_cycle":
            assert task.with_cycle, task.task_id


def test_conditional_tasks(dagbag):
    from carddemo_airflow import cycle

    dag = dagbag.dags["carddemo_daily_cycle"]
    assert dag.get_task("calculate_interest").run_if is cycle.cycle_interest
    assert dag.get_task("generate_statements").run_if is cycle.statements_due
    assert dag.get_task("purge_pending_authorizations_before_posting").run_if is cycle.purge_before_posting
    assert dag.get_task("purge_pending_authorizations").run_if is cycle.purge_after_cycle
    ref = dagbag.dags["carddemo_reference_refresh"]
    assert ref.get_task("maintain_transaction_types").run_if is cycle.transaction_type_input_present


@pytest.mark.parametrize("dag_id,order", [
    ("carddemo_reference_refresh",
     ["open_batch_window", "maintain_transaction_types", "reload_disclosure_groups", "close_batch_window"]),
    ("carddemo_data_load", ["open_batch_window", "load", "close_batch_window"]),
    ("carddemo_transaction_report", ["run_report"]),
    ("carddemo_category_balance_listing", ["run_listing"]),
    ("carddemo_export_masters", ["export_masters"]),
    ("carddemo_import_migration", ["import_migration"]),
])
def test_other_dag_order(dagbag, dag_id, order):
    assert linear_order(dagbag.dags[dag_id]) == order


def test_reference_refresh_runs_saturdays(dagbag):
    dag = dagbag.dags["carddemo_reference_refresh"]
    assert dag.timetable.expression == "0 6 * * 6"
    assert dag.catchup is False
    assert dag.get_task("reload_disclosure_groups").trigger_rule == "none_failed"
    assert dag.get_task("close_batch_window").trigger_rule == "none_failed"


@pytest.mark.parametrize("dag_id", sorted(DAG_IDS - {"carddemo_daily_cycle", "carddemo_reference_refresh"}))
def test_on_demand_dags_have_no_schedule(dagbag, dag_id):
    dag = dagbag.dags[dag_id]
    assert type(dag.timetable).__name__ == "NullTimetable"
    assert dag.catchup is False


def test_transaction_report_params(dagbag):
    dag = dagbag.dags["carddemo_transaction_report"]
    assert set(dag.params.keys()) == {"runId", "startDate", "endDate", "requestedBy"}
    task = dag.get_task("run_report")
    assert set(task.job_params) == {"runId", "startDate", "endDate", "requestedBy"}


@pytest.mark.parametrize("dag_id", sorted(DAG_IDS))
def test_jobs(dagbag, dag_id):
    dag = dagbag.dags[dag_id]
    jobs = {t.task_id: t.job_name for t in dag.tasks if hasattr(t, "job_name")}
    assert jobs == JOBS[dag_id]


@pytest.mark.parametrize("dag_id", sorted(DAG_IDS))
def test_batch_pool(dagbag, dag_id):
    dag = dagbag.dags[dag_id]
    for task in dag.tasks:
        if dag_id in CHANGES_DATA:
            assert task.pool == "carddemo_batch", task.task_id
        else:
            assert task.pool != "carddemo_batch", task.task_id
    if dag_id in CHANGES_DATA:
        assert dag.max_active_runs == 1


def test_pool_definition():
    """make up imports pools.json; one slot, and deferred waits keep it."""
    with open(os.path.join(AIRFLOW_DIR, "pools.json"), encoding="utf-8") as f:
        pools = json.load(f)
    assert pools["carddemo_batch"]["slots"] == 1
    assert pools["carddemo_batch"]["include_deferred"] is True


@pytest.mark.parametrize("dag_id", sorted(DAG_IDS))
def test_no_retries_on_data_changes(dagbag, dag_id):
    """Failure policy: stop the chain and alert; never re-launch a job by itself."""
    for task in dagbag.dags[dag_id].tasks:
        if dag_id in CHANGES_DATA:
            assert task.retries == 0, task.task_id
