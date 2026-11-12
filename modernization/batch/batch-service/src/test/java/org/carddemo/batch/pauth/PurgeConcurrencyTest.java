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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The purge beside other sessions. A gate session holds the lock of one summary, so the purge
 * stops inside a chunk; a read under {@code READ_COMMITTED_SNAPSHOT} then shows what that chunk
 * has committed, and a listener-style request (as the authorization login: lock the summary, add a
 * detail, update the totals) shows how long a request waits. Blocking is detected through
 * {@code sys.dm_exec_requests}, never through sleeps.
 *
 * <p>The heavy-fixture test measures how long a chunk holds its locks at the default interval
 * and writes the numbers to {@code target/pauth-purge-measurements.txt}. It asserts no bound:
 * no requirement sets an authorization latency during the purge (authorization-service.md
 * section 3); the reply TTL of 5000 ms is the comparison point.
 */
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class PurgeConcurrencyTest extends PurgeTestBase {

    private static final Logger log = LoggerFactory.getLogger(PurgeConcurrencyTest.class);
    static final LocalDate BUSINESS = LocalDate.of(2024, 1, 2);
    static final LocalDateTime EXPIRED = LocalDateTime.of(2023, 12, 20, 10, 0);
    static final LocalDateTime FRESH = LocalDateTime.of(2024, 1, 1, 10, 0);
    static final Path MEASUREMENTS = Path.of("target", "pauth-purge-measurements.txt");
    static final Path LOCK_DIAGNOSTIC = Path.of("target", "pauth-key-type-locks.txt");

    private ExecutorService pool;

    @BeforeEach
    void threads() {
        pool = Executors.newFixedThreadPool(6);
    }

    @AfterEach
    void stop() {
        pool.shutdownNow();
    }

    // ---- sessions -------------------------------------------------------------------------

    /** An admin transaction that holds the update lock of one summary until closed. */
    static final class Gate implements AutoCloseable {
        private final Connection connection;
        final int spid;

        Gate(String acctId) throws SQLException {
            this(acctId, true);
        }

        /**
         * @param typed whether the key is compared as {@code CHAR(11)}. The driver sends strings as
         *        NVARCHAR; compared unconverted with the CHAR key, the seek becomes a range, and
         *        HOLDLOCK keeps range locks on the neighbouring summaries too.
         */
        Gate(String acctId, boolean typed) throws SQLException {
            connection = DB.adminDataSource().getConnection();
            connection.setAutoCommit(false);
            spid = spid(connection);
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT acct_id FROM pauth.auth_summary WITH (UPDLOCK, HOLDLOCK) WHERE acct_id = "
                            + (typed ? "CAST(? AS CHAR(11))" : "?"))) {
                s.setString(1, acctId);
                s.executeQuery().close();
            }
        }

        @Override
        public void close() throws SQLException {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    /** An admin transaction that holds the delete of one expired detail of an account until closed. */
    static final class DetailHolder implements AutoCloseable {
        private final Connection connection;
        final int spid;

        DetailHolder(String acctId) throws SQLException {
            connection = DB.adminDataSource().getConnection();
            connection.setAutoCommit(false);
            spid = spid(connection);
            try (PreparedStatement s = connection.prepareStatement(
                    "DELETE TOP (1) FROM pauth.auth_detail WHERE acct_id = CAST(? AS CHAR(11)) AND processed_at < ?")) {
                s.setString(1, acctId);
                s.setTimestamp(2, Timestamp.valueOf(FRESH));
                assertThat(s.executeUpdate()).isEqualTo(1);
            }
        }

        @Override
        public void close() throws SQLException {
            try {
                connection.rollback();
            } finally {
                connection.close();
            }
        }
    }

    /** What a listener-style request saw: whether its summary existed when it got the lock, and its times. */
    record Request(boolean summaryFound, BigDecimal balanceSeen, long lockWaitNanos, long totalNanos) {
    }

    /**
     * One authorization as the listener writes it, as authorization_svc: lock the summary, create
     * it with zero totals when it is gone, insert an approved detail of 10.00 and add it to the totals.
     */
    static final class ListenerRequest implements Callable<Request> {
        private final String acctId;
        final AtomicInteger spid = new AtomicInteger();

        ListenerRequest(String acctId) {
            this.acctId = acctId;
        }

        @Override
        public Request call() throws SQLException {
            try (Connection c = DB.dataSource(ServiceLogin.AUTHORIZATION).getConnection()) {
                c.setAutoCommit(false);
                spid.set(spid(c));
                long start = System.nanoTime();
                boolean found;
                BigDecimal balance = null;
                try (PreparedStatement s = c.prepareStatement(
                        "SELECT credit_balance FROM pauth.auth_summary WITH (UPDLOCK, HOLDLOCK) WHERE acct_id = CAST(? AS CHAR(11))")) {
                    s.setString(1, acctId);
                    try (ResultSet rs = s.executeQuery()) {
                        found = rs.next();
                        if (found) {
                            balance = rs.getBigDecimal(1);
                        }
                    }
                }
                long locked = System.nanoTime();
                if (!found) {
                    try (PreparedStatement s = c.prepareStatement("""
                            INSERT INTO pauth.auth_summary (acct_id, cust_id, credit_limit, cash_limit, credit_balance,
                                cash_balance, approved_auth_cnt, declined_auth_cnt, approved_auth_amt, declined_auth_amt)
                            VALUES (?, '000000001', 5000.00, 1000.00, 0, 0, 0, 0, 0, 0)""")) {
                        s.setString(1, acctId);
                        s.executeUpdate();
                    }
                }
                try (PreparedStatement s = c.prepareStatement("""
                        INSERT INTO pauth.auth_detail (acct_id, processed_at, auth_date_9c, auth_time_9c, orig_date,
                            orig_time, card_num, auth_type, card_expiry_date, message_type, message_source, auth_id_code,
                            auth_resp_code, auth_resp_reason, processing_code, transaction_amt, approved_amt,
                            merchant_category_code, acqr_country_code, pos_entry_mode, merchant_id, merchant_name,
                            merchant_city, merchant_state, merchant_zip, transaction_id, match_status)
                        VALUES (?, ?, 0, ?, '240102', '120000', ?, 'AUTH', '0130', 'MSGREQ', 'POS   ', '000001', '00',
                            '0000', '000000', 10.00, 10.00, '5411', '840', '01', 'MERCHANT0000001', 'LISTENER', 'TOWN',
                            'NY', '10001', 'TRAN00000000002', 'P')""")) {
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
                c.commit();
                return new Request(found, balance, locked - start, System.nanoTime() - start);
            }
        }
    }

    static int spid(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT @@SPID"); ResultSet rs = s.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static int blockedBy(int spid) {
        return DB.admin().sql("SELECT COUNT(*) FROM sys.dm_exec_requests WHERE blocking_session_id = :s")
                .param("s", spid).query(Integer.class).single();
    }

    static boolean isBlocked(int spid) {
        return DB.admin().sql("SELECT COUNT(*) FROM sys.dm_exec_requests WHERE session_id = :s AND blocking_session_id <> 0")
                .param("s", spid).query(Integer.class).single() > 0;
    }

    static int blockedInThisDatabase() {
        return DB.admin().sql("SELECT COUNT(*) FROM sys.dm_exec_requests WHERE blocking_session_id <> 0 AND database_id = DB_ID()")
                .query(Integer.class).single();
    }

    /** The table and lock mode that the request blocked by {@code spid} waits for, e.g. {@code auth_summary U}. */
    static String waitOf(int spid) {
        return DB.admin().sql("""
                SELECT CONCAT(OBJECT_NAME(p.object_id), ' ', l.request_mode)
                FROM sys.dm_tran_locks AS l
                JOIN sys.dm_exec_requests AS r ON r.session_id = l.request_session_id
                JOIN sys.partitions AS p ON p.hobt_id = l.resource_associated_entity_id
                WHERE r.blocking_session_id = :s AND l.request_status = 'WAIT' AND l.resource_database_id = DB_ID()""")
                .param("s", spid).query(String.class).single();
    }

    static void await(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting until " + what);
            }
            Thread.sleep(20);
        }
    }

    private CompletableFuture<JobExecution> startPurge(JobParameters parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return jobOperator.start(purgePendingAuthorizationsJob, parameters);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, pool);
    }

    private static JobExecution completed(CompletableFuture<JobExecution> run) throws Exception {
        JobExecution execution = run.get(120, TimeUnit.SECONDS);
        assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
        return execution;
    }

    /** n summaries whose details are all expired: two approvals of 10.00 each, totals to match. */
    private void expiredSummaries(int first, int n) {
        for (int i = first; i < first + n; i++) {
            pauth.summary(acct(i), "20.00", 2, "20.00", 0, "0.00");
            pauth.approvals(acct(i), 2, EXPIRED, "10.00");
        }
    }

    // ---- commit interval ------------------------------------------------------------------

    private void assertCommittedWhileHeldAt(int commitInterval, int gateAt, int committedThrough) throws Exception {
        expiredSummaries(1, 7);
        CompletableFuture<JobExecution> run;
        try (Gate gate = new Gate(acct(gateAt))) {
            run = startPurge(parameters(BUSINESS, 5, commitInterval));
            await("the purge waits for the gate", Duration.ofSeconds(30), () -> blockedBy(gate.spid) == 1);
            for (int i = 1; i <= 7; i++) {
                assertThat(pauth.summaryExists(acct(i))).as("interval %d, summary %d while the purge waits at %d",
                        commitInterval, i, gateAt).isEqualTo(i > committedThrough);
            }
        }
        JobExecution execution = completed(run);
        assertThat(pauth.count("pauth.auth_summary")).isZero();
        assertThat(pauth.count("pauth.auth_detail")).isZero();
        assertThat(totals(execution).getLong(PurgeSummaryWriter.SUMMARIES_DELETED)).isEqualTo(7);
    }

    @Test
    @SpecItem("BR-AUPG-005")
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void withCommitIntervalOneEverySummaryBeforeTheHeldOneIsCommitted() throws Exception {
        assertCommittedWhileHeldAt(1, 5, 4);
    }

    @Test
    @SpecItem("BR-AUPG-005")
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void withCommitIntervalThreeOnlyWholeChunksAreCommitted() throws Exception {
        // Chunks 1-3, 4-6, 7: held at 5, summary 4 is purged but not committed.
        assertCommittedWhileHeldAt(3, 5, 3);
    }

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aKeyComparedAsNvarcharAlsoHoldsNeighbouringSummariesAgainstThePurge() throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("# a session holds summary 5 WITH (UPDLOCK, HOLDLOCK); the purge runs with commitInterval 1");
        lines.add("# summaries are synthetic indexes 1-7 in key order; 'past 7' is the end of the key range");
        lines.add("key compared as | key locks the holder keeps | summary the purge waits at | summaries committed while it waits");
        int typedAt = purgeWaitsAt(true, lines);
        int untypedAt = purgeWaitsAt(false, lines);
        Files.createDirectories(LOCK_DIAGNOSTIC.getParent());
        Files.write(LOCK_DIAGNOSTIC, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        assertThat(typedAt).as("CHAR(11): only summary 5 is held").isEqualTo(5);
        assertThat(untypedAt).as("NVARCHAR: the summary before 5 is held too").isEqualTo(4);
    }

    /** The summary at which the purge waits for a holder of summary 5, with what the holder keeps locked. */
    private int purgeWaitsAt(boolean typed, List<String> lines) throws Exception {
        DB.fixtures().deleteAll();
        expiredSummaries(1, 7);
        Map<String, String> byLockResource = new HashMap<>();
        for (int i = 1; i <= 7; i++) {
            byLockResource.put(DB.admin().sql("SELECT %%lockres%% FROM pauth.auth_summary WITH (INDEX(1)) WHERE acct_id = :a")
                    .param("a", acct(i)).query(String.class).single().trim(), String.valueOf(i));
        }
        int waitsAt;
        CompletableFuture<JobExecution> run;
        try (Gate gate = new Gate(acct(5), typed)) {
            List<String> held = DB.admin().sql("""
                    SELECT RTRIM(resource_description) AS res, request_mode AS mode FROM sys.dm_tran_locks
                    WHERE request_session_id = :s AND resource_type = 'KEY'""")
                    .param("s", gate.spid).query((rs, n) -> byLockResource.getOrDefault(rs.getString("res"), "past 7")
                            + " " + rs.getString("mode")).list();
            run = startPurge(parameters(BUSINESS, 5, 1));
            await("the purge waits for the holder", Duration.ofSeconds(30), () -> blockedBy(gate.spid) == 1);
            String resource = DB.admin().sql("SELECT wait_resource FROM sys.dm_exec_requests WHERE blocking_session_id = :s")
                    .param("s", gate.spid).query(String.class).single();
            String hash = resource.substring(resource.indexOf('('));
            waitsAt = Integer.parseInt(byLockResource.getOrDefault(hash, "0"));
            List<String> committed = new ArrayList<>();
            for (int i = 1; i <= 7; i++) {
                if (!pauth.summaryExists(acct(i))) {
                    committed.add(String.valueOf(i));
                }
            }
            lines.add((typed ? "CHAR(11)" : "NVARCHAR (driver default)") + " | "
                    + String.join(", ", held.stream().sorted().toList()) + " | " + waitsAt + " | "
                    + String.join(", ", committed));
        }
        completed(run);
        assertThat(pauth.count("pauth.auth_summary")).isZero();
        return waitsAt;
    }

    // ---- lock scope -----------------------------------------------------------------------

    /**
     * Another session's uncommitted delete of a detail of the last account holds up the purge only
     * when it reaches that account: the purge reads and deletes the details of one account through
     * its index, never by scanning the rows of the others. The last account keeps a fresh detail,
     * inserted first so that it also has the lowest 9c key, as the newest detail has in real data
     * (nine's complement). It is then summary 9's first key both in IX_auth_detail_acct_newest and
     * in UQ_auth_detail_9c, where the summary delete took range locks while the key still cascaded
     * (before V507): the held row is not the key next to summary 8's details in either index.
     */
    @Test
    @SpecItem("BR-AUPG-003")
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aPurgeLocksOnlyTheDetailsOfTheSummaryItPurges() throws Exception {
        expiredSummaries(1, 8);
        pauth.summary(acct(9), "30.00", 3, "30.00", 0, "0.00");
        pauth.approvals(acct(9), 1, FRESH, "10.00");
        pauth.approvals(acct(9), 2, EXPIRED, "10.00");
        CompletableFuture<JobExecution> run;
        try (DetailHolder holder = new DetailHolder(acct(9))) {
            run = startPurge(parameters(BUSINESS, 5, 1));
            await("the purge waits for the holder", Duration.ofSeconds(30), () -> blockedBy(holder.spid) == 1);
            for (int i = 1; i <= 8; i++) {
                assertThat(pauth.summaryExists(acct(i)))
                        .as("summary %d is purged before the purge reaches the held detail of summary 9", i).isFalse();
            }
        }
        JobExecution execution = completed(run);
        assertThat(pauth.count("pauth.auth_summary")).isOne();
        assertThat(pauth.details(acct(9))).isOne();
        assertThat(totals(execution).getLong(PurgeSummaryWriter.SUMMARIES_DELETED)).isEqualTo(8);
        assertThat(totals(execution).getLong(PurgeSummaryWriter.DETAILS_DELETED)).isEqualTo(18);
    }

    /**
     * Another session's uncommitted delete of the next account's first detail does not hold up the
     * summary before it. Account 3's only detail has the lowest 9c key, so it is the key that follows
     * account 2's details in UQ_auth_detail_9c. The summary delete has no cascade (V507) and reads no
     * detail; a cascading delete seeks account 2's range in UQ_auth_detail_9c again and waits for the
     * range lock on that next key, with summary 2 deleted but not committed.
     */
    @Test
    @SpecItem("BR-AUPG-003")
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void theHeldFirstDetailOfTheNextAccountDoesNotHoldUpTheSummaryBefore() throws Exception {
        expiredSummaries(1, 2);
        pauth.summary(acct(3), "10.00", 1, "10.00", 0, "0.00");
        pauth.lowestKeyApproval(acct(3), EXPIRED, "10.00");
        CompletableFuture<JobExecution> run;
        try (DetailHolder holder = new DetailHolder(acct(3))) {
            run = startPurge(parameters(BUSINESS, 5, 1));
            await("the purge waits for the holder", Duration.ofSeconds(30), () -> blockedBy(holder.spid) == 1);
            String waits = waitOf(holder.spid);
            for (int i = 1; i <= 2; i++) {
                assertThat(pauth.summaryExists(acct(i)))
                        .as("summary %d is committed before the purge reaches summary 3 (the purge waits for %s)", i, waits)
                        .isFalse();
            }
        }
        JobExecution execution = completed(run);
        assertThat(pauth.count("pauth.auth_summary")).isZero();
        assertThat(totals(execution).getLong(PurgeSummaryWriter.SUMMARIES_DELETED)).isEqualTo(3);
        assertThat(totals(execution).getLong(PurgeSummaryWriter.DETAILS_DELETED)).isEqualTo(5);
    }

    /**
     * A listener request on the next account inside the chunk. The chunk of summaries 1 and 2 waits
     * at a gate on summary 1; meanwhile the request locks summary 2 and inserts its newest detail,
     * which has the account's lowest 9c key and so follows account 1's details in
     * UQ_auth_detail_9c. Released, the chunk deletes summary 1 without reading a detail (no cascade,
     * V507) and waits for the request only at summary 2's own lock. A cascading delete would wait
     * for the range lock on the request's new key instead. Neither session fails (no 1205), the
     * request's detail is kept and the purge deletes summary 1 alone.
     */
    @Test
    @SpecItem("BR-AUPG-003")
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aListenerRequestOnTheNextAccountHoldsUpTheChunkOnlyAtItsOwnSummary() throws Exception {
        expiredSummaries(1, 1);
        pauth.summary(acct(2), "10.00", 1, "10.00", 0, "0.00");
        pauth.approvals(acct(2), 1, FRESH, "10.00");
        CompletableFuture<JobExecution> run;
        try (Connection c = DB.dataSource(ServiceLogin.AUTHORIZATION).getConnection()) {
            c.setAutoCommit(false);
            int listener = spid(c);
            try (Gate gate = new Gate(acct(1))) {
                run = startPurge(parameters(BUSINESS, 5, 2));
                await("the chunk waits at summary 1", Duration.ofSeconds(30), () -> blockedBy(gate.spid) == 1);
                try (PreparedStatement s = c.prepareStatement(
                        "SELECT credit_balance FROM pauth.auth_summary WITH (UPDLOCK, HOLDLOCK) WHERE acct_id = CAST(? AS CHAR(11))")) {
                    s.setString(1, acct(2));
                    s.executeQuery().close();
                }
                try (PreparedStatement s = c.prepareStatement("""
                        INSERT INTO pauth.auth_detail (acct_id, processed_at, auth_date_9c, auth_time_9c, orig_date,
                            orig_time, card_num, auth_type, card_expiry_date, message_type, message_source, auth_id_code,
                            auth_resp_code, auth_resp_reason, processing_code, transaction_amt, approved_amt,
                            merchant_category_code, acqr_country_code, pos_entry_mode, merchant_id, merchant_name,
                            merchant_city, merchant_state, merchant_zip, transaction_id, match_status)
                        VALUES (?, ?, 0, 0, '240102', '120000', ?, 'AUTH', '0130', 'MSGREQ', 'POS   ', '000001', '00',
                            '0000', '000000', 10.00, 10.00, '5411', '840', '01', 'MERCHANT0000001', 'LISTENER', 'TOWN',
                            'NY', '10001', 'TRAN00000000002', 'P')""")) {
                    s.setString(1, acct(2));
                    s.setTimestamp(2, Timestamp.valueOf(FRESH.plusHours(1)));
                    s.setString(3, PauthFixtures.CARD);
                    s.executeUpdate();
                }
            }
            await("the chunk waits for the request", Duration.ofSeconds(30), () -> blockedBy(listener) == 1);
            assertThat(waitOf(listener)).as("the chunk waits for summary 2's lock, not for a detail key range")
                    .isEqualTo("auth_summary U");
            try (PreparedStatement s = c.prepareStatement("""
                    UPDATE pauth.auth_summary SET credit_balance = credit_balance + 10.00,
                        approved_auth_cnt = approved_auth_cnt + 1, approved_auth_amt = approved_auth_amt + 10.00
                    WHERE acct_id = CAST(? AS CHAR(11))""")) {
                s.setString(1, acct(2));
                s.executeUpdate();
            }
            c.commit();
        }
        JobExecution execution = completed(run);

        assertThat(pauth.summaryExists(acct(1))).isFalse();
        Map<String, Object> row = pauth.summaryRow(acct(2));
        assertThat((BigDecimal) row.get("credit_balance")).isEqualByComparingTo("20.00");
        assertThat(row.get("approved_auth_cnt")).isEqualTo(2);
        assertThat(pauth.details(acct(2))).as("the request's detail is kept").isEqualTo(2);
        assertThat(totals(execution).getLong(PurgeSummaryWriter.SUMMARIES_DELETED)).isEqualTo(1);
    }

    // ---- listener -------------------------------------------------------------------------

    @Test
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aListenerRequestOnASummaryInAnOpenChunkWaitsForTheCommitAndThenSeesThePurge() throws Exception {
        expiredSummaries(1, 5);
        ListenerRequest request = new ListenerRequest(acct(1));
        CompletableFuture<JobExecution> run;
        Future<Request> answer;
        long heldNanos;
        try (Gate gate = new Gate(acct(4))) {
            run = startPurge(parameters(BUSINESS, 5, 10));
            await("the purge waits for the gate at summary 4", Duration.ofSeconds(30), () -> blockedBy(gate.spid) == 1);
            answer = pool.submit(request);
            await("the request waits for the purge's lock on summary 1", Duration.ofSeconds(30),
                    () -> request.spid.get() != 0 && isBlocked(request.spid.get()));
            assertThat(pauth.summaryExists(acct(1))).as("summary 1's delete is not committed yet").isTrue();
            long held = System.nanoTime();
            Thread.sleep(500);
            assertThat(answer.isDone()).isFalse();
            heldNanos = System.nanoTime() - held;
        }
        completed(run);
        Request r = answer.get(30, TimeUnit.SECONDS);

        assertThat(r.summaryFound()).as("the request reads the committed purge: summary 1 is gone").isFalse();
        assertThat(Duration.ofNanos(r.lockWaitNanos())).isGreaterThanOrEqualTo(Duration.ofNanos(heldNanos));
        assertThat(pauth.summaryRow(acct(1)).get("credit_balance")).as("a new summary holds only the new approval")
                .isEqualTo(new BigDecimal("10.00"));
        assertThat(pauth.details(acct(1))).isEqualTo(1);
        for (int i = 2; i <= 5; i++) {
            assertThat(pauth.summaryExists(acct(i))).isFalse();
        }
    }

    // ---- two purges -----------------------------------------------------------------------

    /** The fixture of the two-purge test with the expected end state, worked out by hand per summary. */
    private record Expected(String acctId, boolean kept, String creditBalance, int approvedCnt, long details) {
    }

    @Test
    @SpecItem({"BR-AUPG-003", "BR-AUPG-006"})
    @Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void twoPurgesOfDifferentJobInstancesTogetherLeaveWhatOneRunLeaves() throws Exception {
        // The first waits at summary 8 inside its chunk 7-9; the second waits on the first's lock.
        twoPurges(8, 3, 3);
    }

    @Test
    @SpecItem({"BR-AUPG-003", "BR-AUPG-006"})
    @Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void twoPurgesReleasedFromTheSameSummaryAtOnceLeaveWhatOneRunLeaves() throws Exception {
        // Both wait at summary 2, then race through the rest with different chunk sizes.
        twoPurges(2, 1, 2);
    }

    private void twoPurges(int gateAt, long firstInterval, long secondInterval) throws Exception {
        List<Expected> expected = new ArrayList<>();
        int expiredDetails = 0;
        long summariesDeleted = 0;
        BigDecimal credit = BigDecimal.ZERO;
        for (int i = 1; i <= 30; i++) {
            String a = acct(i);
            switch (i % 3) {
                case 0 -> {
                    // All expired: deleted, 30.00 released.
                    pauth.summary(a, "30.00", 3, "30.00", 0, "0.00");
                    pauth.approvals(a, 3, EXPIRED, "10.00");
                    expected.add(new Expected(a, false, null, 0, 0));
                    expiredDetails += 3;
                    summariesDeleted++;
                    credit = credit.add(new BigDecimal("30.00"));
                }
                case 1 -> {
                    // Two expired, one fresh: kept with 10.00.
                    pauth.summary(a, "30.00", 3, "30.00", 0, "0.00");
                    pauth.approvals(a, 2, EXPIRED, "10.00");
                    pauth.approvals(a, 1, FRESH, "10.00");
                    expected.add(new Expected(a, true, "10.00", 1, 1));
                    expiredDetails += 2;
                    credit = credit.add(new BigDecimal("20.00"));
                }
                default -> {
                    // One expired matched approval (no credit) and one flagged F: kept, credit unchanged.
                    pauth.summary(a, "10.00", 2, "20.00", 0, "0.00");
                    pauth.detail(a, EXPIRED, "00", "M", " ", "10.00", "10.00");
                    pauth.detail(a, EXPIRED, "00", "P", "F", "10.00", "10.00");
                    expected.add(new Expected(a, true, "10.00", 1, 1));
                    expiredDetails += 1;
                }
            }
        }

        CompletableFuture<JobExecution> first;
        CompletableFuture<JobExecution> second;
        try (Gate gate = new Gate(acct(gateAt))) {
            // The second launch starts once the first job runs: two launches creating their job
            // instances at the same instant can deadlock in the job repository, which is the
            // launcher's concern, not the purge's.
            first = startPurge(parameters(BUSINESS, 5, firstInterval));
            await("the first purge waits at the gate", Duration.ofSeconds(60), () -> blockedBy(gate.spid) == 1);
            second = startPurge(parameters(BUSINESS, 5, secondInterval));
            // At the same summary the second queues behind the first's request, so it reports the
            // first as its blocker.
            await("both purges wait", Duration.ofSeconds(60), () -> blockedInThisDatabase() >= 2);
        }
        JobExecution a = completed(first);
        JobExecution b = completed(second);

        for (Expected e : expected) {
            assertThat(pauth.summaryExists(e.acctId())).as(e.acctId()).isEqualTo(e.kept());
            if (e.kept()) {
                Map<String, Object> row = pauth.summaryRow(e.acctId());
                assertThat((BigDecimal) row.get("credit_balance")).as(e.acctId()).isEqualByComparingTo(e.creditBalance());
                assertThat(row.get("approved_auth_cnt")).as(e.acctId()).isEqualTo(e.approvedCnt());
                assertThat(pauth.details(e.acctId())).as(e.acctId()).isEqualTo(e.details());
            }
        }
        ExecutionContext ta = totals(a);
        ExecutionContext tb = totals(b);
        assertThat(ta.getLong(PurgeSummaryWriter.DETAILS_DELETED) + tb.getLong(PurgeSummaryWriter.DETAILS_DELETED))
                .as("every expired detail deleted once").isEqualTo(expiredDetails);
        assertThat(ta.getLong(PurgeSummaryWriter.SUMMARIES_DELETED) + tb.getLong(PurgeSummaryWriter.SUMMARIES_DELETED))
                .isEqualTo(summariesDeleted);
        assertThat(new BigDecimal(ta.getString(PurgeSummaryWriter.CREDIT_RELEASED))
                .add(new BigDecimal(tb.getString(PurgeSummaryWriter.CREDIT_RELEASED))))
                .as("credit released once").isEqualByComparingTo(credit);
        log.info("two purges (gate at {}, intervals {} and {}): run A deleted {} details and {} summaries, run B {} and {}",
                gateAt, firstInterval, secondInterval,
                ta.getLong(PurgeSummaryWriter.DETAILS_DELETED), ta.getLong(PurgeSummaryWriter.SUMMARIES_DELETED),
                tb.getLong(PurgeSummaryWriter.DETAILS_DELETED), tb.getLong(PurgeSummaryWriter.SUMMARIES_DELETED));
    }

    // ---- measurement ----------------------------------------------------------------------

    @Test
    @Timeout(value = 900, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void measureHowLongAChunkAtTheDefaultIntervalDelaysAListenerRequest() throws Exception {
        int summaries = 30;
        int interval = 5;
        List<String> lines = new ArrayList<>();
        lines.add("# purgePendingAuthorizationsJob, heavy fixture, commitInterval " + interval + " (the default), "
                + summaries + " summaries (" + summaries / interval + " chunks)");
        lines.add("# every summary keeps one fresh detail; probes are listener-style requests (lock, insert, update,"
                + " commit) looping on the first summary of each chunk while the purge runs");
        lines.add("expired details per summary | expired details per chunk | purge step ms | mean chunk ms"
                + " | max listener lock wait ms (one per chunk) | max listener request ms");
        for (int perSummary : new int[] {100, 1_000, 5_000}) {
            DB.fixtures().deleteAll();
            for (int i = 1; i <= summaries; i++) {
                pauth.summary(acct(i), "0.00", 0, "0.00", 0, "0.00");
                pauth.approvals(acct(i), perSummary, EXPIRED, "1.00");
                pauth.approvals(acct(i), 1, FRESH, "1.00");
            }
            AtomicBoolean running = new AtomicBoolean(true);
            List<Future<long[]>> probes = new ArrayList<>();
            for (int chunk = 0; chunk < summaries / interval; chunk++) {
                String probed = acct(chunk * interval + 1);
                probes.add(pool.submit(() -> {
                    long maxWait = 0;
                    long maxTotal = 0;
                    while (running.get()) {
                        Request r = new ListenerRequest(probed).call();
                        maxWait = Math.max(maxWait, r.lockWaitNanos());
                        maxTotal = Math.max(maxTotal, r.totalNanos());
                        Thread.sleep(2);
                    }
                    return new long[] {maxWait, maxTotal};
                }));
            }
            Thread.sleep(200);
            JobExecution execution;
            try {
                execution = jobOperator.start(purgePendingAuthorizationsJob, parameters(BUSINESS, 5, interval));
            } finally {
                running.set(false);
            }
            assertThat(execution.getStatus()).as(failures(execution)).isEqualTo(BatchStatus.COMPLETED);
            StepExecution step = step(execution, PurgePendingAuthorizationsJobConfiguration.PURGE_STEP);
            assertThat(step.getExecutionContext().getLong(PurgeSummaryWriter.DETAILS_DELETED))
                    .isEqualTo((long) summaries * perSummary);
            long stepMs = Duration.between(step.getStartTime(), step.getEndTime()).toMillis();
            List<String> waits = new ArrayList<>();
            long maxTotal = 0;
            for (Future<long[]> p : probes) {
                long[] m = p.get(60, TimeUnit.SECONDS);
                waits.add(String.valueOf(m[0] / 1_000_000));
                maxTotal = Math.max(maxTotal, m[1]);
            }
            String line = perSummary + " | " + perSummary * interval + " | " + stepMs + " | "
                    + stepMs / (summaries / interval) + " | " + String.join(", ", waits) + " | " + maxTotal / 1_000_000;
            lines.add(line);
            log.info("purge measurement: {}", line);
        }
        try {
            Files.createDirectories(MEASUREMENTS.getParent());
            Files.write(MEASUREMENTS, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("cannot write {}: {}", MEASUREMENTS, e.getMessage());
        }
    }
}
