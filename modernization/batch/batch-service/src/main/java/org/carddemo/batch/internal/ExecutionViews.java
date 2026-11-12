package org.carddemo.batch.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.carddemo.batch.execution.ExecutionLeases;
import org.carddemo.batch.execution.LeaseView;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;

/**
 * GET /internal/jobs/executions/{executionId} (contract section 3). It only reads: it never
 * recovers and never writes. The lease is reported as the database clock sees it.
 */
public class ExecutionViews {

    private final JobRepository repository;
    private final ExecutionLeases leases;

    public ExecutionViews(JobRepository repository, ExecutionLeases leases) {
        this.repository = repository;
        this.leases = leases;
    }

    public Map<String, Object> view(long executionId) {
        JobExecution execution = find(executionId).orElseThrow(() -> new InternalApiException(HttpStatus.NOT_FOUND,
                "EXECUTION_NOT_FOUND", "No job execution has this ID.", null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", execution.getId());
        body.put("jobName", execution.getJobInstance().getJobName());
        body.put("cycleId", parameter(execution, "cycleId"));
        body.put("runId", parameter(execution, "runId"));
        body.put("runKey", parameter(execution, "runKey"));
        body.put("executionStatus", execution.getStatus().name());
        body.put("exitStatus", ExecutionOutputs.exitStatus(execution));
        body.put("rejectCount", ExecutionOutputs.rejectCount(execution));
        body.put("startTime", Times.utc(execution.getStartTime()));
        body.put("endTime", Times.utc(execution.getEndTime()));
        body.put("lease", lease(execution));
        body.put("outputs", ExecutionOutputs.outputs(execution));
        List<Map<String, Object>> steps = execution.getStepExecutions().stream()
                .sorted((a, b) -> Long.compare(a.getId(), b.getId())).map(ExecutionViews::step).toList();
        body.put("steps", steps);
        return body;
    }

    private Optional<JobExecution> find(long executionId) {
        // Spring Batch 6 throws for an unknown ID instead of returning null.
        try {
            return Optional.ofNullable(repository.getJobExecution(executionId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private Map<String, Object> lease(JobExecution execution) {
        boolean running = execution.getStatus().isRunning();
        Optional<LeaseView> view = leases.view(execution.getId());
        Map<String, Object> lease = new LinkedHashMap<>();
        lease.put("state", view.map(v -> v.state().name()).orElse(LeaseView.State.NONE.name()));
        lease.put("renewedAt", view.map(v -> Times.utc(v.renewedAt())).orElse(null));
        lease.put("expiresAt", view.map(v -> Times.utc(v.expiresAt())).orElse(null));
        lease.put("recoverableAt", view.map(v -> Times.utc(v.recoverableAt())).orElse(null));
        // A terminal execution is never recovered, whatever its lease row says.
        lease.put("recoverable", running && leases.recoverable(execution.getId()));
        lease.put("dbTime", Times.utc(view.map(LeaseView::dbTime).orElseGet(leases::databaseTime)));
        return lease;
    }

    private static Object parameter(JobExecution execution, String name) {
        JobParameter<?> parameter = execution.getJobParameters().getParameter(name);
        return parameter == null ? null : parameter.value();
    }

    private static Map<String, Object> step(StepExecution step) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", step.getStepName());
        body.put("status", step.getStatus().name());
        body.put("readCount", step.getReadCount());
        body.put("writeCount", step.getWriteCount());
        body.put("skipCount", step.getSkipCount());
        return body;
    }
}
