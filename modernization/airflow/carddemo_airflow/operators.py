"""Operators that drive batch-service from the DAGs, as the batch-service
execution contract (architecture batch-execution-contract.md) defines.
They hold no business rule: batch-service decides; the operators register
the cycle, open and close the window, launch jobs, wait and map the answers
to task states (contract section 5)."""

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

# A lost owner is recovered by the next launch, which only a task retry
# sends; so every task retries at least twice (contract section 5).
MIN_RETRIES = 2
RETRY_DELAY = datetime.timedelta(seconds=30)
# Bounds what recovery cannot resolve (an owner paused between fence and
# commit) and how long a window open waits for another holder's window.
EXECUTION_TIMEOUT = datetime.timedelta(hours=2)
WINDOW_BUSY_WAIT = 60
# batch-service's lock wait between a holder's launch and its close
# (contract section 9); Retry-After normally carries it.
LOCK_WAIT = 30
MAX_KEY_LENGTH = 250

# Contract section 2.1: how each job is identified.
CYCLE_JOBS = frozenset({
    "archiveMasterJob", "ingestDailyFileJob", "postDailyTransactionsJob", "calculateInterestJob",
    "statementJob", "applyRetentionJob", "purgePendingAuthorizationsJob",
})
REPORT_JOBS = frozenset({"transactionReportJob"})
RUN_KEYED_JOBS = frozenset({
    "categoryBalanceListingJob", "dataLoadJob", "disclosureGroupReloadJob", "transactionTypeMaintenanceJob",
    "exportMastersJob", "importMigrationJob",
})
# Contract section 6: the jobs that refuse to run unless their own holder
# has the window open. The purge writes only pauth, which the window does
# not cover.
WINDOW_JOBS = (CYCLE_JOBS - {"purgePendingAuthorizationsJob"}) | frozenset({
    "dataLoadJob", "disclosureGroupReloadJob", "transactionTypeMaintenanceJob", "importMigrationJob",
})


def _raise(e: client.BatchServiceError):
    if e.permanent:
        raise AirflowFailException(str(e)) from e
    raise AirflowException(str(e)) from e


def _key(value: str, what: str) -> str:
    if not 1 <= len(value) <= MAX_KEY_LENGTH:
        raise AirflowFailException(f"{what} has {len(value)} characters; batch-service takes 1 to {MAX_KEY_LENGTH}")
    return value


def window_run_key(context) -> str:
    """The window holder of a DAG run that is not a cycle: <dag_id>/<run_id>."""
    ti = context["ti"]
    return _key(f"{ti.dag_id}/{ti.run_id}", "windowRunKey")


def run_key(context) -> str:
    """The identity of a run-keyed job: <dag_id>/<run_id>/<task_id>."""
    ti = context["ti"]
    return _key(f"{ti.dag_id}/{ti.run_id}/{ti.task_id}", "runKey")


def cycle_of(context, task_id: str = REGISTER_CYCLE_TASK) -> dict:
    """The cycle that the run's register_cycle task returned."""
    registered = context["ti"].xcom_pull(task_ids=task_id)
    if not registered or "cycleId" not in registered:
        raise AirflowFailException(f"no cycle from task {task_id}")
    return registered


def _now() -> datetime.datetime:
    return datetime.datetime.now(datetime.timezone.utc)


def _deadline(task: BaseOperator, deadline: str | None) -> str | None:
    """The end of the deferred waits: execution_timeout after the first try."""
    if deadline is None and task.execution_timeout is not None:
        deadline = (_now() + task.execution_timeout).isoformat()
    return deadline


def _remaining(deadline: str | None) -> float | None:
    if deadline is None:
        return None
    return (datetime.datetime.fromisoformat(deadline) - _now()).total_seconds()


def _wait_and_retry(task: BaseOperator, e: client.BatchServiceError, default_wait: int, deadline: str | None,
                    method_name: str, waiting_for: str, given_up: str):
    """Defers for Retry-After seconds (default_wait without one) and then
    calls method_name again, without a worker slot or a task retry. Past the
    deadline the task fails without retry."""
    wait = float(e.retry_after or default_wait)
    remaining = _remaining(deadline)
    if remaining is not None:
        if remaining <= 0:
            raise AirflowFailException(f"{given_up} after execution_timeout {task.execution_timeout}") from e
        wait = min(wait, remaining)
    task.log.warning("%s (%s); trying again in %.0f s", waiting_for, e.code, wait)
    task.defer(trigger=TimeDeltaTrigger(datetime.timedelta(seconds=wait)), method_name=method_name,
               kwargs={"deadline": deadline})


