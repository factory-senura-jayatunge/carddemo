"""The operators and the trigger against a stub batch-service that answers
as the batch-service execution contract (architecture
batch-execution-contract.md) says: cycles, job executions with leases, the
batch window."""

from __future__ import annotations

import asyncio
import datetime
import logging

import pytest
from airflow.sdk.exceptions import AirflowException, AirflowFailException, AirflowSkipException, TaskDeferred

from carddemo_airflow import client, cycle, operators
from carddemo_airflow.operators import BatchWindowOperator, RegisterCycleOperator, SpringBatchJobOperator
from carddemo_airflow.triggers import SpringBatchJobTrigger
from conftest import TOKEN, WINDOW_JOBS, WindowModel

CYCLE = {"cycleId": 17, "businessDate": "2022-07-06", "attempt": 1, "interest": True,
         "interestParm": "2022070600", "status": "OPEN"}
LIVE = {"state": "LIVE", "renewedAt": "2022-07-06T21:00:10.000Z", "recoverable": False}


def answer(execution_id, status="STARTED", exit_status="EXECUTING", **extra):
    return {"executionId": execution_id, "jobName": "job", "executionStatus": status, "exitStatus": exit_status,
            "rejectCount": 0, **extra}


def polled(execution_id, status="COMPLETED", exit_status="COMPLETED", lease=None, **extra):
    return {**answer(execution_id, status, exit_status), "lease": lease or LIVE, "outputs": {}, "steps": [],
            **extra}


def first_event(trigger):
    async def run():
        gen = trigger.run()
        try:
            return await gen.__anext__()
        finally:
            await gen.aclose()

    return asyncio.run(run()).payload


def run_job(op, context):
    """execute, the deferred wait, execute_complete: what Airflow does."""
    with pytest.raises(TaskDeferred) as deferred:
        op.execute(context)
    trigger = deferred.value.trigger
    assert isinstance(trigger, SpringBatchJobTrigger)
    assert deferred.value.method_name == "execute_complete"
    trigger.poll_interval = 0.01
    return getattr(op, deferred.value.method_name)(context, event=first_event(trigger))


def job(job_name="postDailyTransactionsJob", task_id="post_daily_transactions", **kwargs):
    return SpringBatchJobOperator(task_id=task_id, job_name=job_name, **kwargs)


def launches(stub, job_name):
    return stub.posted(f"/internal/jobs/{job_name}/executions")


def retryable(raised) -> bool:
    return isinstance(raised.value, AirflowException) and not isinstance(raised.value, AirflowFailException)


# --- register_cycle -----------------------------------------------------

@pytest.mark.parametrize("status", [201, 200])
def test_register_cycle_sends_the_policy_and_keeps_the_stored_interest(batch_service, make_context, status):
    batch_service.reply("POST", "/internal/cycles", (status, {**CYCLE, "interest": False, "interestParm": None}))
    result = RegisterCycleOperator(task_id="register_cycle").execute(make_context({"interest": "month_end"}))
    req = batch_service.requests[0]
    assert req["json"] == {"businessDate": "2022-07-06", "interest": "month_end"}
    assert req["headers"]["Authorization"] == f"Bearer {TOKEN}"
    assert result["cycleId"] == 17 and result["interest"] is False and result["attempt"] == 1


def test_conf_business_date_overrides_logical_date(batch_service, make_context):
    batch_service.reply("POST", "/internal/cycles", (201, {**CYCLE, "businessDate": "2022-07-07"}))
    RegisterCycleOperator(task_id="register_cycle").execute(
        make_context({"businessDate": "2022-07-07"}, ds="2026-11-06"))
    assert batch_service.requests[0]["json"]["businessDate"] == "2022-07-07"


def test_business_date_of_a_run_without_logical_date():
    class Run:
        run_after = datetime.datetime(2022, 7, 9, 3, tzinfo=datetime.timezone.utc)

    assert cycle.business_date({"params": {}, "dag_run": Run()}) == datetime.date(2022, 7, 9)


def test_completed_cycle_fails_before_the_window(batch_service, make_context):
    batch_service.reply("POST", "/internal/cycles", (409, {"code": "CYCLE_COMPLETED", **CYCLE, "status": "COMPLETED"}))
    with pytest.raises(AirflowFailException, match="cycle-rerun CYCLE=17"):
        RegisterCycleOperator(task_id="register_cycle").execute(make_context())
    assert [r["path"] for r in batch_service.requests] == ["/internal/cycles"]


