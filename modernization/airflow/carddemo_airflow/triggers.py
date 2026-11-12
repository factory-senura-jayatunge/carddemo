"""Deferrable wait for a batch-service job execution."""

from __future__ import annotations

import asyncio

from airflow.triggers.base import BaseTrigger, TriggerEvent

from carddemo_airflow import client

# Consecutive failed polls (batch-service unreachable or 5xx) before the wait
# gives up; a 4xx answer ends it at once.
MAX_POLL_FAILURES = 6


class SpringBatchJobTrigger(BaseTrigger):
    """Polls GET /internal/jobs/executions/{id} until the execution is
    terminal, or until batch-service reports its lease recoverable (the
    owner stopped renewing it for TTL + grace); the waiting task holds no
    worker slot meanwhile. The poll only reads: recovery is left to the
    next launch, which the task's retry sends."""

    def __init__(self, execution_id, job_name: str, poll_interval: float = 10.0):
        super().__init__()
        self.execution_id = execution_id
        self.job_name = job_name
        self.poll_interval = poll_interval

    def serialize(self):
        return (
            "carddemo_airflow.triggers.SpringBatchJobTrigger",
            {"execution_id": self.execution_id, "job_name": self.job_name, "poll_interval": self.poll_interval},
        )

    async def run(self):
        failures = 0
        lease_state = None
        while True:
            try:
                body = await client.execution(self.execution_id)
            except client.BatchServiceError as e:
                failures += 1
                if e.permanent or failures >= MAX_POLL_FAILURES:
                    yield TriggerEvent({"executionId": self.execution_id, "jobName": self.job_name,
                                        "executionStatus": "UNKNOWN", "exitStatus": "UNKNOWN",
                                        "error": str(e), "permanent": e.permanent})
                    return
                self.log.warning("%s (attempt %d of %d)", e, failures, MAX_POLL_FAILURES)
            else:
                failures = 0
                if body.get("executionStatus") in client.TERMINAL_STATUSES:
                    yield TriggerEvent({"jobName": self.job_name, **body})
                    return
                lease = body.get("lease") or {}
                if lease.get("recoverable") is True:
                    yield TriggerEvent({"jobName": self.job_name, **body, "recoverable": True})
                    return
                if lease.get("state") != lease_state:
                    lease_state = lease.get("state")
                    self.log.info("%s: execution %s %s, lease %s (renewed %s)", self.job_name,
                                  self.execution_id, body.get("executionStatus"), lease_state,
                                  lease.get("renewedAt"))
            await asyncio.sleep(self.poll_interval)
