package org.carddemo.transaction;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Transaction reports (CORPT00C). The files have no setting of their own: they are under the
 * database instance's output root in CARDDEMO_VAR_DIR ({@link ReportFiles}). The download's staging
 * folder, {@code carddemo.transaction.report.staging-dir}, is not bound here: {@link ReportStaging}
 * parses it once at start, before any bean, so that its value never reaches a message or a log.
 *
 * @param notStartedAfter how old a {@code REQUESTED} row is when the status read shows it as
 *     {@code NOT_STARTED}
 * @param trigger how the DAG run is started
 */
@ConfigurationProperties("carddemo.transaction.report")
public record ReportProperties(@DefaultValue("15m") Duration notStartedAfter,
                               @DefaultValue Trigger trigger) {

    /**
     * The Airflow REST API and its report account. The values are checked when the service
     * starts: a wrong one fails the start with the property's name and is never clamped.
     *
     * @param url Airflow's base URL (CARDDEMO_AIRFLOW_URL)
     * @param username the report account (CARDDEMO_AIRFLOW_REPORT_USER)
     * @param password its password (CARDDEMO_AIRFLOW_REPORT_PASSWORD)
     * @param retries how many times an attempt whose outcome is unknown is retried; zero or more
     * @param backoff the wait before the first retry, doubled before each next one; zero or more
     * @param timeout the connect and the response timeout of each call, cut to the time the
     *     deadline leaves; positive
     * @param deadline the most the whole trigger takes, sign-on and retries included; positive
     */
    public record Trigger(@DefaultValue("http://127.0.0.1:28080") URI url,
                          @DefaultValue("transaction-service") String username,
                          @DefaultValue("") String password,
                          @DefaultValue("3") int retries,
                          @DefaultValue("1s") Duration backoff,
                          @DefaultValue("10s") Duration timeout,
                          @DefaultValue("30s") Duration deadline) {

        static final String PREFIX = "carddemo.transaction.report.trigger.";

        public Trigger {
            if (retries < 0) {
                throw new IllegalArgumentException(PREFIX + "retries must be zero or more, not " + retries);
            }
            if (backoff == null || backoff.isNegative()) {
                throw new IllegalArgumentException(PREFIX + "backoff must be zero or more, not " + backoff);
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException(PREFIX + "timeout must be positive, not " + timeout);
            }
            if (deadline == null || deadline.isNegative() || deadline.isZero()) {
                throw new IllegalArgumentException(PREFIX + "deadline must be positive, not " + deadline);
            }
        }

        @Override
        public String toString() {
            return "Trigger[" + url + ", " + username + ", retries " + retries + ", deadline " + deadline + "]";
        }
    }
}
