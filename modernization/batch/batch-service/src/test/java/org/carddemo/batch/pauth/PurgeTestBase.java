package org.carddemo.batch.pauth;

import java.time.LocalDate;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** purgePendingAuthorizationsJob against the module's test database, connected as batch_svc. */
abstract class PurgeTestBase {

    static final TestDatabase DB = TestDatabase.shared();
    /** One job instance per launch: cycle IDs are never reused in the JVM. */
    private static final AtomicLong CYCLES = new AtomicLong(1);

    final PauthFixtures pauth = new PauthFixtures(DB);

    @Autowired
    JobOperator jobOperator;

    @Autowired
    Job purgePendingAuthorizationsJob;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.BATCH);
    }

    @BeforeEach
    void emptyTables() {
        DB.fixtures().deleteAll();
    }

    static JobParameters parameters(LocalDate businessDate, long expiryDays, long commitInterval) {
        return new JobParametersBuilder()
                .addLong(PurgeParameters.CYCLE_ID, CYCLES.getAndIncrement())
                .addLocalDate(PurgeParameters.BUSINESS_DATE, businessDate)
                .addLong(PurgeParameters.ATTEMPT, 1L)
                .addLong(PurgeParameters.EXPIRY_DAYS, expiryDays, false)
                .addLong(PurgeParameters.COMMIT_INTERVAL, commitInterval, false)
                .toJobParameters();
    }

    JobExecution purge(LocalDate businessDate, long expiryDays, long commitInterval) throws Exception {
        return jobOperator.start(purgePendingAuthorizationsJob, parameters(businessDate, expiryDays, commitInterval));
    }

    static StepExecution step(JobExecution execution, String name) {
        return execution.getStepExecutions().stream().filter(s -> s.getStepName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no step " + name));
    }

    static ExecutionContext totals(JobExecution execution) {
        return step(execution, PurgePendingAuthorizationsJobConfiguration.PURGE_STEP).getExecutionContext();
    }

    /** Every failure with its causes: a chunk step wraps the writer's exception. */
    static String failures(JobExecution execution) {
        return execution.getAllFailureExceptions().stream()
                .map(e -> Stream.iterate(e, Objects::nonNull, Throwable::getCause).map(Throwable::toString)
                        .collect(Collectors.joining(" <- ")))
                .collect(Collectors.joining(" | "));
    }
}
