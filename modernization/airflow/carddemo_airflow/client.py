"""HTTP client for batch-service's internal API (/internal/*).

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
# legacy return code 4 (POSTTRAN rejects); NOOP is a job with nothing to do,
# such as the purge when the authorization module is not installed.
SUCCESS_EXIT_STATUSES = frozenset({"COMPLETED", "COMPLETED_WITH_REJECTS", "NOOP"})
# Window mode D-BAT-05: reads, sign-on and sign-off stay available.
WINDOW_MODE = "WRITES_BLOCKED"


class BatchServiceError(Exception):
    """batch-service refused a request or could not be reached.

    status is the HTTP status (None when unreachable), code the error code
    of the body (JOB_INSTANCE_COMPLETE, WINDOW_DRAIN_TIMEOUT, ...), and
    retry_after the Retry-After header in seconds, when given."""

    def __init__(self, message: str, status: int | None = None, code: str | None = None,
                 retry_after: int | None = None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.retry_after = retry_after

    @property
    def permanent(self) -> bool:
        """A 4xx answer: retrying the same request cannot succeed."""
        return self.status is not None and 400 <= self.status < 500


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
        raise BatchServiceError(f"{what}: HTTP {response.status_code} {detail}", response.status_code,
                                code, _retry_after(response))
    return body


def _call(method: str, path: str, what: str, timeout=TIMEOUT, **kwargs) -> dict:
    try:
        with httpx.Client(base_url=base_url(), headers=headers(), timeout=timeout, trust_env=False) as c:
            return _check(c.request(method, path, **kwargs), what)
    except httpx.TransportError as e:
        raise BatchServiceError(f"{what}: batch-service at {base_url()} not reachable ({type(e).__name__})") from e


def register_cycle(business_date: str, interest: bool) -> dict:
    """Creates the business date's cycle, or returns it when it exists:
    {cycleId, businessDate, interestParm, status}."""
    body = _call("POST", "/internal/cycles", f"register the cycle of {business_date}",
                 json={"businessDate": business_date, "interest": interest})
    if "cycleId" not in body:
        raise BatchServiceError(f"register the cycle of {business_date}: no cycleId in the answer")
    return body


def launch(job_name: str, params: dict) -> dict:
    """Starts a job execution; returns batch-service's answer with executionId."""
    body = _call("POST", f"/internal/jobs/{job_name}/executions", f"launch {job_name}", json={"params": params})
    if "executionId" not in body:
        raise BatchServiceError(f"launch {job_name}: no executionId in the answer")
    return body


def window(action: str, cycle_id: int | None = None, mode: str = WINDOW_MODE) -> dict:
    """Opens or closes the single global batch window."""
    payload: dict = {} if cycle_id is None else {"cycleId": cycle_id}
    if action == "open":
        payload["mode"] = mode
    return _call("POST", f"/internal/batch-window/{action}", f"{action} the batch window",
                 timeout=WINDOW_TIMEOUT, json=payload)


async def execution(execution_id) -> dict:
    what = f"execution {execution_id}"
    try:
        async with httpx.AsyncClient(base_url=base_url(), headers=headers(), timeout=TIMEOUT,
                                     trust_env=False) as c:
            return _check(await c.get(f"/internal/jobs/executions/{execution_id}"), what)
    except httpx.TransportError as e:
        raise BatchServiceError(f"{what}: batch-service at {base_url()} not reachable ({type(e).__name__})") from e
