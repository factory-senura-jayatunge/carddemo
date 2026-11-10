"""Whole DAG runs in real Airflow (dag.test(), a SQLite metadata database in
the test AIRFLOW_HOME) against the stub batch-service. Unlike the operator
tests, the tasks here exchange the cycle through real XCom, run_if reads the
real register_cycle result and the trigger rules decide what runs after a
skipped task."""

from __future__ import annotations

import pytest

DAILY_JOBS = ["archiveMasterJob", "ingestDailyFileJob", "postDailyTransactionsJob", "calculateInterestJob",
              "statementJob", "applyRetentionJob", "purgePendingAuthorizationsJob", "dataLoadJob"]


@pytest.fixture(scope="module")
def metadata_db():
    from airflow.utils import db

    db.initdb()


@pytest.fixture
def stub(batch_service, metadata_db):
    batch_service.reply("POST", "/internal/cycles",
                        (200, {"cycleId": 17, "businessDate": "2022-07-06", "status": "OPEN"}))
    for action in ("open", "close"):
        batch_service.reply("POST", f"/internal/batch-window/{action}", (200, {"state": action.upper()}))
    for n, job in enumerate(DAILY_JOBS, start=1):
        batch_service.reply("POST", f"/internal/jobs/{job}/executions", (202, {"executionId": n, "status": "STARTING"}))
        batch_service.reply("GET", f"/internal/jobs/executions/{n}",
                            (200, {"executionId": n, "status": "COMPLETED", "exitStatus": "COMPLETED"}))
    return batch_service


def run(dagbag, dag_id, conf=None):
    run = dagbag.get_dag(dag_id).test(run_conf=conf or {})
    return run.state, {ti.task_id: ti.state for ti in run.get_task_instances()}


def launched(stub):
    return [(r["path"].split("/")[3], r["json"]["params"]) for r in stub.requests
            if r["method"] == "POST" and r["path"].startswith("/internal/jobs/")]


def cycle_request(stub):
    return next(r["json"] for r in stub.requests if r["path"] == "/internal/cycles")


@pytest.mark.parametrize("conf, interest", [
    ({"businessDate": "2022-07-06", "interest": "month_end"}, False),
    ({"businessDate": "2022-07-31", "interest": "month_end"}, True),
    ({"businessDate": "2022-07-06", "interest": "always"}, True),
    ({"businessDate": "2022-07-06", "interest": "never"}, False),
])
def test_daily_cycle_interest(dagbag, stub, conf, interest):
    state, tasks = run(dagbag, "carddemo_daily_cycle", conf)
    assert state == "success"
    assert cycle_request(stub) == {"businessDate": conf["businessDate"], "interest": interest}
    assert tasks["calculate_interest"] == ("success" if interest else "skipped")
    # A skipped interest step must not stop the rest of the cycle.
    for task in ("generate_statements", "apply_retention", "close_batch_window", "purge_pending_authorizations"):
        assert tasks[task] == "success", task
    assert tasks["purge_pending_authorizations_before_posting"] == "skipped"
    jobs = [job for job, _ in launched(stub)]
    assert jobs == ["archiveMasterJob", "ingestDailyFileJob", "postDailyTransactionsJob"] \
        + (["calculateInterestJob"] if interest else []) \
        + ["statementJob", "applyRetentionJob", "purgePendingAuthorizationsJob"]
    assert all(params == {"cycleId": 17, "businessDate": "2022-07-06"} for _, params in launched(stub))


def test_statements_only_with_interest(dagbag, stub):
    state, tasks = run(dagbag, "carddemo_daily_cycle",
                       {"businessDate": "2022-07-06", "interest": "never", "statements": "with_interest"})
    assert state == "success"
    assert tasks["calculate_interest"] == tasks["generate_statements"] == "skipped"
    assert tasks["apply_retention"] == tasks["close_batch_window"] == "success"


def test_purge_before_posting(dagbag, stub):
    state, tasks = run(dagbag, "carddemo_daily_cycle",
                       {"businessDate": "2022-07-06", "purge_position": "before_posting"})
    assert state == "success"
    assert tasks["purge_pending_authorizations_before_posting"] == "success"
    assert tasks["purge_pending_authorizations"] == "skipped"
    assert [job for job, _ in launched(stub)][:2] == ["purgePendingAuthorizationsJob", "archiveMasterJob"]


def test_failed_job_stops_the_cycle_with_the_window_open(dagbag, stub):
    stub.reply("GET", "/internal/jobs/executions/3",
               (200, {"executionId": 3, "status": "FAILED", "exitStatus": "FAILED"}))
    state, tasks = run(dagbag, "carddemo_daily_cycle", {"businessDate": "2022-07-06"})
    assert state == "failed"
    assert tasks["post_daily_transactions"] == "failed"
    assert tasks["close_batch_window"] == "upstream_failed"
    assert not any(r["path"] == "/internal/batch-window/close" for r in stub.requests)


def test_data_load_ends_after_the_load(dagbag, stub):
    state, tasks = run(dagbag, "carddemo_data_load")
    assert state == "success"
    assert tasks == {"open_batch_window": "success", "load": "success", "close_batch_window": "success"}
    assert launched(stub) == [("dataLoadJob", {})]
