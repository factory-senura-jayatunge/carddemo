package org.carddemo.identity.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.carddemo.identity.IdentityProperties;
import org.carddemo.identity.audit.AuditLog;
import org.carddemo.identity.audit.AuditLog.EventType;
import org.carddemo.identity.fidelity.IdentityFidelity;
import org.carddemo.identity.session.SessionRepository.EndReason;
import org.carddemo.identity.session.SessionRepository.SessionRow;
import org.carddemo.identity.signon.AuthFailure;
import org.carddemo.identity.token.AccessTokens;
import org.carddemo.identity.token.RefreshTokens;
import org.carddemo.identity.user.AppUser;
import org.carddemo.identity.user.Roles;
import org.carddemo.identity.user.UserRepository;
import org.carddemo.platform.web.SafeTransactionTemplate;
import org.springframework.stereotype.Service;

/**
 * Sessions (D-ARCH-11, D-05): a refresh token is used once; a refresh is refused after 15 idle
 * minutes or 8 hours in all; presenting the token a refresh replaced ends the session.
 */
@Service
public class SessionService {

    private final SessionRepository sessions;
    private final UserRepository users;
    private final AuditLog audit;
    private final AccessTokens tokens;
    private final IdentityFidelity fidelity;
    private final IdentityProperties props;
    private final Clock clock;
    private final SafeTransactionTemplate tx;

    public SessionService(SessionRepository sessions, UserRepository users, AuditLog audit, AccessTokens tokens,
            IdentityFidelity fidelity, IdentityProperties props, Clock clock, SafeTransactionTemplate tx) {
        this.sessions = sessions;
        this.users = users;
        this.audit = audit;
        this.tokens = tokens;
        this.fidelity = fidelity;
        this.props = props;
        this.clock = clock;
        this.tx = tx;
    }

    /**
     * Step 10 of the sign-on rules: resets the failure count, records the sign-on, opens a
     * session and audits SIGN_ON_OK. Runs in the caller's transaction.
     */
    public StartedSession start(AppUser user, String role, String clientIp) {
        Instant now = now();
        UUID sessionId = UUID.randomUUID();
        String refresh = RefreshTokens.generate();
        users.recordSignOn(user.userId(), utc(now));
        sessions.insert(sessionId, user.userId(), utc(now), utc(now.plus(props.sessionMaxAge())),
                RefreshTokens.hash(refresh));
        audit.record(EventType.SIGN_ON_OK, user.userId(), user.userId(), null, clientIp);
        AccessTokens.Issued access = tokens.issue(user.userId(), user.userType(), role, sessionId, now);
        return new StartedSession(user, role, sessionId, access.value(), props.accessTokenTtl(), refresh,
                props.sessionMaxAge());
    }

    /** Uses the presented refresh token and issues the next pair, or ends the session. */
    public StartedSession refresh(String presented, String clientIp) {
        if (presented == null || presented.isBlank()) {
            throw AuthFailure.sessionExpired();
        }
        byte[] hash = RefreshTokens.hash(presented);
        Instant now = now();
        Optional<SessionRow> found = sessions.findByRefreshHash(hash);
        if (found.isEmpty()) {
            sessions.findByPreviousRefreshHash(hash)
                    .filter(SessionRow::active)
                    .ifPresent(s -> endWithAudit(s, EndReason.REUSE_DETECTED, EventType.REFRESH_REUSE, clientIp));
            throw AuthFailure.sessionExpired();
        }
        SessionRow s = found.get();
        if (!s.active()) {
            throw AuthFailure.sessionExpired();
        }
        if (!utc(now).isBefore(s.absoluteExpiryAt())) {
            endWithAudit(s, EndReason.EXPIRED, EventType.SESSION_EXPIRED, clientIp);
            throw AuthFailure.sessionExpired();
        }
        if (Duration.between(s.lastActivityAt(), utc(now)).compareTo(props.idleTimeout()) > 0) {
            endWithAudit(s, EndReason.IDLE, EventType.SESSION_EXPIRED, clientIp);
            throw AuthFailure.sessionExpired();
        }
        Optional<AppUser> user = users.find(s.userId());
        Optional<String> role = user.flatMap(u -> Roles.forType(u.userType(), fidelity.onlyKnownUserTypes()));
        if (role.isEmpty()) {
            endWithAudit(s, EndReason.USER_REFUSED, EventType.SESSION_EXPIRED, clientIp);
            throw AuthFailure.sessionExpired();
        }
        String next = RefreshTokens.generate();
        if (!sessions.rotate(s.sessionId(), hash, RefreshTokens.hash(next), utc(now))) {
            // Another request used the same token first: the same token was presented twice.
            endWithAudit(s, EndReason.REUSE_DETECTED, EventType.REFRESH_REUSE, clientIp);
            throw AuthFailure.sessionExpired();
        }
        AppUser u = user.get();
        AccessTokens.Issued access = tokens.issue(u.userId(), u.userType(), role.get(), s.sessionId(), now);
        Duration left = Duration.between(utc(now), s.absoluteExpiryAt());
        return new StartedSession(u, role.get(), s.sessionId(), access.value(), props.accessTokenTtl(), next, left);
    }

    /**
     * FLOW-SGN-003: ends the session named by the access token, else the one of the refresh
     * token. Always succeeds; there may be nothing to end.
     */
    public void signOff(Optional<UUID> sessionId, String refreshToken, String clientIp) {
        Optional<SessionRow> s = sessionId.flatMap(sessions::find);
        if (s.isEmpty() && refreshToken != null && !refreshToken.isBlank()) {
            s = sessions.findByRefreshHash(RefreshTokens.hash(refreshToken));
        }
        s.filter(SessionRow::active).ifPresent(row -> tx.executeWithoutResult(t -> {
            if (sessions.end(row.sessionId(), utc(now()), EndReason.SIGN_OFF)) {
                audit.record(EventType.SIGN_OFF, row.userId(), row.userId(), null, clientIp);
            }
        }));
    }

    /** Ends a session because its user changed the password. Runs in the caller's transaction. */
    public void endForPasswordChange(UUID sessionId) {
        sessions.end(sessionId, utc(now()), EndReason.PASSWORD_CHANGE);
    }

    public Optional<SessionRow> active(UUID sessionId) {
        return sessions.find(sessionId).filter(SessionRow::active);
    }

    private void endWithAudit(SessionRow s, EndReason reason, EventType event, String clientIp) {
        tx.executeWithoutResult(t -> {
            if (sessions.end(s.sessionId(), utc(now()), reason)) {
                audit.record(event, null, s.userId(), reason.name(), clientIp);
            }
        });
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static LocalDateTime utc(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneOffset.UTC);
    }
}
