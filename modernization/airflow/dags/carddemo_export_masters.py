"""carddemo_export_masters: the master export (CBEXPORT), manual. Reads
only, so it is not in the batch pool."""

from airflow.sdk import DAG

from carddemo_airflow import cycle
from carddemo_airflow.operators import SpringBatchJobOperator

with DAG(
    dag_id="carddemo_export_masters",
    description="Export the masters to var/export/<runId>/EXPORT.DATA, on demand",
    schedule=None,
    start_date=cycle.START_DATE,
    catchup=False,
    max_active_runs=1,
    tags=["carddemo", "batch", "manual"],
) as dag:
    SpringBatchJobOperator(task_id="export_masters", job_name="exportMastersJob",
                           job_params={"runId": cycle.RUN_ID})
