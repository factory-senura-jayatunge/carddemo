package org.carddemo.batch.pauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.carddemo.batch.pauth.PauthFixtures.acct;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.carddemo.db.ServiceLogin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Commit interval 5 against 10 on the same data: 30 summaries, the same expired details per
 * summary, each keeping one fresh detail. Per run it records:
 * <ul>
 * <li>each whole chunk as the database saw it: the instant its summaries' totals became visible
 * under {@code READ_COMMITTED_SNAPSHOT}, so the time between two commits covers the key read,
 * the purge batches, the step-execution update and the commit of the fenced transaction. The
 * first chunk is timed from the launch and includes the job start.</li>
 * <li>how long a listener-style request (the authorization login: lock the summary, insert a
 * detail, update the totals) waited for the summary lock, at the first and last summary of the
 * first chunk and at the first summary of a middle and of the last chunk. The probes roll back,
 * so every run purges the same rows.</li>
 * </ul>
 * The numbers go to {@code target/pauth-purge-interval-comparison.txt}. No bound is asserted;
 * the test checks only that each chunk commits whole and the purge leaves what it should.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class PurgeChunkIntervalMeasurementTest extends PurgeTestBase {

    private static final Logger log = LoggerFactory.getLogger(PurgeChunkIntervalMeasurementTest.class);
    static final LocalDate BUSINESS = LocalDate.of(2024, 1, 2);
    static final LocalDateTime EXPIRED = LocalDateTime.of(2023, 12, 20, 10, 0);
    static final LocalDateTime FRESH = LocalDateTime.of(2024, 1, 1, 10, 0);
    static final int SUMMARIES = 30;
    static final Path OUT = Path.of("target", "pauth-purge-interval-comparison.txt");

    /** A probed summary: its index, the chunk it falls in and where in that chunk. */
    record Probe(int summary, int chunk, String position) {
        String label() {
            return "chunk " + chunk + " " + position;
        }
    }

    @Test
    @Timeout(value = 1800, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void commitIntervalFiveAgainstTenOnTheSameData() throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("# purgePendingAuthorizationsJob, " + SUMMARIES + " summaries, each with N expired details and"
                + " one fresh one; intervals run in the order 10, 5, 5, 10 per N");
        lines.add("# chunk ms: commit to commit as seen under RCSI (chunk 1 from the launch, job start included)");
        lines.add("# wait ms: the longest a listener-style request waited for the summary lock (probes every ~20 ms)");
        lines.add("N per summary | interval | chunks | expired per chunk | step ms | chunk ms (1..n) | max chunk ms"
                + " | wait ms by probe | max wait ms");
        for (int perSummary : new int[] {1_000, 5_000}) {
            for (int interval : new int[] {10, 5, 5, 10}) {
                String line = run(perSummary, interval);
                lines.add(line);
                log.info("purge interval comparison: {}", line);
            }
        }
        try {
            Files.createDirectories(OUT.getParent());
            Files.write(OUT, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("cannot write {}: {}", OUT, e.getMessage());
        }
    }

    private String run(int perSummary, int interval) throws Exception {
        DB.fixtures().deleteAll();
        String total = BigDecimal.valueOf(perSummary + 1L).setScale(2).toPlainString();
        for (int i = 1; i <= SUMMARIES; i++) {
            pauth.summary(acct(i), total, perSummary + 1, total, 0, "0.00");
            pauth.approvals(acct(i), perSummary, EXPIRED, "1.00");
            pauth.approvals(acct(i), 1, FRESH, "1.00");
        }
        int chunks = (SUMMARIES + interval - 1) / interval;
        Set<Integer> probedChunks = new LinkedHashSet<>(List.of(1, (chunks + 1) / 2, chunks));
        List<Probe> probes = new ArrayList<>();
        probes.add(new Probe(1, 1, "first"));
        probes.add(new Probe(Math.min(interval, SUMMARIES), 1, "last"));
        for (int c : probedChunks) {
            if (c != 1) {
                probes.add(new Probe((c - 1) * interval + 1, c, "first"));
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(probes.size() + 1);
        AtomicBoolean running = new AtomicBoolean(true);
        Map<Integer, Long> committedAt = new ConcurrentHashMap<>();
        try {
            Future<?> watcher = pool.submit(() -> {
                while (running.get() && committedAt.size() < SUMMARIES) {
                    List<Map<String, Object>> rows = DB.admin()
                            .sql("SELECT acct_id, approved_auth_cnt FROM pauth.auth_summary").query().listOfRows();
                    long now = System.nanoTime();
                    for (Map<String, Object> row : rows) {
                        if (((Number) row.get("approved_auth_cnt")).intValue() == 1) {
                            committedAt.putIfAbsent(Integer.parseInt((String) row.get("acct_id")), now);
                        }
                    }
                    Thread.sleep(5);
                }
                return null;
            });
            List<Future<Long>> waits = new ArrayList<>();
            for (Probe p : probes) {
                waits.add(pool.submit(() -> {
                    long max = 0;
                    while (running.get()) {
                        max = Math.max(max, lockWait(acct(p.summary())));
                        Thread.sleep(20);
                    }
                    return max;
                }));
            }
            Thread.sleep(200);
            long launched = System.nanoTime();
            JobExecution execution;
            try {
                execution = jobOperator.start(purgePendingAuthorizationsJob, parameters(BUSINESS, 5, interval));
                watcher.get(30, TimeUnit.SECONDS);
            } finally {
                running.set(false);
            }

            assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
            StepExecution step = step(execution, PurgePendingAuthorizationsJobConfiguration.PURGE_STEP);
            assertThat(step.getExecutionContext().getLong(PurgeSummaryWriter.DETAILS_DELETED))
                    .isEqualTo((long) SUMMARIES * perSummary);
            assertThat(committedAt).hasSize(SUMMARIES);
            List<Long> chunkMs = new ArrayList<>();
            long previous = launched;
            for (int c = 1; c <= chunks; c++) {
                int first = (c - 1) * interval + 1;
                long at = committedAt.get(first);
                for (int i = first; i <= Math.min(c * interval, SUMMARIES); i++) {
                    assertThat(committedAt.get(i)).as("summary %d commits with chunk %d", i, c).isEqualTo(at);
                }
                assertThat(at).as("chunk %d after the one before", c).isGreaterThan(previous);
                chunkMs.add((at - previous) / 1_000_000);
                previous = at;
            }
            List<String> waitText = new ArrayList<>();
            long maxWait = 0;
            for (int i = 0; i < probes.size(); i++) {
                long w = waits.get(i).get(60, TimeUnit.SECONDS) / 1_000_000;
                maxWait = Math.max(maxWait, w);
                waitText.add(probes.get(i).label() + " " + w);
            }
            long stepMs = Duration.between(step.getStartTime(), step.getEndTime()).toMillis();
            return perSummary + " | " + interval + " | " + chunks + " | " + (long) perSummary * interval + " | "
                    + stepMs + " | " + String.join(", ", chunkMs.stream().map(String::valueOf).toList()) + " | "
                    + chunkMs.stream().mapToLong(Long::longValue).max().orElse(0) + " | "
                    + String.join(", ", waitText) + " | " + maxWait;
        } finally {
            running.set(false);
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /** One listener-style request, rolled back; returns how long it waited for the summary lock. */
    private static long lockWait(String acctId) throws SQLException {
        try (Connection c = DB.dataSource(ServiceLogin.AUTHORIZATION).getConnection()) {
            c.setAutoCommit(false);
            try {
                long start = System.nanoTime();
                try (PreparedStatement s = c.prepareStatement("SELECT credit_balance FROM pauth.auth_summary"
                        + " WITH (UPDLOCK, HOLDLOCK) WHERE acct_id = CAST(? AS CHAR(11))")) {
                    s.setString(1, acctId);
                    s.executeQuery().close();
                }
                long waited = System.nanoTime() - start;
                try (PreparedStatement s = c.prepareStatement("""
                        INSERT INTO pauth.auth_detail (acct_id, processed_at, auth_date_9c, auth_time_9c, orig_date,
                            orig_time, card_num, auth_type, card_expiry_date, message_type, message_source, auth_id_code,
                            auth_resp_code, auth_resp_reason, processing_code, transaction_amt, approved_amt,
                            merchant_category_code, acqr_country_code, pos_entry_mode, merchant_id, merchant_name,
                            merchant_city, merchant_state, merchant_zip, transaction_id, match_status)
                        VALUES (CAST(? AS CHAR(11)), ?, 0, ?, '240102', '120000', ?, 'AUTH', '0130', 'MSGREQ',
                            'POS   ', '000001', '00', '0000', '000000', 10.00, 10.00, '5411', '840', '01',
                            'MERCHANT0000001', 'PROBE', 'TOWN', 'NY', '10001', 'TRAN00000000003', 'P')""")) {
                    s.setString(1, acctId);
                    s.setTimestamp(2, Timestamp.valueOf(FRESH));
                    s.setInt(3, PauthFixtures.nextKey());
                    s.setString(4, PauthFixtures.CARD);
                    s.executeUpdate();
                }
                try (PreparedStatement s = c.prepareStatement("""
                        UPDATE pauth.auth_summary SET credit_balance = credit_balance + 10.00,
                            approved_auth_cnt = approved_auth_cnt + 1, approved_auth_amt = approved_auth_amt + 10.00
                        WHERE acct_id = CAST(? AS CHAR(11))""")) {
                    s.setString(1, acctId);
                    s.executeUpdate();
                }
                return waited;
            } finally {
                c.rollback();
            }
        }
    }
}
