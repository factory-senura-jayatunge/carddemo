"""carddemo_reference_refresh: weekly reference reloads on Saturday, inside a
batch window that the run holds by its windowRunKey. Batch type maintenance
(MNTTRDB2) runs only when its input file for the date was delivered
(D-BAT-07); the disclosure groups (DISCGRP) reload every week."""

from airflow.sdk import DAG, Param, TriggerRule, chain

from carddemo_airflow import cycle
from carddemo_airflow.operators import BATCH_POOL, BatchWindowOperator, SpringBatchJobOperator

with DAG(
    dag_id="carddemo_reference_refresh",
    description="Weekly reference refresh: transaction types (with input) and disclosure groups",
    schedule="0 6 * * 6",
    start_date=cycle.START_DATE,
    catchup=False,
    max_active_runs=1,
    default_args={"pool": BATCH_POOL},
    params={
        "businessDate": Param(None, type=["null", "string"], format="date",
                              description="Date of the inbound files; empty means the logical date"),
    },
    tags=["carddemo", "batch"],
) as dag:
    open_window = BatchWindowOperator(task_id="open_batch_window", action="open")
    types = SpringBatchJobOperator(task_id="maintain_transaction_types", job_name="transactionTypeMaintenanceJob",
                                   run_if=cycle.transaction_type_input_present)
    groups = SpringBatchJobOperator(task_id="reload_disclosure_groups", job_name="disclosureGroupReloadJob",
                                    trigger_rule=TriggerRule.NONE_FAILED)
    close_window = BatchWindowOperator(task_id="close_batch_window", action="close",
                                       trigger_rule=TriggerRule.NONE_FAILED)
    chain(open_window, types, groups, close_window)
