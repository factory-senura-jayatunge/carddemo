package org.carddemo.batch.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
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

import org.carddemo.batch.internal.JobCatalog.JobSpec;
import org.carddemo.batch.internal.JobCatalog.Kind;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Launches that reach the job repository at the same moment, for one job instance and for
 * different ones, as Airflow's parallel tasks and retries send them. A probe job without a window
 * waits at its run key's gate, so its execution is still running while the other launches land.
 */
// Each gated probe holds its step's connection while it waits, besides the launches' own.
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.batch.async-launch=true",
        "spring.datasource.hikari.maximum-pool-size=40"})
class ConcurrentLaunchTest {

    static final TestDatabase DB = TestDatabase.shared();
    static final String JOB = "probeConcurrentJob";
    static final int THREADS = 8;
    static final int ROUNDS = 5;
    static final Map<String, CountDownLatch> GATES = new ConcurrentHashMap<>();
    static final Set<String> FAIL_ONCE = ConcurrentHashMap.newKeySet();
    /** Set when a test ends, so a probe that reaches its gate after the gates were opened does not wait. */
    static volatile boolean open;

    @Autowired
    JobLaunches launches;

    @Autowired
    JobRepository repository;

    final List<Long> started = new ArrayList<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.BATCH);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Probe {

        @Bean
        Job probeConcurrentJob(JobRepository jobRepository, PlatformTransactionManager tx) {
            return new JobBuilder(JOB, jobRepository).start(new StepBuilder("probe", jobRepository)
                    .tasklet((contribution, chunk) -> {
                        String key = chunk.getStepContext().getStepExecution().getJobParameters().getString("runKey");
                        if (FAIL_ONCE.remove(key)) {
                            throw new IllegalStateException("probe fails its first execution");
                        }
                        CountDownLatch gate = GATES.computeIfAbsent(key, k -> new CountDownLatch(1));
                        if (!open && !gate.await(60, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("gate not opened");
                        }
                        return RepeatStatus.FINISHED;
                    }, tx).build()).build();
        }

        @Bean
        @Primary
        JobCatalog probeCatalog() {
            List<JobSpec> specs = new ArrayList<>(JobCatalog.contract());
            specs.add(new JobSpec(JOB, Kind.RUN_KEYED, false, Set.of()));
            return new JobCatalog(specs);
        }
    }

    @BeforeEach
    void closeGates() {
        open = false;
    }

    @AfterEach
    void openGatesAndWait() throws InterruptedException {
        open = true;
        GATES.values().forEach(CountDownLatch::countDown);
        Instant deadline = Instant.now().plusSeconds(60);
        for (long id : started) {
            while (repository.getJobExecution(id).getStatus().isRunning() && Instant.now().isBefore(deadline)) {
                Thread.sleep(50);
            }
        }
        GATES.clear();
        FAIL_ONCE.clear();
    }

    @Test
    void simultaneousLaunchesOfOneInstanceStartOneExecutionAndAnswerTheOthersFromIt() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String key = "test/" + UUID.randomUUID();
            List<Object> answers = simultaneously(i -> key);

            assertThat(answers).filteredOn(a -> a instanceof JobLaunches.Launched).hasSize(1);
            assertThat(answers).filteredOn(a -> !(a instanceof JobLaunches.Launched))
                    .allSatisfy(a -> assertThat(a).isEqualTo("409 JOB_EXECUTION_RUNNING"));
            assertThat(executions(key)).isEqualTo(1);
        }
    }

    @Test
    void simultaneousLaunchesOfDifferentInstancesAllStart() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String prefix = "test/" + UUID.randomUUID() + "/";
            List<Object> answers = simultaneously(i -> prefix + i);

            assertThat(answers).allSatisfy(a -> assertThat(a).isInstanceOf(JobLaunches.Launched.class));
            for (int i = 0; i < THREADS; i++) {
                assertThat(executions(prefix + i)).isEqualTo(1);
            }
        }
    }

    @Test
    void simultaneousRestartsOfAFailedInstanceStartOneExecution() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String key = "test/" + UUID.randomUUID();
            FAIL_ONCE.add(key);
            JobExecution failed = launches.launch(JOB, request(key)).execution();
            started.add(failed.getId());
            awaitEnd(failed.getId());
            assertThat(repository.getJobExecution(failed.getId()).getStatus()).isEqualTo(BatchStatus.FAILED);

            List<Object> answers = simultaneously(i -> key);

            assertThat(answers).filteredOn(a -> a instanceof JobLaunches.Launched l && l.restart()).hasSize(1);
            assertThat(answers).filteredOn(a -> !(a instanceof JobLaunches.Launched))
                    .allSatisfy(a -> assertThat(a).isEqualTo("409 JOB_EXECUTION_RUNNING"));
            assertThat(executions(key)).isEqualTo(2);
        }
    }

    /** Every thread launches the run key it is given at the same moment; a Launched, or "status code", or the failure. */
    private List<Object> simultaneously(java.util.function.IntFunction<String> runKey) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                String key = runKey.apply(i);
                futures.add(pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    try {
                        JobLaunches.Launched launched = launches.launch(JOB, request(key));
                        synchronized (started) {
                            started.add(launched.execution().getId());
                        }
                        return launched;
                    } catch (InternalApiException e) {
                        return e.status().value() + " " + e.code();
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            List<Object> answers = new ArrayList<>();
            for (Future<Object> f : futures) {
                answers.add(f.get(60, TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<String, Object> request(String runKey) {
        return Map.of("params", Map.of("runKey", runKey, "businessDate", "2022-07-06"));
    }

    private void awaitEnd(long id) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (repository.getJobExecution(id).getStatus().isRunning() && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
        }
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
