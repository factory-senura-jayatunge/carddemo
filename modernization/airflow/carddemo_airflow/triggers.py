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
    terminal; the waiting task holds no worker slot meanwhile."""

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
        while True:
            try:
                body = await client.execution(self.execution_id)
            except client.BatchServiceError as e:
                failures += 1
                if e.permanent or failures >= MAX_POLL_FAILURES:
                    yield TriggerEvent({"executionId": self.execution_id, "jobName": self.job_name,
                                        "status": "UNKNOWN", "exitStatus": "UNKNOWN", "error": str(e)})
                    return
                self.log.warning("%s (attempt %d of %d)", e, failures, MAX_POLL_FAILURES)
            else:
                failures = 0
                if body.get("status") in client.TERMINAL_STATUSES:
                    yield TriggerEvent({"jobName": self.job_name, **body})
                    return
            await asyncio.sleep(self.poll_interval)
