"""The operators and the trigger against a stub batch-service, with the
agreed /internal API: cycles, job executions, the batch window."""

from __future__ import annotations

import asyncio
import datetime
import logging

import pytest
from airflow.sdk.exceptions import AirflowException, AirflowFailException, AirflowSkipException, TaskDeferred

from carddemo_airflow import client, cycle
from carddemo_airflow.operators import BatchWindowOperator, RegisterCycleOperator, SpringBatchJobOperator
from carddemo_airflow.triggers import SpringBatchJobTrigger
from conftest import TOKEN

CYCLE = {"cycleId": 17, "businessDate": "2022-07-06", "interest": True}


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


# --- register_cycle -----------------------------------------------------

def test_register_cycle_posts_date_and_interest(batch_service, make_context):
    batch_service.reply("POST", "/internal/cycles",
                        (200, {"cycleId": 17, "businessDate": "2022-07-06", "interestParm": "2022070600",
                               "status": "OPEN"}))
    result = RegisterCycleOperator(task_id="register_cycle").execute(make_context())
    req = batch_service.requests[0]
    assert req["json"] == {"businessDate": "2022-07-06", "interest": True}
    assert req["headers"]["Authorization"] == f"Bearer {TOKEN}"
    assert result["cycleId"] == 17 and result["interest"] is True


@pytest.mark.parametrize("day,choice,expected", [
    ("2022-07-06", "month_end", False),
    ("2022-07-31", "month_end", True),
    ("2024-02-29", "month_end", True),
    ("2022-07-31", "never", False),
    ("2022-07-06", "always", True),
])
def test_interest_choice(batch_service, make_context, day, choice, expected):
    batch_service.reply("POST", "/internal/cycles", (200, {"cycleId": 3, "businessDate": day, "status": "OPEN"}))
    RegisterCycleOperator(task_id="register_cycle").execute(make_context({"interest": choice}, ds=day))
    assert batch_service.requests[0]["json"] == {"businessDate": day, "interest": expected}


def test_conf_business_date_overrides_logical_date(batch_service, make_context):
    batch_service.reply("POST", "/internal/cycles",
                        (200, {"cycleId": 4, "businessDate": "2022-07-07", "status": "OPEN"}))
    RegisterCycleOperator(task_id="register_cycle").execute(
        make_context({"businessDate": "2022-07-07"}, ds="2026-11-06"))
    assert batch_service.requests[0]["json"]["businessDate"] == "2022-07-07"


def test_completed_cycle_fails_before_the_window(batch_service, make_context):
    batch_service.reply("POST", "/internal/cycles",
                        (200, {"cycleId": 17, "businessDate": "2022-07-06", "status": "COMPLETED"}))
    with pytest.raises(AirflowFailException, match="already completed"):
        RegisterCycleOperator(task_id="register_cycle").execute(make_context())
    assert [r["path"] for r in batch_service.requests] == ["/internal/cycles"]


# --- jobs ---------------------------------------------------------------

def test_job_gets_cycle_and_succeeds_with_rejects(batch_service, make_context):
    batch_service.reply("POST", "/internal/jobs/postDailyTransactionsJob/executions",
                        (202, {"executionId": 41, "status": "STARTING"}))
    batch_service.reply("GET", "/internal/jobs/executions/41",
                        (200, {"executionId": 41, "status": "STARTED", "exitStatus": "UNKNOWN"}),
                        (200, {"executionId": 41, "status": "COMPLETED", "exitStatus": "COMPLETED_WITH_REJECTS",
                               "rejectCount": 3}))
    context = make_context(cycle=CYCLE)
    op = SpringBatchJobOperator(task_id="post_daily_transactions", job_name="postDailyTransactionsJob",
                                with_cycle=True)
    result = run_job(op, context)
    assert batch_service.requests[0]["json"] == {"params": {"cycleId": 17, "businessDate": "2022-07-06"}}
    assert result == {"executionId": 41, "exitStatus": "COMPLETED_WITH_REJECTS", "rejectCount": 3}
    assert context["ti"].xcom[(None, "reject_count")] == 3
    assert sum(r["method"] == "GET" for r in batch_service.requests) == 2


