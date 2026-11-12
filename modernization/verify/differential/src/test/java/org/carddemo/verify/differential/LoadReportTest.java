package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The load report on built executions: its JSON and every check, without a database. */
class LoadReportTest {

    private static final String SAMPLE_SHA256 = "c2a97b6a32dc4a87a7aafdf7f72e6712e560412d30b00c5526cca80fc9dfd260";
    private static final String MANIFEST_SHA256 = "0123456789abcdef".repeat(4);

    private static JobParameters launched() {
        return new JobParametersBuilder().addString("runKey", "cli/host/20221106T000000Z/id", true)
                .addLocalDate("businessDate", LocalDate.of(2022, 7, 6), false)
                .addString("windowRunKey", "cli/host/20221106T000000Z/id", false).toJobParameters();
    }

    /** A completed legacy load of the 50 sample accounts, as execution {@code id}. */
    static JobExecution load(long id) {
        return load(id, "dataLoadJob", launched());
    }

    private static JobExecution load(long id, String jobName, JobParameters parameters) {
        JobExecution job = new JobExecution(id, new JobInstance(id, jobName), parameters);
        job.setStatus(BatchStatus.COMPLETED);
        job.setExitStatus(ExitStatus.COMPLETED);
        job.getExecutionContext().putString(LoadReport.JOB_ACCTDATA_SHA256, SAMPLE_SHA256);
        StepExecution step = new StepExecution(id * 10, "loadAccounts", job);
        step.setStatus(BatchStatus.COMPLETED);
        step.setWriteCount(50);
        ExecutionContext c = step.getExecutionContext();
        c.putLong("accountsAsRead", 50);
        c.putLong("accountsMigrated", 0);
        c.putString("accountLayoutSource", "ignored");
        c.putLong("accountLayoutWarnings", 0);
        c.putString("acctdataSha256", SAMPLE_SHA256);
        c.putString("accountLayoutManifestSha256", "");
        c.putString("groupIdMode", "legacy");
        job.addStepExecutions(List.of(step));
        return job;
    }

    private static StepExecution accounts(JobExecution job) {
        return job.getStepExecutions().iterator().next();
    }

    /** A fixed load with a supplied manifest that migrated every account. */
    private static JobExecution suppliedLoad(long id) {
        JobExecution job = load(id);
        job.getExecutionContext().putString(LoadReport.JOB_MANIFEST_SHA256, MANIFEST_SHA256);
        ExecutionContext c = accounts(job).getExecutionContext();
        c.putLong("accountsAsRead", 0);
        c.putLong("accountsMigrated", 50);
        c.putString("accountLayoutSource", "supplied");
        c.putString("accountLayoutManifestSha256", MANIFEST_SHA256);
        c.putString("groupIdMode", "fixed");
        return job;
    }

    @Test
    void theSampleLegacyLoadPrintsTheContractsExampleByteForByte() {
        String expected = """
                {
                  "schemaVersion": 1,
                  "loadExecutionId": 1,
                  "jobName": "dataLoadJob",
                  "status": "COMPLETED",
                  "exitCode": "COMPLETED",
                  "businessDate": "2022-07-06",
                  "groupIdMode": "legacy",
                  "accountRecords": 50,
                  "accountsAsRead": 50,
                  "accountsMigrated": 0,
                  "accountLayoutSource": "ignored",
                  "accountLayoutWarnings": 0,
                  "acctdataSha256": "c2a97b6a32dc4a87a7aafdf7f72e6712e560412d30b00c5526cca80fc9dfd260",
                  "accountLayoutManifestSha256": ""
                }
                """;

        assertThat(new String(LoadReport.of(load(1), 1).json(), StandardCharsets.UTF_8)).isEqualTo(expected);
    }

    /** Spring Batch's SQL Server sequences start at 0: the first load of a fresh repository is execution 0. */
    @Test
    void theFirstExecutionOfAFreshRepositoryIsReportedAsZero() {
        LoadReport report = LoadReport.of(load(0), 0);

        assertThat(report.loadExecutionId()).isZero();
        assertThat(new String(report.json(), StandardCharsets.UTF_8)).contains("\n  \"loadExecutionId\": 0,\n");
        assertThatThrownBy(() -> LoadReport.of(load(0), 1))
                .hasMessage("dataLoadJob execution 0 is not the last load (a later dataLoadJob execution exists)");
    }

    @Test
    void aSuppliedManifestIsReportedWithItsHash() {
        LoadReport report = LoadReport.of(suppliedLoad(7), 7);

        assertThat(report.accountsMigrated()).isEqualTo(50);
        assertThat(new String(report.json(), StandardCharsets.UTF_8))
                .contains("\"accountLayoutManifestSha256\": \"" + MANIFEST_SHA256 + "\"\n}\n");
    }

