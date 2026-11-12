package org.carddemo.batch.load;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.listener.JobExecutionListener;

/**
 * Hands the ID of a {@code dataLoadJob} execution to whoever launched it under a given
 * {@code runKey}, as soon as the execution exists and before its first step: the one-shot load
 * launches synchronously, so the launch returns only when the job has ended, and a load killed
 * mid-run would otherwise leave its execution-id file empty. A recorder that fails fails the
 * execution before it changes anything.
 */
public final class DataLoadStarts implements JobExecutionListener {

    /** Receives the execution ID once. */
    @FunctionalInterface
    public interface Recorder {
        void started(long executionId) throws IOException;
    }

    private final Map<String, Recorder> expected = new ConcurrentHashMap<>();

    /** The next execution started with this {@code runKey} goes to {@code recorder}. */
    public void expect(String runKey, Recorder recorder) {
        expected.put(runKey, recorder);
    }

    public void forget(String runKey) {
        expected.remove(runKey);
    }

    @Override
    public void beforeJob(JobExecution execution) {
        JobParameter<?> runKey = execution.getJobParameters().getParameter("runKey");
        if (runKey == null || !(runKey.value() instanceof String key)) {
            return;
        }
        Recorder recorder = expected.remove(key);
        if (recorder == null) {
            return;
        }
        try {
            recorder.started(execution.getId());
        } catch (IOException e) {
            throw new IllegalStateException("the execution ID cannot be recorded (" + e.getClass().getSimpleName() + ")");
        }
    }
}
