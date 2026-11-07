"""carddemo_daily_cycle: the batch cycle in data-dependency order (option F
of the architecture's batch-cycle decision).

register_cycle → open_batch_window → [purge before posting] → archive_master
→ ingest_daily_file → post_daily_transactions → calculate_interest (when the
cycle charges interest) → generate_statements → apply_retention →
close_batch_window → purge_pending_authorizations (after the cycle).

A failed task stops the run and leaves the window open; an operator closes
it with make -C modernization batch-window-close after recovery.
"""

from airflow.sdk import DAG, Param, TriggerRule, chain

from carddemo_airflow import cycle
from carddemo_airflow.operators import BATCH_POOL, BatchWindowOperator, RegisterCycleOperator, SpringBatchJobOperator

with DAG(
    dag_id="carddemo_daily_cycle",
    description="Daily batch cycle: posting, interest, statements, retention, authorization purge",
    schedule="@daily",
    start_date=cycle.START_DATE,
    catchup=False,
    max_active_runs=1,
    default_args={"pool": BATCH_POOL, "retries": 0},
    params={
        "businessDate": Param(None, type=["null", "string"], format="date",
                              description="Business date of the cycle; empty means the logical date"),
        "interest": Param("always", enum=cycle.INTEREST_CHOICES,
                          description="When interest is charged: every cycle, on the last day of the month, or never"),
        "statements": Param("every_cycle", enum=cycle.STATEMENT_CHOICES,
                            description="Statements every cycle, or only in cycles that charge interest"),
        "purge_position": Param("after_cycle", enum=cycle.PURGE_CHOICES,
                                description="Authorization purge after the cycle, or before posting (CA-7 order)"),
    },
    tags=["carddemo", "batch"],
) as dag:
    register = RegisterCycleOperator(task_id="register_cycle")
    open_window = BatchWindowOperator(task_id="open_batch_window", action="open", with_cycle=True)
    purge_early = SpringBatchJobOperator(
        task_id="purge_pending_authorizations_before_posting", job_name="purgePendingAuthorizationsJob",
        with_cycle=True, run_if=cycle.purge_before_posting)
    archive = SpringBatchJobOperator(task_id="archive_master", job_name="archiveMasterJob", with_cycle=True,
                                     trigger_rule=TriggerRule.NONE_FAILED)
    ingest = SpringBatchJobOperator(task_id="ingest_daily_file", job_name="ingestDailyFileJob", with_cycle=True)
    post = SpringBatchJobOperator(task_id="post_daily_transactions", job_name="postDailyTransactionsJob",
                                  with_cycle=True)
    interest = SpringBatchJobOperator(task_id="calculate_interest", job_name="calculateInterestJob",
                                      with_cycle=True, run_if=cycle.cycle_interest)
    statements = SpringBatchJobOperator(task_id="generate_statements", job_name="statementJob", with_cycle=True,
                                        run_if=cycle.statements_due, trigger_rule=TriggerRule.NONE_FAILED)
    retention = SpringBatchJobOperator(task_id="apply_retention", job_name="applyRetentionJob", with_cycle=True,
                                       trigger_rule=TriggerRule.NONE_FAILED)
    close_window = BatchWindowOperator(task_id="close_batch_window", action="close", with_cycle=True,
                                       trigger_rule=TriggerRule.NONE_FAILED)
    purge = SpringBatchJobOperator(task_id="purge_pending_authorizations", job_name="purgePendingAuthorizationsJob",
                                   with_cycle=True, run_if=cycle.purge_after_cycle)

    chain(register, open_window, purge_early, archive, ingest, post, interest, statements, retention,
          close_window, purge)