def test_cycle_with_other_interest_fails(batch_service, make_context):
    batch_service.reply("POST", "/internal/cycles", (409, {"code": "CYCLE_PARAMETERS_DIFFER", **CYCLE}))
    with pytest.raises(AirflowFailException, match="resolves differently"):
        RegisterCycleOperator(task_id="register_cycle").execute(make_context({"interest": "never"}))


def test_invalid_cycle_request_fails(batch_service, make_context):
    batch_service.reply("POST", "/internal/cycles", (400, {"code": "INVALID_REQUEST", "field": "businessDate"}))
    with pytest.raises(AirflowFailException, match="INVALID_REQUEST"):
        RegisterCycleOperator(task_id="register_cycle").execute(make_context())


def test_interest_branch_reads_only_the_stored_boolean(batch_service, make_context):
    op = job("calculateInterestJob", "calculate_interest", run_if=cycle.cycle_interest)
    with pytest.raises(AirflowSkipException):
        op.execute(make_context({"interest": "always"}, cycle={**CYCLE, "interest": False}))
    with pytest.raises(AirflowSkipException):
        op.execute(make_context(cycle={**CYCLE, "interest": "true"}))
    assert batch_service.requests == []


# --- launch parameters (contract 2.1) ------------------------------------

def test_contract_job_tables():
    assert operators.WINDOW_JOBS == WINDOW_JOBS
    assert operators.CYCLE_JOBS | operators.RUN_KEYED_JOBS | operators.REPORT_JOBS == WINDOW_JOBS | {
        "purgePendingAuthorizationsJob", "transactionReportJob", "categoryBalanceListingJob", "exportMastersJob"}
    assert not operators.CYCLE_JOBS & operators.RUN_KEYED_JOBS


def test_cycle_job_sends_only_the_cycle_id(batch_service, make_context):
    batch_service.reply("POST", "/internal/jobs/postDailyTransactionsJob/executions", (202, answer(41)))
    batch_service.reply("GET", "/internal/jobs/executions/41",
                        (200, polled(41, "STARTED", "EXECUTING")),
                        (200, polled(41, exit_status="COMPLETED_WITH_REJECTS", rejectCount=3)))
    context = make_context(cycle=CYCLE)
    result = run_job(job(), context)
    assert launches(batch_service, "postDailyTransactionsJob") == [{"params": {"cycleId": 17}}]
    assert result == {"executionId": 41, "exitStatus": "COMPLETED_WITH_REJECTS", "rejectCount": 3, "outputs": {}}
    assert context["ti"].xcom[(None, "reject_count")] == 3
    assert sum(r["method"] == "GET" for r in batch_service.requests) == 2


def test_run_keyed_window_job_sends_its_keys(batch_service, make_context):
    batch_service.reply("POST", "/internal/jobs/dataLoadJob/executions", (202, answer(9)))
    context = make_context(dag_id="carddemo_data_load", run_id="manual__2026-11-10T12:00:00+00:00", task_id="load")
    with pytest.raises(TaskDeferred):
        job("dataLoadJob", "load").execute(context)
    assert launches(batch_service, "dataLoadJob") == [{"params": {
        "runKey": "carddemo_data_load/manual__2026-11-10T12:00:00+00:00/load",
        "windowRunKey": "carddemo_data_load/manual__2026-11-10T12:00:00+00:00",
        "businessDate": "2022-07-06"}}]


def test_run_keyed_job_without_window(batch_service, make_context):
    batch_service.reply("POST", "/internal/jobs/categoryBalanceListingJob/executions", (202, answer(10)))
    context = make_context({"businessDate": "2022-07-31"}, dag_id="carddemo_category_balance_listing",
                           task_id="run_listing")
    with pytest.raises(TaskDeferred):
        job("categoryBalanceListingJob", "run_listing").execute(context)
    assert launches(batch_service, "categoryBalanceListingJob") == [{"params": {
        "runKey": "carddemo_category_balance_listing/manual__1/run_listing", "businessDate": "2022-07-31"}}]


def test_import_sends_the_export_id(batch_service, make_context):
    batch_service.reply("POST", "/internal/jobs/importMigrationJob/executions", (202, answer(11)))
    op = job("importMigrationJob", "import_migration", job_params={"exportId": 42})
    with pytest.raises(TaskDeferred):
        op.execute(make_context(dag_id="carddemo_import_migration", task_id="import_migration"))
    params = launches(batch_service, "importMigrationJob")[0]["params"]
    assert params["exportId"] == 42
    assert params["windowRunKey"] == "carddemo_import_migration/manual__1"


