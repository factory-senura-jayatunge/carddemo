"""carddemo_category_balance_listing: the category balance listing
(PRTCATBL), on demand. Reads only, so it needs no window and is not in the
batch pool. The job is run-keyed; batch-service names its output folder by
the job instance ID."""

from airflow.sdk import DAG, Param

from carddemo_airflow import cycle
from carddemo_airflow.operators import SpringBatchJobOperator

with DAG(
    dag_id="carddemo_category_balance_listing",
    description="Category balance listing, on demand",
    schedule=None,
    start_date=cycle.START_DATE,
    catchup=False,
    max_active_runs=1,
    params={
        "businessDate": Param(None, type=["null", "string"], format="date",
                              description="Business date of the listing; empty means the logical date"),
    },
    tags=["carddemo", "report"],
) as dag:
    SpringBatchJobOperator(task_id="run_listing", job_name="categoryBalanceListingJob")
