package org.carddemo.batch.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.jayway.jsonpath.JsonPath;
import org.carddemo.batch.execution.LeaseKeeper;
import org.carddemo.batch.internal.JobCatalog.Extra;
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
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The internal API (batch-execution-contract.md) in the server profile, against SQL Server, with
 * interval 0.5 s, TTL 2 s and grace 2 s. A probe window job waits at a per-execution gate before
 * each of its two units, so a test decides when it ends; suspending the renewals stands in for a
 * lost owner.
 */
@SpringBootTest(properties = {
        "carddemo.batch.internal.window-drain-timeout=1s",
        "carddemo.batch.internal.launch-lock-timeout=2s",
        "carddemo.batch.lease.renewal-interval=500ms",
        "carddemo.batch.lease.ttl=2s",
        "carddemo.batch.lease.grace=2s"})
@ActiveProfiles({"server", "demo"})
@AutoConfigureMockMvc
class InternalApiTest {

    static final TestDatabase DB = TestDatabase.shared();
    static final String TOKEN = UUID.randomUUID().toString();
    static final Map<Long, Semaphore> GATES = new ConcurrentHashMap<>();
    static final Path SAMPLE_DIR = Path.of(System.getProperty("carddemo.repo.root", "../../.."), "app", "data", "ASCII")
            .toAbsolutePath().normalize();
    /** Business dates of this test's cycles; no other test uses them. */
    static final String TEST_DATES = "business_date >= '2200-01-01'";

    @Autowired
    MockMvc mvc;

    @Autowired
    LeaseKeeper keeper;

