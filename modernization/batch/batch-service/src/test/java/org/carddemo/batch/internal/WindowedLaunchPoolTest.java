package org.carddemo.batch.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;
import org.carddemo.batch.execution.LeaseKeeper;
import org.carddemo.batch.internal.JobCatalog.JobSpec;
import org.carddemo.batch.internal.JobCatalog.Kind;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Parallel launches and restarts of a window job over HTTP, with the default connection pool (10)
 * and the default timeouts, while a long job holds a connection and, in one case, while a window
 * close holds the launch lock. More callers than connections must still get only contract answers
 * (202, 409, 503), never a pool timeout's 500, and leave no connection, session lock or lease behind.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("server")
class WindowedLaunchPoolTest {

    static final TestDatabase DB = TestDatabase.shared();
    static final String TOKEN = UUID.randomUUID().toString();
    static final String JOB = "probePoolJob";
    static final Set<String> FAIL_ONCE = ConcurrentHashMap.newKeySet();
    static volatile CountDownLatch gate = new CountDownLatch(1);
    /** More callers than the pool has connections. */
    static final int CALLERS = 20;

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    LeaseKeeper keeper;

    final List<Long> started = new ArrayList<>();
    String windowKey;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.BATCH);
        registry.add("carddemo.batch.internal.token", () -> TOKEN);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probe {

        /** Fails once for a run key in FAIL_ONCE; a run key ending in /gate holds its step's connection until the gate opens. */
        @Bean
        Job probePoolJob(JobRepository jobRepository, PlatformTransactionManager tx) {
            return new JobBuilder(JOB, jobRepository).start(new StepBuilder("probe", jobRepository)
                    .tasklet((contribution, chunk) -> {
                        String key = chunk.getStepContext().getStepExecution().getJobParameters().getString("runKey");
                        if (FAIL_ONCE.remove(key)) {
                            throw new IllegalStateException("probe fails its first execution");
                        }
                        if (key.endsWith("/gate") && !gate.await(120, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("gate not opened");
                        }
                        return RepeatStatus.FINISHED;
                    }, tx).build()).build();
        }

        @Bean
        @Primary
        JobCatalog probeCatalog() {
            List<JobSpec> specs = new ArrayList<>(JobCatalog.contract());
            specs.add(new JobSpec(JOB, Kind.RUN_KEYED, true, Set.of()));
            return new JobCatalog(specs);
        }
    }

    record Answer(int status, String code, Long executionId, Boolean restart, String retryAfter, long millis) {

        @Override
        public String toString() {
            return status + " " + code;
        }
    }

    @BeforeEach
    void openWindow() throws Exception {
        gate = new CountDownLatch(1);
        windowKey = "carddemo_test/pool__" + UUID.randomUUID();
        assertThat(post("/internal/batch-window/open", "{\"windowRunKey\":\"" + windowKey + "\"}").status()).isEqualTo(200);
    }

    @AfterEach
    void endJobsAndCloseWindow() throws Exception {
        gate.countDown();
        FAIL_ONCE.clear();
        for (long id : started) {
            awaitEnd(id);
        }
        post("/internal/batch-window/close", "{\"windowRunKey\":\"" + windowKey + "\"}");
    }

    @Test
    void theDefaultPoolIsTheOneUnderTest() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(10);
        assertThat(pool.getConnectionTimeout()).isEqualTo(30_000);
    }

    @Test
    void launchesQueuedBehindAWindowCloseGetContractAnswersWhenItEnds() throws Exception {
        long longJob = launchedId(launch(windowKey + "/long/gate"));
        List<String> restartKeys = failedInstances(4);

        List<Answer> answers;
        try (Connection close = DB.adminDataSource().getConnection()) {
            holdLaunchLock(close);
            List<Future<Answer>> pending;
            ExecutorService callers = Executors.newFixedThreadPool(CALLERS);
            try {
                pending = storm(callers, restartKeys);
                // Every caller is now waiting for the lock or for a connection.
                Thread.sleep(2_000);
            } finally {
                releaseLaunchLock(close);
            }
            long released = System.nanoTime();
            answers = collect(pending);
            callers.shutdownNow();
            // Well inside the pool's 30 s connection timeout: nobody waited for a connection that never came.
            assertThat(Duration.ofNanos(System.nanoTime() - released)).isLessThan(Duration.ofSeconds(20));
        }

        assertStormAnswers(answers, restartKeys);
        assertThat(executionStatus(longJob)).isEqualTo("STARTED");
        assertNothingHeldAfterTheJobsEnd();
    }

    @Test
    void moreParallelLaunchesAndRestartsThanConnectionsGetContractAnswers() throws Exception {
        long longJob = launchedId(launch(windowKey + "/long/gate"));
        List<String> restartKeys = failedInstances(4);

        ExecutorService callers = Executors.newFixedThreadPool(CALLERS + 1);
        List<Future<Answer>> pending = storm(callers, restartKeys);
        Future<Answer> close = callers.submit(() -> post("/internal/batch-window/close",
                "{\"windowRunKey\":\"" + windowKey + "\"}"));
        List<Answer> answers = collect(pending);
        Answer closed = close.get(90, TimeUnit.SECONDS);
        callers.shutdownNow();

        assertStormAnswers(answers, restartKeys);
        // The long job is live, so the close refuses; it may also time out on the launches in flight.
        assertThat(closed.toString()).isIn("409 EXECUTIONS_RUNNING", "503 LAUNCH_IN_PROGRESS");
        assertThat(executionStatus(longJob)).isEqualTo("STARTED");
        assertNothingHeldAfterTheJobsEnd();
    }

    /**
     * CALLERS launches at one moment: 6 of one gated instance, 6 of distinct instances, and two
     * restarts of each failed instance.
     */
    private List<Future<Answer>> storm(ExecutorService callers, List<String> restartKeys) {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            keys.add(windowKey + "/same/gate");
        }
        for (int i = 0; i < 6; i++) {
            keys.add(windowKey + "/distinct/" + i);
        }
        for (String key : restartKeys) {
            keys.add(key);
            keys.add(key);
        }
        assertThat(keys).hasSize(CALLERS);
        CyclicBarrier barrier = new CyclicBarrier(keys.size());
        List<Future<Answer>> pending = new ArrayList<>();
        for (String key : keys) {
            pending.add(callers.submit(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                return launch(key);
            }));
        }
        return pending;
    }

    private List<Answer> collect(List<Future<Answer>> pending) throws Exception {
        List<Answer> answers = new ArrayList<>();
        for (Future<Answer> f : pending) {
            Answer a = f.get(90, TimeUnit.SECONDS);
            if (a.executionId() != null && a.status() == 202) {
                started.add(a.executionId());
            }
            answers.add(a);
        }
        return answers;
    }

    private void assertStormAnswers(List<Answer> answers, List<String> restartKeys) {
        assertThat(answers).as("only contract answers").allSatisfy(a -> {
            assertThat(a.status()).isIn(202, 409, 503);
            if (a.status() == 503) {
                assertThat(a.code()).isEqualTo("WINDOW_CLOSE_IN_PROGRESS");
                assertThat(a.retryAfter()).isNotNull();
            }
        });
        List<Answer> same = answers.subList(0, 6);
        assertThat(same).filteredOn(a -> a.status() == 202).hasSize(1);
        assertThat(same).filteredOn(a -> a.status() != 202)
                .allSatisfy(a -> assertThat(a.toString()).isEqualTo("409 JOB_EXECUTION_RUNNING"));
        assertThat(answers.subList(6, 12)).allSatisfy(a -> assertThat(a.status()).isEqualTo(202));
        for (int k = 0; k < restartKeys.size(); k++) {
            List<Answer> pair = answers.subList(12 + 2 * k, 14 + 2 * k);
            assertThat(pair).filteredOn(a -> a.status() == 202 && Boolean.TRUE.equals(a.restart())).hasSize(1);
            assertThat(pair).filteredOn(a -> a.status() != 202).allSatisfy(a -> assertThat(a.toString())
                    .isIn("409 JOB_EXECUTION_RUNNING", "409 JOB_INSTANCE_COMPLETE"));
            assertThat(executions(restartKeys.get(k))).isEqualTo(2);
        }
        assertThat(executions(windowKey + "/same/gate")).isEqualTo(1);
    }

    private void assertNothingHeldAfterTheJobsEnd() throws Exception {
        gate.countDown();
        for (long id : started) {
            awaitEnd(id);
            assertThat(executionStatus(id)).isEqualTo("COMPLETED");
            assertThat(keeper.owns(id)).as("lease of %d still renewed", id).isFalse();
        }
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (pool.getHikariPoolMXBean().getActiveConnections() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        assertThat(DB.admin().sql("""
                        SELECT COUNT(*) FROM sys.dm_tran_locks AS l
                        JOIN sys.dm_exec_sessions AS s ON s.session_id = l.request_session_id
                        WHERE l.resource_type = 'APPLICATION' AND l.resource_database_id = DB_ID()
                          AND s.login_name = :login""")
                .param("login", DB.loginName(ServiceLogin.BATCH)).query(Integer.class).single()).isZero();
    }

    /** Failed first executions of {@code n} instances, ready to restart. */
    private List<String> failedInstances(int n) throws Exception {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String key = windowKey + "/restart/" + i;
            FAIL_ONCE.add(key);
            Answer first = launch(key);
            assertThat(first.status()).as(first.toString()).isEqualTo(202);
            long id = first.executionId();
            awaitEnd(id);
            assertThat(executionStatus(id)).isEqualTo("FAILED");
            keys.add(key);
        }
        return keys;
    }

    private long launchedId(Answer answer) {
        assertThat(answer.status()).as(answer.toString()).isEqualTo(202);
        started.add(answer.executionId());
        return answer.executionId();
    }

    private Answer launch(String runKey) throws Exception {
        return post("/internal/jobs/" + JOB + "/executions", """
                {"params":{"runKey":"%s","businessDate":"2022-07-06","windowRunKey":"%s"}}"""
                .formatted(runKey, windowKey));
    }

    private Answer post(String path, String json) throws Exception {
        long start = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(90)).POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }
        Map<String, Object> body = response.body().isBlank() ? Map.of() : JsonPath.read(response.body(), "$");
        Object id = body.get("executionId");
        return new Answer(response.statusCode(), (String) body.get("code"),
                id == null ? null : ((Number) id).longValue(), (Boolean) body.get("restart"),
                response.headers().firstValue("Retry-After").orElse(null),
                Duration.ofNanos(System.nanoTime() - start).toMillis());
    }

    private static void holdLaunchLock(Connection session) throws Exception {
        try (Statement s = session.createStatement()) {
            s.execute("EXEC sp_getapplock @Resource = 'carddemo.batch_launch', @LockMode = 'Exclusive', "
                    + "@LockOwner = 'Session'");
        }
    }

    private static void releaseLaunchLock(Connection session) throws Exception {
        try (Statement s = session.createStatement()) {
            s.execute("EXEC sp_releaseapplock @Resource = 'carddemo.batch_launch', @LockOwner = 'Session'");
        }
    }

    private static void awaitEnd(long id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (Set.of("STARTING", "STARTED", "STOPPING").contains(executionStatus(id))
                && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
    }

    private static String executionStatus(long id) {
        return DB.admin().sql("SELECT STATUS FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", id).query(String.class).single();
    }

    private static long executions(String runKey) {
        return DB.admin().sql("""
                        SELECT COUNT_BIG(*) FROM batch.BATCH_JOB_EXECUTION e
                        JOIN batch.BATCH_JOB_INSTANCE i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID
                        JOIN batch.BATCH_JOB_EXECUTION_PARAMS p ON p.JOB_EXECUTION_ID = e.JOB_EXECUTION_ID
                        WHERE i.JOB_NAME = :job AND p.PARAMETER_NAME = 'runKey' AND p.PARAMETER_VALUE = :key""")
                .param("job", JOB).param("key", runKey).query(Long.class).single();
    }
}