def test_report_sends_only_its_run_id(batch_service, make_context):
    batch_service.reply("POST", "/internal/jobs/transactionReportJob/executions", (202, answer(12)))
    with pytest.raises(TaskDeferred):
        job("transactionReportJob", "run_report", job_params={"runId": 5}).execute(make_context())
    assert launches(batch_service, "transactionReportJob") == [{"params": {"runId": 5}}]
    with pytest.raises(AirflowFailException, match="runId"):
        job("transactionReportJob", "run_report", job_params={"runId": ""}).execute(make_context())


def test_run_key_longer_than_250_fails(batch_service, make_context):
    with pytest.raises(AirflowFailException, match="1 to 250"):
        job("dataLoadJob", "load").execute(make_context(run_id="r" * 240))
    assert batch_service.requests == []


def test_unknown_job_is_refused_at_parse_time():
    with pytest.raises(ValueError, match="not a job"):
        job("someOtherJob", "x")


def test_missing_cycle_fails(batch_service, make_context):
    with pytest.raises(AirflowFailException, match="no cycle"):
        job().execute(make_context())


def test_run_if_false_skips_without_a_call(batch_service, make_context):
    op = job("calculateInterestJob", "calculate_interest", run_if=cycle.cycle_interest)
    with pytest.raises(AirflowSkipException):
        op.execute(make_context(cycle={**CYCLE, "interest": False}))
    assert batch_service.requests == []


# --- launch answers and poll results (contract 2.2, 3, 5) -----------------

@pytest.mark.parametrize("exit_status", ["COMPLETED", "COMPLETED_WITH_REJECTS"])
def test_success_exit_statuses(batch_service, make_context, exit_status):
    batch_service.reply("POST", "/internal/jobs/archiveMasterJob/executions", (202, answer(5)))
    batch_service.reply("GET", "/internal/jobs/executions/5", (200, polled(5, exit_status=exit_status)))
    assert run_job(job("archiveMasterJob", "archive_master"), make_context(cycle=CYCLE))["exitStatus"] == exit_status


@pytest.mark.parametrize("status,exit_status", [("FAILED", "FAILED"), ("STOPPED", "FAILED"), ("ABANDONED", "FAILED"),
                                                ("COMPLETED", "FAILED"), ("COMPLETED", "NOOP")])
def test_other_ends_fail_without_retry(batch_service, make_context, status, exit_status):
    """A failed job stops the cycle; clearing the task, not a retry, restarts it."""
    batch_service.reply("POST", "/internal/jobs/calculateInterestJob/executions", (202, answer(6)))
    batch_service.reply("GET", "/internal/jobs/executions/6", (200, polled(6, status, exit_status)))
    with pytest.raises(AirflowFailException, match="ended"):
        run_job(job("calculateInterestJob", "calculate_interest"), make_context(cycle=CYCLE))


def test_running_execution_is_attached(batch_service, make_context):
    batch_service.reply("POST", "/internal/jobs/postDailyTransactionsJob/executions",
                        (409, {"code": "JOB_EXECUTION_RUNNING", **answer(41)}))
    batch_service.reply("GET", "/internal/jobs/executions/41", (200, polled(41)))
    assert run_job(job(), make_context(cycle=CYCLE))["executionId"] == 41
    assert len(launches(batch_service, "postDailyTransactionsJob")) == 1


@pytest.mark.parametrize("exit_status", ["COMPLETED", "COMPLETED_WITH_REJECTS"])
def test_completed_instance_succeeds_without_relaunch(batch_service, make_context, exit_status):
    """A cleared task finds its instance complete and pushes the same results as after a poll."""
    batch_service.reply("POST", "/internal/jobs/exportMastersJob/executions",
                        (409, {"code": "JOB_INSTANCE_COMPLETE", **answer(30, "COMPLETED", exit_status)}))
    batch_service.reply("GET", "/internal/jobs/executions/30",
                        (200, polled(30, exit_status=exit_status, rejectCount=2, outputs={"exportId": 42})))
    context = make_context(dag_id="carddemo_export_masters", task_id="export_masters")
    result = job("exportMastersJob", "export_masters").execute(context)
    assert result["outputs"] == {"exportId": 42} and result["rejectCount"] == 2
    assert context["ti"].xcom[(None, "outputs")] == {"exportId": 42}
    assert context["ti"].xcom[(None, "reject_count")] == 2
    assert len(launches(batch_service, "exportMastersJob")) == 1


def test_completed_instance_with_other_exit_fails(batch_service, make_context):
    batch_service.reply("POST", "/internal/jobs/archiveMasterJob/executions",
                        (409, {"code": "JOB_INSTANCE_COMPLETE", **answer(31, "COMPLETED", "FAILED")}))
    with pytest.raises(AirflowFailException, match="already completed with exit FAILED"):
        job("archiveMasterJob", "archive_master").execute(make_context(cycle=CYCLE))
    assert not any(r["method"] == "GET" for r in batch_service.requests)


