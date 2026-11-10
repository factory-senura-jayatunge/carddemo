"""carddemo_category_balance_listing: the category balance listing
(PRTCATBL), on demand. Reads only, so it is not in the batch pool."""

from airflow.sdk import DAG

from carddemo_airflow import cycle
from carddemo_airflow.operators import SpringBatchJobOperator

with DAG(
    dag_id="carddemo_category_balance_listing",
    description="Category balance listing, on demand",
    schedule=None,
    start_date=cycle.START_DATE,
    catchup=False,
    max_active_runs=1,
    tags=["carddemo", "report"],
) as dag:
    SpringBatchJobOperator(task_id="run_listing", job_name="categoryBalanceListingJob",
                           job_params={"runId": cycle.RUN_ID})
