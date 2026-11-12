"""carddemo_export_masters: the master export (CBEXPORT), manual. Reads the
masters only, so it needs no window and is not in the batch pool.
batch-service records the export as a batch.export_run row and writes it to
exports/<exportId>; the task pushes outputs {exportId} to XCom and logs it,
and carddemo_import_migration takes that exportId."""

from airflow.sdk import DAG, Param

from carddemo_airflow import cycle
from carddemo_airflow.operators import SpringBatchJobOperator

with DAG(
    dag_id="carddemo_export_masters",
    description="Export the masters; the run's outputs give the exportId to import",
    schedule=None,
    start_date=cycle.START_DATE,
    catchup=False,
    max_active_runs=1,
    params={
        "businessDate": Param(None, type=["null", "string"], format="date",
                              description="Business date of the export; empty means the logical date"),
    },
    tags=["carddemo", "batch", "manual"],
) as dag:
    SpringBatchJobOperator(task_id="export_masters", job_name="exportMastersJob")