@pytest.mark.parametrize("status,code", [(409, "JOB_INSTANCE_NOT_RESTARTABLE"), (409, "BATCH_WINDOW_REQUIRED"),
                                         (400, "INVALID_JOB_PARAMETERS"), (401, "UNAUTHENTICATED"),
                                         (404, "JOB_NOT_FOUND")])
def test_refused_launch_fails_without_retry(batch_service, make_context, status, code):
    batch_service.reply("POST", "/internal/jobs/archiveMasterJob/executions",
                        (status, {"code": code, "message": f"refused: {code}"}))
    with pytest.raises(AirflowFailException, match=code):
        job("archiveMasterJob", "archive_master").execute(make_context(cycle=CYCLE))


@pytest.mark.parametrize("status", [500, 503])
def test_server_error_may_retry(batch_service, make_context, status):
    batch_service.reply("POST", "/internal/jobs/archiveMasterJob/executions", (status, {"code": "INTERNAL"}))
    with pytest.raises(AirflowException) as raised:
        job("archiveMasterJob", "archive_master").execute(make_context(cycle=CYCLE))
    assert retryable(raised)


def test_unreachable_batch_service(monkeypatch, make_context):
    monkeypatch.setenv("CARDDEMO_BATCH_URL", "http://127.0.0.1:9")
    monkeypatch.setenv("CARDDEMO_BATCH_INTERNAL_TOKEN", TOKEN)
    with pytest.raises(AirflowException, match="not reachable") as raised:
        job("archiveMasterJob", "archive_master").execute(make_context(cycle=CYCLE))
    assert retryable(raised)


def test_missing_token(monkeypatch, make_context):
    monkeypatch.delenv("CARDDEMO_BATCH_INTERNAL_TOKEN", raising=False)
    with pytest.raises(AirflowException, match="CARDDEMO_BATCH_INTERNAL_TOKEN"):
        job("archiveMasterJob", "archive_master").execute(make_context(cycle=CYCLE))


def test_token_is_never_logged(batch_service, make_context, caplog):
    caplog.set_level(logging.DEBUG)
    batch_service.reply("POST", "/internal/jobs/archiveMasterJob/executions", (202, answer(7)))
    batch_service.reply("GET", "/internal/jobs/executions/7", (200, polled(7)))
    run_job(job("archiveMasterJob", "archive_master"), make_context(cycle=CYCLE))
    assert caplog.records
    assert TOKEN not in caplog.text


# --- lost owner (contract 4.4, 5) ----------------------------------------

def test_recoverable_lease_fails_the_try_and_the_retry_recovers(batch_service, make_context):
    """The poll reports LIVE, then EXPIRED, then recoverable; it never
    recovers. The try fails as retryable, and the retry's launch is what
    recovers and restarts."""
    batch_service.reply("POST", "/internal/jobs/postDailyTransactionsJob/executions",
                        (202, answer(41)), (202, answer(42, "STARTING", restart=True)))
    batch_service.reply("GET", "/internal/jobs/executions/41",
                        (200, polled(41, "STARTED", "EXECUTING")),
                        (200, polled(41, "STARTED", "EXECUTING", lease={"state": "EXPIRED", "recoverable": False})),
                        (200, polled(41, "STARTED", "EXECUTING",
                                     lease={"state": "EXPIRED", "recoverable": True,
                                            "renewedAt": "2022-07-06T21:00:10.000Z"})))
    batch_service.reply("GET", "/internal/jobs/executions/42", (200, polled(42)))
    context = make_context(cycle=CYCLE)
    with pytest.raises(AirflowException, match="lost its owner") as raised:
        run_job(job(), context)
    assert retryable(raised)
    assert sum(r["path"] == "/internal/jobs/executions/41" for r in batch_service.requests) == 3
    assert len(launches(batch_service, "postDailyTransactionsJob")) == 1
    # The retry: the same identity, so batch-service recovers and restarts.
    assert run_job(job(), context)["executionId"] == 42
    assert launches(batch_service, "postDailyTransactionsJob") == [{"params": {"cycleId": 17}}] * 2


def test_every_task_retries_at_least_twice():
    from airflow.sdk import DAG

    for op in (RegisterCycleOperator(task_id="r"), BatchWindowOperator(task_id="w", action="open"), job()):
        assert op.retries >= 2 and op.execution_timeout == operators.EXECUTION_TIMEOUT
    with pytest.raises(ValueError, match="at least 2"):
        job(retries=1)
    with pytest.raises(ValueError, match="at least 2"):
        with DAG("carddemo_retries", schedule=None, default_args={"retries": 0}):
            job()
    with DAG("carddemo_retries_3", schedule=None, default_args={"retries": 3}):
        assert job().retries == 3


