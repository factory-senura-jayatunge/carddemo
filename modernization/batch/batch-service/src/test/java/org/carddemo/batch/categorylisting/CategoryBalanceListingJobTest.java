package org.carddemo.batch.categorylisting;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.carddemo.batch.internal.JobLaunches;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.BothModes;
import org.carddemo.platform.testsupport.CoreFixtures.Account;
import org.carddemo.platform.testsupport.FidelityOverrides;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real entry-point tests, compiled offline; DB execution requires a scheduled slot. */
@BothModes("DEF-JPCAT-001")
@SpringBootTest(properties = "spring.batch.job.enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CategoryBalanceListingJobTest {
    static final TestDatabase DB = TestDatabase.shared();
    @Autowired JobLaunches launches;
    @Autowired JobRepository repository;
    @Autowired BoundListingRoot roots;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.BATCH);
    }

    @BeforeEach
    void cleanTables() {
        DB.fixtures().deleteAll();
        DB.fixtures().closeBatchWindows();
    }

    JobExecution run() {
        var execution = launches.launch("categoryBalanceListingJob", Map.of("params", Map.of(
                "runKey", "catalogue-test/" + UUID.randomUUID(), "businessDate", "2022-07-06"))).execution();
        return repository.getJobExecution(execution.getId());
    }

    Path output(JobExecution job) {
        return roots.root(job).resolve("reports/prtcatbl/" + job.getJobInstance().getId() + "/PRTCATBL.txt");
    }

    static List<String> hashes() {
        return DB.admin().sql("""
                SELECT CONVERT(VARCHAR(64), HASHBYTES('SHA2_256',
                    (SELECT b.* FOR JSON PATH, WITHOUT_ARRAY_WRAPPER)), 2)
                FROM core.tcat_bal b ORDER BY acct_id, type_cd, cat_cd
                """).query(String.class).list();
    }

    @Test
    @SpecItem({"BR-JPCAT-001", "NUM-JPCAT-001", "DEF-JPCAT-001", "DEF-JPCAT-002"})
    void listsEveryRowInKeyOrderWithoutWindowOrBalanceChanges() throws Exception {
        DB.fixtures().account(Account.of("00000000002", "0.00")).account(Account.of("00000000001", "0.00"));
        // Insert deliberately out of key order, with every specified numeric edge.
        DB.fixtures().categoryBalance("00000000002", "02", "0002", "-999999999.99")
                .categoryBalance("00000000001", "02", "0002", "999999999.99")
                .categoryBalance("00000000001", "01", "0002", "-12.34")
                .categoryBalance("00000000002", "01", "0001", "0.00")
                .categoryBalance("00000000001", "01", "0001", "-0.01");
        var before = hashes();
        JobExecution job = run();
        assertThat(job.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        byte[] bytes = Files.readAllBytes(output(job));
        assertThat(bytes).hasSize(5 * 41);
        String sign = FidelityOverrides.current() == FidelityMode.FIXED ? "-" : " ";
        String expected = "00000000001 01 0001 000000000.01" + sign + "       \n"
                + "00000000001 01 0002 000000012.34" + sign + "       \n"
                + "00000000001 02 0002 999999999.99        \n"
                + "00000000002 01 0001 000000000.00        \n"
                + "00000000002 02 0002 999999999.99" + sign + "       \n";
        assertThat(bytes).isEqualTo(expected.getBytes(StandardCharsets.ISO_8859_1));
        assertThat(hashes()).isEqualTo(before);
        assertThat(DB.admin().sql("SELECT COUNT(*) FROM ops.batch_window WHERE closed_at IS NULL")
                .query(Integer.class).single()).isZero();
    }

    @Test
    @SpecItem("BR-JPCAT-001")
    void emptyTablePublishesEmptyFile() throws Exception {
        var job = run();
        assertThat(job.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(Files.size(output(job))).isZero();
        assertThat(hashes()).isEmpty();
    }
}