@pytest.mark.parametrize("exit_status", ["COMPLETED", "NOOP"])
def test_success_exit_statuses(batch_service, make_context, exit_status):
    batch_service.reply("POST", "/internal/jobs/archiveMasterJob/executions", (202, {"executionId": 5}))
    batch_service.reply("GET", "/internal/jobs/executions/5",
                        (200, {"executionId": 5, "status": "COMPLETED", "exitStatus": exit_status}))
    op = SpringBatchJobOperator(task_id="archive_master", job_name="archiveMasterJob", with_cycle=True)
    assert run_job(op, make_context(cycle=CYCLE))["exitStatus"] == exit_status


@pytest.mark.parametrize("status,exit_status", [("FAILED", "FAILED"), ("STOPPED", "STOPPED"),
                                                ("COMPLETED", "SOMETHING_ELSE")])
def test_failed_execution_fails_the_task(batch_service, make_context, status, exit_status):
    batch_service.reply("POST", "/internal/jobs/calculateInterestJob/executions", (202, {"executionId": 6}))
    batch_service.reply("GET", "/internal/jobs/executions/6",
                        (200, {"executionId": 6, "status": status, "exitStatus": exit_status}))
    op = SpringBatchJobOperator(task_id="calculate_interest", job_name="calculateInterestJob", with_cycle=True)
    with pytest.raises(AirflowException, match="ended"):
        run_job(op, make_context(cycle=CYCLE))


@pytest.mark.parametrize("status,code", [(409, "JOB_INSTANCE_COMPLETE"), (409, "JOB_EXECUTION_RUNNING"),
                                         (400, "INVALID_JOB_PARAMETERS")])
def test_refused_launch_fails_without_retry(batch_service, make_context, status, code):
    batch_service.reply("POST", "/internal/jobs/archiveMasterJob/executions",
                        (status, {"code": code, "message": f"refused: {code}"}))
    op = SpringBatchJobOperator(task_id="archive_master", job_name="archiveMasterJob", with_cycle=True)
    with pytest.raises(AirflowFailException, match=code):
        op.execute(make_context(cycle=CYCLE))


def test_server_error_may_retry(batch_service, make_context):
    batch_service.reply("POST", "/internal/jobs/archiveMasterJob/executions", (500, {"code": "INTERNAL"}))
    op = SpringBatchJobOperator(task_id="archive_master", job_name="archiveMasterJob", with_cycle=True)
    with pytest.raises(AirflowException) as raised:
        op.execute(make_context(cycle=CYCLE))
    assert not isinstance(raised.value, AirflowFailException)


def test_unreachable_batch_service(monkeypatch, make_context):
    monkeypatch.setenv("CARDDEMO_BATCH_URL", "http://127.0.0.1:9")
    monkeypatch.setenv("CARDDEMO_BATCH_INTERNAL_TOKEN", TOKEN)
    op = SpringBatchJobOperator(task_id="archive_master", job_name="archiveMasterJob", with_cycle=True)
    with pytest.raises(AirflowException, match="not reachable"):
        op.execute(make_context(cycle=CYCLE))


def test_missing_token(monkeypatch, make_context):
    monkeypatch.delenv("CARDDEMO_BATCH_INTERNAL_TOKEN", raising=False)
    op = SpringBatchJobOperator(task_id="archive_master", job_name="archiveMasterJob", with_cycle=True)
    with pytest.raises(AirflowException, match="CARDDEMO_BATCH_INTERNAL_TOKEN"):
        op.execute(make_context(cycle=CYCLE))


def test_run_if_false_skips_without_a_call(batch_service, make_context):
    op = SpringBatchJobOperator(task_id="calculate_interest", job_name="calculateInterestJob", with_cycle=True,
                                run_if=cycle.cycle_interest)
    with pytest.raises(AirflowSkipException):
        op.execute(make_context(cycle={**CYCLE, "interest": False}))
    assert batch_service.requests == []


def test_job_without_cycle_sends_its_params(batch_service, make_context):
    batch_service.reply("POST", "/internal/jobs/dataLoadJob/executions", (202, {"executionId": 9}))
    op = SpringBatchJobOperator(task_id="load", job_name="dataLoadJob")
    with pytest.raises(TaskDeferred):
        op.execute(make_context())
    assert batch_service.requests[0]["json"] == {"params": {}}


def test_missing_cycle_fails(batch_service, make_context):
    op = SpringBatchJobOperator(task_id="archive_master", job_name="archiveMasterJob", with_cycle=True)
    with pytest.raises(AirflowFailException, match="no cycle"):
        op.execute(make_context())


