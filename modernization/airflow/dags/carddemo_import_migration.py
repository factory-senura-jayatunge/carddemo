"""carddemo_import_migration: the migration import (CBIMPORT) of an export
run's file, manual. It changes data, so it shares the batch pool."""

from airflow.sdk import DAG, Param

from carddemo_airflow import cycle
from carddemo_airflow.operators import BATCH_POOL, SpringBatchJobOperator

with DAG(
    dag_id="carddemo_import_migration",
    description="Import an export file (var/export/<exportRunId>/EXPORT.DATA), on demand",
    schedule=None,
    start_date=cycle.START_DATE,
    catchup=False,
    max_active_runs=1,
    default_args={"pool": BATCH_POOL, "retries": 0},
    params={"exportRunId": Param(type="string", minLength=1, description="runId of the export to import")},
    tags=["carddemo", "batch", "manual"],
) as dag:
    SpringBatchJobOperator(task_id="import_migration", job_name="importMigrationJob",
                           job_params={"runId": cycle.RUN_ID, "exportRunId": "{{ params.exportRunId }}"})
