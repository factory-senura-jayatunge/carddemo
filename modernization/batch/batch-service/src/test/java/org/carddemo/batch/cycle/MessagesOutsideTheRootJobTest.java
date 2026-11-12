package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.carddemo.batch.PrivateVarDir;
import org.carddemo.batch.internal.ExecutionViews;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Messages outside the root (contract section 4.10): the private CARDDEMO_VAR_DIR's name holds
 * {@link PrivateVarDir#SENTINEL}, and a step that fails on a path below it, by an output folder
 * that fails its check, by the fence's commit check, by a refused publication or by a file system
 * failure, leaves that word out of the failure exceptions, both EXIT_MESSAGE columns, the poll
 * answer and the log.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.batch.files.chunk-size=7"})
@ExtendWith(OutputCaptureExtension.class)
class MessagesOutsideTheRootJobTest extends CycleJobTestBase {

    static final String DATE = "2101-07-01";
    private static final List<String> REFERENCE_TABLES = List.of("ref.disclosure_group", "ref.tran_category",
            "ref.tran_type", "ref.us_state_zip_prefix", "ref.us_state", "ref.nanp_area_code");

    @Autowired
    DailyFiles files;

    @Autowired
    ExecutionViews views;

    @Autowired
    JobOperator jobOperator;

    @Autowired
    Job dataLoadJob;

    @AfterEach
    void noHook() {
        files.afterCopyPublished = execution -> { };
    }

    @Test
    void anArchiveFolderThatFailsItsCheckIsReportedByCodeOnly(CapturedOutput output) throws IOException {
        dailyFile(DATE, sampleDaily());
        long cycle = openCycle(DATE);
        JobExecution failed;
        widen(archiveFolder());
        try {
            failed = run("ingestDailyFileJob", cycle);
        } finally {
            narrow(archiveFolder());
        }

        assertReported(failed, "OUTPUT_PERMISSIONS: step stage failed: output root refused", output);
    }

    @Test
    void aRejectsFolderThatFailsItsCheckIsReportedByCodeOnly(CapturedOutput output) throws Exception {
        long cycle = ingested();
        Path rejects = root().resolve("rejects");
        JobExecution failed;
        widen(rejects);
        try {
            failed = run("postDailyTransactionsJob", cycle);
        } finally {
            narrow(rejects);
        }

        assertReported(failed, "OUTPUT_PERMISSIONS: step exportRejects failed: output root refused", output);
    }

    /** The database becomes another instance while stage runs; the fence refuses the commit. */
    @Test
    void anotherInstanceAtTheCommitIsReportedByCodeOnly(CapturedOutput output) {
        dailyFile(DATE, sampleDaily());
        long cycle = openCycle(DATE);
        String instance = DB.admin().sql("SELECT CONVERT(CHAR(36), instance_id) FROM ops.database_instance WHERE id = 1")
                .query(String.class).single();
        files.afterCopyPublished = execution -> DB.admin()
                .sql("UPDATE ops.database_instance SET instance_id = NEWID() WHERE id = 1").update();
        JobExecution failed;
        try {
            failed = run("ingestDailyFileJob", cycle);
        } finally {
            DB.admin().sql("UPDATE ops.database_instance SET instance_id = :id WHERE id = 1").param("id", instance)
                    .update();
        }

        assertReported(failed, "OUTPUT_ROOT_MISMATCH: step stage failed: output root refused", output);
    }

    @Test
    void aRefusedPublicationIsReportedByCodeOnly(CapturedOutput output) throws Exception {
        long cycle = ingested();
        Path folder = root().resolve("rejects").resolve(cycle + "-1");
        Files.createDirectory(folder);
        narrow(folder);
        Path other = Files.writeString(folder.resolve(ExportRejectsTasklet.FILE_NAME), "other bytes\n",
                StandardCharsets.ISO_8859_1);
        Files.setPosixFilePermissions(other, PosixFilePermissions.fromString("rw-------"));

        JobExecution failed = run("postDailyTransactionsJob", cycle);

        assertReported(failed, "PUBLICATION_MISMATCH: step exportRejects failed: publication refused", output);
        assertThat(Files.readString(other, StandardCharsets.ISO_8859_1)).as("never replaced").isEqualTo("other bytes\n");
    }

    @Test
    void aFileSystemFailureThatNamesAPathIsReportedByTypeOnly(CapturedOutput output) {
        dailyFile(DATE, sampleDaily());
        long cycle = openCycle(DATE);
        String path = inbound(DATE).toString();
        files.afterCopyPublished = execution -> {
            throw new UncheckedIOException(new NoSuchFileException(path));
        };

        JobExecution failed = run("ingestDailyFileJob", cycle);

        assertReported(failed, "STEP_FAILED: step stage failed (NoSuchFileException)", output);
    }

    /** The execution that ran: its failure exceptions live only on it, not in the repository. */
    private JobExecution run(String jobName, long cycle) {
        return launches.launch(jobName, Map.of("params", Map.of("cycleId", cycle))).execution();
    }

    private void assertReported(JobExecution failed, String description, CapturedOutput output) {
        assertThat(roots.varDir().toString()).as("the folder that must stay unnamed").contains(PrivateVarDir.SENTINEL);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        String jobExit = DB.admin().sql("SELECT EXIT_MESSAGE FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", failed.getId()).query(String.class).single();
        assertThat(jobExit).isEqualTo(description);
        Map<String, Object> poll = views.view(failed.getId());
        assertThat(poll).containsEntry("exitStatus", "FAILED");
        Map<String, String> records = new LinkedHashMap<>();
        records.put("failure exceptions", failed.getAllFailureExceptions().stream().map(Throwable::toString)
                .collect(Collectors.joining(" | ")));
        records.put("BATCH_JOB_EXECUTION.EXIT_MESSAGE", jobExit);
        records.put("BATCH_STEP_EXECUTION.EXIT_MESSAGE", String.join(" | ", DB.admin()
                .sql("SELECT EXIT_MESSAGE FROM batch.BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", failed.getId()).query(String.class).list()));
        records.put("poll answer", poll.toString());
        records.put("output", output.getAll());
        records.forEach((where, text) -> assertThat(text).as(where).doesNotContain(PrivateVarDir.SENTINEL));
    }

    /** The sample data loaded and the sample day staged for a new cycle. */
    private long ingested() throws Exception {
        DB.admin().sql("DELETE FROM batch.snapshot_tcat_bal").update();
        DB.admin().sql("DELETE FROM batch.snapshot_account").update();
        for (String table : REFERENCE_TABLES) {
            DB.admin().sql("DELETE FROM " + table).update();
        }
        JobExecution load = jobOperator.start(dataLoadJob, new JobParametersBuilder()
                .addString("runKey", "test/" + UUID.randomUUID())
                .addLocalDate("businessDate", LocalDate.of(2022, 7, 6), false).toJobParameters());
        assertThat(load.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        dailyFile(DATE, sampleDaily());
        long cycle = openCycle(DATE);
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        return cycle;
    }

    private static void widen(Path folder) throws IOException {
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static void narrow(Path folder) throws IOException {
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
    }
}
