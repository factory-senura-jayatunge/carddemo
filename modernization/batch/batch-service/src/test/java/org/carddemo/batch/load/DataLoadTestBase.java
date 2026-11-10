package org.carddemo.batch.load;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Autowired;
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
        DB.fixtures().deleteAll();
        for (String table : ClearCoreTablesTasklet.CHILDREN_FIRST) {
            if (table.startsWith("ref.")) {
                DB.admin().sql("DELETE FROM " + table).update();
            }
        }
    }

    /** One run, as the application runner starts it: the next instance of the incrementer. */
    JobExecution run() throws Exception {
        return jobOperator.startNextInstance(dataLoadJob);
    }

    static String failures(JobExecution execution) {
        return execution.getAllFailureExceptions().stream().map(Throwable::getMessage)
                .collect(Collectors.joining(" | "));
    }

    static long count(String table) {
        return DB.admin().sql("SELECT COUNT_BIG(*) FROM " + table).query(Long.class).single();
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