class _BatchServiceTask(BaseOperator):
    """Sets the contract's task defaults (retries, retry delay, execution
    timeout) unless the task or the DAG's default_args set them, and
    refuses fewer than MIN_RETRIES retries."""

    def __init__(self, **kwargs):
        defaults = kwargs.get("default_args") or {}
        for name, value in (("retries", MIN_RETRIES), ("retry_delay", RETRY_DELAY),
                            ("execution_timeout", EXECUTION_TIMEOUT)):
            if name not in kwargs and name not in defaults:
                kwargs[name] = value
        super().__init__(**kwargs)
        if (self.retries or 0) < MIN_RETRIES:
            raise ValueError(f"{self.task_id}: retries must be at least {MIN_RETRIES}, so that a retry's "
                             f"launch can recover an execution whose owner was lost")


class RegisterCycleOperator(_BatchServiceTask):
    """POST /internal/cycles for the run's business date (params.businessDate,
    else the logical date) with the interest policy (params.interest).
    batch-service resolves the policy and creates the cycle or returns the
    open one. A completed cycle (409 CYCLE_COMPLETED) or a different
    resolved interest (409 CYCLE_PARAMETERS_DIFFER) fails the run before
    the window opens. Returns the cycle to XCom; its interest boolean is
    the only input of the interest branch."""

    ui_color = "#eef6ee"

    def execute(self, context):
        day = cycle.business_date(context).isoformat()
        policy = context["params"]["interest"]
        try:
            body = client.register_cycle(day, policy)
        except client.BatchServiceError as e:
            if e.code == "CYCLE_COMPLETED":
                self._completed(day, e.body)
            if e.code == "CYCLE_PARAMETERS_DIFFER":
                raise AirflowFailException(
                    f"cycle {e.body.get('cycleId')} of {day} was registered with interest "
                    f"{e.body.get('interest')}; this run's policy {policy!r} resolves differently") from e
            _raise(e)
        if body.get("status") == "COMPLETED":
            self._completed(day, body)
        self.log.info("cycle %s for %s: attempt %s, status %s, interest %s", body["cycleId"], day,
                      body.get("attempt"), body.get("status"), body.get("interest"))
        return body

    @staticmethod
    def _completed(day: str, body: dict):
        raise AirflowFailException(
            f"the cycle of {day} (cycle {body.get('cycleId')}, attempt {body.get('attempt')}) already completed; "
            f"a deliberate rerun goes through make -C modernization cycle-rerun CYCLE={body.get('cycleId')}")


