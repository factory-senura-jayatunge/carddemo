"""carddemo_transaction_report: the transaction report (TRANREPT), triggered
by transaction-service through the Airflow REST API with conf {runId,
startDate, endDate, requestedBy}. Reads only, so it is not in the batch pool."""

from airflow.sdk import DAG, Param

from carddemo_airflow import cycle
from carddemo_airflow.operators import SpringBatchJobOperator

with DAG(
    dag_id="carddemo_transaction_report",
    description="Transaction report for a date range, requested online",
    schedule=None,
    start_date=cycle.START_DATE,
    catchup=False,
    max_active_runs=4,
    params={
        "runId": Param(type="string", minLength=1, description="Report run ID chosen by transaction-service"),
        "startDate": Param(type="string", format="date"),
        "endDate": Param(type="string", format="date"),
        "requestedBy": Param(type="string", minLength=1, description="User ID of the requester"),
    },
    tags=["carddemo", "report"],
) as dag:
    SpringBatchJobOperator(
        task_id="run_report",
        job_name="transactionReportJob",
        job_params={
            "runId": "{{ params.runId }}",
            "startDate": "{{ params.startDate }}",
            "endDate": "{{ params.endDate }}",
            "requestedBy": "{{ params.requestedBy }}",
        },
    )