    @Test
    void anotherJobAFailedLoadAndALaterLoadAreRefused() {
        assertThatThrownBy(() -> LoadReport.of(load(3, "statementJob", launched()), 3))
                .hasMessage("execution 3 is statementJob, not dataLoadJob");
        JobExecution failed = load(4);
        failed.setStatus(BatchStatus.FAILED);
        failed.setExitStatus(ExitStatus.FAILED);
        assertThatThrownBy(() -> LoadReport.of(failed, 4)).hasMessage("dataLoadJob execution 4 is FAILED FAILED, not completed");
        JobExecution stopped = load(5);
        stopped.setExitStatus(new ExitStatus("COMPLETED_WITH_REJECTS"));
        assertThatThrownBy(() -> LoadReport.of(stopped, 5)).hasMessageEndingWith(", not completed");
        assertThatThrownBy(() -> LoadReport.of(load(6), 9))
                .hasMessage("dataLoadJob execution 6 is not the last load (a later dataLoadJob execution exists)");
    }

    @Test
    void onlyTheLaunchsParametersAreAccepted() {
        JobParameters noDate = new JobParametersBuilder().addString("runKey", "k", true).toJobParameters();
        JobParameters dateIdentifying = new JobParametersBuilder().addString("runKey", "k", true)
                .addLocalDate("businessDate", LocalDate.of(2022, 7, 6), true).toJobParameters();
        JobParameters blankKey = new JobParametersBuilder().addString("runKey", " ", true)
                .addLocalDate("businessDate", LocalDate.of(2022, 7, 6), false).toJobParameters();
        JobParameters dateAsText = new JobParametersBuilder().addString("runKey", "k", true)
                .addString("businessDate", "2022-07-06", false).toJobParameters();
        JobParameters extra = new JobParametersBuilder(launched()).addString("restartOf", "7", false).toJobParameters();
        JobParameters windowAsNumber = new JobParametersBuilder(launched()).addLong("windowRunKey", 7L, false)
                .toJobParameters();
        JobParameters blankWindow = new JobParametersBuilder(launched()).addString("windowRunKey", " ", false)
                .toJobParameters();
        JobParameters windowIdentifying = new JobParametersBuilder(launched()).addString("windowRunKey", "w", true)
                .toJobParameters();
        for (JobParameters p : List.of(noDate, dateIdentifying, blankKey, dateAsText, new JobParameters(), extra,
                windowAsNumber, blankWindow, windowIdentifying)) {
            assertThatThrownBy(() -> LoadReport.of(load(2, "dataLoadJob", p), 2))
                    .hasMessage("dataLoadJob execution 2 has unexpected parameters");
        }
        JobParameters noWindow = new JobParametersBuilder().addString("runKey", "k", true)
                .addLocalDate("businessDate", LocalDate.of(2022, 7, 6), false).toJobParameters();
        assertThat(LoadReport.of(load(2, "dataLoadJob", noWindow), 2).businessDate()).isEqualTo(LocalDate.of(2022, 7, 6));
    }

    @Test
    void theAccountStepMustExistOnceAndComplete() {
        JobExecution none = new JobExecution(8L, new JobInstance(8L, "dataLoadJob"), launched());
        none.setStatus(BatchStatus.COMPLETED);
        none.setExitStatus(ExitStatus.COMPLETED);
        assertThatThrownBy(() -> LoadReport.of(none, 8)).hasMessage("step loadAccounts of execution 8 did not complete");
        JobExecution failedStep = load(8);
        accounts(failedStep).setStatus(BatchStatus.FAILED);
        assertThatThrownBy(() -> LoadReport.of(failedStep, 8)).hasMessage("step loadAccounts of execution 8 did not complete");
        JobExecution twice = load(8);
        StepExecution again = new StepExecution(99L, "loadAccounts", twice);
        again.setStatus(BatchStatus.COMPLETED);
        twice.addStepExecutions(List.of(again));
        assertThatThrownBy(() -> LoadReport.of(twice, 8)).hasMessage("step loadAccounts of execution 8 did not complete");
    }

    @ParameterizedTest
    @ValueSource(strings = {"accountsAsRead", "accountsMigrated", "accountLayoutSource", "accountLayoutWarnings",
        "acctdataSha256", "accountLayoutManifestSha256", "groupIdMode"})
    void everyKeyMustBeThereWithItsType(String key) {
        JobExecution missing = load(2);
        accounts(missing).getExecutionContext().remove(key);
        assertThatThrownBy(() -> LoadReport.of(missing, 2)).hasMessage("step loadAccounts has no " + key);
        JobExecution wrongType = load(2);
        Object value = accounts(wrongType).getExecutionContext().get(key);
        accounts(wrongType).getExecutionContext().put(key, value instanceof String ? (Object) 1L : "1");
        assertThatThrownBy(() -> LoadReport.of(wrongType, 2)).hasMessage("step loadAccounts has no " + key);
    }

