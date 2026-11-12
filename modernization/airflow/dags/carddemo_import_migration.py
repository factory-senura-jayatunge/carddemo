"""carddemo_import_migration: the migration import (CBIMPORT) of a completed
export, manual. It changes data, so it runs inside a batch window that the
run holds by its windowRunKey, and shares the batch pool. batch-service
refuses an exportId whose export did not complete (400, not retried)."""

from airflow.sdk import DAG, Param, chain

from carddemo_airflow import cycle
from carddemo_airflow.operators import BATCH_POOL, BatchWindowOperator, SpringBatchJobOperator

with DAG(
    dag_id="carddemo_import_migration",
    description="Import a completed export, given by the exportId of the export run's outputs",
    schedule=None,
    start_date=cycle.START_DATE,
    catchup=False,
    max_active_runs=1,
    # exportId reaches batch-service as the integer the run was given.
    render_template_as_native_obj=True,
    default_args={"pool": BATCH_POOL},
    params={
        "exportId": Param(type="integer", minimum=1,
                          description="exportId from the outputs of a carddemo_export_masters run"),
        "businessDate": Param(None, type=["null", "string"], format="date",
                              description="Business date of the import; empty means the logical date"),
    },
    tags=["carddemo", "batch", "manual"],
) as dag:
    chain(
        BatchWindowOperator(task_id="open_batch_window", action="open"),
        SpringBatchJobOperator(task_id="import_migration", job_name="importMigrationJob",
                               job_params={"exportId": "{{ params.exportId }}"}),
        BatchWindowOperator(task_id="close_batch_window", action="close"),
    )
