"""Whole DAG runs in real Airflow (dag.test(), a SQLite metadata database in
the test AIRFLOW_HOME) against the stub batch-service. Unlike the operator
tests, the tasks here exchange the cycle and the export's outputs through
real XCom, run_if reads the real register_cycle result, the trigger rules
decide what runs after a skipped task, and the stub keeps the one global
batch window: a window job launched outside its own holder's window is
refused."""

from __future__ import annotations

import pytest

from conftest import WindowModel

JOBS = ["archiveMasterJob", "ingestDailyFileJob", "postDailyTransactionsJob", "calculateInterestJob",
        "statementJob", "applyRetentionJob", "purgePendingAuthorizationsJob", "dataLoadJob",
        "transactionTypeMaintenanceJob", "disclosureGroupReloadJob", "exportMastersJob", "importMigrationJob"]
EXPORT_ID = 42


@pytest.fixture(scope="module")
def metadata_db():
    from airflow.utils import db

    db.initdb()


@pytest.fixture
def stub(batch_service, metadata_db):
    stub = batch_service
    stub.cycle = {"cycleId": 17, "businessDate": "2022-07-06", "attempt": 1, "interest": True,
                  "status": "OPEN"}
    stub.handle("POST", "/internal/cycles", lambda request: (201, stub.cycle))
    stub.window = WindowModel(stub, retry_after=1)
    stub.outcomes = {}

    def launch(request):
        name = request["path"].split("/")[3]
        if not stub.window.admits(name, request["json"]["params"]):
            return 409, {"code": "BATCH_WINDOW_REQUIRED", "message": f"{name}: no window for its holder"}
        n = JOBS.index(name) + 1
        return 202, {"executionId": n, "jobName": name, "executionStatus": "STARTING", "exitStatus": "UNKNOWN",
                     "rejectCount": 0, "restart": False}

    def poll(n):
        def answer(request):
            name = JOBS[n - 1]
            status, exit_status = stub.outcomes.get(name, ("COMPLETED", "COMPLETED"))
            outputs = {"exportId": EXPORT_ID} if name == "exportMastersJob" else {}
            return 200, {"executionId": n, "jobName": name, "executionStatus": status, "exitStatus": exit_status,
                         "rejectCount": 0, "lease": {"state": "NONE", "recoverable": False}, "outputs": outputs,
                         "steps": []}
        return answer

    for n, name in enumerate(JOBS, start=1):
        stub.handle("POST", f"/internal/jobs/{name}/executions", launch)
        stub.handle("GET", f"/internal/jobs/executions/{n}", poll(n))
    return stub


def run(dagbag, dag_id, conf=None):
    dag_run = dagbag.get_dag(dag_id).test(run_conf=conf or {})
    return dag_run, {ti.task_id: ti.state for ti in dag_run.get_task_instances()}


def launched(stub):
    return [(r["path"].split("/")[3], r["json"]["params"]) for r in stub.requests
            if r["method"] == "POST" and r["path"].startswith("/internal/jobs/")]


def calls(stub):
    """The POSTs in order: cycles, open, close or the job name."""
    return [r["path"].split("/")[3] if r["path"].startswith("/internal/jobs/") else r["path"].rsplit("/", 1)[1]
            for r in stub.requests if r["method"] == "POST"]


@pytest.mark.parametrize("policy, stored", [("month_end", False), ("month_end", True), ("always", True),
                                            ("never", False)])
def test_daily_cycle_interest(dagbag, stub, policy, stored):
    """batch-service resolves the policy; the interest branch follows the stored boolean."""
    stub.cycle = {**stub.cycle, "interest": stored}
    dag_run, tasks = run(dagbag, "carddemo_daily_cycle", {"businessDate": "2022-07-06", "interest": policy})
    assert dag_run.state == "success"
    assert stub.posted("/internal/cycles") == [{"businessDate": "2022-07-06", "interest": policy}]
    assert tasks["calculate_interest"] == ("success" if stored else "skipped")
    # A skipped interest step must not stop the rest of the cycle.
    for task in ("generate_statements", "apply_retention", "close_batch_window", "purge_pending_authorizations"):
        assert tasks[task] == "success", task
    assert tasks["purge_pending_authorizations_before_posting"] == "skipped"
    jobs = [job for job, _ in launched(stub)]
    assert jobs == ["archiveMasterJob", "ingestDailyFileJob", "postDailyTransactionsJob"] \
        + (["calculateInterestJob"] if stored else []) \
        + ["statementJob", "applyRetentionJob", "purgePendingAuthorizationsJob"]
    assert all(params == {"cycleId": 17} for _, params in launched(stub))
    assert stub.posted("/internal/batch-window/open") == [{"cycleId": 17}]
    assert stub.posted("/internal/batch-window/close") == [{"cycleId": 17, "complete": True}]
    # The purge after the cycle runs outside the window.
    assert calls(stub)[-2:] == ["close", "purgePendingAuthorizationsJob"]


def test_statements_only_with_interest(dagbag, stub):
    stub.cycle = {**stub.cycle, "interest": False}
    dag_run, tasks = run(dagbag, "carddemo_daily_cycle",
                         {"businessDate": "2022-07-06", "interest": "never", "statements": "with_interest"})
    assert dag_run.state == "success"
    assert tasks["calculate_interest"] == tasks["generate_statements"] == "skipped"
    assert tasks["apply_retention"] == tasks["close_batch_window"] == "success"


def test_purge_before_posting(dagbag, stub):
    dag_run, tasks = run(dagbag, "carddemo_daily_cycle",
                         {"businessDate": "2022-07-06", "purge_position": "before_posting"})
    assert dag_run.state == "success"
    assert tasks["purge_pending_authorizations_before_posting"] == "success"
    assert tasks["purge_pending_authorizations"] == "skipped"
    assert [job for job, _ in launched(stub)][:2] == ["purgePendingAuthorizationsJob", "archiveMasterJob"]


