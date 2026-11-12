package org.carddemo.batch.internal;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * What a job reports through the poll and the job-instance answers (contract sections 2.2 and 3).
 * A job puts its reject count under {@link #REJECT_COUNT} and each output under
 * {@link #OUTPUT_PREFIX} + name in its job execution context; outputs are shown once it completed.
 */
public final class ExecutionOutputs {

    public static final String REJECT_COUNT = "carddemo.rejectCount";
    public static final String OUTPUT_PREFIX = "carddemo.output.";
    /** The exit code of a job that completed and rejected records (the legacy return code 4). */
    public static final String COMPLETED_WITH_REJECTS = "COMPLETED_WITH_REJECTS";

    private ExecutionOutputs() {
    }

    static long rejectCount(JobExecution execution) {
        Object value = execution.getExecutionContext().get(REJECT_COUNT);
        return value instanceof Number n ? n.longValue() : 0;
    }

    static Map<String, Object> outputs(JobExecution execution) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            return outputs;
        }
        ExecutionContext context = execution.getExecutionContext();
        context.entrySet().stream().filter(e -> e.getKey().startsWith(OUTPUT_PREFIX))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> outputs.put(e.getKey().substring(OUTPUT_PREFIX.length()), e.getValue()));
        return outputs;
    }

    static boolean terminal(BatchStatus status) {
        return status == BatchStatus.COMPLETED || status == BatchStatus.FAILED || status == BatchStatus.STOPPED
                || status == BatchStatus.ABANDONED;
    }

    /** COMPLETED, COMPLETED_WITH_REJECTS or FAILED once terminal, otherwise EXECUTING or UNKNOWN. */
    static String exitStatus(JobExecution execution) {
        String code = execution.getExitStatus().getExitCode();
        if (terminal(execution.getStatus())) {
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                return ExitStatus.FAILED.getExitCode();
            }
            return COMPLETED_WITH_REJECTS.equals(code) ? COMPLETED_WITH_REJECTS : ExitStatus.COMPLETED.getExitCode();
        }
        return ExitStatus.EXECUTING.getExitCode().equals(code) ? code : ExitStatus.UNKNOWN.getExitCode();
    }

    /** The members every job-instance answer has (section 2.2), without restart. */
    static Map<String, Object> answer(JobExecution execution) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", execution.getId());
        body.put("jobName", execution.getJobInstance().getJobName());
        body.put("executionStatus", execution.getStatus().name());
        body.put("exitStatus", exitStatus(execution));
        body.put("rejectCount", rejectCount(execution));
        return body;
    }
}
