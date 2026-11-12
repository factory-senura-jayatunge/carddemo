package org.carddemo.batch.cycle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.carddemo.batch.TextFixtures;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ingestDailyFileJob (contract sections 4.3 and 4.9, D-27) with BR-TRNA-003 as the ledger decides
 * it: fixed. The inbound file is never moved or rewritten; each attempt stages its own archive copy.
 */
@SpringBootTest(properties = {"spring.batch.job.enabled=false", "carddemo.batch.files.chunk-size=7"})
class IngestDailyFileJobTest extends CycleJobTestBase {

    static final String DATE = "2101-03-01";

    @Test
    void stagesTheFileInRecordOrderClaimsTheWaitingAddsAfterItAndArchivesACopy() throws Exception {
        List<String> lines = sampleDaily();
        // DALYTRAN-AMT, bytes 133-143 of CVTRA06Y: not zoned decimal, so posting must reject it, not staging.
        lines.set(9, lines.get(9).substring(0, 132) + "ABCDEFGHIJK" + lines.get(9).substring(143));
        byte[] content = dailyFile(DATE, lines);
        Inbound before = Inbound.of(inbound(DATE));
        waitingAdd(withId(lines.get(0), "A000000000000002"));
        waitingAdd(withId(lines.get(1), "A000000000000001"));
        long cycle = openCycle(DATE);

        JobExecution run = launch("ingestDailyFileJob", cycle);

        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(run, "stage").getWriteCount()).isEqualTo(300);
        assertThat(step(run, "claimOnlineAdds").getWriteCount()).isEqualTo(2);
        List<String> raw = DB.admin().sql("""
                        SELECT raw_record FROM core.daily_tran WHERE cycle_id = :c AND origin = 'DAILY_FILE' ORDER BY seq""")
                .param("c", cycle).query(String.class).list();
        assertThat(raw).containsExactlyElementsOf(lines);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.daily_tran WHERE cycle_id = ? AND origin = 'DAILY_FILE' "
                + "AND seq BETWEEN 1 AND 300", cycle)).isEqualTo(300);
        assertThat(DB.admin().sql("SELECT tran_id FROM core.daily_tran WHERE cycle_id = :c AND origin = 'ONLINE_ADD' ORDER BY seq")
                .param("c", cycle).query(String.class).list()).containsExactly("A000000000000002", "A000000000000001");
        assertThat(DB.admin().sql("SELECT seq FROM core.daily_tran WHERE cycle_id = :c AND origin = 'ONLINE_ADD' ORDER BY seq")
                .param("c", cycle).query(Integer.class).list()).containsExactly(301, 302);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.daily_tran WHERE cycle_id = ? AND seq = 10 AND amount IS NULL",
                cycle)).isEqualTo(1);
        FixedRecord first = FixedRecord.of(Layouts.DAILY_TRAN, lines.get(0).getBytes(StandardCharsets.ISO_8859_1));
        assertThat(DB.admin().sql("SELECT amount FROM core.daily_tran WHERE cycle_id = :c AND seq = 1")
                .param("c", cycle).query(java.math.BigDecimal.class).single())
                .isEqualByComparingTo(first.decimal("DALYTRAN-AMT"));
        assertThat(Inbound.of(inbound(DATE))).as("the inbound file is the operator's").isEqualTo(before);
        assertThat(names(inbound(DATE).getParent())).containsExactly(DATE + ".txt");
        Path copy = archived(DATE, 1, content);
        assertThat(Files.readAllBytes(copy)).isEqualTo(content);
        assertThat(Files.getPosixFilePermissions(copy)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE);
        assertThat(names(archiveFolder())).containsExactly(".work", copy.getFileName().toString());
        assertThat(names(archiveFolder().resolve(".work"))).isEmpty();
        assertThat(DB.admin().sql("SELECT daily_file_name FROM batch.cycle WHERE cycle_id = :c").param("c", cycle)
                .query(String.class).single()).isEqualTo(copy.getFileName().toString());
        byte[] stored = DB.admin().sql("SELECT daily_file_sha256 FROM batch.cycle WHERE cycle_id = :c")
                .param("c", cycle).query(byte[].class).single();
        assertThat(stored).isEqualTo(DailyFiles.sha256(content));
    }

    @Test
    void aRestartedStageResumesAfterTheCommittedChunks() {
        dailyFile(DATE, sampleDaily());
        long cycle = openCycle(DATE);
        blockSeq(cycle, 20);

        JobExecution failed = launch("ingestDailyFileJob", cycle);

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(staged(cycle)).isEqualTo(14);
        unblock(cycle);
        JobExecution restarted = launch("ingestDailyFileJob", cycle);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(step(restarted, "stage").getWriteCount()).isEqualTo(286);
        assertThat(staged(cycle)).isEqualTo(300);
        assertThat(DB.admin().sql("SELECT raw_record FROM core.daily_tran WHERE cycle_id = :c ORDER BY seq")
                .param("c", cycle).query(String.class).list()).containsExactlyElementsOf(sampleDaily());
    }

    @Test
    void aStageRestartReadsItsArchiveCopyNotTheInboundFile() throws Exception {
        List<String> lines = sampleDaily();
        byte[] content = dailyFile(DATE, lines);
        long cycle = openCycle(DATE);
        blockSeq(cycle, 20);
        launch("ingestDailyFileJob", cycle);
        unblock(cycle);
        // The operator replaces the inbound file while the attempt is under way.
        List<String> replaced = new ArrayList<>(lines);
        replaced.set(200, withId(lines.get(200), "B000000000000001"));
        byte[] replacement = dailyFile(DATE, replaced);

        JobExecution restarted = launch("ingestDailyFileJob", cycle);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(DB.admin().sql("SELECT raw_record FROM core.daily_tran WHERE cycle_id = :c AND origin = 'DAILY_FILE'"
                + " ORDER BY seq").param("c", cycle).query(String.class).list()).containsExactlyElementsOf(lines);
        assertThat(Files.readAllBytes(inbound(DATE))).isEqualTo(replacement);
        assertThat(names(archiveFolder())).containsExactly(".work", archived(DATE, 1, content).getFileName().toString());
    }

    @Test
    void aStageRestartNeedsNoInboundFile() throws Exception {
        byte[] content = dailyFile(DATE, sampleDaily());
        long cycle = openCycle(DATE);
        blockSeq(cycle, 20);
        launch("ingestDailyFileJob", cycle);
        unblock(cycle);
        Files.delete(inbound(DATE));

        JobExecution restarted = launch("ingestDailyFileJob", cycle);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(staged(cycle)).isEqualTo(300);
        assertThat(Files.readAllBytes(archived(DATE, 1, content))).isEqualTo(content);
        assertThat(Files.exists(inbound(DATE))).isFalse();
    }

    /**
     * Contract section 4.7: a crash after the archive copy and before the identity commit, then a
     * restart, gives the same result. The first chunk, which commits the identity, fails here after
     * the copy was published; a working copy of that execution, as a crash between the link and
     * its removal leaves it, is removed by the restart's clean-up.
     */
    @Test
    void aFailureAfterTheArchiveCopyBeforeTheIdentityCommitEndsAsAnUninterruptedRun() throws Exception {
        byte[] content = dailyFile(DATE, sampleDaily());
        Inbound before = Inbound.of(inbound(DATE));
        long cycle = openCycle(DATE);
        blockSeq(cycle, 3);

        JobExecution failed = launch("ingestDailyFileJob", cycle);

        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(staged(cycle)).isZero();
        assertThat(count("SELECT COUNT_BIG(*) FROM batch.cycle WHERE cycle_id = ? AND daily_file_sha256 IS NULL",
                cycle)).isEqualTo(1);
        Path copy = archived(DATE, 1, content);
        assertThat(Files.readAllBytes(copy)).isEqualTo(content);
        Path leftover = archiveFolder().resolve(".work").resolve(DATE + ".1." + failed.getId() + ".tmp");
        Files.createFile(leftover, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(leftover, content);
        unblock(cycle);

        JobExecution restarted = launch("ingestDailyFileJob", cycle);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(DB.admin().sql("SELECT raw_record FROM core.daily_tran WHERE cycle_id = :c AND origin = 'DAILY_FILE'"
                + " ORDER BY seq").param("c", cycle).query(String.class).list()).containsExactlyElementsOf(sampleDaily());
        assertThat(names(archiveFolder())).containsExactly(".work", copy.getFileName().toString());
        assertThat(names(archiveFolder().resolve(".work"))).isEmpty();
        assertThat(Files.readAllBytes(copy)).isEqualTo(content);
        assertThat(Inbound.of(inbound(DATE))).isEqualTo(before);
        assertThat(DB.admin().sql("SELECT daily_file_name FROM batch.cycle WHERE cycle_id = :c").param("c", cycle)
                .query(String.class).single()).isEqualTo(copy.getFileName().toString());
    }

    @Test
    void aMissingFileFailsWithoutIdentifyingOne() {
        long cycle = openCycle(DATE);

        JobExecution run = launch("ingestDailyFileJob", cycle);

        assertThat(run.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(run.getExitStatus().getExitDescription()).startsWith("DAILY_FILE_NOT_FOUND: ");
        assertThat(count("SELECT COUNT_BIG(*) FROM batch.cycle WHERE cycle_id = ? AND daily_file_sha256 IS NULL",
                cycle)).isEqualTo(1);
    }

    @Test
    void aRestartRefusesAnArchiveCopyChangedSinceStageStarted() throws Exception {
        List<String> lines = sampleDaily();
        byte[] content = dailyFile(DATE, lines);
        long cycle = openCycle(DATE);
        blockSeq(cycle, 20);
        launch("ingestDailyFileJob", cycle);
        unblock(cycle);
        lines.set(200, withId(lines.get(200), "B000000000000001"));
        Files.write(archived(DATE, 1, content), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.ISO_8859_1));

        JobExecution restarted = launch("ingestDailyFileJob", cycle);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(restarted.getExitStatus().getExitDescription()).startsWith("DAILY_FILE_CHANGED: ");
        assertThat(staged(cycle)).isEqualTo(14);
        assertThat(Files.readAllBytes(inbound(DATE))).isEqualTo(content);
    }

    @Test
    void aRestartRefusesAMissingArchiveCopy() throws Exception {
        byte[] content = dailyFile(DATE, sampleDaily());
        long cycle = openCycle(DATE);
        blockSeq(cycle, 20);
        launch("ingestDailyFileJob", cycle);
        unblock(cycle);
        Files.delete(archived(DATE, 1, content));

        JobExecution restarted = launch("ingestDailyFileJob", cycle);

        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(restarted.getExitStatus().getExitDescription()).startsWith("DAILY_FILE_NOT_FOUND: ");
        assertThat(staged(cycle)).isEqualTo(14);
        assertThat(Files.readAllBytes(inbound(DATE))).as("never read again by the attempt").isEqualTo(content);
    }

    @Test
    void aRecordOfTheWrongLengthFailsTheStageBeforeAnyRowIsStaged() {
        List<String> lines = sampleDaily();
        lines.set(4, lines.get(4).substring(0, 349) + "XX");
        dailyFile(DATE, lines);
        long cycle = openCycle(DATE);

        JobExecution run = launch("ingestDailyFileJob", cycle);

        assertThat(run.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(run.getExitStatus().getExitDescription()).startsWith("DAILY_FILE_INVALID: ");
        assertThat(staged(cycle)).isZero();
    }

    @Test
    void aDuplicateInTheFileLeavesTheAddsWaitingAndTheFileInbound() {
        List<String> lines = sampleDaily();
        String id = lines.get(1).substring(0, 16);
        lines.set(4, withId(lines.get(4), id));
        dailyFile(DATE, lines);
        waitingAdd(withId(lines.get(0), "A000000000000001"));
        long cycle = openCycle(DATE);

        JobExecution run = launch("ingestDailyFileJob", cycle);

        assertClaimRefused(run, cycle, "DUPLICATE_TRAN_ID: tran_id " + id + " at file record 2 and file record 5",
                lines);
    }

    @Test
    void aFileIdThatAWaitingAddHoldsIsRefusedUntilTheAddIsGone() {
        List<String> lines = sampleDaily();
        String id = lines.get(2).substring(0, 16);
        dailyFile(DATE, lines);
        waitingAdd(withId(lines.get(0), id));
        long cycle = openCycle(DATE);

        JobExecution run = launch("ingestDailyFileJob", cycle);

        assertClaimRefused(run, cycle, "DUPLICATE_TRAN_ID: tran_id " + id + " at file record 3 and an ONLINE_ADD row",
                lines);
        DB.admin().sql("DELETE FROM core.daily_tran WHERE cycle_id IS NULL").update();
        JobExecution restarted = launch("ingestDailyFileJob", cycle);
        assertThat(restarted.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(restarted.getStepExecutions()).extracting(StepExecution::getStepName)
                .containsExactly("claimOnlineAdds");
    }

    @Test
    void aFileIdInTheMasterIsRefused() {
        List<String> lines = sampleDaily();
        String id = lines.get(3).substring(0, 16);
        dailyFile(DATE, lines);
        DB.fixtures().transaction(id, "0000000000000000", "1.00");
        long cycle = openCycle(DATE);

        JobExecution run = launch("ingestDailyFileJob", cycle);

        assertClaimRefused(run, cycle, "DUPLICATE_TRAN_ID: tran_id " + id + " at file record 4 and a master row (LOADED)",
                lines);
    }

    @Test
    void aFileIdInTheGeneratedRangeIsRefusedInFixedMode() {
        List<String> lines = sampleDaily();
        lines.set(6, withId(lines.get(6), "9000000000000123"));
        dailyFile(DATE, lines);
        long cycle = openCycle(DATE);

        JobExecution run = launch("ingestDailyFileJob", cycle);

        assertClaimRefused(run, cycle,
                "TRAN_ID_RESERVED: tran_id 9000000000000123 at file record 7 is in the range of generated IDs", lines);
    }

    private void assertClaimRefused(JobExecution run, long cycle, String description, List<String> lines) {
        assertThat(run.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(step(run, "claimOnlineAdds").getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(run.getExitStatus().getExitDescription()).isEqualTo(description);
        assertThat(step(run, "claimOnlineAdds").getExitStatus().getExitDescription()).contains(description);
        assertThat(lines.stream().map(l -> l.substring(262, 278)))
                .noneMatch(card -> description.contains(card));
        assertThat(staged(cycle)).isEqualTo(300);
        assertThat(count("SELECT COUNT_BIG(*) FROM core.daily_tran WHERE cycle_id = ? AND origin = 'ONLINE_ADD'", cycle))
                .isZero();
        assertThat(Files.exists(inbound(DATE))).isTrue();
    }

    /** A claimed row at {@code seq} makes the stage chunk holding that record fail on UX_daily_claimed. */
    private static void blockSeq(long cycle, int seq) {
        DB.admin().sql("""
                INSERT INTO core.daily_tran (cycle_id, seq, origin, created_by, tran_id, type_cd, cat_cd, source,
                    description, amount, merchant_id, merchant_name, merchant_city, merchant_zip, card_num, orig_ts,
                    proc_ts, raw_record)
                VALUES (:c, :s, 'ONLINE_ADD', 'USER0001', 'BLOCKER000000001', '01', '0001', 'POS TERM', 'BLOCKER',
                    1.00, '000000001', 'M', 'C', 'Z', '0000000000000000', '', '', 'BLOCKER000000001')""")
                .param("c", cycle).param("s", seq).update();
    }

    private static void unblock(long cycle) {
        DB.admin().sql("DELETE FROM core.daily_tran WHERE cycle_id = :c AND tran_id = 'BLOCKER000000001'")
                .param("c", cycle).update();
    }

    @Test
    void freeTextOfEveryByteValueIsStagedUnchanged() {
        TextFixtures text = new TextFixtures();
        List<String> fields = List.of("DALYTRAN-SOURCE", "DALYTRAN-DESC", "DALYTRAN-MERCHANT-NAME",
                "DALYTRAN-MERCHANT-CITY", "DALYTRAN-MERCHANT-ZIP", "DALYTRAN-FILLER");
        List<String> lines = sampleDaily().stream().map(l -> text.fill(l, Layouts.DAILY_TRAN, fields)).toList();
        assertThat(text.charactersUsed()).as("every character but LF").isEqualTo(255);
        dailyFile(DATE, lines);
        long cycle = openCycle(DATE);

        JobExecution run = launch("ingestDailyFileJob", cycle);

        assertThat(run.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        List<java.util.Map<String, Object>> rows = DB.admin().sql("""
                SELECT raw_record, source, description, merchant_name, merchant_city, merchant_zip
                FROM core.daily_tran WHERE cycle_id = :c ORDER BY seq""").param("c", cycle).query().listOfRows();
        assertThat(rows).hasSize(300);
        for (int i = 0; i < rows.size(); i++) {
            FixedRecord r = FixedRecord.of(Layouts.DAILY_TRAN, lines.get(i));
            var row = rows.get(i);
            assertThat(row.get("raw_record")).as("record %d", i + 1).isEqualTo(lines.get(i));
            assertThat(row.get("source")).isEqualTo(r.text("DALYTRAN-SOURCE"));
            assertThat(row.get("description")).isEqualTo(r.text("DALYTRAN-DESC"));
            assertThat(row.get("merchant_name")).isEqualTo(r.text("DALYTRAN-MERCHANT-NAME"));
            assertThat(row.get("merchant_city")).isEqualTo(r.text("DALYTRAN-MERCHANT-CITY"));
            assertThat(row.get("merchant_zip")).isEqualTo(r.text("DALYTRAN-MERCHANT-ZIP"));
        }
    }

    /** Pending V414: core.daily_tran.card_num is code page 1252 until then, which has no U+0081. */
    @Tag("pending-V414")
    @Test
    void aFieldItsColumnCannotHoldFailsTheStageNamingTheRecordAndTheColumn() {
        List<String> lines = sampleDaily();
        // DALYTRAN-CARD-NUM, bytes 263-278.
        lines.set(4, lines.get(4).substring(0, 277) + "\u0081" + lines.get(4).substring(278));
        dailyFile(DATE, lines);
        long cycle = openCycle(DATE);

        JobExecution run = launch("ingestDailyFileJob", cycle);

        assertThat(run.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(run.getExitStatus().getExitDescription()).isEqualTo("DAILY_FILE_INVALID: " + DATE
                + ".txt record 5: core.daily_tran.card_num cannot hold the value: it has a character that the"
                + " column's code page lacks");
        assertThat(staged(cycle)).isZero();
    }

    /** What the operator owns of the inbound file: its bytes, its inode and its modification time. */
    record Inbound(String sha256, Object inode, FileTime modified) {

        static Inbound of(Path file) throws IOException {
            return new Inbound(HexFormat.of().formatHex(DailyFiles.sha256(Files.readAllBytes(file))),
                    Files.getAttribute(file, "unix:ino", LinkOption.NOFOLLOW_LINKS),
                    Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS));
        }
    }

    private static long staged(long cycle) {
        return count("SELECT COUNT_BIG(*) FROM core.daily_tran WHERE cycle_id = ? AND origin = 'DAILY_FILE'", cycle);
    }

    static StepExecution step(JobExecution run, String name) {
        return run.getStepExecutions().stream().filter(s -> s.getStepName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no step " + name + " in " + Arrays.toString(
                        run.getStepExecutions().stream().map(StepExecution::getStepName).toArray())));
    }
}
