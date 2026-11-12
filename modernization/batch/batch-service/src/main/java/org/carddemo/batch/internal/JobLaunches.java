package org.carddemo.batch.internal;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

import org.carddemo.batch.execution.ExecutionLeases;
import org.carddemo.batch.execution.ExecutionRecovery;
import org.carddemo.batch.internal.JobCatalog.Extra;
import org.carddemo.batch.internal.JobCatalog.JobSpec;
import org.carddemo.batch.internal.JobCatalog.Kind;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * POST /internal/jobs/{jobName}/executions (contract sections 2 and 4.4). batch-service builds the
 * job parameters; a launch starts a new instance, restarts a failed or stopped one with its stored
 * parameters, recovers an execution whose owner is lost, or answers from the instance's state.
 */
public class JobLaunches {

    /** A launch's answer: 202 with restart, or the job-instance 409 bodies thrown as problems. */
    public record Launched(JobExecution execution, boolean restart) {

        Map<String, Object> body() {
            Map<String, Object> body = ExecutionOutputs.answer(execution);
            body.put("restart", restart);
            return body;
        }
    }

    /**
     * {@code requested} names the non-identifying parameters the request sent, as opposed to
     * configuration; {@code cycle} is the cycle and attempt a cycle job's launch re-reads under the
     * launch lock, null for the purge and for the other kinds.
     */
    private record Prepared(JobParameters parameters, WindowHolder holder, Set<String> requested,
            CycleAttempt cycle) { }

    private record CycleAttempt(long cycleId, int attempt) { }

    static final String EXPIRY_DAYS = "carddemo.batch.pauth.expiry-days";
    static final String COMMIT_INTERVAL = "carddemo.batch.pauth.commit-interval";

    private final JobCatalog catalog;
    private final Map<String, Job> jobs;
    private final JobOperator operator;
    private final JobRepository repository;
    private final ExecutionLeases leases;
    private final ExecutionRecovery recovery;
    private final BatchWindows windows;
    private final Cycles cycles;
    private final JdbcClient db;
    private final DataSource dataSource;
    private final Environment environment;
    private final InternalApiProperties properties;

    public JobLaunches(JobCatalog catalog, Map<String, Job> jobs, JobOperator operator, JobRepository repository,
            ExecutionLeases leases, ExecutionRecovery recovery, BatchWindows windows, Cycles cycles, JdbcClient db,
            DataSource dataSource, Environment environment, InternalApiProperties properties) {
        this.catalog = catalog;
        this.jobs = jobs;
        this.operator = operator;
        this.repository = repository;
        this.leases = leases;
        this.recovery = recovery;
        this.windows = windows;
        this.cycles = cycles;
        this.db = db;
        this.dataSource = dataSource;
        this.environment = environment;
        this.properties = properties;
    }

