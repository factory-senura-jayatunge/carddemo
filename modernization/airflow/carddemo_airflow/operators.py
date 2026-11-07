"""Operators that drive batch-service from the DAGs. They hold no business
rule: batch-service decides; the operators register the cycle, open and
close the window, launch jobs, wait and map exit statuses to task states."""

from __future__ import annotations

import datetime
from collections.abc import Callable

from airflow.providers.standard.triggers.temporal import TimeDeltaTrigger
from airflow.sdk import BaseOperator
from airflow.sdk.exceptions import AirflowException, AirflowFailException, AirflowSkipException

from carddemo_airflow import client, cycle
from carddemo_airflow.triggers import SpringBatchJobTrigger

BATCH_POOL = "carddemo_batch"
REGISTER_CYCLE_TASK = "register_cycle"


def _raise(e: client.BatchServiceError):
    # 4xx (400 INVALID_JOB_PARAMETERS, 409 JOB_INSTANCE_COMPLETE or
    # JOB_EXECUTION_RUNNING) cannot succeed on a retry; anything else may.
    if e.permanent:
        raise AirflowFailException(str(e)) from e
    raise AirflowException(str(e)) from e


def cycle_of(context, task_id: str = REGISTER_CYCLE_TASK) -> dict:
    """The cycle that the run's register_cycle task returned."""
    registered = context["ti"].xcom_pull(task_ids=task_id)
    if not registered or "cycleId" not in registered:
        raise AirflowFailException(f"no cycle from task {task_id}")
    return registered


class RegisterCycleOperator(BaseOperator):
    """POST /internal/cycles for the run's business date (params.businessDate,
    else the logical date). batch-service creates the cycle or returns the
    existing one; a cycle that already completed fails the run before the
    window opens, so a repeated trigger changes nothing. Returns
    {cycleId, businessDate, interest, ...} to XCom for the later tasks."""

    ui_color = "#eef6ee"

    def execute(self, context):
        day = cycle.business_date(context).isoformat()
        interest = cycle.interest_due(context)
        try:
            body = client.register_cycle(day, interest)
        except client.BatchServiceError as e:
            _raise(e)
        self.log.info("cycle %s for %s: status %s, interest %s", body["cycleId"], day, body.get("status"), interest)
        if body.get("status") == "COMPLETED":
            raise AirflowFailException(
                f"the cycle of {day} (cycle {body['cycleId']}) already completed; "
                f"a deliberate rerun goes through make -C modernization cycle-rerun")
        return {"interest": interest, **body}


class SpringBatchJobOperator(BaseOperator):
    """Launches a Spring Batch job through POST /internal/jobs/{job}/executions
    and defers to SpringBatchJobTrigger until it ends.

    With with_cycle, the job gets cycleId and businessDate from the run's
    register_cycle task. COMPLETED, NOOP and COMPLETED_WITH_REJECTS succeed
    (the reject count goes to XCom as reject_count); anything else fails the
    task, so the DAG stops and the batch window stays open. run_if(context),
    when given, skips the task when it returns False (for example interest
    on a cycle without interest).
    """

    template_fields = ("job_params",)
    template_fields_renderers = {"job_params": "json"}
    ui_color = "#e8f1fb"

    def __init__(self, *, job_name: str, job_params: dict | None = None, with_cycle: bool = False,
                 run_if: Callable[[dict], bool] | None = None, poll_interval: float = 10.0, **kwargs):
        super().__init__(**kwargs)
        self.job_name = job_name
        self.job_params = job_params or {}
        self.with_cycle = with_cycle
        self.run_if = run_if
        self.poll_interval = poll_interval

    def execute(self, context):
        if self.run_if is not None and not self.run_if(context):
            raise AirflowSkipException(f"{self.job_name} is not due in this run")
        params = dict(self.job_params)
        if self.with_cycle:
            registered = cycle_of(context)
            params.update(cycleId=int(registered["cycleId"]), businessDate=registered["businessDate"])
        try:
            started = client.launch(self.job_name, params)
        except client.BatchServiceError as e:
            _raise(e)
        self.log.info("%s: execution %s %s", self.job_name, started["executionId"], started.get("status", ""))
        self.defer(trigger=SpringBatchJobTrigger(started["executionId"], self.job_name, self.poll_interval),
                   method_name="execute_complete")

    def execute_complete(self, context, event=None):
        event = event or {}
        exit_status = event.get("exitStatus")
        self.log.info("%s: execution %s ended %s / %s %s", self.job_name, event.get("executionId"),
                      event.get("status"), exit_status, event.get("exitDescription") or "")
        if event.get("error"):
            raise AirflowException(f"{self.job_name}: {event['error']}")
        if event.get("status") != "COMPLETED" or exit_status not in client.SUCCESS_EXIT_STATUSES:
            raise AirflowException(f"{self.job_name}: execution {event.get('executionId')} ended "
                                   f"{event.get('status')} / {exit_status}")
        rejects = event.get("rejectCount")
        if rejects is not None:
            context["ti"].xcom_push(key="reject_count", value=rejects)
        return {"executionId": event.get("executionId"), "exitStatus": exit_status, "rejectCount": rejects}


class BatchWindowOperator(BaseOperator):
    """Opens or closes the global batch window (POST /internal/batch-window/
    open or /close), for the run's cycle when with_cycle is set.

    Opening waits until online writes drain. When batch-service answers 503
    WINDOW_DRAIN_TIMEOUT, the task defers for Retry-After seconds (no worker
    slot held) and tries again, at most drain_attempts times in all."""

    ui_color = "#fdf1e3"

    def __init__(self, *, action: str, with_cycle: bool = False, mode: str = client.WINDOW_MODE,
                 drain_attempts: int = 5, default_retry_after: int = 30, **kwargs):
        if action not in ("open", "close"):
            raise ValueError(f"action must be open or close, not {action!r}")
        super().__init__(**kwargs)
        self.action = action
        self.with_cycle = with_cycle
        self.mode = mode
        self.drain_attempts = drain_attempts
        self.default_retry_after = default_retry_after

    def execute(self, context, attempt: int = 1):
        cycle_id = int(cycle_of(context)["cycleId"]) if self.with_cycle else None
        try:
            body = client.window(self.action, cycle_id, self.mode)
        except client.BatchServiceError as e:
            if e.code == "WINDOW_DRAIN_TIMEOUT" and attempt < self.drain_attempts:
                wait = e.retry_after or self.default_retry_after
                self.log.warning("online writes still running; retrying in %d s (attempt %d of %d)",
                                 wait, attempt, self.drain_attempts)
                self.defer(trigger=TimeDeltaTrigger(datetime.timedelta(seconds=wait)),
                           method_name="retry_after_drain", kwargs={"attempt": attempt + 1})
            _raise(e)
        self.log.info("batch window %s (cycle %s): %s", self.action, cycle_id, body)
        return body

    def retry_after_drain(self, context, event=None, attempt: int = 2):
        return self.execute(context, attempt=attempt)
