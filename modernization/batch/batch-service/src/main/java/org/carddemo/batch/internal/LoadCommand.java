package org.carddemo.batch.internal;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.carddemo.batch.execution.ExecutionLeases;
import org.carddemo.batch.execution.SafeFailures;
import org.carddemo.batch.load.DataLoadStarts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The one-shot load (make load): opens a CLI batch window, launches dataLoadJob through the same
 * rules as the internal API with runKey and windowRunKey {@code cli/<host>/<UTC timestamp>/<UUID>},
 * waits for it and closes the window. The exit code is 0 only when the job completed. With
 * {@code carddemo.batch.load.execution-id-file} it names the execution it started in that file
 * ({@link ExecutionIdFile}) as soon as the execution exists ({@link DataLoadStarts}), and, when
 * the launch fails, looks the execution up by its runKey. An empty file with a non-zero exit code
 * means that whether an execution exists is unknown.
 */
@Component
@ConditionalOnBooleanProperty(name = "spring.batch.job.enabled", matchIfMissing = true)
@ConditionalOnProperty(name = "carddemo.batch.command", havingValue = "load")
class LoadCommand implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(LoadCommand.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Duration POLL = Duration.ofMillis(500);

    private final BatchWindows windows;
    private final JobLaunches launches;
    private final JobRepository repository;
    private final ExecutionLeases leases;
    private final DataLoadStarts starts;
    private final LocalDate businessDate;
    private final String executionIdFile;
    private int exitCode = 1;

    LoadCommand(BatchWindows windows, JobLaunches launches, JobRepository repository, ExecutionLeases leases,
            DataLoadStarts starts, @Value("${carddemo.batch.business-date}") LocalDate businessDate,
            @Value("${" + ExecutionIdFile.PROPERTY + ":}") String executionIdFile) {
        this.windows = windows;
        this.launches = launches;
        this.repository = repository;
        this.leases = leases;
        this.starts = starts;
        this.businessDate = businessDate;
        this.executionIdFile = executionIdFile;
    }

    /** At most 250 characters: the fixed parts take 65, so a longer host name is cut. */
    static String runKey(String host, Instant now, UUID id) {
        String h = host.length() > 185 ? host.substring(0, 185) : host;
        return "cli/" + h + "/" + STAMP.format(now.truncatedTo(ChronoUnit.MICROS)) + "/" + id;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        ExecutionIdFile idFile;
        try {
            idFile = ExecutionIdFile.create(executionIdFile);
        } catch (ExecutionIdFile.Refused e) {
            log.error("dataLoadJob not started: {}", e.getMessage());
            return;
        }
        try {
            run(idFile);
        } finally {
            if (idFile != null) {
                try {
                    idFile.close();
                } catch (IOException e) {
                    exitCode = 1;
                    log.error("{} cannot be closed ({})", ExecutionIdFile.PROPERTY, e.getClass().getSimpleName());
                }
            }
        }
    }

    private void run(ExecutionIdFile idFile) throws InterruptedException {
        String key = runKey(leases.owner().host(), Instant.now(), UUID.randomUUID());
        WindowHolder holder = WindowHolder.run(key);
        try {
            windows.open(holder);
        } catch (InternalApiException e) {
            log.error("dataLoadJob not started: the batch window did not open ({})", e.code());
            return;
        } catch (RuntimeException e) {
            failed("dataLoadJob not started: the batch window did not open", e);
            return;
        }
        IdRecord record = idFile == null ? null : new IdRecord(idFile);
        if (record != null) {
            starts.expect(key, record::write);
        }
        try {
            JobExecution execution = launches.launch("dataLoadJob", Map.of("params",
                    Map.of("runKey", key, "businessDate", businessDate.toString(), "windowRunKey", key))).execution();
            if (record != null) {
                record.writeLogged(execution.getId());
            }
            while (execution.getStatus().isRunning()) {
                Thread.sleep(POLL.toMillis());
                execution = repository.getJobExecution(execution.getId());
            }
            log.info("dataLoadJob execution {} ended {} / {}", execution.getId(), execution.getStatus(),
                    execution.getExitStatus().getExitCode());
            exitCode = execution.getStatus() == BatchStatus.COMPLETED && (record == null || record.written()) ? 0 : 1;
        } catch (InternalApiException e) {
            exitCode = 1;
            log.error("dataLoadJob not started: {}", e.code());
            recordFromRepository(record, key);
        } catch (RuntimeException e) {
            exitCode = 1;
            failed("dataLoadJob failed", e);
            recordFromRepository(record, key);
        } finally {
            starts.forget(key);
            try {
                windows.close(holder, false);
            } catch (InternalApiException e) {
                exitCode = 1;
                log.error("the batch window of {} did not close ({}); close it with make batch-window-close", key,
                        e.code());
            } catch (RuntimeException e) {
                exitCode = 1;
                failed("the batch window of " + key + " did not close; close it with make batch-window-close", e);
            }
        }
    }

    /**
     * A launch that failed may still have created the execution. Only this invocation's generated
     * runKey identifies it, and only one execution of that instance is accepted: none means
     * nothing was created, several mean the key was reused, and either leaves the file empty.
     * Never the latest load, never a match by date.
     */
    private void recordFromRepository(IdRecord record, String key) {
        if (record == null || record.written() || record.failed()) {
            return;
        }
        try {
            JobInstance instance = repository.getJobInstance("dataLoadJob",
                    new JobParametersBuilder().addString("runKey", key, true).toJobParameters());
            List<JobExecution> executions = instance == null ? List.of() : repository.getJobExecutions(instance);
            if (executions.size() == 1) {
                record.writeLogged(executions.getFirst().getId());
            } else if (executions.isEmpty()) {
                log.error("{} stays empty: the runKey of this load names no execution", ExecutionIdFile.PROPERTY);
            } else {
                log.error("{} stays empty: the runKey of this load names {} executions", ExecutionIdFile.PROPERTY,
                        executions.size());
            }
        } catch (RuntimeException e) {
            log.error("whether dataLoadJob started is unknown; {} stays empty: {}", ExecutionIdFile.PROPERTY,
                    SafeFailures.describe(e), SafeFailures.sanitized(e));
        }
    }

    /** The file's one write, from the job's start or after the launch, whichever comes first. */
    private static final class IdRecord {
        private final ExecutionIdFile file;
        private boolean written;
        private boolean failed;

        IdRecord(ExecutionIdFile file) {
            this.file = file;
        }

        synchronized void write(long executionId) throws IOException {
            if (written || failed) {
                return;
            }
            try {
                file.write(executionId);
                written = true;
            } catch (IOException e) {
                failed = true;
                log.error("{} cannot be written ({})", ExecutionIdFile.PROPERTY, e.getClass().getSimpleName());
                throw e;
            }
        }

        void writeLogged(long executionId) {
            try {
                write(executionId);
            } catch (IOException e) {
                // Logged by write; the exit code is 1 because nothing was written.
            }
        }

        synchronized boolean written() {
            return written;
        }

        synchronized boolean failed() {
            return failed;
        }
    }

    /** Not left to Spring Boot, whose "Application run failed" log quotes the driver's message. */
    private static void failed(String what, RuntimeException e) {
        log.error("{}: {}", what, SafeFailures.describe(e), SafeFailures.sanitized(e));
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
