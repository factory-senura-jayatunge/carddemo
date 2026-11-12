package org.carddemo.batch.execution;

import java.time.LocalDateTime;

/**
 * A lease as the database clock sees it (the poll's {@code lease} member). Times are UTC
 * database times; {@code recoverable} is the recovery predicate, and an execution that is
 * already terminal is never recovered whatever it says.
 */
public record LeaseView(State state, String ownerHost, int ownerPid, LocalDateTime renewedAt,
        LocalDateTime expiresAt, LocalDateTime recoverableAt, boolean recoverable, LocalDateTime revokedAt,
        String revokedReason, LocalDateTime dbTime) {

    public enum State { LIVE, EXPIRED, REVOKED, NONE }
}