# --- trigger ------------------------------------------------------------

def test_trigger_serializes_for_the_triggerer():
    path, kwargs = SpringBatchJobTrigger(41, "postDailyTransactionsJob", 5.0).serialize()
    assert path == "carddemo_airflow.triggers.SpringBatchJobTrigger"
    assert SpringBatchJobTrigger(**kwargs).serialize() == (path, kwargs)


def test_unknown_execution_fails_without_retry(batch_service, make_context):
    batch_service.reply("POST", "/internal/jobs/archiveMasterJob/executions", (202, answer(99)))
    batch_service.reply("GET", "/internal/jobs/executions/99", (404, {"code": "EXECUTION_NOT_FOUND"}))
    with pytest.raises(AirflowFailException, match="EXECUTION_NOT_FOUND"):
        run_job(job("archiveMasterJob", "archive_master"), make_context(cycle=CYCLE))


def test_trigger_survives_a_short_outage(batch_service):
    batch_service.reply("GET", "/internal/jobs/executions/8", (503, {}), (503, {}), (200, polled(8)))
    event = first_event(SpringBatchJobTrigger(8, "archiveMasterJob", 0.01))
    assert event["exitStatus"] == "COMPLETED"


def test_long_outage_fails_the_try_as_retryable(batch_service, make_context):
    batch_service.reply("POST", "/internal/jobs/archiveMasterJob/executions", (202, answer(8)))
    batch_service.reply("GET", "/internal/jobs/executions/8", (503, {}))
    with pytest.raises(AirflowException, match="503") as raised:
        run_job(job("archiveMasterJob", "archive_master"), make_context(cycle=CYCLE))
    assert retryable(raised)


# --- batch window (contract 6) ------------------------------------------

def test_open_window_for_the_cycle(batch_service, make_context):
    batch_service.reply("POST", "/internal/batch-window/open", (200, {"windowId": 1, "cycleId": 17}))
    BatchWindowOperator(task_id="open_batch_window", action="open", with_cycle=True).execute(
        make_context(cycle=CYCLE))
    assert batch_service.requests[0]["json"] == {"cycleId": 17}


def test_close_window_completes(batch_service, make_context):
    batch_service.reply("POST", "/internal/batch-window/close", (200, {"windowId": 1, "cycle": {**CYCLE}}))
    BatchWindowOperator(task_id="close_batch_window", action="close", with_cycle=True).execute(
        make_context(cycle=CYCLE))
    assert batch_service.requests[0]["json"] == {"cycleId": 17, "complete": True}


def test_window_of_a_run_is_held_by_its_run_key(batch_service, make_context):
    batch_service.reply("POST", "/internal/batch-window/open", (200, {"windowId": 2}))
    batch_service.reply("POST", "/internal/batch-window/close", (200, {"windowId": 2, "cycle": None}))
    context = make_context(dag_id="carddemo_data_load", run_id="manual__x")
    for action in ("open", "close"):
        BatchWindowOperator(task_id=f"{action}_batch_window", action=action).execute(context)
    assert [r["json"] for r in batch_service.requests] == [
        {"windowRunKey": "carddemo_data_load/manual__x"},
        {"windowRunKey": "carddemo_data_load/manual__x"}]


@pytest.mark.parametrize("dag_id", ["carddemo_data_load", "carddemo_reference_refresh", "carddemo_import_migration"])
def test_run_key_dag_closes_without_complete(dagbag, batch_service, make_context, dag_id):
    """The DAG's own close task, against the stub that refuses what
    batch-service refuses (complete: true for a run key gets 400)."""
    WindowModel(batch_service)
    context = make_context(dag_id=dag_id, run_id="manual__x")
    tasks = dagbag.dags[dag_id].task_dict
    tasks["open_batch_window"].execute(context)
    body = tasks["close_batch_window"].execute(context)
    assert body["windowRunKey"] == f"{dag_id}/manual__x"
    assert batch_service.posted("/internal/batch-window/close") == [{"windowRunKey": f"{dag_id}/manual__x"}]


def test_daily_cycle_close_completes_the_cycle(dagbag, batch_service, make_context):
    WindowModel(batch_service)
    context = make_context(cycle=CYCLE, dag_id="carddemo_daily_cycle")
    tasks = dagbag.dags["carddemo_daily_cycle"].task_dict
    tasks["open_batch_window"].execute(context)
    tasks["close_batch_window"].execute(context)
    assert batch_service.posted("/internal/batch-window/close") == [{"cycleId": 17, "complete": True}]


