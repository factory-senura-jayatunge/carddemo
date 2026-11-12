package org.carddemo.batch.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.carddemo.batch.execution.ExecutionLeases;
import org.carddemo.batch.execution.LeaseOwner;
import org.carddemo.batch.load.DataLoadStarts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

/**
 * {@code carddemo.batch.load.execution-id-file}: the one-shot load names the execution it
 * started, so a caller never looks up "the last load" (load report contract section 1).
 */
@ExtendWith(OutputCaptureExtension.class)
class ExecutionIdFileTest {

    @TempDir
    Path dir;

    private final AtomicInteger launched = new AtomicInteger();
    private final DataLoadStarts starts = new DataLoadStarts();
    /** The execution the fake job repository holds for the run's runKey, if any. */
    private final AtomicReference<JobExecution> stored = new AtomicReference<>();
    /** More executions of the same instance, as a reused runKey would have. */
    private final List<JobExecution> moreOfTheInstance = new java.util.ArrayList<>();
    private final AtomicReference<RuntimeException> repositoryFailure = new AtomicReference<>();

    private BatchWindows windows() {
        return new BatchWindows(null, null, null, null, null, null) {
            @Override
            public Map<String, Object> open(WindowHolder holder) {
                return Map.of();
            }

            @Override
            public Map<String, Object> close(WindowHolder holder, Boolean complete) {
                return Map.of();
            }
        };
    }

    private JobLaunches launches(BatchStatus status) {
        return new JobLaunches(null, null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public Launched launch(String jobName, Map<String, Object> request) {
                launched.incrementAndGet();
                JobExecution execution = new JobExecution(4711L, new JobInstance(3L, jobName), new JobParameters());
                execution.setStatus(status);
                return new Launched(execution, false);
            }
        };
    }

    private JobLaunches refused() {
        return new JobLaunches(null, null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public Launched launch(String jobName, Map<String, Object> request) {
                launched.incrementAndGet();
                throw new InternalApiException(HttpStatus.CONFLICT, "WINDOW_HELD", "held", null);
            }
        };
    }