def test_token_is_never_logged(batch_service, make_context, caplog):
    caplog.set_level(logging.DEBUG)
    batch_service.reply("POST", "/internal/jobs/archiveMasterJob/executions", (202, {"executionId": 7}))
    batch_service.reply("GET", "/internal/jobs/executions/7",
                        (200, {"executionId": 7, "status": "COMPLETED", "exitStatus": "COMPLETED"}))
    op = SpringBatchJobOperator(task_id="archive_master", job_name="archiveMasterJob", with_cycle=True)
    run_job(op, make_context(cycle=CYCLE))
    assert caplog.records
    assert TOKEN not in caplog.text


# --- trigger ------------------------------------------------------------

def test_trigger_serializes_for_the_triggerer():
    path, kwargs = SpringBatchJobTrigger(41, "postDailyTransactionsJob", 5.0).serialize()
    assert path == "carddemo_airflow.triggers.SpringBatchJobTrigger"
    assert SpringBatchJobTrigger(**kwargs).serialize() == (path, kwargs)


def test_trigger_reports_unknown_execution(batch_service):
    batch_service.reply("GET", "/internal/jobs/executions/99", (404, {"code": "EXECUTION_NOT_FOUND"}))
    event = first_event(SpringBatchJobTrigger(99, "archiveMasterJob", 0.01))
    assert event["status"] == "UNKNOWN" and "404" in event["error"]


def test_trigger_survives_a_short_outage(batch_service):
    batch_service.reply("GET", "/internal/jobs/executions/8",
                        (503, {}), (503, {}),
                        (200, {"executionId": 8, "status": "COMPLETED", "exitStatus": "COMPLETED"}))
    event = first_event(SpringBatchJobTrigger(8, "archiveMasterJob", 0.01))
    assert event["exitStatus"] == "COMPLETED"


# --- batch window -------------------------------------------------------

def test_open_window_for_the_cycle(batch_service, make_context):
    batch_service.reply("POST", "/internal/batch-window/open", (200, {"status": "OPEN"}))
    BatchWindowOperator(task_id="open_batch_window", action="open", with_cycle=True).execute(
        make_context(cycle=CYCLE))
    assert batch_service.requests[0]["json"] == {"cycleId": 17, "mode": "WRITES_BLOCKED"}


def test_close_window_without_cycle(batch_service, make_context):
    batch_service.reply("POST", "/internal/batch-window/close", (200, {"status": "CLOSED"}))
    BatchWindowOperator(task_id="close_batch_window", action="close").execute(make_context())
    assert batch_service.requests[0]["json"] == {}


def test_drain_timeout_defers_for_retry_after(batch_service, make_context):
    batch_service.reply("POST", "/internal/batch-window/open",
                        (503, {"code": "WINDOW_DRAIN_TIMEOUT"}, {"Retry-After": "7"}),
                        (200, {"status": "OPEN"}))
    op = BatchWindowOperator(task_id="open_batch_window", action="open", with_cycle=True)
    context = make_context(cycle=CYCLE)
    before = datetime.datetime.now(datetime.timezone.utc)
    with pytest.raises(TaskDeferred) as deferred:
        op.execute(context)
    wait = (deferred.value.trigger.moment - before).total_seconds()
    assert 7 <= wait < 9
    assert deferred.value.kwargs == {"attempt": 2}
    result = getattr(op, deferred.value.method_name)(context, event=None, **deferred.value.kwargs)
    assert result == {"status": "OPEN"}
    assert len(batch_service.requests) == 2


def test_drain_timeout_gives_up(batch_service, make_context):
    batch_service.reply("POST", "/internal/batch-window/open",
                        (503, {"code": "WINDOW_DRAIN_TIMEOUT"}, {"Retry-After": "7"}))
    op = BatchWindowOperator(task_id="open_batch_window", action="open", with_cycle=True, drain_attempts=2)
    with pytest.raises(AirflowException, match="503"):
        op.retry_after_drain(make_context(cycle=CYCLE), attempt=2)


def test_window_action_is_checked():
    with pytest.raises(ValueError):
        BatchWindowOperator(task_id="x", action="toggle")


def test_client_error_carries_code_and_retry_after(batch_service):
    batch_service.reply("POST", "/internal/batch-window/open",
                        (503, {"code": "WINDOW_DRAIN_TIMEOUT", "message": "writes still running"},
                         {"Retry-After": "12"}))
    with pytest.raises(client.BatchServiceError) as raised:
        client.window("open", 17)
    assert (raised.value.status, raised.value.code, raised.value.retry_after) == (503, "WINDOW_DRAIN_TIMEOUT", 12)
    assert not raised.value.permanent