class SpringBatchJobOperator(_BatchServiceTask):
    """Launches a Spring Batch job through POST /internal/jobs/{job}/executions
    and defers to SpringBatchJobTrigger until it ends.

    The job's kind (contract section 2.1) decides its parameters: a cycle
    job gets cycleId from register_cycle; a run-keyed job gets runKey, the
    business date and, when it needs the window, windowRunKey; the report
    gets only the runId of its job_params. Answers (section 5): 409
    JOB_EXECUTION_RUNNING attaches to the running execution; 409
    JOB_INSTANCE_COMPLETE with a successful exit succeeds without a new
    run; other refusals fail without retry. COMPLETED with exit COMPLETED
    or COMPLETED_WITH_REJECTS succeeds and pushes reject_count and outputs
    to XCom; any other end fails without retry, and clearing the task
    restarts the job. An execution whose lease became recoverable fails
    the try, so the retry's launch recovers it. A launch of a window job
    while its holder's window is closing (503 WINDOW_CLOSE_IN_PROGRESS)
    defers for Retry-After seconds and launches again, until
    execution_timeout; after the close it gets BATCH_WINDOW_REQUIRED and
    fails. run_if(context), when given, skips the task when it returns
    False.
    """

    template_fields = ("job_params",)
    template_fields_renderers = {"job_params": "json"}
    ui_color = "#e8f1fb"

    def __init__(self, *, job_name: str, job_params: dict | None = None,
                 run_if: Callable[[dict], bool] | None = None, poll_interval: float = 10.0,
                 lock_wait: int = LOCK_WAIT, **kwargs):
        if job_name in CYCLE_JOBS:
            kind = "cycle"
        elif job_name in RUN_KEYED_JOBS:
            kind = "run-keyed"
        elif job_name in REPORT_JOBS:
            kind = "report"
        else:
            raise ValueError(f"{job_name} is not a job of the batch-service execution contract")
        super().__init__(**kwargs)
        self.job_name = job_name
        self.job_params = job_params or {}
        self.kind = kind
        self.with_cycle = kind == "cycle"
        self.needs_window = job_name in WINDOW_JOBS
        self.run_if = run_if
        self.poll_interval = poll_interval
        self.lock_wait = lock_wait

    def launch_params(self, context) -> dict:
        params = dict(self.job_params)
        if self.kind == "cycle":
            params["cycleId"] = int(cycle_of(context)["cycleId"])
        elif self.kind == "run-keyed":
            params["runKey"] = run_key(context)
            params.setdefault("businessDate", cycle.business_date(context).isoformat())
            if self.needs_window:
                params["windowRunKey"] = window_run_key(context)
        elif params.get("runId") in (None, ""):
            raise AirflowFailException(f"{self.job_name} needs the runId of its report run")
        return params

    def execute(self, context, deadline: str | None = None):
        if self.run_if is not None and not self.run_if(context):
            raise AirflowSkipException(f"{self.job_name} is not due in this run")
        try:
            answer = client.launch(self.job_name, self.launch_params(context))
        except client.BatchServiceError as e:
            if e.code == "JOB_INSTANCE_COMPLETE":
                return self._already_complete(context, e.body)
            if e.code == "WINDOW_CLOSE_IN_PROGRESS" and e.status == 503:
                _wait_and_retry(self, e, self.lock_wait, _deadline(self, deadline), "launch_again",
                                f"{self.job_name}: its holder's batch window is closing",
                                f"{self.job_name}: the batch window was still closing")
            if e.code != "JOB_EXECUTION_RUNNING" or e.body.get("executionId") is None:
                _raise(e)
            answer = e.body
            self.log.info("%s: execution %s is running; waiting for it", self.job_name, answer["executionId"])
        else:
            self.log.info("%s: execution %s %s%s", self.job_name, answer["executionId"],
                          answer.get("executionStatus", ""), " (restart)" if answer.get("restart") else "")
        remaining = _remaining(deadline)
        timeout = self.execution_timeout if remaining is None else datetime.timedelta(seconds=max(remaining, 1))
        self.defer(trigger=SpringBatchJobTrigger(answer["executionId"], self.job_name, self.poll_interval),
                   method_name="execute_complete", timeout=timeout)

    def launch_again(self, context, event=None, deadline: str | None = None):
        return self.execute(context, deadline=deadline)

    def _already_complete(self, context, answer: dict):
        execution_id = answer.get("executionId")
        if answer.get("exitStatus") not in client.SUCCESS_EXIT_STATUSES:
            raise AirflowFailException(
                f"{self.job_name}: the job instance already completed with exit {answer.get('exitStatus')} "
                f"(execution {execution_id}); a rerun is a new DAG run, or cycle-rerun for a cycle")
        self.log.info("%s: the job instance already completed (execution %s); not launched again",
                      self.job_name, execution_id)
        try:
            polled = client.execution_sync(execution_id)
        except client.BatchServiceError as e:
            _raise(e)
        return self._succeed(context, {**answer, **polled})

    def execute_complete(self, context, event=None):
        event = event or {}
        execution_id = event.get("executionId")
        if event.get("error"):
            if event.get("permanent"):
                raise AirflowFailException(f"{self.job_name}: {event['error']}")
            raise AirflowException(f"{self.job_name}: {event['error']}")
        if event.get("recoverable"):
            lease = event.get("lease") or {}
            raise AirflowException(
                f"{self.job_name}: execution {execution_id} lost its owner (lease not renewed since "
                f"{lease.get('renewedAt')}); the retry's launch recovers and restarts it")
        status, exit_status = event.get("executionStatus"), event.get("exitStatus")
        if status != "COMPLETED" or exit_status not in client.SUCCESS_EXIT_STATUSES:
            raise AirflowFailException(f"{self.job_name}: execution {execution_id} ended {status} / {exit_status}; "
                                       f"clear the task to restart the job")
        return self._succeed(context, event)

    def _succeed(self, context, result: dict):
        rejects = result.get("rejectCount")
        outputs = result.get("outputs") or {}
        self.log.info("%s: execution %s ended %s / %s, %s rejects%s", self.job_name, result.get("executionId"),
                      result.get("executionStatus"), result.get("exitStatus"), rejects,
                      f", outputs {outputs}" if outputs else "")
        ti = context["ti"]
        if rejects is not None:
            ti.xcom_push(key="reject_count", value=rejects)
        ti.xcom_push(key="outputs", value=outputs)
        return {"executionId": result.get("executionId"), "exitStatus": result.get("exitStatus"),
                "rejectCount": rejects, "outputs": outputs}