    /** Answers the runKey lookup from {@link #stored}; any other call fails. */
    private JobRepository repository() {
        return (JobRepository) Proxy.newProxyInstance(JobRepository.class.getClassLoader(),
                new Class<?>[] {JobRepository.class}, (proxy, method, args) -> {
                    if (repositoryFailure.get() != null) {
                        throw repositoryFailure.get();
                    }
                    JobExecution e = stored.get();
                    return switch (method.getName()) {
                        case "getJobInstance" -> e != null && args[0].equals("dataLoadJob")
                                && runKeyOf((JobParameters) args[1]).equals(runKeyOf(e.getJobParameters()))
                                && ((JobParameters) args[1]).getIdentifyingParameters().size() == 1
                                        ? e.getJobInstance() : null;
                        case "getJobExecutions" -> {
                            List<JobExecution> all = new java.util.ArrayList<>(List.of(e));
                            all.addAll(moreOfTheInstance);
                            yield all;
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private LoadCommand command(JobLaunches launches, String idFile) {
        return new LoadCommand(windows(), launches, repository(),
                new ExecutionLeases(null, LeaseOwner.ofThisProcess(), null), starts, LocalDate.of(2022, 7, 6), idFile);
    }

    private static Object runKeyOf(JobParameters parameters) {
        return parameters.getParameter("runKey").value();
    }

    @SuppressWarnings("unchecked")
    private static String runKey(Map<String, Object> request) {
        return (String) ((Map<String, Object>) request.get("params")).get("runKey");
    }

    private static JobExecution execution(long id, String runKey) {
        JobParameters identity = new JobParametersBuilder().addString("runKey", runKey, true).toJobParameters();
        return new JobExecution(id, new JobInstance(3L, "dataLoadJob"), identity);
    }

    /** A synchronous launch: the job starts (the listener runs), then fails after the given check. */
    private JobLaunches startsThen(long id, Path file, AtomicReference<String> seenAtStart, RuntimeException after) {
        return new JobLaunches(null, null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public Launched launch(String jobName, Map<String, Object> request) {
                launched.incrementAndGet();
                JobExecution execution = execution(id, runKey(request));
                stored.set(execution);
                starts.beforeJob(execution);
                try {
                    seenAtStart.set(Files.readString(file));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                if (after != null) {
                    throw after;
                }
                execution.setStatus(BatchStatus.COMPLETED);
                return new Launched(execution, false);
            }
        };
    }

    @Test
    void theFileIsCreatedOwnerOnlyAndNamesTheExecutionWithOneLineFeed() throws Exception {
        Path file = dir.resolve("load.id");
        LoadCommand command = command(launches(BatchStatus.COMPLETED), file.toString());

        command.run(null);

        assertThat(command.getExitCode()).isZero();
        assertThat(Files.readString(file)).isEqualTo("4711\n");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
    }

    @Test
    void aFailedJobStillNamesItsExecutionAndExitsOne() throws Exception {
        Path file = dir.resolve("load.id");
        LoadCommand command = command(launches(BatchStatus.FAILED), file.toString());

        command.run(null);

        assertThat(command.getExitCode()).isOne();
        assertThat(Files.readString(file)).isEqualTo("4711\n");
    }

    @Test
    void aRefusedLaunchLeavesTheFileEmpty(CapturedOutput output) throws Exception {
        Path file = dir.resolve("load.id");
        LoadCommand command = command(refused(), file.toString());

        command.run(null);

        assertThat(command.getExitCode()).isOne();
        assertThat(launched).hasValue(1);
        assertThat(Files.readString(file)).isEmpty();
        assertThat(output.getAll()).contains("carddemo.batch.load.execution-id-file stays empty: the runKey of this"
                + " load names no execution");
    }

    @Test
    void theIdIsInTheFileBeforeTheJobsFirstStep() throws Exception {
        Path file = dir.resolve("load.id");
        AtomicReference<String> seenAtStart = new AtomicReference<>();
        LoadCommand command = command(startsThen(4713L, file, seenAtStart, null), file.toString());

        command.run(null);

        assertThat(seenAtStart).hasValue("4713\n");
        assertThat(command.getExitCode()).isZero();
        assertThat(Files.readString(file)).isEqualTo("4713\n");
    }

    /** Spring Batch's SQL Server sequences start at 0: a fresh repository's first load is execution 0. */
    @Test
    void theFirstExecutionOfAFreshRepositoryIsWrittenAsZero() throws Exception {
        Path file = dir.resolve("load.id");
        AtomicReference<String> seenAtStart = new AtomicReference<>();
        LoadCommand command = command(startsThen(0L, file, seenAtStart, null), file.toString());

        command.run(null);

        assertThat(seenAtStart).hasValue("0\n");
        assertThat(command.getExitCode()).isZero();
        assertThat(Files.readString(file)).isEqualTo("0\n");
    }

    @Test
    void aLaunchThatFailsBeforeItsFirstExecutionStartsIsFoundByItsRunKeyEvenAsZero() throws Exception {
        Path file = dir.resolve("load.id");
        JobLaunches createdThenFailed = new JobLaunches(null, null, null, null, null, null, null, null, null, null, null,
                null) {
            @Override
            public Launched launch(String jobName, Map<String, Object> request) {
                stored.set(execution(0L, runKey(request)));
                throw new IllegalStateException("before start");
            }
        };
        LoadCommand command = command(createdThenFailed, file.toString());

        command.run(null);

        assertThat(command.getExitCode()).isOne();
        assertThat(Files.readString(file)).isEqualTo("0\n");
    }

    @Test
    void aStartForAnotherRunKeyIsNotRecorded() throws Exception {
        Path file = dir.resolve("load.id");
        JobLaunches other = new JobLaunches(null, null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public Launched launch(String jobName, Map<String, Object> request) {
                starts.beforeJob(execution(1L, "api/other-run"));
                JobExecution execution = execution(4714L, runKey(request));
                execution.setStatus(BatchStatus.COMPLETED);
                return new Launched(execution, false);
            }
        };
        LoadCommand command = command(other, file.toString());

        command.run(null);

        assertThat(Files.readString(file)).isEqualTo("4714\n");
    }

    @Test
    void aLaunchThatFailsAfterTheStartKeepsTheIdAndExitsOne() throws Exception {
        Path file = dir.resolve("load.id");
        AtomicReference<String> seenAtStart = new AtomicReference<>();
        LoadCommand command = command(startsThen(4715L, file, seenAtStart, new IllegalStateException("after start")),
                file.toString());

        command.run(null);

        assertThat(command.getExitCode()).isOne();
        assertThat(Files.readString(file)).isEqualTo("4715\n");
    }

    @Test
    void aLaunchThatFailsBeforeTheStartIsFoundByItsRunKey() throws Exception {
        Path file = dir.resolve("load.id");
        JobLaunches createdThenFailed = new JobLaunches(null, null, null, null, null, null, null, null, null, null, null,
                null) {
            @Override
            public Launched launch(String jobName, Map<String, Object> request) {
                stored.set(execution(4716L, runKey(request)));
                throw new IllegalStateException("before start");
            }
        };
        LoadCommand command = command(createdThenFailed, file.toString());

        command.run(null);

        assertThat(command.getExitCode()).isOne();
        assertThat(Files.readString(file)).isEqualTo("4716\n");
    }

    @Test
    void aLaunchThatCreatedNothingLeavesTheFileEmpty(CapturedOutput output) throws Exception {
        Path file = dir.resolve("load.id");
        stored.set(execution(4710L, "cli/host/20221106T000000.000000Z/an-earlier-load"));
        JobLaunches failed = new JobLaunches(null, null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public Launched launch(String jobName, Map<String, Object> request) {
                throw new IllegalStateException("launch failed");
            }
        };
        LoadCommand command = command(failed, file.toString());

        command.run(null);

        assertThat(command.getExitCode()).isOne();
        assertThat(Files.readString(file)).as("another load's execution is never named").isEmpty();
        assertThat(output.getAll()).contains("carddemo.batch.load.execution-id-file stays empty: the runKey of this"
                + " load names no execution").doesNotContain("an-earlier-load");
    }

    @Test
    void severalExecutionsUnderTheRunKeyLeaveTheFileEmpty(CapturedOutput output) throws Exception {
        Path file = dir.resolve("load.id");
        JobLaunches createdTwice = new JobLaunches(null, null, null, null, null, null, null, null, null, null, null,
                null) {
            @Override
            public Launched launch(String jobName, Map<String, Object> request) {
                stored.set(execution(4717L, runKey(request)));
                moreOfTheInstance.add(execution(4718L, runKey(request)));
                throw new IllegalStateException("before start");
            }
        };
        LoadCommand command = command(createdTwice, file.toString());

        command.run(null);

        assertThat(command.getExitCode()).isOne();
        assertThat(Files.readString(file)).isEmpty();
        assertThat(output.getAll()).contains("carddemo.batch.load.execution-id-file stays empty: the runKey of this"
                + " load names 2 executions");
    }

    @Test
    void whenTheLookupFailsTheFileStaysEmptyAndTheLoadSaysItCannotTell(CapturedOutput output) throws Exception {
        Path file = dir.resolve("load.id");
        JobLaunches failed = new JobLaunches(null, null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public Launched launch(String jobName, Map<String, Object> request) {
                repositoryFailure.set(new IllegalStateException("repository down"));
                throw new IllegalStateException("launch failed");
            }
        };
        LoadCommand command = command(failed, file.toString());

        command.run(null);

        assertThat(command.getExitCode()).isOne();
        assertThat(Files.readString(file)).isEmpty();
        assertThat(output.getAll()).contains("whether dataLoadJob started is unknown; "
                + "carddemo.batch.load.execution-id-file stays empty");
    }

    @Test
    void aRecorderThatCannotWriteFailsTheStart() {
        String key = "cli/host/x";
        starts.expect(key, id -> {
            throw new IOException("disk full");
        });

        assertThatThrownBy(() -> starts.beforeJob(execution(1L, key))).isInstanceOf(IllegalStateException.class)
                .hasMessage("the execution ID cannot be recorded (IOException)");
        // Recorded once: a second start under the key has no recorder.
        starts.beforeJob(execution(2L, key));
    }

    @Test
    void aSettingThatCannotBeUsedStopsTheLoadBeforeTheWindowOpens(CapturedOutput output) throws Exception {
        Path existing = Files.writeString(dir.resolve("existing.id"), "secret-content-31415");
        Map<String, String> cases = Map.of(
                "relative/load.id", "carddemo.batch.load.execution-id-file must be an absolute path",
                existing.toString(), "carddemo.batch.load.execution-id-file already exists",
                dir.resolve("missing").resolve("load.id").toString(),
                "carddemo.batch.load.execution-id-file cannot be created (NoSuchFileException)");
        for (Map.Entry<String, String> c : cases.entrySet()) {
            LoadCommand command = command(launches(BatchStatus.COMPLETED), c.getKey());

            command.run(null);

            assertThat(command.getExitCode()).as(c.getKey()).isOne();
            assertThat(output.getAll()).contains("dataLoadJob not started: " + c.getValue());
        }
        assertThat(launched).hasValue(0);
        assertThat(Files.readString(existing)).isEqualTo("secret-content-31415");
        assertThat(output.getAll()).doesNotContain("secret-content-31415");
        assertThat(Files.exists(Path.of("relative/load.id"))).isFalse();
    }

    @Test
    void withoutThePropertyNothingIsWritten() throws Exception {
        assertThat(ExecutionIdFile.create(null)).isNull();
        assertThat(ExecutionIdFile.create(" ")).isNull();
        LoadCommand command = command(launches(BatchStatus.COMPLETED), "");
        command.run(null);
        assertThat(command.getExitCode()).isZero();
        assertThat(dir).isEmptyDirectory();
        assertThatThrownBy(() -> ExecutionIdFile.create("relative")).isInstanceOf(ExecutionIdFile.Refused.class);
    }
}