def test_cycle_close_can_leave_the_cycle_open(batch_service, make_context):
    WindowModel(batch_service)
    context = make_context(cycle=CYCLE)
    BatchWindowOperator(task_id="open_batch_window", action="open", with_cycle=True).execute(context)
    BatchWindowOperator(task_id="close_batch_window", action="close", with_cycle=True, complete=False).execute(context)
    assert batch_service.posted("/internal/batch-window/close") == [{"cycleId": 17, "complete": False}]


def test_run_key_close_may_send_complete_false(batch_service, make_context):
    WindowModel(batch_service)
    context = make_context(dag_id="carddemo_data_load", run_id="manual__x")
    BatchWindowOperator(task_id="open_batch_window", action="open").execute(context)
    BatchWindowOperator(task_id="close_batch_window", action="close", complete=False).execute(context)
    assert batch_service.posted("/internal/batch-window/close") == [
        {"windowRunKey": "carddemo_data_load/manual__x", "complete": False}]


def test_complete_is_checked_when_the_dag_is_parsed():
    with pytest.raises(ValueError, match="no cycle to complete"):
        BatchWindowOperator(task_id="close_batch_window", action="close", complete=True)
    with pytest.raises(ValueError, match="only a close"):
        BatchWindowOperator(task_id="open_batch_window", action="open", with_cycle=True, complete=True)


def test_client_sends_complete_only_where_the_contract_allows(batch_service):
    with pytest.raises(ValueError):
        client.window("close", {"cycleId": 17})
    with pytest.raises(ValueError):
        client.window("close", {"windowRunKey": "d/r"}, complete=True)
    with pytest.raises(ValueError):
        client.window("open", {"cycleId": 17}, complete=False)
    assert batch_service.requests == []


def test_busy_window_defers_for_retry_after(batch_service, make_context):
    batch_service.reply("POST", "/internal/batch-window/open",
                        (503, {"code": "WINDOW_BUSY", "windowId": 3, "cycleId": 16}, {"Retry-After": "7"}),
                        (200, {"windowId": 4, "cycleId": 17}))
    op = BatchWindowOperator(task_id="open_batch_window", action="open", with_cycle=True)
    context = make_context(cycle=CYCLE)
    before = datetime.datetime.now(datetime.timezone.utc)
    with pytest.raises(TaskDeferred) as deferred:
        op.execute(context)
    wait = (deferred.value.trigger.moment - before).total_seconds()
    assert 7 <= wait < 9
    deadline = datetime.datetime.fromisoformat(deferred.value.kwargs["deadline"])
    assert abs((deadline - before - operators.EXECUTION_TIMEOUT).total_seconds()) < 5
    result = getattr(op, deferred.value.method_name)(context, event=None, **deferred.value.kwargs)
    assert result == {"windowId": 4, "cycleId": 17}
    assert len(batch_service.requests) == 2


def test_busy_wait_never_passes_the_deadline(batch_service, make_context):
    batch_service.reply("POST", "/internal/batch-window/open",
                        (503, {"code": "WINDOW_BUSY", "cycleId": 16}, {"Retry-After": "60"}))
    op = BatchWindowOperator(task_id="open_batch_window", action="open", with_cycle=True)
    soon = (datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=5)).isoformat()
    with pytest.raises(TaskDeferred) as deferred:
        op.execute(make_context(cycle=CYCLE), deadline=soon)
    assert deferred.value.kwargs == {"deadline": soon}
    assert (deferred.value.trigger.moment - datetime.datetime.now(datetime.timezone.utc)).total_seconds() < 6


def test_busy_window_past_the_deadline_fails_without_retry(batch_service, make_context):
    batch_service.reply("POST", "/internal/batch-window/open",
                        (503, {"code": "WINDOW_BUSY", "windowId": 3, "cycleId": 16}, {"Retry-After": "7"}))
    op = BatchWindowOperator(task_id="open_batch_window", action="open", with_cycle=True)
    past = (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=1)).isoformat()
    with pytest.raises(AirflowFailException, match="still held by .*'cycleId': 16"):
        op.retry_when_free(make_context(cycle=CYCLE), deadline=past)


