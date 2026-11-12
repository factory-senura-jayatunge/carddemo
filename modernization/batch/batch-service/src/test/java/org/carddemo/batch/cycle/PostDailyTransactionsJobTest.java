package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.carddemo.batch.internal.ExecutionOutputs;
import org.junit.jupiter.api.BeforeEach;
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
 * postDailyTransactionsJob (CBTRN02C) on the sample data, as dataLoadJob loads it and
 * ingestDailyFileJob stages it. The expected counts are the sample day's figures of the CBTRN02C
 * spec (section 15): 300 read, 262 posted, 38 rejected over the limit, 50 new category balances.
 * dataLoadJob also loads the 202 sample authorizations, none of which matches a sample daily
 * transaction (D-33 case 20): their dates are more than a year apart.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.batch.files.chunk-size=7"})
class PostDailyTransactionsJobTest extends CycleJobTestBase {

    static final String DATE = "2101-06-01";
    private static final List<String> REFERENCE_TABLES = List.of("ref.disclosure_group", "ref.tran_category",
            "ref.tran_type", "ref.us_state_zip_prefix", "ref.us_state", "ref.nanp_area_code");

    @Autowired
    JobOperator jobOperator;

    @Autowired
    Job dataLoadJob;

    @Autowired
    org.carddemo.batch.internal.CycleReruns reruns;

    @BeforeEach
    void loadSample() throws Exception {
        DB.admin().sql("DELETE FROM batch.snapshot_tcat_bal").update();
        DB.admin().sql("DELETE FROM batch.snapshot_account").update();
        for (String table : REFERENCE_TABLES) {
            DB.admin().sql("DELETE FROM " + table).update();
        }
        JobExecution load = jobOperator.start(dataLoadJob, new JobParametersBuilder()
                .addString("runKey", "test/" + UUID.randomUUID())
                .addLocalDate("businessDate", LocalDate.of(2022, 7, 6), false).toJobParameters());
        assertThat(load.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    private static long rejectCount(JobExecution run) {
        return ((Number) run.getExecutionContext().get(ExecutionOutputs.REJECT_COUNT)).longValue();
    }

    private long ingested(List<String> lines) {
        dailyFile(DATE, lines);
        long cycle = openCycle(DATE);
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        return cycle;
    }

    @Test
    void postsTheSampleDayAndRejectsWhatCbtrn02cRejects() throws Exception {
        long cycle = ingested(sampleDaily());

        JobExecution run = launch("postDailyTransactionsJob", cycle);

        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(run.getExitStatus().getExitCode()).isEqualTo(ExecutionOutputs.COMPLETED_WITH_REJECTS);
        assertThat(rejectCount(run)).isEqualTo(38);
        assertThat(IngestDailyFileJobTest.step(run, "post").getReadCount()).isEqualTo(300);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE origin = 'POSTED' AND in_master = 1"))
                .isEqualTo(262);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.daily_reject WHERE cycle_id = ?", cycle)).isEqualTo(38);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.daily_reject WHERE cycle_id = ? AND reason_cd = '0102'"
                + " AND reason_cds = '0102' AND reason_desc = 'OVERLIMIT TRANSACTION'", cycle)).isEqualTo(38);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE origin = 'POSTED' AND cycle_id = ?", cycle))
                .isEqualTo(262);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.tcat_bal")).isEqualTo(100);
        // Every new category follows a read of a sample record, whose FILLER is 22 zeros (DEF-POST-010).
        assertThat(count("SELECT COUNT_BIG(*) FROM core.tcat_bal WHERE legacy_filler = REPLICATE(N'0', 22)"))
                .isEqualTo(100);
        assertThat(count("SELECT COUNT_BIG(*) FROM batch.snapshot_account WHERE cycle_id = ?", cycle)).isEqualTo(50);
        assertThat(count("SELECT COUNT_BIG(*) FROM batch.snapshot_tcat_bal WHERE cycle_id = ?", cycle)).isEqualTo(50);
        // Every posted amount reached the account of its card, and nothing else changed a balance.
        assertThat(count("""
                SELECT COUNT_BIG(*) FROM core.account AS a
                JOIN batch.snapshot_account AS s ON s.acct_id = a.acct_id AND s.cycle_id = ?
                WHERE a.curr_bal - s.curr_bal <> COALESCE((SELECT SUM(t.amount) FROM core.[transaction] AS t
                    JOIN core.card_xref AS x ON x.card_num = t.card_num
                    WHERE x.acct_id = a.acct_id AND t.origin = 'POSTED'), 0)""", cycle)).isZero();

        Path file = root().resolve(ExportRejectsTasklet.relativePath(new CycleRun(cycle, date(DATE), 1)));
        assertThat(run.getExecutionContext().get(ExportRejectsTasklet.OUTPUT))
                .isEqualTo("rejects/" + cycle + "-1/DALYREJS.txt");
        byte[] content = Files.readAllBytes(file);
        assertThat(content).hasSize(38 * 431);
        List<String> records = Arrays.asList(new String(content, StandardCharsets.ISO_8859_1).split("\n"));
        List<String> expected = DB.admin().sql("""
                        SELECT d.raw_record FROM core.daily_reject AS r JOIN core.daily_tran AS d ON d.daily_pk = r.daily_pk
                        WHERE r.cycle_id = :c ORDER BY r.seq""").param("c", cycle).query(String.class).list()
                .stream().map(raw -> raw + "0102" + String.format("%-76s", "OVERLIMIT TRANSACTION")).toList();
        assertThat(records).containsExactlyElementsOf(expected);
        List<Integer> rejectedSeqs = DB.admin().sql("""
                        SELECT d.seq FROM core.daily_reject AS r JOIN core.daily_tran AS d ON d.daily_pk = r.daily_pk
                        WHERE r.cycle_id = :c ORDER BY r.seq""").param("c", cycle).query(Integer.class).list();
        assertThat(rejectedSeqs).isSorted().doesNotHaveDuplicates();
        assertThat(names(file.getParent())).as("no working file is left").containsExactly(ExportRejectsTasklet.FILE_NAME);
        assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);