    public Launched launch(String jobName, Map<String, Object> request) {
        JobSpec spec = catalog.find(jobName).filter(s -> jobs.containsKey(s.name()))
                .orElseThrow(() -> new InternalApiException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND",
                        "batch-service has no job of this name.", null));
        Job job = jobs.get(jobName);
        Object params = request == null ? Map.of() : request.getOrDefault("params", Map.of());
        if (!(params instanceof Map<?, ?> map)) {
            throw InternalApiException.invalidParameter("params", "params must be an object.");
        }
        @SuppressWarnings("unchecked")
        Prepared prepared = prepare(spec, (Map<String, Object>) map);
        if (prepared.holder() == null && prepared.cycle() == null) {
            // No window to check and no cycle to re-read, so nothing a close or a rerun's restore could race with.
            return decideOnce(spec, job, prepared);
        }
        return AppLocks.sharedForSession(dataSource, AppLocks.LAUNCH, properties.launchLockTimeout(),
                () -> decideOnce(spec, job, prepared))
                .orElseThrow(() -> new InternalApiException(HttpStatus.SERVICE_UNAVAILABLE, "WINDOW_CLOSE_IN_PROGRESS",
                        "The batch window of this holder is closing. Try again later.", null)
                        .header("Retry-After", properties.launchLockRetryAfter()));
    }

    private Launched decideOnce(JobSpec spec, Job job, Prepared prepared) {
        try {
            return decide(spec, job, prepared);
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException e) {
            // Another launch of the same instance created it or its execution first; answer from that.
            return decide(spec, job, prepared);
        }
    }

    private Prepared prepare(JobSpec spec, Map<String, Object> params) {
        RequestValues values = RequestValues.forJobParameters();
        Set<String> allowed = new HashSet<>(switch (spec.kind()) {
            case CYCLE -> Set.of("cycleId", "businessDate");
            case REPORT -> Set.of("runId");
            case RUN_KEYED -> Set.of("runKey", "businessDate");
        });
        if (spec.kind() == Kind.RUN_KEYED && spec.needsWindow()) {
            allowed.add("windowRunKey");
        }
        if (spec.extras().contains(Extra.EXPORT_ID)) {
            allowed.add("exportId");
        }
        for (String name : params.keySet()) {
            if (!allowed.contains(name)) {
                throw values.error(name, name + " is not a parameter of " + spec.name() + ".");
            }
        }
        JobParametersBuilder builder = new JobParametersBuilder();
        Set<String> requested = new HashSet<>();
        WindowHolder holder = null;
        CycleAttempt checked = null;
        switch (spec.kind()) {
            case CYCLE -> {
                long cycleId = values.id(params.get("cycleId"), "cycleId");
                Cycle cycle = cycles.find(cycleId)
                        .orElseThrow(() -> values.error("cycleId", "No cycle has this cycleId."));
                if (params.get("businessDate") != null
                        && !values.date(params.get("businessDate"), "businessDate").equals(cycle.businessDate())) {
                    throw values.error("businessDate", "businessDate is not the business date of the cycle.");
                }
                builder.addLong("cycleId", cycleId, true).addLocalDate("businessDate", cycle.businessDate(), true)
                        .addLong("attempt", (long) cycle.attempt(), true);
                if (spec.extras().contains(Extra.INTEREST_PARM)) {
                    if (!cycle.interest()) {
                        throw values.error("cycleId", "The cycle has no interest due.");
                    }
                    builder.addString("interestParm", cycle.interestParm(), false);
                }
                holder = WindowHolder.cycle(cycleId);
                if (spec.checksCycle()) {
                    checked = new CycleAttempt(cycleId, cycle.attempt());
                }
            }
            case REPORT -> {
                long runId = values.id(params.get("runId"), "runId");
                String failure = db.sql("SELECT COALESCE(failure_code, '') FROM batch.report_run WHERE report_run_id = :id")
                        .param("id", runId).query(String.class).optional()
                        .orElseThrow(() -> values.error("runId", "No report run has this runId."));
                if ("TRIGGER_FAILED".equals(failure)) {
                    throw values.error("runId", "The report run failed before its job was triggered.");
                }
                builder.addLong("runId", runId, true);
            }
            case RUN_KEYED -> {
                builder.addString("runKey", values.key(params.get("runKey"), "runKey"), true)
                        .addLocalDate("businessDate", values.date(params.get("businessDate"), "businessDate"), false);
                requested.add("businessDate");
                if (spec.needsWindow()) {
                    String windowRunKey = values.key(params.get("windowRunKey"), "windowRunKey");
                    builder.addString("windowRunKey", windowRunKey, false);
                    requested.add("windowRunKey");
                    holder = WindowHolder.run(windowRunKey);
                }
                if (spec.extras().contains(Extra.EXPORT_ID)) {
                    long exportId = values.id(params.get("exportId"), "exportId");
                    String status = db.sql("SELECT status FROM batch.export_run WHERE export_id = :id")
                            .param("id", exportId).query(String.class).optional()
                            .orElseThrow(() -> values.error("exportId", "No export has this exportId."));
                    if (!"COMPLETED".equals(status)) {
                        throw values.error("exportId", "The export is not completed.");
                    }
                    builder.addLong("exportId", exportId, false);
                    requested.add("exportId");
                }
            }
        }
        if (spec.extras().contains(Extra.EXPIRY_DAYS)) {
            builder.addLong("expiryDays", setting(EXPIRY_DAYS), false);
        }
        if (spec.extras().contains(Extra.COMMIT_INTERVAL)) {
            builder.addLong("commitInterval", setting(COMMIT_INTERVAL), false);
        }
        return new Prepared(builder.toJobParameters(), spec.needsWindow() ? holder : null, Set.copyOf(requested),
                checked);
    }

    private long setting(String name) {
        Long value = environment.getProperty(name, Long.class);
        if (value == null || value < 1) {
            throw new IllegalStateException(name + " must be set to a positive number");
        }
        return value;
    }

    private Launched decide(JobSpec spec, Job job, Prepared prepared) {
        JobInstance instance = repository.getJobInstance(spec.name(), prepared.parameters());
        JobExecution last = instance == null ? null : repository.getLastJobExecution(instance);
        // The cycle is re-read only where the launch would create an instance or an execution: the
        // answers that create nothing come first, so a cleared, completed task still succeeds.
        if (last == null) {
            requireCurrentCycle(prepared);
            requireWindow(prepared);
            return start(job, prepared);
        }
        if (last.getStatus().isRunning()) {
            if (!leases.recoverable(last.getId())) {
                throw instanceAnswer(HttpStatus.CONFLICT, "JOB_EXECUTION_RUNNING", "The job instance is running.", last);
            }
            requireCurrentCycle(prepared);
            requireWindow(prepared);
            ExecutionRecovery.Outcome outcome = recovery.recover(last.getId());
            if (outcome instanceof ExecutionRecovery.Outcome.Locked) {
                throw instanceAnswer(HttpStatus.CONFLICT, "JOB_EXECUTION_RUNNING", "The job instance is running.", last);
            }
            last = repository.getJobExecution(last.getId());
            if (last.getStatus().isRunning()) {
                throw instanceAnswer(HttpStatus.CONFLICT, "JOB_EXECUTION_RUNNING", "The job instance is running.", last);
            }
        }
        BatchStatus status = last.getStatus();
        if (status == BatchStatus.COMPLETED) {
            throw instanceAnswer(HttpStatus.CONFLICT, "JOB_INSTANCE_COMPLETE", "The job instance is complete.", last);
        }
        if ((status != BatchStatus.FAILED && status != BatchStatus.STOPPED) || !job.isRestartable()) {
            throw instanceAnswer(HttpStatus.CONFLICT, "JOB_INSTANCE_NOT_RESTARTABLE",
                    "The job instance cannot be restarted.", last);
        }
        // The restart runs with the stored values; only a value the request sent can differ from them.
        // A setting that changed since the first launch does not apply to this instance.
        for (JobParameter<?> parameter : prepared.parameters()) {
            if (!parameter.identifying() && prepared.requested().contains(parameter.name())) {
                JobParameter<?> stored = last.getJobParameters().getParameter(parameter.name());
                if (stored == null || !Objects.equals(stored.value(), parameter.value())) {
                    throw InternalApiException.invalidParameter(parameter.name(),
                            parameter.name() + " differs from the value the job instance was started with.");
                }
            }
        }
        requireCurrentCycle(prepared);
        requireWindow(prepared);
        try {
            return new Launched(operator.restart(last), true);
        } catch (JobRestartException e) {
            // The operator wraps the launcher's answers; another launch may have started or ended an
            // execution of the instance since it was read above, so the answer comes from the new last one.
            if (e.getCause() instanceof JobExecutionAlreadyRunningException) {
                throw instanceAnswer(HttpStatus.CONFLICT, "JOB_EXECUTION_RUNNING", "The job instance is running.",
                        lastOf(job, prepared));
            }
            if (e.getCause() instanceof JobInstanceAlreadyCompleteException) {
                throw instanceAnswer(HttpStatus.CONFLICT, "JOB_INSTANCE_COMPLETE", "The job instance is complete.",
                        lastOf(job, prepared));
            }
            throw instanceAnswer(HttpStatus.CONFLICT, "JOB_INSTANCE_NOT_RESTARTABLE",
                    "The job instance cannot be restarted.", last);
        }
    }

    private Launched start(Job job, Prepared prepared) {
        try {
            return new Launched(operator.start(job, prepared.parameters()), false);
        } catch (JobExecutionAlreadyRunningException e) {
            throw instanceAnswer(HttpStatus.CONFLICT, "JOB_EXECUTION_RUNNING", "The job instance is running.",
                    lastOf(job, prepared));
        } catch (JobInstanceAlreadyCompleteException e) {
            throw instanceAnswer(HttpStatus.CONFLICT, "JOB_INSTANCE_COMPLETE", "The job instance is complete.",
                    lastOf(job, prepared));
        } catch (JobRestartException e) {
            throw instanceAnswer(HttpStatus.CONFLICT, "JOB_INSTANCE_NOT_RESTARTABLE",
                    "The job instance cannot be restarted.", lastOf(job, prepared));
        } catch (InvalidJobParametersException e) {
            throw InternalApiException.invalidParameter(null, "The job refused its parameters.");
        }
    }

    private JobExecution lastOf(Job job, Prepared prepared) {
        return Optional.ofNullable(repository.getLastJobExecution(job.getName(), prepared.parameters()))
                .orElseThrow(() -> new IllegalStateException("no execution of the instance of " + job.getName()));
    }

    /**
     * Contract section 2.1, the cycle row under the launch lock: the cycle was read before the lock,
     * and cycle-rerun's restore, which takes the lock exclusively, may have committed since. The read
     * takes no row hint; the shared launch lock this thread holds is what orders it after a restore.
     */
    private void requireCurrentCycle(Prepared prepared) {
        CycleAttempt checked = prepared.cycle();
        if (checked == null) {
            return;
        }
        Cycle cycle = cycles.find(checked.cycleId())
                .orElseThrow(() -> InternalApiException.invalidParameter("cycleId", "No cycle has this cycleId."));
        if (cycle.completed()) {
            throw new InternalApiException(HttpStatus.CONFLICT, "CYCLE_COMPLETED", "The cycle is completed.", null)
                    .withAll(cycle.body());
        }
        if (cycle.attempt() != checked.attempt()) {
            throw new InternalApiException(HttpStatus.CONFLICT, "CYCLE_ATTEMPT_CHANGED",
                    "The cycle's attempt changed after this launch read it. Send the request again.", null)
                    .withAll(cycle.body());
        }
    }

    private void requireWindow(Prepared prepared) {
        if (prepared.holder() != null && !windows.heldBy(prepared.holder())) {
            throw new InternalApiException(HttpStatus.CONFLICT, "BATCH_WINDOW_REQUIRED",
                    "This job needs the batch window held by exactly its cycleId or windowRunKey.", null);
        }
    }

    private static InternalApiException instanceAnswer(HttpStatus status, String code, String message,
            JobExecution execution) {
        return new InternalApiException(status, code, message, null).withAll(ExecutionOutputs.answer(execution));
    }
}