def test_close_during_a_launch_defers_for_retry_after(batch_service, make_context):
    batch_service.reply("POST", "/internal/batch-window/close",
                        (503, {"code": "LAUNCH_IN_PROGRESS", "message": "launch"}, {"Retry-After": "2"}),
                        (200, {"windowId": 4, "cycleId": 17, "cycle": {**CYCLE, "status": "COMPLETED"}}))
    op = BatchWindowOperator(task_id="close_batch_window", action="close", with_cycle=True)
    context = make_context(cycle=CYCLE)
    before = datetime.datetime.now(datetime.timezone.utc)
    with pytest.raises(TaskDeferred) as deferred:
        op.execute(context)
    wait = (deferred.value.trigger.moment - before).total_seconds()
    assert 2 <= wait < 3
    assert deferred.value.method_name == "retry_when_free"
    result = getattr(op, deferred.value.method_name)(context, event=None, **deferred.value.kwargs)
    assert result["windowId"] == 4
    assert batch_service.posted("/internal/batch-window/close") == [{"cycleId": 17, "complete": True}] * 2


def test_close_without_retry_after_waits_the_lock_wait(batch_service, make_context):
    batch_service.reply("POST", "/internal/batch-window/close", (503, {"code": "LAUNCH_IN_PROGRESS"}))
    op = BatchWindowOperator(task_id="close_batch_window", action="close", lock_wait=2)
    before = datetime.datetime.now(datetime.timezone.utc)
    with pytest.raises(TaskDeferred) as deferred:
        op.execute(make_context(dag_id="carddemo_data_load"))
    assert 2 <= (deferred.value.trigger.moment - before).total_seconds() < 3
    assert operators.LOCK_WAIT == 30
    assert BatchWindowOperator(task_id="c", action="close").lock_wait == 30


def test_close_during_a_launch_past_the_deadline_fails_without_retry(batch_service, make_context):
    batch_service.reply("POST", "/internal/batch-window/close",
                        (503, {"code": "LAUNCH_IN_PROGRESS"}, {"Retry-After": "2"}))
    op = BatchWindowOperator(task_id="close_batch_window", action="close", with_cycle=True)
    past = (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=1)).isoformat()
    with pytest.raises(AirflowFailException, match="launch .* still held the window lock"):
        op.retry_when_free(make_context(cycle=CYCLE), deadline=past)


@pytest.mark.parametrize("action,code", [("open", "LAUNCH_IN_PROGRESS"), ("close", "WINDOW_BUSY")])
def test_a_wait_code_of_the_other_action_is_not_waited_for(batch_service, make_context, action, code):
    batch_service.reply("POST", f"/internal/batch-window/{action}", (503, {"code": code}, {"Retry-After": "2"}))
    with pytest.raises(AirflowException, match=code) as raised:
        BatchWindowOperator(task_id=f"{action}_batch_window", action=action, with_cycle=True).execute(
            make_context(cycle=CYCLE))
    assert retryable(raised)


def test_close_of_a_window_never_opened_fails_without_retry(batch_service, make_context):
    batch_service.reply("POST", "/internal/batch-window/close",
                        (404, {"code": "WINDOW_NOT_FOUND", "message": "No batch window was opened for this holder."}))
    with pytest.raises(AirflowFailException, match="no batch window was opened"):
        BatchWindowOperator(task_id="close_batch_window", action="close").execute(
            make_context(dag_id="carddemo_data_load"))
    assert len(batch_service.requests) == 1


def test_open_for_a_completed_cycle_fails_without_retry(batch_service, make_context):
    batch_service.reply("POST", "/internal/batch-window/open",
                        (409, {"code": "CYCLE_COMPLETED", **CYCLE, "status": "COMPLETED"}))
    with pytest.raises(AirflowFailException, match="cycle-rerun CYCLE=17"):
        BatchWindowOperator(task_id="open_batch_window", action="open", with_cycle=True).execute(
            make_context(cycle=CYCLE))
    assert len(batch_service.requests) == 1


def test_launch_while_the_window_closes_waits_and_launches_again(batch_service, make_context):
    path = "/internal/jobs/postDailyTransactionsJob/executions"
    batch_service.reply("POST", path,
                        (503, {"code": "WINDOW_CLOSE_IN_PROGRESS", "message": "closing"}, {"Retry-After": "2"}),
                        (409, {"code": "BATCH_WINDOW_REQUIRED", "message": "no window"}))
    op = job()
    context = make_context(cycle=CYCLE)
    before = datetime.datetime.now(datetime.timezone.utc)
    with pytest.raises(TaskDeferred) as deferred:
        op.execute(context)
    assert 2 <= (deferred.value.trigger.moment - before).total_seconds() < 3
    assert deferred.value.method_name == "launch_again"
    with pytest.raises(AirflowFailException, match="BATCH_WINDOW_REQUIRED"):
        op.launch_again(context, event=None, **deferred.value.kwargs)
    assert launches(batch_service, "postDailyTransactionsJob") == [{"params": {"cycleId": 17}}] * 2