        // D-33 case 20: the sample approvals stay pending and keep their holds.
        assertThat(IngestDailyFileJobTest.step(run, "stageAuthorizationMatches").getExecutionContext()
                .getLong(StageAuthorizationMatchesTasklet.CANDIDATE_PAIRS)).isZero();
        assertThat(IngestDailyFileJobTest.step(run, "applyAuthorizationMatches").getExecutionContext()
                .getLong(ApplyAuthorizationMatchesTasklet.APPLIED)).isZero();
        assertThat(count("SELECT COUNT_BIG(*) FROM pauth.auth_detail WHERE match_status = 'P'")).isEqualTo(202);
        assertThat(count("SELECT COUNT_BIG(*) FROM pauth.auth_detail WHERE match_status <> 'P'")).isZero();
        assertThat(DB.admin().sql("SELECT SUM(credit_balance) FROM pauth.auth_summary").query(BigDecimal.class).single())
                .isEqualByComparingTo("1838.30");
    }

    /**
     * Contract section 4.7, fixed inputs: an attempt-1 rejects export that resumes after cycle-rerun
     * and attempt 2's posting fails with CYCLE_ATTEMPT_CHANGED and writes nothing.
     */
    @Test
    void anAttemptOneExportResumedAfterARerunWritesNothing() throws Exception {
        long cycle = ingested(sampleDaily());
        // A file where attempt 1's reject folder must be fails exportRejects after the posting committed.
        Path folder = root().resolve("rejects").resolve(cycle + "-1");
        Files.createFile(folder, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        JobExecution failed = launch("postDailyTransactionsJob", cycle);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(IngestDailyFileJobTest.step(failed, "exportRejects").getStatus()).isEqualTo(BatchStatus.FAILED);
        reruns.rerun(cycle);
        assertThat(launch("ingestDailyFileJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(launch("postDailyTransactionsJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Files.delete(folder);

        JobExecution resumed = repository.getJobExecution(jobOperator.restart(failed).getId());

        assertThat(resumed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(resumed.getExitStatus().getExitDescription()).startsWith("CYCLE_ATTEMPT_CHANGED: ");
        assertThat(folder).as("attempt 1 writes nothing, not even its folder").doesNotExist();
        Path second = root().resolve(ExportRejectsTasklet.relativePath(new CycleRun(cycle, date(DATE), 2)));
        assertThat(Files.readAllBytes(second)).hasSize(38 * 431);
        assertThat(names(second.getParent())).containsExactly(ExportRejectsTasklet.FILE_NAME);
    }

    @Test
    void aRestartResumesAfterTheCommittedChunksAndEndsAsAnUninterruptedRun() {
        long cycle = ingested(sampleDaily());
        BigDecimal amount = DB.admin().sql("SELECT amount FROM core.daily_tran WHERE cycle_id = :c AND seq = 20")
                .param("c", cycle).query(BigDecimal.class).single();
        DB.admin().sql("UPDATE core.daily_tran SET amount = NULL WHERE cycle_id = :c AND seq = 20")
                .param("c", cycle).update();

        JobExecution failed = launch("postDailyTransactionsJob", cycle);

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.getExitStatus().getExitDescription())
                .isEqualTo("DAILY_AMOUNT_INVALID: daily record 20 has no valid DALYTRAN-AMT");
        long done = count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE origin = 'POSTED'")
                + count("SELECT COUNT_BIG(*) FROM core.daily_reject WHERE cycle_id = ?", cycle);
        assertThat(done).isEqualTo(14);
        DB.admin().sql("UPDATE core.daily_tran SET amount = :a WHERE cycle_id = :c AND seq = 20")
                .param("a", amount).param("c", cycle).update();

        JobExecution restarted = launch("postDailyTransactionsJob", cycle);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(IngestDailyFileJobTest.step(restarted, "post").getReadCount()).isEqualTo(286);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE origin = 'POSTED'")).isEqualTo(262);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.daily_reject WHERE cycle_id = ?", cycle)).isEqualTo(38);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.tcat_bal")).isEqualTo(100);
        assertThat(count("SELECT COUNT_BIG(*) FROM batch.snapshot_account WHERE cycle_id = ?", cycle)).isEqualTo(50);
        assertThat(rejectCount(restarted)).isEqualTo(38);
    }

    private String filler(String acctTypeCat) {
        return DB.admin().sql("SELECT legacy_filler FROM core.tcat_bal WHERE acct_id + type_cd + cat_cd = :k")
                .param("k", acctTypeCat).query(String.class).optional().orElse(null);
    }

    private String categoryOf(long cycle, int seq) {
        return DB.admin().sql("""
                        SELECT x.acct_id + d.type_cd + d.cat_cd FROM core.daily_tran AS d
                        JOIN core.card_xref AS x ON x.card_num = d.card_num WHERE d.cycle_id = :c AND d.seq = :s""")
                .param("c", cycle).param("s", seq).query(String.class).single();
    }

    @Test
    void aRestartGivesANewCategoryTheFillerReadBeforeTheFailure() {
        long cycle = ingested(sampleDaily());
        String read = "F".repeat(22);
        String later = "G".repeat(22);
        DB.admin().sql("UPDATE core.tcat_bal SET legacy_filler = :f").param("f", read).update();
        // Seq 57 opens the ninth chunk of 7 and creates a category (the CBTRN02C reimplementation on the sample).
        int first = 57;
        String category = categoryOf(cycle, first);
        BigDecimal amount = DB.admin().sql("SELECT amount FROM core.daily_tran WHERE cycle_id = :c AND seq = :s")
                .param("c", cycle).param("s", first).query(BigDecimal.class).single();
        DB.admin().sql("UPDATE core.daily_tran SET amount = NULL WHERE cycle_id = :c AND seq = :s")
                .param("c", cycle).param("s", first).update();

        JobExecution failed = launch("postDailyTransactionsJob", cycle);

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.getExitStatus().getExitDescription()).startsWith("DAILY_AMOUNT_INVALID: daily record 57 ");
        assertThat(filler(category)).as("not created before the failure").isNull();
        DB.admin().sql("UPDATE core.daily_tran SET amount = :a WHERE cycle_id = :c AND seq = :s")
                .param("a", amount).param("c", cycle).param("s", first).update();
        // Rows read after the restart now hand on another FILLER; the first row must not read any.
        DB.admin().sql("UPDATE core.tcat_bal SET legacy_filler = :f").param("f", later).update();

        JobExecution restarted = launch("postDailyTransactionsJob", cycle);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(filler(category)).as("the work area the committed chunks left").isEqualTo(read);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.tcat_bal WHERE legacy_filler = ?", read)).isEqualTo(1);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.tcat_bal WHERE legacy_filler = ?", later)).isEqualTo(99);
    }

    @Test
    void aCategoryWithoutFillerHandsOnTheFillerItsExportWrites() {
        long cycle = ingested(sampleDaily());
        // As online posting leaves the rows it creates.
        DB.admin().sql("UPDATE core.tcat_bal SET legacy_filler = NULL").update();

        assertThat(launch("postDailyTransactionsJob", cycle).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(count("SELECT COUNT_BIG(*) FROM core.tcat_bal WHERE legacy_filler IS NULL")).isEqualTo(50);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.tcat_bal WHERE legacy_filler = REPLICATE(N'0', 22)"))
                .as("not the low-values of an untouched work area").isEqualTo(50);
    }

    @Test
    void anUnknownCardAndAnExpiredAccountAreRejectedWithTheirReasons() {
        List<String> lines = sampleDaily();
        // DALYTRAN-CARD-NUM, bytes 263-278: a card no cross-reference holds.
        lines.set(0, lines.get(0).substring(0, 262) + "0000000000000000" + lines.get(0).substring(278));
        long cycle = ingested(lines);
        String acct = DB.admin().sql("""
                        SELECT x.acct_id FROM core.daily_tran AS d JOIN core.card_xref AS x ON x.card_num = d.card_num
                        WHERE d.cycle_id = :c AND d.seq = 2""").param("c", cycle).query(String.class).single();
        DB.admin().sql("UPDATE core.account SET expiration_date = '2000-01-01', credit_limit = 0 WHERE acct_id = :a")
                .param("a", acct).update();

        JobExecution run = launch("postDailyTransactionsJob", cycle);

        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(DB.admin().sql("""
                        SELECT r.reason_cd + '/' + r.reason_desc COLLATE Latin1_General_100_BIN2 FROM core.daily_reject AS r
                        JOIN core.daily_tran AS d ON d.daily_pk = r.daily_pk WHERE r.cycle_id = :c AND d.seq = 1""")
                .param("c", cycle).query(String.class).single()).isEqualTo("0100/INVALID CARD NUMBER FOUND");
        List<String> expired = DB.admin().sql("""
                        SELECT r.reason_cd + '/' + r.reason_cds FROM core.daily_reject AS r
                        JOIN core.daily_tran AS d ON d.daily_pk = r.daily_pk
                        JOIN core.card_xref AS x ON x.card_num = d.card_num
                        WHERE r.cycle_id = :c AND x.acct_id = :a""")
                .param("c", cycle).param("a", acct).query(String.class).list();
        assertThat(expired).isNotEmpty().allMatch(s -> s.equals("0103/0103") || s.equals("0103/0102,0103"))
                .contains("0103/0102,0103");
        assertThat(count("""
                SELECT COUNT_BIG(*) FROM core.[transaction] AS t JOIN core.card_xref AS x ON x.card_num = t.card_num
                WHERE x.acct_id = ? AND t.origin = 'POSTED'""", acct)).isZero();
    }

    @Test
    void aTransactionIdThatReachedTheMasterSinceTheClaimFailsThePosting() {
        long cycle = ingested(sampleDaily());
        String tranId = DB.admin().sql("SELECT tran_id FROM core.daily_tran WHERE cycle_id = :c AND seq = 5")
                .param("c", cycle).query(String.class).single();
        DB.fixtures().transaction(tranId, "0000000000000000", "1.00");

        JobExecution run = launch("postDailyTransactionsJob", cycle);

        assertThat(run.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(run.getExitStatus().getExitDescription()).isEqualTo(
                "DUPLICATE_TRAN_ID: tran_id " + tranId + " at daily record 5 is already in the master");
        assertThat(count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE origin = 'POSTED'")
                + count("SELECT COUNT_BIG(*) FROM core.daily_reject WHERE cycle_id = ?", cycle)).isZero();
    }

    /**
     * A failure inside a step whose driver message quotes a value: the failure exceptions, both
     * EXIT_MESSAGE columns and the log name the step and the SQL error number, and no card number.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aStepFailureIsReportedWithoutTheValuesTheDriverQuoted(CapturedOutput output) {
        long cycle = ingested(sampleDaily());
        List<String> cards = DB.admin().sql("SELECT DISTINCT card_num FROM core.daily_tran WHERE cycle_id = :c")
                .param("c", cycle).query(String.class).list();
        // On core.tcat_bal, not core.[transaction]: the transaction insert reads its key back with a
        // plain OUTPUT clause, which SQL Server refuses (error 334) on a table with an enabled trigger.
        DB.admin().sql("""
                CREATE TRIGGER core.tr_test_quote_value ON core.tcat_bal AFTER INSERT, UPDATE AS
                BEGIN
                    DECLARE @m NVARCHAR(2048) = CONCAT(N'test: quoted value ',
                            (SELECT TOP 1 card_num FROM core.daily_tran ORDER BY cycle_id DESC, seq));
                    THROW 50000, @m, 1;
                END""").update();
        JobExecution failed;
        try {
            // The execution that ran: the failure exceptions live only on it, not in the repository.
            failed = launches.launch("postDailyTransactionsJob", Map.of("params", Map.of("cycleId", cycle))).execution();
        } finally {
            DB.admin().sql("DROP TRIGGER core.tr_test_quote_value").update();
        }

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        String reported = "STEP_FAILED: step post failed with SQL error 50000";
        Map<String, String> records = new LinkedHashMap<>();
        records.put("failure exceptions", failed.getAllFailureExceptions().stream().map(Throwable::toString)
                .collect(Collectors.joining(" | ")));
        records.put("BATCH_JOB_EXECUTION.EXIT_MESSAGE", DB.admin()
                .sql("SELECT EXIT_MESSAGE FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", failed.getId()).query(String.class).single());
        records.put("BATCH_STEP_EXECUTION.EXIT_MESSAGE", String.join(" | ", DB.admin()
                .sql("SELECT EXIT_MESSAGE FROM batch.BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", failed.getId()).query(String.class).list()));
        records.put("output", output.getAll());
        assertThat(cards).isNotEmpty();
        records.forEach((where, text) -> {
            assertThat(text).as(where).contains(reported).doesNotContain("test: quoted value");
            for (String card : cards) {
                assertThat(text).as(where + " holds a card number").doesNotContain(card);
            }
        });
    }
}
