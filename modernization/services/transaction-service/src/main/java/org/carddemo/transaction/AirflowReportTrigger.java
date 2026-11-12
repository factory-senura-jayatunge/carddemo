package org.carddemo.transaction;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.carddemo.platform.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts DAG {@code carddemo_transaction_report} through the Airflow REST API as the report
 * account: {@code POST /auth/token}, then {@code POST /api/v2/dags/carddemo_transaction_report/dagRuns}
 * with {@code dag_run_id tranrept-<runId>}. The run ID makes the call idempotent: Airflow's
 * {@code 409} for an existing run counts as started, so a call whose outcome is unknown (connection
 * error, timeout, {@code 5xx}, a sign-on answer without a token) can be retried, since Airflow may
 * have created the run. {@code deadline} bounds the whole trigger: no attempt starts after it,
 * each call's timeout is cut to the time it leaves, and a call it cuts off is one more unknown
 * outcome.
 * The token is kept and renewed once after a {@code 401}. Neither the password nor the token is
 * logged. The calls and answers ({@code 201} with {@code access_token}, then {@code 200}, then
 * {@code 409} for the same run ID) are the ones {@code airflow/tests/test_report_access.py} checks
 * against Airflow's own API app with the stack's auth manager.
 */
public class AirflowReportTrigger implements ReportTrigger {

    static final String DAG_ID = "carddemo_transaction_report";

    /** Waits between attempts; tests replace it. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final Logger log = LoggerFactory.getLogger(AirflowReportTrigger.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ReportProperties.Trigger settings;
    private final HttpClient http;
    private final Sleeper sleeper;
    private final LongSupplier nanoTime;
    private volatile String token;

    public AirflowReportTrigger(ReportProperties.Trigger settings) {
        this(settings, d -> Thread.sleep(d.toMillis()), System::nanoTime);
    }

    AirflowReportTrigger(ReportProperties.Trigger settings, Sleeper sleeper, LongSupplier nanoTime) {
        this.settings = settings;
        this.sleeper = sleeper;
        this.nanoTime = nanoTime;
        this.http = HttpClient.newBuilder().connectTimeout(settings.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * True once Airflow answers {@code 2xx} or {@code 409} to the run call within the deadline,
     * whatever the earlier attempts gave; false on a definite refusal, or when the retries or the
     * deadline run out first.
     */
    @Override
    public boolean trigger(long runId, LocalDate startDate, LocalDate endDate, String requestedBy) {
        String body = runBody(runId, startDate, endDate, requestedBy);
        long deadline = nanoTime.getAsLong() + settings.deadline().toNanos();
        Duration wait = settings.backoff();
        int attempts = 0;
        while (attempts <= settings.retries()) {
            if (attempts > 0) {
                if (wait.compareTo(left(deadline)) >= 0) {
                    break;
                }
                try {
                    sleeper.sleep(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                wait = wait.multipliedBy(2);
            }
            attempts++;
            try {
                int status = startRun(body, deadline);
                if (status / 100 == 2 || status == 409) {
                    return true;
                }
                if (status < 500) {
                    log.error("report run {} refused by Airflow: HTTP {}, correlation ID {}", runId, status,
                            MDC.get(CorrelationIdFilter.MDC_KEY));
                    return false;
                }
                log.warn("report run {}: Airflow answered HTTP {}, attempt {} of {}", runId, status, attempts,
                        settings.retries() + 1);
            } catch (SignOnRefused e) {
                log.error("report run {}: Airflow refused the report account's sign-on: HTTP {}, correlation ID {}",
                        runId, e.status, MDC.get(CorrelationIdFilter.MDC_KEY));
                return false;
            } catch (DeadlineReached e) {
                break;
            } catch (IOException e) {
                log.warn("report run {}: Airflow not reached ({}), attempt {} of {}", runId,
                        e.getClass().getSimpleName(), attempts, settings.retries() + 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log.error("report run {}: Airflow not reached after {} attempts within the {} deadline, correlation ID {}",
                runId, attempts, settings.deadline(), MDC.get(CorrelationIdFilter.MDC_KEY));
        return false;
    }

    private Duration left(long deadline) {
        return Duration.ofNanos(deadline - nanoTime.getAsLong());
    }

    /** The timeout of the next call: the setting, cut to what the deadline leaves. */
    private Duration callTimeout(long deadline) throws DeadlineReached {
        Duration left = left(deadline);
        if (left.isNegative() || left.isZero()) {
            throw new DeadlineReached();
        }
        return left.compareTo(settings.timeout()) < 0 ? left : settings.timeout();
    }

    /** The status of the run's creation, after one renewed sign-on if the token was refused. */
    private int startRun(String body, long deadline) throws IOException, InterruptedException {
        String current = token != null ? token : signOn(deadline);
        int status = post(body, current, deadline);
        if (status == 401) {
            token = null;
            status = post(body, signOn(deadline), deadline);
        }
        return status;
    }

    private int post(String body, String bearer, long deadline) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(resolve("/api/v2/dags/" + DAG_ID + "/dagRuns"))
                .timeout(callTimeout(deadline))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private String signOn(long deadline) throws IOException, InterruptedException {
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("username", settings.username());
        credentials.put("password", settings.password());
        HttpRequest request = HttpRequest.newBuilder(resolve("/auth/token"))
                .timeout(callTimeout(deadline))                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(credentials), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status >= 500) {
            throw new IOException("sign-on answered HTTP " + status);
        }
        if (status / 100 != 2) {
            throw new SignOnRefused(status);
        }
        String accessToken;
        try {
            JsonNode value = JSON.readTree(response.body()).get("access_token");
            accessToken = value != null && value.isValueNode() && !value.isNull() ? value.asString() : "";
        } catch (JacksonException e) {
            throw new IOException("sign-on answer is not JSON");
        }
        if (accessToken.isEmpty()) {
            throw new IOException("sign-on answer has no access_token");
        }
        token = accessToken;
        return accessToken;
    }

    private URI resolve(String path) {
        String base = settings.url().toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
    }

    /** {@code {dag_run_id, logical_date null, conf {runId, startDate, endDate, requestedBy}}}. */
    static String runBody(long runId, LocalDate startDate, LocalDate endDate, String requestedBy) {
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("runId", runId);
        conf.put("startDate", startDate.toString());
        conf.put("endDate", endDate.toString());
        conf.put("requestedBy", requestedBy);
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("dag_run_id", "tranrept-" + runId);
        run.put("logical_date", null);
        run.put("conf", conf);
        return JSON.writeValueAsString(run);
    }

    /** The deadline left no time for the next call: the same as retries used up. */
    private static final class DeadlineReached extends IOException {
        DeadlineReached() {
            super("trigger deadline reached", null);
        }
    }

    /** A definite answer to the sign-on: the account or its password is wrong. */
    private static final class SignOnRefused extends RuntimeException {
        final int status;

        SignOnRefused(int status) {
            super("sign-on refused", null, false, false);
            this.status = status;
        }
    }
}
