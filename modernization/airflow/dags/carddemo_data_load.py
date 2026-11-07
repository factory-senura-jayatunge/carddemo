"""carddemo_data_load: the sample-data load (the ACCTFILE ... DUSRSECJ
reload jobs), manual. dataLoadJob takes no parameters; batch-service reads
its source directory from its own configuration and allows a reload of
non-empty tables only where configured (D-41). The reconciliation is not a
batch job: make -C modernization verify-data runs it after the load."""

from airflow.sdk import DAG, chain

from carddemo_airflow import cycle
from carddemo_airflow.operators import BATCH_POOL, BatchWindowOperator, SpringBatchJobOperator

with DAG(
    dag_id="carddemo_data_load",
    description="Load the sample data into the core tables, inside a batch window",
    schedule=None,
    start_date=cycle.START_DATE,
    catchup=False,
    max_active_runs=1,
    default_args={"pool": BATCH_POOL, "retries": 0},
    tags=["carddemo", "batch", "manual"],
) as dag:
    chain(
        BatchWindowOperator(task_id="open_batch_window", action="open"),
        SpringBatchJobOperator(task_id="load", job_name="dataLoadJob"),
        BatchWindowOperator(task_id="close_batch_window", action="close"),
    )
