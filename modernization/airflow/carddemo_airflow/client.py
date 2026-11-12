"""HTTP client for batch-service's internal API (/internal/*), as the
batch-service execution contract (architecture batch-execution-contract.md)
defines it.

batch-service listens on 127.0.0.1 and accepts a bearer token that `make
setup` generates into the shared .env (CARDDEMO_BATCH_INTERNAL_TOKEN); the
Airflow processes get both values in their environment from `make up`.
The token is sent only in the Authorization header and never logged.
"""

from __future__ import annotations

import os

import httpx

DEFAULT_URL = "http://127.0.0.1:28090"
TIMEOUT = httpx.Timeout(30.0, connect=5.0)
# The clients ignore proxy settings (trust_env=False): batch-service is on
# loopback, and on macOS the system proxy lookup crashes in the triggerer's
# forked runner process.
# Opening the window waits for running online writes to drain; batch-service
# gives up first with 503 WINDOW_DRAIN_TIMEOUT.
WINDOW_TIMEOUT = httpx.Timeout(300.0, connect=5.0)

# Spring Batch BatchStatus values after which an execution no longer changes.
TERMINAL_STATUSES = frozenset({"COMPLETED", "FAILED", "STOPPED", "ABANDONED"})
# Exit statuses that count as task success. COMPLETED_WITH_REJECTS is the
# legacy return code 4 (POSTTRAN rejects).
SUCCESS_EXIT_STATUSES = frozenset({"COMPLETED", "COMPLETED_WITH_REJECTS"})
# 4xx answers that a later try of the same request can pass: the holder's
# executions are still running when the window is closed.
RETRYABLE_CONFLICTS = frozenset({"EXECUTIONS_RUNNING"})


class BatchServiceError(Exception):
    """batch-service refused a request or could not be reached.

    status is the HTTP status (None when unreachable), code the error code
    of the body (JOB_INSTANCE_COMPLETE, WINDOW_BUSY, ...), retry_after the
    Retry-After header in seconds, when given, and body the problem body
    (a job-instance 409 carries executionId and exitStatus)."""

    def __init__(self, message: str, status: int | None = None, code: str | None = None,
                 retry_after: int | None = None, body: dict | None = None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.retry_after = retry_after
        self.body = body or {}

    @property
    def permanent(self) -> bool:
        """A 4xx answer that retrying the same request cannot change."""
        return (self.status is not None and 400 <= self.status < 500
                and self.code not in RETRYABLE_CONFLICTS)


def base_url() -> str:
    return os.environ.get("CARDDEMO_BATCH_URL", DEFAULT_URL).rstrip("/")


def headers() -> dict:
    token = os.environ.get("CARDDEMO_BATCH_INTERNAL_TOKEN", "")
    if not token:
        raise BatchServiceError("CARDDEMO_BATCH_INTERNAL_TOKEN is not set; run make setup and start Airflow with make up")
    return {"Authorization": f"Bearer {token}", "Accept": "application/json"}


def _retry_after(response: httpx.Response) -> int | None:
    try:
        return max(1, int(response.headers.get("Retry-After", "")))
    except ValueError:
        return None


def _check(response: httpx.Response, what: str) -> dict:
    try:
        body = response.json() if response.content else {}
    except ValueError:
        body = {}
    if not isinstance(body, dict):
        body = {}
    if response.status_code >= 400:
        code = body.get("code")
        detail = body.get("message") or code or response.reason_phrase
        if code and code not in detail:
            detail = f"{code}: {detail}"
        raise BatchServiceError(f"{what}: HTTP {response.status_code} {detail}", response.status_code,
                                code, _retry_after(response), body)
    return body


def _call(method: str, path: str, what: str, timeout=TIMEOUT, **kwargs) -> dict:
    try:
        with httpx.Client(base_url=base_url(), headers=headers(), timeout=timeout, trust_env=False) as c:
            return _check(c.request(method, path, **kwargs), what)
    except httpx.TransportError as e:
        raise BatchServiceError(f"{what}: batch-service at {base_url()} not reachable ({type(e).__name__})") from e


def register_cycle(business_date: str, interest: str) -> dict:
    """POST /internal/cycles with the interest policy (always, month_end or
    never); batch-service resolves it. Returns the cycle: {cycleId,
    businessDate, attempt, interest (the stored boolean), interestParm,
    status, ...}. A completed cycle or a different resolved interest is a
    409 with the cycle in the body."""
    body = _call("POST", "/internal/cycles", f"register the cycle of {business_date}",
                 json={"businessDate": business_date, "interest": interest})
    if "cycleId" not in body:
        raise BatchServiceError(f"register the cycle of {business_date}: no cycleId in the answer")
    return body


def launch(job_name: str, params: dict) -> dict:
    """POST /internal/jobs/{job}/executions. Returns the 202 answer
    {executionId, executionStatus, exitStatus, rejectCount, restart}; a
    job-instance 409 raises BatchServiceError with the same members in body."""
    body = _call("POST", f"/internal/jobs/{job_name}/executions", f"launch {job_name}", json={"params": params})
    if "executionId" not in body:
        raise BatchServiceError(f"launch {job_name}: no executionId in the answer")
    return body


def window(action: str, holder: dict, complete: bool | None = None) -> dict:
    """Opens or closes the global batch window for holder, {cycleId} or
    {windowRunKey}. A cycle close must say whether it completes the cycle
    (complete: true only from the DAG's close_batch_window, after every
    earlier task succeeded); batch-service never infers it. A run-key holder
    has no cycle: complete is left out, or false."""
    payload = dict(holder)
    if action != "close":
        if complete is not None:
            raise ValueError("only a close takes complete")
    elif "cycleId" in holder:
        if complete is None:
            raise ValueError("a cycle close must send complete true or false")
        payload["complete"] = bool(complete)
    elif complete:
        raise ValueError("a run-key window has no cycle to complete")
    elif complete is not None:
        payload["complete"] = False
    return _call("POST", f"/internal/batch-window/{action}", f"{action} the batch window",
                 timeout=WINDOW_TIMEOUT, json=payload)


def execution_sync(execution_id) -> dict:
    """GET /internal/jobs/executions/{id}; the poll never changes anything."""
    return _call("GET", f"/internal/jobs/executions/{execution_id}", f"execution {execution_id}")


async def execution(execution_id) -> dict:
    what = f"execution {execution_id}"
    try:
        async with httpx.AsyncClient(base_url=base_url(), headers=headers(), timeout=TIMEOUT,
                                     trust_env=False) as c:
            return _check(await c.get(f"/internal/jobs/executions/{execution_id}"), what)
    except httpx.TransportError as e:
        raise BatchServiceError(f"{what}: batch-service at {base_url()} not reachable ({type(e).__name__})") from e