class BatchWindowOperator(_BatchServiceTask):
    """Opens or closes the global WRITES_BLOCKED batch window (POST
    /internal/batch-window/open or /close) for the run: held by its cycle
    with with_cycle, else by its windowRunKey <dag_id>/<run_id>.

    A cycle close sends complete (default true: the DAG's
    close_batch_window runs only after every earlier task succeeded, and it
    completes the cycle). A run-key window has no cycle, so its close sends
    no complete; complete=True for it is refused when the DAG is parsed.

    Two answers make the task defer for Retry-After seconds (no worker
    slot, no task retry used) and try again, until execution_timeout has
    passed since the first try, and then fail without retry: 503
    WINDOW_BUSY on an open (another holder's window is open; default wait
    busy_wait) and 503 LAUNCH_IN_PROGRESS on a close (a launch of the same
    holder holds the lock; default wait lock_wait). A completed cycle's
    open (409 CYCLE_COMPLETED) and a close of a holder that never opened a
    window (404 WINDOW_NOT_FOUND) fail without retry."""

    ui_color = "#fdf1e3"

    def __init__(self, *, action: str, with_cycle: bool = False, complete: bool | None = None,
                 busy_wait: int = WINDOW_BUSY_WAIT, lock_wait: int = LOCK_WAIT, **kwargs):
        if action not in ("open", "close"):
            raise ValueError(f"action must be open or close, not {action!r}")
        if action == "open" and complete is not None:
            raise ValueError("only a close takes complete")
        if action == "close" and not with_cycle and complete:
            raise ValueError("a run-key window has no cycle to complete; leave complete out")
        if action == "close" and with_cycle and complete is None:
            complete = True
        super().__init__(**kwargs)
        self.action = action
        self.with_cycle = with_cycle
        self.complete = complete
        self.busy_wait = busy_wait
        self.lock_wait = lock_wait

    def holder(self, context) -> dict:
        if self.with_cycle:
            return {"cycleId": int(cycle_of(context)["cycleId"])}
        return {"windowRunKey": window_run_key(context)}

    def execute(self, context, deadline: str | None = None):
        holder = self.holder(context)
        try:
            body = client.window(self.action, holder, complete=self.complete)
        except client.BatchServiceError as e:
            self._refused(e, holder, _deadline(self, deadline))
        self.log.info("batch window %s for %s: %s", self.action, holder, body)
        return body

    def _refused(self, e: client.BatchServiceError, holder: dict, deadline: str | None):
        if e.status == 503 and e.code == "WINDOW_BUSY" and self.action == "open":
            held = {k: e.body.get(k) for k in ("windowId", "cycleId", "windowRunKey", "openedAt") if e.body.get(k)}
            _wait_and_retry(self, e, self.busy_wait, deadline, "retry_when_free",
                            f"the batch window is held by {held}",
                            f"the batch window is still held by {held}; a failed cycle keeps its window open "
                            f"until it is recovered and the window is closed (make -C modernization "
                            f"batch-window-close)")
        if e.status == 503 and e.code == "LAUNCH_IN_PROGRESS" and self.action == "close":
            _wait_and_retry(self, e, self.lock_wait, deadline, "retry_when_free",
                            f"a job launch for {holder} is in progress",
                            f"a job launch for {holder} still held the window lock")
        if e.code == "CYCLE_COMPLETED":
            raise AirflowFailException(
                f"cycle {e.body.get('cycleId')} (attempt {e.body.get('attempt')}) already completed, so its "
                f"window is not opened again; a deliberate rerun goes through make -C modernization "
                f"cycle-rerun CYCLE={e.body.get('cycleId')}") from e
        if e.code == "WINDOW_NOT_FOUND":
            raise AirflowFailException(f"no batch window was opened for {holder}; there is nothing to close") from e
        _raise(e)

    def retry_when_free(self, context, event=None, deadline: str | None = None):
        return self.execute(context, deadline=deadline)
