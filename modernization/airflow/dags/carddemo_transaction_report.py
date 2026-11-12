"""carddemo_transaction_report: the transaction report (TRANREPT), triggered
by transaction-service through the Airflow REST API with conf {runId,
startDate, endDate, requestedBy}. runId names the batch.report_run row that
transaction-service created; the job reads its date range and requester
from that row, so only runId is sent to batch-service. Reads only, so it
needs no window and is not in the batch pool."""

from airflow.sdk import DAG, Param

from carddemo_airflow import cycle
from carddemo_airflow.operators import SpringBatchJobOperator

with DAG(
    dag_id="carddemo_transaction_report",
    description="Transaction report for a date range, requested online",
    schedule=None,
    start_date=cycle.START_DATE,
    catchup=False,
    # Requested online at any time and only reads, so it is not held back
    # like the DAGs that change data.
    is_paused_upon_creation=False,
    max_active_runs=4,
    # runId reaches batch-service with the type transaction-service gave it.
    render_template_as_native_obj=True,
    params={
        "runId": Param(type=["integer", "string"], minLength=1, minimum=1,
                       description="batch.report_run ID created by transaction-service"),
        "startDate": Param(None, type=["null", "string"], format="date",
                           description="Shown for the operator; the job reads the stored row"),
        "endDate": Param(None, type=["null", "string"], format="date",
                         description="Shown for the operator; the job reads the stored row"),
        "requestedBy": Param(None, type=["null", "string"],
                             description="Shown for the operator; the job reads the stored row"),
    },
    tags=["carddemo", "report"],
) as dag:
    SpringBatchJobOperator(task_id="run_report", job_name="transactionReportJob",
                           job_params={"runId": "{{ params.runId }}"})