    private static void inconsistent(JobExecution job, String rule) {
        assertThatThrownBy(() -> LoadReport.of(job, job.getId()))
                .hasMessage("dataLoadJob execution " + job.getId() + ": inconsistent load report (" + rule + ")");
    }

    private static JobExecution changed(JobExecution job, Consumer<ExecutionContext> change) {
        change.accept(accounts(job).getExecutionContext());
        return job;
    }

    @Test
    void theCountsMustAddUpToTheWriteCount() {
        inconsistent(changed(load(2), c -> c.putLong("accountsAsRead", 49)),
                "accountsAsRead + accountsMigrated is not the write count");
        JobExecution written = load(2);
        accounts(written).setWriteCount(51);
        inconsistent(written, "accountsAsRead + accountsMigrated is not the write count");
    }

    @Test
    void theSourceAndTheModeMustAgree() {
        inconsistent(changed(load(2), c -> c.putString("accountLayoutSource", "manifest")),
                "accountLayoutSource is not supplied, builtin, none or ignored; groupIdMode legacy exactly when the"
                        + " layouts are ignored, then nothing migrated or warned");
        String modeRule = "groupIdMode legacy exactly when the layouts are ignored, then nothing migrated or warned";
        inconsistent(changed(load(2), c -> c.putString("groupIdMode", "fixed")), modeRule);
        inconsistent(changed(load(2), c -> {
            c.putLong("accountsAsRead", 49);
            c.putLong("accountsMigrated", 1);
        }), modeRule);
        inconsistent(changed(load(2), c -> c.putLong("accountLayoutWarnings", 1)), modeRule);
        inconsistent(changed(suppliedLoad(2), c -> c.putString("groupIdMode", "legacy")), modeRule);
        for (String source : List.of("builtin", "none")) {
            JobExecution fixed = changed(load(2), c -> {
                c.putString("accountLayoutSource", source);
                c.putString("groupIdMode", "fixed");
                c.putLong("accountLayoutWarnings", 3);
            });
            assertThat(LoadReport.of(fixed, 2).accountLayoutWarnings()).isEqualTo(3);
        }
    }

    @Test
    void theManifestHashIsSetExactlyWhenSupplied() {
        String rule = "accountLayoutManifestSha256 set exactly when the manifest is supplied";
        inconsistent(changed(suppliedLoad(2), c -> c.putString("accountLayoutManifestSha256", "")),
                rule + "; accountLayoutManifestSha256 differs from the job's dataLoad.accountLayoutManifestSha256");
        JobExecution notSupplied = changed(load(2), c -> c.putString("accountLayoutManifestSha256", MANIFEST_SHA256));
        notSupplied.getExecutionContext().putString(LoadReport.JOB_MANIFEST_SHA256, MANIFEST_SHA256);
        inconsistent(notSupplied, rule);
        JobExecution upper = suppliedLoad(2);
        upper.getExecutionContext().putString(LoadReport.JOB_MANIFEST_SHA256, MANIFEST_SHA256.toUpperCase());
        accounts(upper).getExecutionContext().putString("accountLayoutManifestSha256", MANIFEST_SHA256.toUpperCase());
        inconsistent(upper, rule);
    }

    @Test
    void theStepsHashesMustBeTheJobsHashes() {
        JobExecution otherFile = load(2);
        otherFile.getExecutionContext().putString(LoadReport.JOB_ACCTDATA_SHA256, MANIFEST_SHA256);
        inconsistent(otherFile, "acctdataSha256 differs from the job's dataLoad.acctdataSha256");
        JobExecution noJobHash = load(2);
        noJobHash.getExecutionContext().remove(LoadReport.JOB_ACCTDATA_SHA256);
        inconsistent(noJobHash, "acctdataSha256 differs from the job's dataLoad.acctdataSha256");
        JobExecution otherManifest = suppliedLoad(2);
        otherManifest.getExecutionContext().putString(LoadReport.JOB_MANIFEST_SHA256, SAMPLE_SHA256);
        inconsistent(otherManifest, "accountLayoutManifestSha256 differs from the job's"
                + " dataLoad.accountLayoutManifestSha256");
        inconsistent(changed(load(2), c -> c.putString("acctdataSha256", "x")), "acctdataSha256 is not a SHA-256;"
                + " acctdataSha256 differs from the job's dataLoad.acctdataSha256");
    }
}
