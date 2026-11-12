package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;

/** Every cycle job execution must come after the load the export is checked against. */
class CycleJobsTest {

    private static final LocalDate DATE = LocalDate.of(2022, 7, 6);
    private static final long LOAD = 100;

    private record Ran(String jobName, long attempt, long... executionIds) {
    }

    /** A job repository holding only the given executions; any other call fails. */
    private static JobRepository repository(Ran... runs) {
        return (JobRepository) Proxy.newProxyInstance(JobRepository.class.getClassLoader(),
                new Class<?>[] {JobRepository.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getJobInstance" -> {
                        String jobName = (String) args[0];
                        JobParameters p = (JobParameters) args[1];
                        for (Ran r : runs) {
                            if (r.jobName().equals(jobName) && p.getParameter("attempt").value().equals(r.attempt())
                                    && p.getParameter("cycleId").value().equals(5L)
                                    && p.getParameter("businessDate").value().equals(DATE)) {
                                yield new JobInstance(r.attempt() * 1000 + r.jobName().length(), jobName);
                            }
                        }
                        yield null;
                    }
                    case "getJobExecutions" -> {
                        JobInstance instance = (JobInstance) args[0];
                        List<JobExecution> executions = new ArrayList<>();
                        for (Ran r : runs) {
                            if (r.attempt() * 1000 + r.jobName().length() == instance.getInstanceId()
                                    && r.jobName().equals(instance.getJobName())) {
                                for (long id : r.executionIds()) {
                                    executions.add(new JobExecution(id, instance, new JobParameters()));
                                }
                            }
                        }
                        yield executions;
                    }
                    case "toString" -> "fake job repository " + Map.of();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static CycleJobs attempt(int attempt, Ran... runs) {
        return new CycleJobs(repository(runs), 5, DATE, attempt);
    }

    @Test
    void anEarlierAttemptsExecutionBeforeTheLoadIsRefused() {
        CycleJobs jobs = attempt(2, new Ran("postDailyTransactionsJob", 1, 42), new Ran("postDailyTransactionsJob", 2, 101));

        assertThatThrownBy(() -> jobs.requireAfterLoad(List.of("postDailyTransactionsJob"), LOAD))
                .isInstanceOf(ExportException.class)
                .hasMessage("postDailyTransactionsJob execution 42 of cycle 5 attempt 1 started before dataLoadJob"
                        + " execution 100");
    }

    @Test
    void anyExecutionOfTheAttemptBeforeTheLoadIsRefused() {
        CycleJobs jobs = attempt(1, new Ran("interestJob", 1, 99, 101));

        assertThatThrownBy(() -> jobs.requireAfterLoad(List.of("postDailyTransactionsJob", "interestJob"), LOAD))
                .hasMessage("interestJob execution 99 of cycle 5 attempt 1 started before dataLoadJob execution 100");
    }

    @Test
    void aJobThatNeverRanAndExecutionsAfterTheLoadPass() {
        CycleJobs jobs = attempt(2, new Ran("postDailyTransactionsJob", 1, LOAD + 1),
                new Ran("postDailyTransactionsJob", 2, LOAD + 2, LOAD + 3));

        assertThatCode(() -> jobs.requireAfterLoad(List.of("postDailyTransactionsJob", "interestJob"), LOAD))
                .doesNotThrowAnyException();
    }

    /** A fresh repository's first job is the load, execution 0; every cycle job comes after it. */
    @Test
    void aCycleAfterTheLoadOfAFreshRepositoryPasses() {
        CycleJobs jobs = attempt(1, new Ran("postDailyTransactionsJob", 1, 1, 2), new Ran("interestJob", 1, 3));

        assertThatCode(() -> jobs.requireAfterLoad(List.of("postDailyTransactionsJob", "interestJob"), 0))
                .doesNotThrowAnyException();
    }

    @Test
    void aLaterAttemptIsNotLookedAt() {
        CycleJobs jobs = attempt(1, new Ran("interestJob", 1, LOAD + 1), new Ran("interestJob", 2, 1));

        assertThatCode(() -> jobs.requireAfterLoad(List.of("interestJob"), LOAD)).doesNotThrowAnyException();
    }
}
