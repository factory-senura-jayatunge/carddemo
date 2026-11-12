package org.carddemo.batch.disclosure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.carddemo.batch.internal.BatchWindows;
import org.carddemo.batch.internal.JobLaunches;
import org.carddemo.batch.internal.WindowHolder;
import org.carddemo.batch.execution.OutputRoots;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** SQL Server tests, compiled offline and executed only in an orchestrator-scheduled DB slot. */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DisclosureGroupReloadJobTest {
    static final TestDatabase DB = TestDatabase.shared();
    static final LocalDate DATE = LocalDate.of(2022, 7, 6);
    static final String HOLDER = "catalogue-test/disclosure";
    Path varDir;

    @Autowired JobLaunches launches;
    @Autowired BatchWindows windows;
    @Autowired JobRepository repository;
    @Autowired OutputRoots roots;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.BATCH);
    }

    @BeforeEach
    void seedOldSet() throws Exception {
        varDir = roots.varDir();
        DB.admin().sql("UPDATE ops.batch_window SET closed_at = SYSUTCDATETIME() WHERE closed_at IS NULL").update();
        DB.admin().sql("DELETE FROM ref.disclosure_group").update();
        DB.admin().sql("""
                INSERT INTO ref.disclosure_group (group_id, type_cd, cat_cd, int_rate)
                VALUES ('DEFAULT', '01', '0001', 1.00), ('OLDSET', '01', '0001', 2.00)
                """).update();
        Files.createDirectories(varDir.resolve("inbound/discgrp"));
        Files.deleteIfExists(inbound());
    }

    Path inbound() { return varDir.resolve("inbound/discgrp/" + DATE + ".txt"); }

    static List<String> sample() throws Exception {
        return Files.readAllLines(Path.of(System.getProperty("carddemo.repo.root", "../../.."),
                "app/data/ASCII/discgrp.txt"), StandardCharsets.ISO_8859_1);
    }

    void input(List<String> lines) throws Exception {
        Files.writeString(inbound(), String.join("\n", lines) + "\n", StandardCharsets.ISO_8859_1);
    }

    JobExecution run(boolean openWindow) {
        if (openWindow) windows.open(WindowHolder.run(HOLDER));
        var execution = launches.launch("disclosureGroupReloadJob", Map.of("params", Map.of(
                "runKey", "catalogue-test/" + UUID.randomUUID(), "windowRunKey", HOLDER,
                "businessDate", DATE.toString()))).execution();
        return repository.getJobExecution(execution.getId());
    }

    static List<String> rows() {
        return DB.admin().sql("""
                SELECT CONCAT(group_id, '|', type_cd, '|', cat_cd, '|', int_rate)
                FROM ref.disclosure_group ORDER BY group_id, type_cd, cat_cd
                """).query(String.class).list();
    }

    @Test
    @SpecItem({"SE-JDISC-001", "SE-JDISC-003"})
    void replacesWholeSetAndRerunsUnderNewKey() throws Exception {
        input(sample());
        byte[] original = Files.readAllBytes(inbound());
        JobExecution first = run(true);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rows()).hasSize(51).noneMatch(r -> r.startsWith("OLDSET"));
        var before = rows();
        assertThat(run(true).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rows()).isEqualTo(before);
        assertThat(Files.readAllBytes(inbound())).isEqualTo(original);
        assertThat(first.getStepExecutions()).extracting(s -> s.getStepName())
                .containsExactly("reload", "checkDefaultGroup");
    }

    @Test
    @SpecItem({"DEF-JDISC-001", "ERR-JDISC-001"})
    void malformedLastRecordLeavesEveryOldColumnUnchanged() throws Exception {
        var lines = new ArrayList<>(sample());
        lines.set(lines.size() - 1, "short");
        input(lines);
        var before = rows();
        JobExecution failed = run(true);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.getExitStatus().getExitDescription()).contains("RECORD_LENGTH", "line 51");
        assertThat(rows()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"order", "duplicate", "default"})
    @SpecItem("VAL-JDISC-001")
    void validationFailuresNeverChangeTheOldSet(String fault) throws Exception {
        var lines = new ArrayList<>(sample());
        if (fault.equals("order")) java.util.Collections.swap(lines, 0, 1);
        if (fault.equals("duplicate")) lines.set(1, lines.getFirst());
        if (fault.equals("default")) lines.removeIf(l -> l.startsWith("DEFAULT"));
        input(lines);
        var before = rows();
        assertThat(run(true).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    @SpecItem({"SE-JDISC-001", "SE-JDISC-003"})
    void aChangedFileRemovesOldKeysAndKeepsChangedRates() throws Exception {
        var lines = new ArrayList<>(sample());
        String removed = lines.removeFirst();
        String changed = lines.getFirst();
        lines.set(0, changed.substring(0, 16) + "001234" + changed.substring(22));
        lines.add("ZZZ       " + removed.substring(10));
        input(lines);
        assertThat(run(true).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rows()).hasSize(51);
        assertThat(DB.admin().sql("""
                SELECT int_rate FROM ref.disclosure_group
                WHERE group_id = :g AND type_cd = :t AND cat_cd = :c
                """).param("g", changed.substring(0, 10).stripTrailing())
                .param("t", changed.substring(10, 12)).param("c", changed.substring(12, 16))
                .query(java.math.BigDecimal.class).single()).isEqualByComparingTo("12.34");
        assertThat(rows()).noneMatch(r -> r.startsWith(removed.substring(0, 10).stripTrailing() + "|"
                + removed.substring(10, 12) + "|" + removed.substring(12, 16) + "|"));
    }

    @Test
    void missingFileFailsWithoutChanges() {
        var before = rows();
        var failed = run(true);
        assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failed.getExitStatus().getExitDescription()).contains("INBOUND_FILE_MISSING");
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void noWindowRefusesLaunch() throws Exception {
        input(sample());
        var before = rows();
        assertThatThrownBy(() -> run(false)).hasMessageContaining("batch window");
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    @SpecItem({"DEF-JDISC-001", "ERR-JDISC-001"})
    void databaseFailureAfterDeleteRollsBackTheWholeReload() throws Exception {
        input(sample());
        var before = rows();
        DB.admin().sql("""
                CREATE OR ALTER TRIGGER ref.catalogue_reload_test ON ref.disclosure_group AFTER INSERT AS
                BEGIN THROW 51000, 'sentinel-must-not-leak', 1; END
                """).update();
        try {
            var failed = run(true);
            assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(failed.getExitStatus().getExitDescription()).doesNotContain("sentinel-must-not-leak");
            assertThat(rows()).isEqualTo(before);
        } finally {
            DB.admin().sql("DROP TRIGGER ref.catalogue_reload_test").update();
        }
    }

    @Test
    void committedTableIsCheckedForDefaultAsADefence() throws Exception {
        input(sample());
        DB.admin().sql("""
                CREATE OR ALTER TRIGGER ref.catalogue_default_test ON ref.disclosure_group AFTER INSERT AS
                BEGIN DELETE FROM ref.disclosure_group WHERE group_id = 'DEFAULT'; END
                """).update();
        try {
            var failed = run(true);
            assertThat(failed.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(failed.getExitStatus().getExitDescription()).contains("DEFAULT_GROUP_MISSING",
                    "checkDefaultGroup");
            assertThat(failed.getStepExecutions()).anyMatch(s -> s.getStepName().equals("reload")
                    && s.getStatus() == BatchStatus.COMPLETED);
        } finally {
            DB.admin().sql("DROP TRIGGER ref.catalogue_default_test").update();
        }
    }

    @Test
    @SpecItem("ERR-JDISC-002")
    void readersSeeOnlyOldOrNewSet() throws Exception {
        String template = sample().stream().filter(l -> l.startsWith("DEFAULT")).findFirst().orElseThrow();
        List<String> lines = new ArrayList<>();
        lines.add(template);
        for (int i = 0; i < 4999; i++) lines.add("G%09d".formatted(i) + template.substring(10));
        input(lines);
        var old = rows();
        var future = CompletableFuture.supplyAsync(() -> run(true));
        List<List<String>> observed = new ArrayList<>();
        do {
            observed.add(rows());
            Thread.sleep(50);
        } while (!future.isDone());
        assertThat(future.join().getStatus()).isEqualTo(BatchStatus.COMPLETED);
        var current = rows();
        assertThat(current).hasSize(5000);
        assertThat(observed).isNotEmpty().allMatch(r -> r.equals(old) || r.equals(current));
    }
}