def test_launch_after_the_close_lock_polls_within_the_deadline(batch_service, make_context):
    path = "/internal/jobs/postDailyTransactionsJob/executions"
    batch_service.reply("POST", path, (503, {"code": "WINDOW_CLOSE_IN_PROGRESS"}), (202, answer(5)))
    op = job(lock_wait=2)
    context = make_context(cycle=CYCLE)
    with pytest.raises(TaskDeferred) as waited:
        op.execute(context)
    with pytest.raises(TaskDeferred) as polling:
        op.launch_again(context, event=None, **waited.value.kwargs)
    assert isinstance(polling.value.trigger, SpringBatchJobTrigger)
    assert polling.value.timeout <= operators.EXECUTION_TIMEOUT


def test_launch_while_the_window_closes_past_the_deadline_fails(batch_service, make_context):
    batch_service.reply("POST", "/internal/jobs/postDailyTransactionsJob/executions",
                        (503, {"code": "WINDOW_CLOSE_IN_PROGRESS"}, {"Retry-After": "2"}))
    past = (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=1)).isoformat()
    with pytest.raises(AirflowFailException, match="still closing"):
        job().launch_again(make_context(cycle=CYCLE), deadline=past)


@pytest.mark.parametrize("action,status,code", [("open", 503, "WINDOW_DRAIN_TIMEOUT"),
                                                ("close", 409, "EXECUTIONS_RUNNING")])
def test_window_errors_that_a_retry_can_pass(batch_service, make_context, action, status, code):
    batch_service.reply("POST", f"/internal/batch-window/{action}", (status, {"code": code}))
    with pytest.raises(AirflowException, match=code) as raised:
        BatchWindowOperator(task_id=f"{action}_batch_window", action=action, with_cycle=True).execute(
            make_context(cycle=CYCLE))
    assert retryable(raised)


def test_overlapping_windows_are_serialized(batch_service, make_context):
    """A data load triggered while the cycle holds the window waits for it,
    and each holder's jobs run only inside its own window."""
    window = WindowModel(batch_service, retry_after=1)

    def launch(request):
        name = request["path"].split("/")[3]
        if not window.admits(name, request["json"]["params"]):
            return 409, {"code": "BATCH_WINDOW_REQUIRED", "message": f"{name} has no window"}
        return 202, answer(1)

    for name in ("postDailyTransactionsJob", "dataLoadJob"):
        batch_service.handle("POST", f"/internal/jobs/{name}/executions", launch)
    cycle_ctx = make_context(cycle=CYCLE, dag_id="carddemo_daily_cycle", run_id="scheduled__1")
    load_ctx = make_context(dag_id="carddemo_data_load", run_id="manual__2", task_id="load")
    open_cycle = BatchWindowOperator(task_id="open_batch_window", action="open", with_cycle=True)
    open_load = BatchWindowOperator(task_id="open_batch_window", action="open")

    open_cycle.execute(cycle_ctx)
    with pytest.raises(TaskDeferred) as busy:
        open_load.execute(load_ctx)
    assert window.busy_answers == 1
    with pytest.raises(AirflowFailException, match="BATCH_WINDOW_REQUIRED"):
        job("dataLoadJob", "load").execute(load_ctx)
    with pytest.raises(TaskDeferred):
        job().execute(cycle_ctx)
    BatchWindowOperator(task_id="close_batch_window", action="close", with_cycle=True).execute(cycle_ctx)

    open_load.retry_when_free(load_ctx, **busy.value.kwargs)
    assert window.holder == {"windowRunKey": "carddemo_data_load/manual__2"}
    with pytest.raises(TaskDeferred):
        job("dataLoadJob", "load").execute(load_ctx)
    with pytest.raises(AirflowFailException, match="BATCH_WINDOW_REQUIRED"):
        job().execute(cycle_ctx)


def test_window_action_is_checked():
    with pytest.raises(ValueError):
        BatchWindowOperator(task_id="x", action="toggle")


def test_client_error_carries_code_retry_after_and_body(batch_service):
    batch_service.reply("POST", "/internal/batch-window/open",
                        (503, {"code": "WINDOW_BUSY", "message": "held", "cycleId": 16}, {"Retry-After": "12"}))
    with pytest.raises(client.BatchServiceError) as raised:
        client.window("open", {"cycleId": 17})
    e = raised.value
    assert (e.status, e.code, e.retry_after, e.body["cycleId"]) == (503, "WINDOW_BUSY", 12, 16)
    assert not e.permanent
