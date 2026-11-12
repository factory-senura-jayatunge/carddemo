"""DAG structure: import, task order, trigger rules, parameters, schedules,
the batch pool and the jobs each task launches (architecture batch.md)."""

from __future__ import annotations

import json
import os

import pytest

from conftest import AIRFLOW_DIR, WINDOW_JOBS

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


def test_inbound_file_is_under_the_absolute_var_dir_from_any_cwd(var_dir, make_context, monkeypatch, tmp_path):
    from carddemo_airflow import cycle

    context = make_context(ds="2022-07-06")
    expected = os.path.join(str(var_dir), "inbound", "trantype", "2022-07-06.txt")
    assert cycle.inbound_file("trantype", context) == expected
    assert not cycle.transaction_type_input_present(context)
    os.makedirs(os.path.dirname(expected))
    with open(expected, "w", encoding="utf-8"):
        pass
    for cwd in (tmp_path, os.path.dirname(AIRFLOW_DIR)):
        monkeypatch.chdir(cwd)
        assert cycle.transaction_type_input_present(context)


@pytest.mark.parametrize("value", [None, "", "var", "./var"])
def test_inbound_file_fails_without_an_absolute_var_dir(value, make_context, monkeypatch, tmp_path):
    from airflow.sdk.exceptions import AirflowFailException

    from carddemo_airflow import cycle

    if value is None:
        monkeypatch.delenv("CARDDEMO_VAR_DIR")
    else:
        monkeypatch.setenv("CARDDEMO_VAR_DIR", value)
    # A relative "var" next to the working directory must not be taken for it.
    os.makedirs(tmp_path / "var" / "inbound" / "trantype" / "2022-07-06.txt")
    monkeypatch.chdir(tmp_path)
    with pytest.raises(AirflowFailException, match="CARDDEMO_VAR_DIR"):
        cycle.transaction_type_input_present(make_context(ds="2022-07-06"))


@pytest.mark.parametrize("dag_id,order", [
    ("carddemo_reference_refresh",
     ["open_batch_window", "maintain_transaction_types", "reload_disclosure_groups", "close_batch_window"]),
    ("carddemo_data_load", ["open_batch_window", "load", "close_batch_window"]),
    ("carddemo_transaction_report", ["run_report"]),
    ("carddemo_category_balance_listing", ["run_listing"]),
    ("carddemo_export_masters", ["export_masters"]),
    ("carddemo_import_migration", ["open_batch_window", "import_migration", "close_batch_window"]),
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
    assert dag.render_template_as_native_obj is True
    # The job reads the stored report run; only its ID is sent.
    assert dag.get_task("run_report").job_params == {"runId": "{{ params.runId }}"}


@pytest.mark.parametrize("dag_id", sorted(DAG_IDS))
def test_only_the_report_runs_without_unpausing(dagbag, dag_id):
    # transaction-service's trigger must start a run; every DAG that changes
    # data waits until someone unpauses it (DAGS_ARE_PAUSED_AT_CREATION).
    assert (dagbag.dags[dag_id].is_paused_upon_creation is False) == (dag_id == "carddemo_transaction_report")


def test_import_takes_an_integer_export_id(dagbag):
    dag = dagbag.dags["carddemo_import_migration"]
    assert dag.params.get_param("exportId").schema["type"] == "integer"
    assert dag.render_template_as_native_obj is True
    assert dag.get_task("import_migration").job_params == {"exportId": "{{ params.exportId }}"}


@pytest.mark.parametrize("dag_id", sorted(DAG_IDS))
def test_window_jobs_run_inside_the_runs_window(dagbag, dag_id):
    """Contract section 6: a window job runs between its run's open and
    close; a DAG without one opens no window. Only the cycle holds it by
    cycleId."""
    dag = dagbag.dags[dag_id]
    order = linear_order(dag)
    window_tasks = [t for t in order if hasattr(dag.get_task(t), "job_name")
                    and dag.get_task(t).job_name in WINDOW_JOBS]
    if not window_tasks:
        assert "open_batch_window" not in order and "close_batch_window" not in order
        return
    first, last = order.index("open_batch_window"), order.index("close_batch_window")
    assert all(first < order.index(t) < last for t in window_tasks)
    for t in ("open_batch_window", "close_batch_window"):
        assert dag.get_task(t).with_cycle is (dag_id == "carddemo_daily_cycle"), t


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
def test_every_task_can_recover_a_lost_execution(dagbag, dag_id):
    """Contract section 5: a lost owner's execution is recovered by the
    launch of a task retry, so every task retries at least twice, and
    execution_timeout bounds what recovery cannot resolve. A failed job
    still stops the chain: that failure is not retried."""
    for task in dagbag.dags[dag_id].tasks:
        assert task.retries >= 2, task.task_id
        assert task.execution_timeout is not None, task.task_id