def test_failed_job_stops_the_cycle_with_the_window_open(dagbag, stub):
    stub.outcomes["postDailyTransactionsJob"] = ("FAILED", "FAILED")
    dag_run, tasks = run(dagbag, "carddemo_daily_cycle", {"businessDate": "2022-07-06"})
    assert dag_run.state == "failed"
    assert tasks["post_daily_transactions"] == "failed"
    assert tasks["close_batch_window"] == "upstream_failed"
    assert stub.posted("/internal/batch-window/close") == []
    assert stub.window.holder == {"cycleId": 17}
    # Failed, not retried: one launch.
    assert [job for job, _ in launched(stub)].count("postDailyTransactionsJob") == 1


def test_data_load_holds_the_window_by_its_run_key(dagbag, stub):
    dag_run, tasks = run(dagbag, "carddemo_data_load", {"businessDate": "2022-07-06"})
    assert dag_run.state == "success"
    assert tasks == {"open_batch_window": "success", "load": "success", "close_batch_window": "success"}
    key = f"carddemo_data_load/{dag_run.run_id}"
    assert launched(stub) == [("dataLoadJob", {"runKey": f"{key}/load", "windowRunKey": key,
                                               "businessDate": "2022-07-06"})]
    assert stub.posted("/internal/batch-window/open") == [{"windowRunKey": key}]
    # A run-key window has no cycle to complete (contract section 9).
    assert stub.posted("/internal/batch-window/close") == [{"windowRunKey": key}]
    assert stub.window.holder is None


@pytest.mark.parametrize("dag_id, conf, holder", [
    ("carddemo_data_load", {"businessDate": "2022-07-06"}, {"cycleId": 16}),
    ("carddemo_reference_refresh", {"businessDate": "2022-07-09"}, {"cycleId": 16}),
    ("carddemo_import_migration", {"exportId": EXPORT_ID}, {"cycleId": 16}),
    ("carddemo_daily_cycle", {"businessDate": "2022-07-06"}, {"windowRunKey": "carddemo_data_load/manual__other"}),
])
def test_overlapping_window_waits_for_the_other_holder(dagbag, stub, dag_id, conf, holder):
    """Another holder's open window (a running or failed cycle, another DAG's
    run) makes the open wait for Retry-After, without a task retry, and no
    job of this run starts before the run holds the window itself."""
    stub.window.held_for(holder, busy_answers=2)
    dag_run, tasks = run(dagbag, dag_id, conf)
    assert dag_run.state == "success", tasks
    assert stub.window.busy_answers == 2
    assert len(stub.posted("/internal/batch-window/open")) == 3
    order = calls(stub)
    opens = [i for i, call in enumerate(order) if call == "open"]
    assert opens == sorted(opens) and opens[-1] < min(i for i, c in enumerate(order) if c.endswith("Job"))
    open_ti = dag_run.get_task_instance("open_batch_window")
    assert open_ti.try_number == 1
    assert not any(params.get("windowRunKey") == holder.get("windowRunKey") or
                   params.get("cycleId") == holder.get("cycleId") for _, params in launched(stub))


def test_export_id_round_trip(dagbag, stub):
    """The export's exportId reaches the import's launch through the run's
    outputs, as the integer that batch-service generated."""
    export_run, tasks = run(dagbag, "carddemo_export_masters", {"businessDate": "2022-07-06"})
    assert export_run.state == "success"
    export_ti = export_run.get_task_instance("export_masters")
    outputs = export_ti.xcom_pull(task_ids="export_masters", key="outputs")
    assert outputs == {"exportId": EXPORT_ID}
    assert export_ti.xcom_pull(task_ids="export_masters")["outputs"] == outputs
    assert stub.posted("/internal/batch-window/open") == []
    export_params = launched(stub)[0][1]
    assert set(export_params) == {"runKey", "businessDate"}

    stub.requests.clear()
    import_run, tasks = run(dagbag, "carddemo_import_migration", {"exportId": outputs["exportId"]})
    assert import_run.state == "success", tasks
    key = f"carddemo_import_migration/{import_run.run_id}"
    assert calls(stub) == ["open", "importMigrationJob", "close"]
    params = launched(stub)[0][1]
    assert params["exportId"] == EXPORT_ID and type(params["exportId"]) is int
    assert params["windowRunKey"] == key and params["runKey"] == f"{key}/import_migration"


def test_import_of_an_unfinished_export_fails_inside_its_window(dagbag, stub):
    stub.handle("POST", "/internal/jobs/importMigrationJob/executions",
                lambda request: (400, {"code": "INVALID_JOB_PARAMETERS", "field": "exportId",
                                       "message": "export 41 is not COMPLETED"}))
    dag_run, tasks = run(dagbag, "carddemo_import_migration", {"exportId": 41})
    assert dag_run.state == "failed"
    assert tasks["import_migration"] == "failed" and tasks["close_batch_window"] == "upstream_failed"
    assert dag_run.get_task_instance("import_migration").try_number == 1


def test_transaction_report_sends_the_run_id(dagbag, stub):
    stub.handle("POST", "/internal/jobs/transactionReportJob/executions",
                lambda request: (202, {"executionId": 1, "executionStatus": "STARTING"}))
    dag_run, _ = run(dagbag, "carddemo_transaction_report",
                     {"runId": 9, "startDate": "2022-06-01", "endDate": "2022-06-30", "requestedBy": "ADMIN001"})
    assert dag_run.state == "success"
    assert launched(stub) == [("transactionReportJob", {"runId": 9})]
