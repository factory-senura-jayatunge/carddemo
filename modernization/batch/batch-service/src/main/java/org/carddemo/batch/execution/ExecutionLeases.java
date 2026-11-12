package org.carddemo.batch.execution;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The SQL of batch.execution_lease. Every statement compares the database clock only,
 * and the renewal, the fence and the recovery share one recoverable predicate.
 */
public class ExecutionLeases {

    /**
     * Not revoked and not renewed for the owner's TTL + grace, as stored with the lease, so the
     * owner's fence, any launch's recovery and batch.v_report_run_stalled agree whatever the
     * settings of the process that evaluates it.
     */
    static final String RECOVERABLE =
            "l.revoked_at IS NULL AND l.renewed_at < DATEADD(MILLISECOND, -l.recoverable_after_ms, SYSUTCDATETIME())";

    public static final String RUNNING_STATUSES = "('STARTING', 'STARTED', 'STOPPING')";

    /**
     * The fixed limit for an execution without a lease row (contract 4.4): no owner recorded its
     * settings, so the reader's are not used. batch.v_report_run_stalled uses the same value.
     */
    static final int LEASELESS_RECOVERABLE_MS = 120_000;

    /**
     * The recovery rule for an execution {@code e} (BATCH_JOB_EXECUTION) LEFT JOINed to its lease
     * {@code l}: the lease's predicate, or for an execution without a lease row its LAST_UPDATED
     * against the fixed limit. The caller adds the running-status condition.
     */
    public static final String RECOVERABLE_EXECUTION = "((l.job_execution_id IS NOT NULL AND " + RECOVERABLE
            + ") OR (l.job_execution_id IS NULL AND e.LAST_UPDATED < DATEADD(MILLISECOND, -" + LEASELESS_RECOVERABLE_MS
            + ", SYSUTCDATETIME())))";

    private final JdbcClient db;
    private final LeaseOwner owner;
    private final LeaseSettings settings;

    public ExecutionLeases(JdbcClient db, LeaseOwner owner, LeaseSettings settings) {
        this.db = db;
        this.owner = owner;
        this.settings = settings;
    }

    public LeaseOwner owner() {
        return owner;
    }

    public LeaseSettings settings() {
        return settings;
    }

    long recoverableMs() {
        return settings.recoverableAfter().toMillis();
    }

    /** Takes the lease of a new execution; runs in the transaction that creates the execution. */
    void acquire(long executionId) {
        db.sql("""
                INSERT INTO batch.execution_lease (job_execution_id, owner_id, owner_host, owner_pid,
                    recoverable_after_ms)
                VALUES (:id, :owner, :host, :pid, :recoverableMs)""")
                .param("id", executionId).param("owner", owner.id().toString()).param("host", owner.host())
                .param("pid", owner.pid()).param("recoverableMs", recoverableMs()).update();
    }

    /**
     * The renewal, which is also the fence: true when this process still owns the lease.
     * Inside a transaction it keeps the row's exclusive lock until the commit, so no
     * revocation can fall between the fence and the commit.
     */
    public boolean renew(long executionId) {
        return db.sql("""
                UPDATE l SET renewed_at = SYSUTCDATETIME()
                FROM batch.execution_lease AS l
                WHERE l.job_execution_id = :id AND l.owner_id = :owner
                  AND l.revoked_at IS NULL AND NOT (%s)""".formatted(RECOVERABLE))
                .param("id", executionId).param("owner", owner.id().toString()).update() == 1;
    }

    /** Whether the lease row still names this process and is not revoked; takes no lock. */
    public boolean held(long executionId) {
        return db.sql("""
                SELECT COUNT(*) FROM batch.execution_lease AS l
                WHERE l.job_execution_id = :id AND l.owner_id = :owner AND l.revoked_at IS NULL""")
                .param("id", executionId).param("owner", owner.id().toString()).query(Integer.class).single() == 1;
    }

    /** Whether a launch may recover the execution now: it is running and its owner is lost. */
    public boolean recoverable(long executionId) {
        return db.sql("""
                SELECT COUNT(*) FROM batch.BATCH_JOB_EXECUTION AS e
                LEFT JOIN batch.execution_lease AS l ON l.job_execution_id = e.JOB_EXECUTION_ID
                WHERE e.JOB_EXECUTION_ID = :id AND e.STATUS IN %s AND %s"""
                        .formatted(RUNNING_STATUSES, RECOVERABLE_EXECUTION))
                .param("id", executionId).query(Integer.class).single() == 1;
    }

    public LocalDateTime databaseTime() {
        return db.sql("SELECT CAST(SYSUTCDATETIME() AS DATETIME2(3))").query(LocalDateTime.class).single();
    }

    /** The lease as the database clock sees it; empty when the execution has no lease row. */
    public Optional<LeaseView> view(long executionId) {
        return db.sql("""
                SELECT l.owner_host, l.owner_pid, l.renewed_at, l.revoked_at, l.revoked_reason,
                       DATEADD(MILLISECOND, :ttlMs, l.renewed_at) AS expires_at,
                       DATEADD(MILLISECOND, l.recoverable_after_ms, l.renewed_at) AS recoverable_at,
                       CAST(SYSUTCDATETIME() AS DATETIME2(3)) AS db_time,
                       CASE WHEN %s THEN 1 ELSE 0 END AS recoverable
                FROM batch.execution_lease AS l
                WHERE l.job_execution_id = :id""".formatted(RECOVERABLE))
                .param("id", executionId).param("ttlMs", settings.ttl().toMillis())
                .query((rs, n) -> {
                    LocalDateTime revokedAt = rs.getObject("revoked_at", LocalDateTime.class);
                    LocalDateTime renewedAt = rs.getObject("renewed_at", LocalDateTime.class);
                    LocalDateTime expiresAt = rs.getObject("expires_at", LocalDateTime.class);
                    LocalDateTime dbTime = rs.getObject("db_time", LocalDateTime.class);
                    LeaseView.State state = revokedAt != null ? LeaseView.State.REVOKED
                            : expiresAt.isBefore(dbTime) ? LeaseView.State.EXPIRED : LeaseView.State.LIVE;
                    return new LeaseView(state, rs.getString("owner_host"), rs.getInt("owner_pid"), renewedAt,
                            expiresAt, rs.getObject("recoverable_at", LocalDateTime.class), rs.getInt("recoverable") == 1,
                            revokedAt, rs.getString("revoked_reason"), dbTime);
                })
                .optional();
    }
}