    @Autowired
    ConfigurableEnvironment environment;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.BATCH);
        registry.add("carddemo.batch.internal.token", () -> TOKEN);
        registry.add("carddemo.data-load.source-dir", SAMPLE_DIR::toString);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probe {

        @Bean
        Job probeWindowJob(JobRepository jobRepository, PlatformTransactionManager tx) {
            return gated("probeWindowJob", jobRepository, tx);
        }

        /** A cycle window job with the interest parameter, as calculateInterestJob. */
        @Bean
        Job probeInterestJob(JobRepository jobRepository, PlatformTransactionManager tx) {
            return gated("probeInterestJob", jobRepository, tx);
        }

        /** A cycle job without a window, as purgePendingAuthorizationsJob. */
        @Bean
        Job probePurgeJob(JobRepository jobRepository, PlatformTransactionManager tx) {
            return gated("probePurgeJob", jobRepository, tx);
        }

        private static Job gated(String name, JobRepository jobRepository, PlatformTransactionManager tx) {
            return new JobBuilder(name, jobRepository).start(new StepBuilder("probe", jobRepository)
                    .tasklet((contribution, chunk) -> {
                        StepExecution step = chunk.getStepContext().getStepExecution();
                        Semaphore gate = GATES.computeIfAbsent(step.getJobExecutionId(), id -> new Semaphore(0));
                        if (!gate.tryAcquire(30, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("gate not opened");
                        }
                        int done = step.getExecutionContext().getInt("done", 0) + 1;
                        step.getExecutionContext().putInt("done", done);
                        return done < 2 ? RepeatStatus.CONTINUABLE : RepeatStatus.FINISHED;
                    }, tx).build()).build();
        }

        @Bean
        @Primary
        JobCatalog probeCatalog() {
            List<JobSpec> specs = new ArrayList<>(JobCatalog.contract());
            specs.add(new JobSpec("probeWindowJob", Kind.RUN_KEYED, true, Set.of(Extra.EXPIRY_DAYS)));
            specs.add(new JobSpec("probeInterestJob", Kind.CYCLE, true, Set.of(Extra.INTEREST_PARM)));
            specs.add(new JobSpec("probePurgeJob", Kind.CYCLE, false, Set.of(Extra.EXPIRY_DAYS, Extra.COMMIT_INTERVAL), true));
            return new JobCatalog(specs);
        }
    }

    @BeforeEach
    void clean() {
        DB.admin().sql("UPDATE ops.batch_window SET closed_at = SYSUTCDATETIME() WHERE closed_at IS NULL").update();
        DB.admin().sql("DELETE FROM ops.batch_window WHERE cycle_id IN (SELECT cycle_id FROM batch.cycle WHERE "
                + TEST_DATES + ")").update();
        DB.admin().sql("DELETE FROM batch.cycle WHERE " + TEST_DATES).update();
    }

    @AfterEach
    void resume() {
        environment.getPropertySources().remove("changedSettings");
        keeper.suspendRenewals(false);
        GATES.values().forEach(g -> g.release(100));
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + TOKEN));
    }

    private ResultActions post(String path, String json) throws Exception {
        return call(MockMvcRequestBuilders.post(path)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static <T> T read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }

    private static long number(ResultActions result, String path) throws Exception {
        return ((Number) read(result, path)).longValue();
    }

    private long cycle(String date, String interest) throws Exception {
        return number(post("/internal/cycles", "{\"businessDate\":\"%s\",\"interest\":\"%s\"}".formatted(date, interest))
                .andExpect(status().isCreated()), "$.cycleId");
    }

    private ResultActions launch(String job, String params) throws Exception {
        return post("/internal/jobs/" + job + "/executions", "{\"params\":" + params + "}");
    }

    private ResultActions poll(long executionId) throws Exception {
        return call(get("/internal/jobs/executions/" + executionId)).andExpect(status().isOk());
    }

    private Map<String, Object> awaitPoll(long executionId, String what, Predicate<ResultActions> condition)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (true) {
            ResultActions polled = poll(executionId);
            if (condition.test(polled)) {
                return read(polled, "$");
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting until " + what + ": "
                        + polled.andReturn().getResponse().getContentAsString());
            }
            Thread.sleep(100);
        }
    }

    private static Predicate<ResultActions> at(String path, Object value) {
        return r -> {
            try {
                return value.equals(read(r, path));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private static void open(long executionId, int units) {
        GATES.computeIfAbsent(executionId, id -> new Semaphore(0)).release(units);
    }

    private static String parameter(long executionId, String name) {
        return DB.admin().sql("SELECT PARAMETER_VALUE FROM batch.BATCH_JOB_EXECUTION_PARAMS "
                        + "WHERE JOB_EXECUTION_ID = :id AND PARAMETER_NAME = :name")
                .param("id", executionId).param("name", name).query(String.class).single();
    }

    private static void holdLaunchLock(Connection session, String mode) throws Exception {
        try (Statement s = session.createStatement()) {
            s.execute("EXEC sp_getapplock @Resource = 'carddemo.batch_launch', @LockMode = '" + mode
                    + "', @LockOwner = 'Session'");
        }
    }

    /** A pooled connection keeps its session, and with it a session lock, after close. */
    private static void releaseLaunchLock(Connection session) throws Exception {
        try (Statement s = session.createStatement()) {
            s.execute("EXEC sp_releaseapplock @Resource = 'carddemo.batch_launch', @LockOwner = 'Session'");
        }
    }

    private static String executionStatus(long executionId) {
        return DB.admin().sql("SELECT STATUS FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", executionId).query(String.class).single();
    }

    @Test
    void onlyALoopbackCallerWithTheTokenIsServed() throws Exception {
        String body = "{\"businessDate\":\"2200-01-02\",\"interest\":\"never\"}";
        mvc.perform(MockMvcRequestBuilders.post("/internal/cycles").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.field").value(nullValue())).andExpect(jsonPath("$.severity").value("error"));
        mvc.perform(MockMvcRequestBuilders.post("/internal/cycles").header("Authorization", "Bearer x" + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.post("/internal/cycles").header("Authorization", "Bearer " + TOKEN).with(r -> {
            r.setRemoteAddr("10.1.2.3");
            return r;
        }).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        assertThat(DB.admin().sql("SELECT COUNT(*) FROM batch.cycle WHERE " + TEST_DATES).query(Integer.class).single())
                .isZero();
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void aCycleIsRegisteredOnceWithItsInterestDecision() throws Exception {
        ResultActions created = post("/internal/cycles", "{\"businessDate\":\"2200-03-15\",\"interest\":\"always\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.businessDate").value("2200-03-15"))
                .andExpect(jsonPath("$.attempt").value(1)).andExpect(jsonPath("$.interest").value(true))
                .andExpect(jsonPath("$.interestParm").value("2200031500")).andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.closedAt").value(nullValue()));
        long id = number(created, "$.cycleId");
        assertThat((String) read(created, "$.openedAt")).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");

        post("/internal/cycles", "{\"businessDate\":\"2200-03-15\",\"interest\":\"always\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleId").value(id));
        post("/internal/cycles", "{\"businessDate\":\"2200-03-15\",\"interest\":\"never\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CYCLE_PARAMETERS_DIFFER"))
                .andExpect(jsonPath("$.cycleId").value(id)).andExpect(jsonPath("$.interest").value(true));

        post("/internal/cycles", "{\"businessDate\":\"2200-03-31\",\"interest\":\"month_end\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.interestParm").value("2200033100"));
        post("/internal/cycles", "{\"businessDate\":\"2200-04-15\",\"interest\":\"month_end\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.interest").value(false))
                .andExpect(jsonPath("$.interestParm").value(nullValue()));
        post("/internal/cycles", "{\"businessDate\":\"2200-04-15\",\"interest\":\"never\"}").andExpect(status().isOk());

        post("/internal/cycles", "{\"businessDate\":\"2200-02-30\",\"interest\":\"never\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("businessDate"));
        post("/internal/cycles", "{\"businessDate\":\"2200-05-01\",\"interest\":\"sometimes\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("interest"));
        post("/internal/cycles", "{").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void theWindowHasOneHolderAndItsCompletingCloseCompletesTheCycle() throws Exception {
        long cycle = cycle("2200-06-01", "always");
        String holder = "{\"cycleId\":" + cycle + "}";
        ResultActions opened = post("/internal/batch-window/open", holder).andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleId").value(cycle)).andExpect(jsonPath("$.windowRunKey").value(nullValue()))
                .andExpect(jsonPath("$.mode").value("WRITES_BLOCKED"));
        long window = number(opened, "$.windowId");
        post("/internal/batch-window/open", holder).andExpect(status().isOk()).andExpect(jsonPath("$.windowId").value(window));

        post("/internal/batch-window/open", "{\"windowRunKey\":\"carddemo_test/manual__1\"}")
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.code").value("WINDOW_BUSY")).andExpect(jsonPath("$.windowId").value(window))
                .andExpect(jsonPath("$.cycleId").value(cycle));
        assertThat(DB.admin().sql("SELECT opened_by FROM ops.batch_window WHERE window_id = :id").param("id", window)
                .query(String.class).single()).startsWith("batch-service ");

        ResultActions closed = post("/internal/batch-window/close", "{\"cycleId\":" + cycle + ",\"complete\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.windowId").value(window))
                .andExpect(jsonPath("$.cycle.cycleId").value(cycle)).andExpect(jsonPath("$.cycle.status").value("COMPLETED"));
        String closedAt = read(closed, "$.closedAt");
        assertThat(closedAt).isNotNull();
        post("/internal/batch-window/close", "{\"cycleId\":" + cycle + ",\"complete\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.windowId").value(window)).andExpect(jsonPath("$.closedAt").value(closedAt));
        post("/internal/cycles", "{\"businessDate\":\"2200-06-01\",\"interest\":\"always\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CYCLE_COMPLETED"))
                .andExpect(jsonPath("$.cycleId").value(cycle)).andExpect(jsonPath("$.status").value("COMPLETED"));

        post("/internal/batch-window/open", "{\"windowRunKey\":\"carddemo_test/manual__1\"}").andExpect(status().isOk());
        post("/internal/batch-window/close", "{\"windowRunKey\":\"carddemo_test/manual__1\",\"complete\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.cycle").value(nullValue()))
                .andExpect(jsonPath("$.windowRunKey").value("carddemo_test/manual__1"));

        post("/internal/batch-window/close", "{\"windowRunKey\":\"carddemo_test/never-opened\",\"complete\":false}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WINDOW_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No batch window was opened for this holder."))
                .andExpect(header().doesNotExist("Retry-After"));
        post("/internal/batch-window/close", "{\"cycleId\":" + cycle("2200-06-02", "never") + ",\"complete\":false}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WINDOW_NOT_FOUND"));
        post("/internal/batch-window/open", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        post("/internal/batch-window/open", "{\"cycleId\":" + cycle + ",\"windowRunKey\":\"x\"}")
                .andExpect(status().isBadRequest());
        post("/internal/batch-window/open", "{\"cycleId\":2147483000}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("cycleId"));
    }

    @Test
    void aCloseAfterAFailureLeavesTheCycleOpenForTheNextRun() throws Exception {
        long cycle = cycle("2200-07-01", "never");
        String holder = "{\"cycleId\":" + cycle + "}";
        long first = number(post("/internal/batch-window/open", holder).andExpect(status().isOk()), "$.windowId");
        ResultActions closed = post("/internal/batch-window/close", "{\"cycleId\":" + cycle + ",\"complete\":false}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.windowId").value(first))
                .andExpect(jsonPath("$.cycle.status").value("OPEN")).andExpect(jsonPath("$.cycle.closedAt").value(nullValue()));
        String closedAt = read(closed, "$.closedAt");
        // Repeating the close, with either flag, answers the closed window as it is.
        post("/internal/batch-window/close", "{\"cycleId\":" + cycle + ",\"complete\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.windowId").value(first)).andExpect(jsonPath("$.closedAt").value(closedAt))
                .andExpect(jsonPath("$.cycle.status").value("OPEN"));
        post("/internal/cycles", "{\"businessDate\":\"2200-07-01\",\"interest\":\"never\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleId").value(cycle)).andExpect(jsonPath("$.status").value("OPEN"));

        long second = number(post("/internal/batch-window/open", holder).andExpect(status().isOk()), "$.windowId");
        assertThat(second).isNotEqualTo(first);
        post("/internal/batch-window/close", "{\"cycleId\":" + cycle + ",\"complete\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.windowId").value(second)).andExpect(jsonPath("$.cycle.status").value("COMPLETED"));
    }

    @Test
    void anOpenThatOnlineWritesDoNotLetThroughInTimeLeavesTheWindowClosed() throws Exception {
        String holder = "{\"windowRunKey\":\"carddemo_test/drain\"}";
        try (Connection writer = DB.adminDataSource().getConnection()) {
            writer.setAutoCommit(false);
            try (Statement s = writer.createStatement()) {
                s.execute("IF @@TRANCOUNT = 0 BEGIN TRANSACTION; EXEC sp_getapplock @Resource = 'carddemo.batch_window', "
                        + "@LockMode = 'Shared', @LockOwner = 'Transaction'");
            }
            post("/internal/batch-window/open", holder).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("WINDOW_DRAIN_TIMEOUT"));
            assertThat(DB.admin().sql("SELECT COUNT(*) FROM ops.batch_window WHERE closed_at IS NULL")
                    .query(Integer.class).single()).isZero();
            writer.rollback();
        }
        post("/internal/batch-window/open", holder).andExpect(status().isOk());
        post("/internal/batch-window/close", holder).andExpect(status().isOk());
    }

    @Test
    void dataLoadJobRunsInItsOwnWindowOnceForItsRunKey() throws Exception {
        DB.admin().sql("DELETE FROM core.daily_reject").update();
        DB.admin().sql("DELETE FROM core.daily_tran").update();
        DB.fixtures().deleteAll();
        String windowKey = "carddemo_data_load/manual__" + UUID.randomUUID();
        String params = "{\"runKey\":\"%s/load\",\"businessDate\":\"2022-07-06\",\"windowRunKey\":\"%s\"}"
                .formatted(windowKey, windowKey);

        launch("dataLoadJob", params).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_WINDOW_REQUIRED"));
        launch("dataLoadJob", "{\"businessDate\":\"2022-07-06\",\"windowRunKey\":\"w\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_JOB_PARAMETERS"))
                .andExpect(jsonPath("$.field").value("runKey"));
        launch("dataLoadJob", "{\"runKey\":\"%s\",\"businessDate\":\"2022-07-06\",\"windowRunKey\":\"w\"}"
                .formatted("k".repeat(251))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("runKey"));
        launch("dataLoadJob", "{\"runKey\":\"k\",\"windowRunKey\":\"w\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("businessDate"));
        launch("dataLoadJob", "{\"runKey\":\"k\",\"businessDate\":\"2022-07-06\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("windowRunKey"));
        launch("dataLoadJob", "{\"runKey\":\"k\",\"businessDate\":\"2022-07-06\",\"windowRunKey\":\"w\",\"sourceDir\":\"/tmp\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sourceDir"));

        post("/internal/batch-window/open", "{\"windowRunKey\":\"" + windowKey + "\"}").andExpect(status().isOk());
        ResultActions launched = launch("dataLoadJob", params).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobName").value("dataLoadJob")).andExpect(jsonPath("$.restart").value(false))
                .andExpect(jsonPath("$.rejectCount").value(0));
        long id = number(launched, "$.executionId");

        Map<String, Object> done = awaitPoll(id, "the load completes", at("$.executionStatus", "COMPLETED"));
        assertThat(done).containsEntry("exitStatus", "COMPLETED").containsEntry("runKey", windowKey + "/load")
                .containsEntry("cycleId", null).containsEntry("runId", null).containsEntry("outputs", Map.of());
        assertThat(done.get("endTime")).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> lease = (Map<String, Object>) done.get("lease");
        assertThat(lease).containsEntry("recoverable", false).containsKeys("state", "renewedAt", "expiresAt",
                "recoverableAt", "dbTime");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) done.get("steps");
        assertThat(steps).hasSize(12);
        assertThat(steps.getFirst()).containsEntry("name", "clearCoreTables").containsEntry("status", "COMPLETED");
        assertThat(steps.getLast()).containsEntry("name", "loadPendingAuthorizations").containsEntry("status", "COMPLETED");
        assertThat(DB.admin().sql("SELECT COUNT(*) FROM core.account").query(Integer.class).single()).isEqualTo(50);

        launch("dataLoadJob", params).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_INSTANCE_COMPLETE")).andExpect(jsonPath("$.executionId").value(id))
                .andExpect(jsonPath("$.exitStatus").value("COMPLETED")).andExpect(jsonPath("$.restart").doesNotExist());
        post("/internal/batch-window/close", "{\"windowRunKey\":\"" + windowKey + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.cycle").value(nullValue()));
    }

    @Test
    void aLaunchAndAWindowCloseThatWaitTooLongForEachOtherAskToRetry() throws Exception {
        String windowKey = "carddemo_test/locks__" + UUID.randomUUID();
        String holder = "{\"windowRunKey\":\"" + windowKey + "\"}";
        String params = "{\"runKey\":\"%s/probe\",\"businessDate\":\"2022-07-06\",\"windowRunKey\":\"%s\"}"
                .formatted(windowKey, windowKey);
        post("/internal/batch-window/open", holder).andExpect(status().isOk());
        try (Connection launch = DB.adminDataSource().getConnection()) {
            holdLaunchLock(launch, "Shared");
            try {
                post("/internal/batch-window/close", holder).andExpect(status().isServiceUnavailable())
                        .andExpect(header().string("Retry-After", "2"))
                        .andExpect(jsonPath("$.code").value("LAUNCH_IN_PROGRESS"))
                        .andExpect(jsonPath("$.message").value("A job launch for this holder is in progress. Try again later."));
            } finally {
                releaseLaunchLock(launch);
            }
        }
        try (Connection close = DB.adminDataSource().getConnection()) {
            holdLaunchLock(close, "Exclusive");
            try {
                launch("probeWindowJob", params).andExpect(status().isServiceUnavailable())
                        .andExpect(header().string("Retry-After", "2"))
                        .andExpect(jsonPath("$.code").value("WINDOW_CLOSE_IN_PROGRESS"))
                        .andExpect(jsonPath("$.message").value("The batch window of this holder is closing. Try again later."));
            } finally {
                releaseLaunchLock(close);
            }
        }
        assertThat(DB.admin().sql("SELECT COUNT(*) FROM batch.BATCH_JOB_EXECUTION_PARAMS WHERE PARAMETER_NAME = 'runKey' "
                + "AND PARAMETER_VALUE = :key").param("key", windowKey + "/probe").query(Integer.class).single()).isZero();
        post("/internal/batch-window/close", holder).andExpect(status().isOk());
    }

    private static int windows(long cycleId) {
        return DB.admin().sql("SELECT COUNT(*) FROM ops.batch_window WHERE cycle_id = :id").param("id", cycleId)
                .query(Integer.class).single();
    }

    private static boolean windowOpen(String column, Object holder) {
        return DB.admin().sql("SELECT COUNT(*) FROM ops.batch_window WHERE closed_at IS NULL AND " + column + " = :h")
                .param("h", holder).query(Integer.class).single() > 0;
    }

    private static int instances(String jobName) {
        return DB.admin().sql("SELECT COUNT(*) FROM batch.BATCH_JOB_INSTANCE WHERE JOB_NAME = :name")
                .param("name", jobName).query(Integer.class).single();
    }

    @Test
    void theInterestJobIsRefusedForACycleWithoutInterest() throws Exception {
        long noInterest = cycle("2200-08-01", "never");
        post("/internal/batch-window/open", "{\"cycleId\":" + noInterest + "}").andExpect(status().isOk());
        int before = instances("probeInterestJob");
        launch("probeInterestJob", "{\"cycleId\":" + noInterest + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_JOB_PARAMETERS")).andExpect(jsonPath("$.field").value("cycleId"));
        assertThat(instances("probeInterestJob")).as("no job instance").isEqualTo(before);
        post("/internal/batch-window/close", "{\"cycleId\":" + noInterest + ",\"complete\":false}").andExpect(status().isOk());

        long withInterest = cycle("2200-08-02", "always");
        post("/internal/batch-window/open", "{\"cycleId\":" + withInterest + "}").andExpect(status().isOk());
        long id = number(launch("probeInterestJob", "{\"cycleId\":" + withInterest + "}").andExpect(status().isAccepted()),
                "$.executionId");
        assertThat(parameter(id, "interestParm")).isEqualTo("2200080200");
        open(id, 2);
        awaitPoll(id, "the job completes", at("$.executionStatus", "COMPLETED"));
        post("/internal/batch-window/close", "{\"cycleId\":" + withInterest + ",\"complete\":true}").andExpect(status().isOk());
    }

    @Test
    void aCycleCloseMustSayWhetherItCompletesAndARunKeyCloseCannotComplete() throws Exception {
        long cycle = cycle("2200-08-10", "never");
        post("/internal/batch-window/open", "{\"cycleId\":" + cycle + "}").andExpect(status().isOk());
        post("/internal/batch-window/close", "{\"cycleId\":" + cycle + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andExpect(jsonPath("$.field").value("complete"));
        post("/internal/batch-window/close", "{\"cycleId\":" + cycle + ",\"complete\":null}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("complete"));
        assertThat(windowOpen("cycle_id", cycle)).as("the window stays open").isTrue();
        post("/internal/cycles", "{\"businessDate\":\"2200-08-10\",\"interest\":\"never\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        post("/internal/batch-window/close", "{\"cycleId\":" + cycle + ",\"complete\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.cycle.status").value("OPEN"));

        String key = "carddemo_test/complete__" + UUID.randomUUID();
        post("/internal/batch-window/open", "{\"windowRunKey\":\"" + key + "\"}").andExpect(status().isOk());
        post("/internal/batch-window/close", "{\"windowRunKey\":\"" + key + "\",\"complete\":true}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("complete"));
        assertThat(windowOpen("run_key", key)).isTrue();
        post("/internal/batch-window/close", "{\"windowRunKey\":\"" + key + "\",\"complete\":false}")
                .andExpect(status().isOk());
        post("/internal/batch-window/open", "{\"windowRunKey\":\"" + key + "\"}").andExpect(status().isOk());
        post("/internal/batch-window/close", "{\"windowRunKey\":\"" + key + "\"}").andExpect(status().isOk());
    }

    @Test
    void aRunningPurgeNeitherWaitsForACloseNorBlocksIt() throws Exception {
        long cycle = cycle("2200-08-20", "always");
        String holder = "{\"cycleId\":" + cycle + "}";
        post("/internal/batch-window/open", holder).andExpect(status().isOk());
        long purge;
        try (Connection close = DB.adminDataSource().getConnection()) {
            holdLaunchLock(close, "Exclusive");
            try {
                purge = number(launch("probePurgeJob", holder).andExpect(status().isAccepted()), "$.executionId");
            } finally {
                releaseLaunchLock(close);
            }
        }
        long interest = number(launch("probeInterestJob", holder).andExpect(status().isAccepted()), "$.executionId");
        post("/internal/batch-window/close", "{\"cycleId\":" + cycle + ",\"complete\":true}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EXECUTIONS_RUNNING"))
                .andExpect(jsonPath("$.executionIds.length()").value(1))
                .andExpect(jsonPath("$.executionIds[0]").value(interest));
        open(interest, 2);
        awaitPoll(interest, "the window job completes", at("$.executionStatus", "COMPLETED"));
        assertThat(executionStatus(purge)).isEqualTo("STARTED");
        post("/internal/batch-window/close", "{\"cycleId\":" + cycle + ",\"complete\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.cycle.status").value("COMPLETED"));
        open(purge, 2);
        awaitPoll(purge, "the purge completes", at("$.executionStatus", "COMPLETED"));
    }

    @Test
    void aCompletedCycleGetsNoWindow() throws Exception {
        long cycle = cycle("2200-08-30", "never");
        String holder = "{\"cycleId\":" + cycle + "}";
        post("/internal/batch-window/open", holder).andExpect(status().isOk());
        post("/internal/batch-window/close", "{\"cycleId\":" + cycle + ",\"complete\":true}").andExpect(status().isOk());
        int before = windows(cycle);
        post("/internal/batch-window/open", holder).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CYCLE_COMPLETED")).andExpect(jsonPath("$.cycleId").value(cycle))
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.attempt").value(1))
                .andExpect(header().doesNotExist("Retry-After"));
        assertThat(windows(cycle)).as("no window row").isEqualTo(before);
        assertThat(windowOpen("cycle_id", cycle)).isFalse();
    }

    @Test
    void unknownJobsAndExecutionsAreNotFound() throws Exception {
        launch("noSuchJob", "{}").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
        launch("noSuchJob", "{\"cycleId\":1}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));
        call(get("/internal/jobs/executions/987654321")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXECUTION_NOT_FOUND"));
        call(get("/internal/jobs/executions/abc")).andExpect(status().isNotFound());
        call(get("/internal/nothing")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void aLostOwnerIsRecoveredByALaunchAndTheRestartKeepsTheFirstParameters() throws Exception {
        String windowKey = "carddemo_test/manual__" + UUID.randomUUID();
        String holder = "{\"windowRunKey\":\"" + windowKey + "\"}";
        String params = "{\"runKey\":\"%s/probe\",\"businessDate\":\"2022-07-06\",\"windowRunKey\":\"%s\"}"
                .formatted(windowKey, windowKey);
        post("/internal/batch-window/open", holder).andExpect(status().isOk());
        long first = number(launch("probeWindowJob", params).andExpect(status().isAccepted()), "$.executionId");

        launch("probeWindowJob", params).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_EXECUTION_RUNNING")).andExpect(jsonPath("$.executionId").value(first));
        post("/internal/batch-window/close", holder).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXECUTIONS_RUNNING")).andExpect(jsonPath("$.executionIds[0]").value(first));
        poll(first).andExpect(jsonPath("$.lease.state").value("LIVE")).andExpect(jsonPath("$.lease.recoverable").value(false));

        // The poll shows the lease expire, then become recoverable, and changes nothing.
        keeper.suspendRenewals(true);
        awaitPoll(first, "the lease expires", at("$.lease.state", "EXPIRED"));
        awaitPoll(first, "the lease is recoverable", at("$.lease.recoverable", true));
        assertThat(executionStatus(first)).isEqualTo("STARTED");
        post("/internal/batch-window/close", holder).andExpect(status().isOk());
        launch("probeWindowJob", params).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_WINDOW_REQUIRED"));
        assertThat(executionStatus(first)).as("no window, no recovery").isEqualTo("STARTED");
        post("/internal/batch-window/open", holder).andExpect(status().isOk());

        // The launch recovers it; a changed non-identifying parameter is refused for the restart.
        launch("probeWindowJob", params.replace("2022-07-06", "2022-07-07")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_JOB_PARAMETERS"))
                .andExpect(jsonPath("$.field").value("businessDate"));
        poll(first).andExpect(jsonPath("$.executionStatus").value("FAILED")).andExpect(jsonPath("$.exitStatus").value("FAILED"))
                .andExpect(jsonPath("$.lease.state").value("REVOKED")).andExpect(jsonPath("$.lease.recoverable").value(false));
        keeper.suspendRenewals(false);

        // A setting changed since the first launch is not a changed request: the restart keeps the stored value.
        environment.getPropertySources().addFirst(new MapPropertySource("changedSettings",
                Map.of("carddemo.batch.pauth.expiry-days", "9")));
        ResultActions restarted = launch("probeWindowJob", params).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.restart").value(true));
        long second = number(restarted, "$.executionId");
        assertThat(second).isNotEqualTo(first);
        assertThat(parameter(first, "expiryDays")).isEqualTo("5");
        assertThat(parameter(second, "expiryDays")).isEqualTo("5");
        open(second, 2);
        awaitPoll(second, "the restart completes", at("$.executionStatus", "COMPLETED"));
        open(first, 2);
        Thread.sleep(1000);
        assertThat(executionStatus(first)).as("the lost owner overwrote nothing").isEqualTo("FAILED");
        assertThat(keeper.isLost(first)).isTrue();
        launch("probeWindowJob", params).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JOB_INSTANCE_COMPLETE")).andExpect(jsonPath("$.executionId").value(second));
        post("/internal/batch-window/close", holder).andExpect(status().isOk());
    }
}
