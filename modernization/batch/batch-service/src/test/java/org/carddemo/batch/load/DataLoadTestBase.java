package org.carddemo.batch.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** dataLoadJob against the module's test database, connected as batch_svc. */
abstract class DataLoadTestBase {

    static final TestDatabase DB = TestDatabase.shared();
    static final Path SAMPLE_DIR = Path.of(System.getProperty("carddemo.repo.root", "../../.."), "app", "data", "ASCII")
            .toAbsolutePath().normalize();
    static final List<String> CORE_TABLES = List.of(
            "core.account", "core.customer", "core.card", "core.card_xref", "core.tcat_bal");
    static final List<String> SAMPLE_FILES = List.of("acctdata.txt", "custdata.txt", "carddata.txt", "cardxref.txt",
            "tcatbal.txt", "trantype.txt", "trancatg.txt", "discgrp.txt");

    @Autowired
    JobOperator jobOperator;

    @Autowired
    Job dataLoadJob;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.BATCH);
    }

    @BeforeEach
    void emptyTables() {
        DB.admin().sql("DELETE FROM core.daily_reject").update();
        DB.admin().sql("DELETE FROM core.daily_tran").update();
        DB.fixtures().deleteAll();
        for (String table : ClearCoreTablesTasklet.CHILDREN_FIRST) {
            if (table.startsWith("ref.")) {
                DB.admin().sql("DELETE FROM " + table).update();
            }
        }
    }

    /** One run, a new instance as each launch of a run-keyed job is. */
    JobExecution run() throws Exception {
        return jobOperator.start(dataLoadJob, new JobParametersBuilder().addString("runKey", "test/" + UUID.randomUUID())
                .addLocalDate("businessDate", LocalDate.of(2022, 7, 6), false).toJobParameters());
    }

    static String failures(JobExecution execution) {
        return execution.getAllFailureExceptions().stream().map(Throwable::getMessage)
                .collect(Collectors.joining(" | "));
    }

    /**
     * Where a failure of the load is written: the exceptions, the job's and the steps' exit
     * messages as stored, and the output, which holds the error log.
     */
    static Map<String, String> failureRecords(JobExecution execution, CapturedOutput output) {
        Map<String, String> records = new LinkedHashMap<>();
        records.put("failure exceptions", failures(execution));
        records.put("BATCH_JOB_EXECUTION.EXIT_MESSAGE", DB.admin()
                .sql("SELECT EXIT_MESSAGE FROM batch.BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", execution.getId()).query(String.class).single());
        records.put("BATCH_STEP_EXECUTION.EXIT_MESSAGE", String.join(" | ", DB.admin()
                .sql("SELECT EXIT_MESSAGE FROM batch.BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID = :id")
                .param("id", execution.getId()).query(String.class).list()));
        records.put("output", output.getAll());
        return records;
    }

    /**
     * Each place a failure is written holds {@code reported}, so the check is not vacuous, and
     * none of {@code values}, the personal identifiers of the failing record (D-NFR-04).
     */
    static void assertReportedWithout(JobExecution execution, CapturedOutput output, String reported,
            Collection<String> values) {
        assertThat(values).as("identifiers to look for").isNotEmpty().allMatch(v -> v.length() >= 9);
        failureRecords(execution, output).forEach((where, text) -> {
            assertThat(text).as(where).contains(reported);
            for (String v : values) {
                assertThat(text).as(where + " holds an identifier of the record").doesNotContain(v);
            }
        });
    }

    /** An online add waiting for a cycle (D-27); the load leaves core.daily_tran as it is. */
    static void waitingAdd(String tranId) {
        DB.admin().sql("""
                INSERT INTO core.daily_tran (origin, created_by, tran_id, type_cd, cat_cd, source, description,
                    amount, merchant_id, merchant_name, merchant_city, merchant_zip, card_num, orig_ts, proc_ts,
                    raw_record)
                VALUES ('ONLINE_ADD', 'USER0001', :id, '01', '0001', 'POS TERM', 'TEST', 1.00, '000000001', 'M',
                    'C', 'Z', '4111111111111111', '', '', :id)""").param("id", tranId).update();
    }

    static long count(String table) {
        return DB.admin().sql("SELECT COUNT_BIG(*) FROM " + table).query(Long.class).single();
    }

    /**
     * Writes a DEF-ACUP-002 layout manifest for the account file as it is now, its hash computed
     * here from the file's bytes.
     */
    static void writeLayoutManifest(Path manifest, Path acctdata, String defaultLayout, String... otherLayoutAccounts) {
        try {
            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(acctdata)));
            String others = Arrays.stream(otherLayoutAccounts).map(a -> "\"" + a + "\"")
                    .collect(Collectors.joining(", "));
            Files.writeString(manifest, "schema = 1\nsha256 = \"" + hash + "\"\ndefault_layout = \"" + defaultLayout
                    + "\"\nother_layout_accounts = [" + others + "]\n", StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fills dir with a copy of the sample files in which one file's lines are changed. */
    static Path sampleWith(Path dir, String file, UnaryOperator<List<String>> change) {
        try {
            for (String name : SAMPLE_FILES) {
                Files.copy(SAMPLE_DIR.resolve(name), dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
            List<String> lines = new ArrayList<>(Files.readAllLines(dir.resolve(file), StandardCharsets.ISO_8859_1));
            Files.writeString(dir.resolve(file), String.join("\n", change.apply(lines)) + "\n",
                    StandardCharsets.ISO_8859_1);
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
