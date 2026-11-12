package org.carddemo.identity.signon;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.carddemo.identity.IdentityProperties;
import org.carddemo.identity.audit.AuditLog;
import org.carddemo.identity.audit.AuditLog.EventType;
import org.carddemo.identity.fidelity.IdentityFidelity;
import org.carddemo.identity.session.SessionService;
import org.carddemo.identity.session.StartedSession;
import org.carddemo.identity.user.AppUser;
import org.carddemo.identity.user.NewPasswords;
import org.carddemo.identity.user.PasswordHasher;
import org.carddemo.identity.user.Roles;
import org.carddemo.identity.user.UserRepository;
import org.carddemo.platform.legacyformat.LegacyCase;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.SafeErrors;
import org.carddemo.platform.web.SafeTransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Sign-on (COSGN00C decision table 8.1 with the switched items) and the password change
 * (D-SEC-07). Failures are written outside any transaction, so the failure count and the audit
 * row survive the refusal. The batch window is not consulted.
 */
@Service
public class SignOnService {

    private static final Logger log = LoggerFactory.getLogger(SignOnService.class);

    private static final int MAX_USER_ID = 8;
    private static final int MAX_PASSWORD = 64;

    /** The session of a password change made while signed on. */
    public record SignedOn(String userId, UUID sessionId) {
    }

    private record Authenticated(AppUser user, String role, String preparedPassword) {
    }

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final SessionService sessions;
    private final AuditLog audit;
    private final AttemptThrottle throttle;
    private final IdentityFidelity fidelity;
    private final IdentityProperties props;
    private final Clock clock;
    private final SafeTransactionTemplate tx;

    public SignOnService(UserRepository users, PasswordHasher hasher, SessionService sessions, AuditLog audit,
            AttemptThrottle throttle, IdentityFidelity fidelity, IdentityProperties props, Clock clock,
            SafeTransactionTemplate tx) {
        this.users = users;
        this.hasher = hasher;
        this.sessions = sessions;
        this.audit = audit;
        this.throttle = throttle;
        this.fidelity = fidelity;
        this.props = props;
        this.clock = clock;
        this.tx = tx;
    }

    public StartedSession signOn(String rawUserId, String rawPassword, String clientIp) {
        Authenticated a = authenticate(rawUserId, rawPassword, "password", null, clientIp);
        if (fidelity.caseSensitivePasswords() && a.user().mustChangePassword()) {
            throw failed(a.user().userId(), "PASSWORD_CHANGE_REQUIRED", clientIp,
                    new AuthFailure(HttpStatus.FORBIDDEN, MessageCatalog.PASSWORD_CHANGE_REQUIRED, "newPassword", null, false));
        }
        String userId = a.user().userId();
        // Hashed before the transaction, so no row lock is held while Argon2 runs.
        String upgrade = hasher.needsUpgrade(a.user().passwordHash()) ? hasher.hash(a.preparedPassword()) : null;
        AtomicBoolean upgraded = new AtomicBoolean();
        Outcome outcome = tx.execute(t -> {
            Rechecked r = recheck(a, "password");
            if (r.refusal() != null) {
                return Outcome.refused(r.refusal());
            }
            AppUser current = r.user();
            if (fidelity.caseSensitivePasswords() && current.mustChangePassword()) {
                return Outcome.refused(new Refusal("PASSWORD_CHANGE_REQUIRED", new AuthFailure(HttpStatus.FORBIDDEN,
                        MessageCatalog.PASSWORD_CHANGE_REQUIRED, "newPassword", null, false)));
            }
            // The {bcrypt} hash the password was just checked against, under the row lock: the
            // same prepared value (legacy mode's upper-cased one) replaces it.
            if (upgrade != null && hasher.needsUpgrade(current.passwordHash())
                    && users.upgradeHash(userId, current.passwordHash(), upgrade)) {
                upgraded.set(true);
            }
            return Outcome.started(sessions.start(current, r.role(), clientIp));
        });
        if (outcome.refusal() != null) {
            throw failed(userId, outcome.refusal().reason(), clientIp, outcome.refusal().failure());
        }
        if (upgraded.get()) {
            log.info("Upgraded the password hash of {} to Argon2id", userId);
        }
        return outcome.session();
    }

    /**
     * Inside the transaction that starts the session: reads the user and its sign-on state again
     * under an update lock and applies the rules that a change committed since {@link #authenticate}
     * could break. A deleted user is unknown; a changed hash must still match the password
     * (another sign-on's upgrade, or a change to the same value), else it is a wrong password; a
     * lock set meanwhile refuses; the role comes from the type read now. Nothing is counted: the
     * password was right when it was checked.
     */
    private Rechecked recheck(Authenticated a, String passwordField) {
        Optional<AppUser> found = users.findLocked(a.user().userId());
        if (found.isEmpty()) {
            return Rechecked.refused(new Refusal("USER_NOT_FOUND", fidelity.neutralMessages() ? neutral(passwordField)
                    : AuthFailure.of(HttpStatus.UNAUTHORIZED, MessageCatalog.USER_NOT_FOUND, "userId")));
        }
        AppUser current = found.get();
        if (!current.passwordHash().equals(a.user().passwordHash())
                && !hasher.matches(a.preparedPassword(), current.passwordHash())) {
            return Rechecked.refused(new Refusal("WRONG_PASSWORD", wrongPassword(passwordField)));
        }
        if (fidelity.lockout() && current.lockedAt(now())) {
            return Rechecked.refused(new Refusal("USER_LOCKED", neutral(passwordField)));
        }
        Optional<String> role = Roles.forType(current.userType(), fidelity.onlyKnownUserTypes());
        if (role.isEmpty()) {
            return Rechecked.refused(new Refusal("USER_TYPE_NOT_ALLOWED", AuthFailure.of(HttpStatus.FORBIDDEN,
                    MessageCatalog.USER_TYPE_NOT_ALLOWED, "userId")));
        }
        return new Rechecked(current, role.get(), null);
    }

    /** A refusal decided inside a transaction; audited after it, so the audit row survives. */
    private record Refusal(String reason, AuthFailure failure) {
    }

    private record Rechecked(AppUser user, String role, Refusal refusal) {
        static Rechecked refused(Refusal refusal) {
            return new Rechecked(null, null, refusal);
        }
    }

    private record Outcome(StartedSession session, Refusal refusal) {
        static Outcome started(StartedSession session) {
            return new Outcome(session, null);
        }

        static Outcome refused(Refusal refusal) {
            return new Outcome(null, refusal);
        }
    }

    /**
     * Without a session this is the forced change before the first sign-on; with one, the user
     * is the session's and a {@code userId} in the request must name the same user.
     */
    public StartedSession changePassword(String rawUserId, String currentPassword, String newPassword,
            Optional<SignedOn> signedOn, String clientIp) {
        String sessionUser = signedOn.map(SignedOn::userId).orElse(null);
        String userIdInput = sessionUser != null && LegacyText.isBlank(rawUserId) ? sessionUser : rawUserId;
        Authenticated a = authenticate(userIdInput, currentPassword, "currentPassword", sessionUser, clientIp);
        String prepared = checkNewPassword(a, newPassword);
        String hash = hasher.hash(prepared);
        String userId = a.user().userId();
        Outcome outcome = tx.execute(t -> {
            Rechecked r = recheck(a, "currentPassword");
            if (r.refusal() != null) {
                return Outcome.refused(r.refusal());
            }
            users.changePassword(userId, hash, now());
            signedOn.ifPresent(s -> sessions.endForPasswordChange(s.sessionId()));
            audit.record(EventType.PASSWORD_CHANGED, userId, userId, null, clientIp);
            AppUser u = r.user();
            AppUser changed = new AppUser(userId, u.firstName(), u.lastName(), hash, u.userType(), false, 0, null);
            return Outcome.started(sessions.start(changed, r.role(), clientIp));
        });
        if (outcome.refusal() != null) {
            throw failed(userId, outcome.refusal().reason(), clientIp, outcome.refusal().failure());
        }
        return outcome.session();
    }

    /**
     * Sign-on rules 1 to 9. With {@code sessionUser} (a password change while signed on), a user
     * ID naming someone else is refused after rule 3, before anything is limited, read or counted.
     */
    private Authenticated authenticate(String rawUserId, String rawPassword, String passwordField,
            String sessionUser, String clientIp) {
        if (LegacyText.isBlank(rawUserId)) {
            throw failed(null, "USER_ID_REQUIRED", clientIp, AuthFailure.of(HttpStatus.BAD_REQUEST,
                    MessageCatalog.USER_ID_REQUIRED, "userId"));
        }
        String typedId = LegacyText.rtrim(rawUserId);
        if (LegacyText.isBlank(rawPassword)) {
            throw failed(typedId, "PASSWORD_REQUIRED", clientIp, AuthFailure.of(HttpStatus.BAD_REQUEST,
                    MessageCatalog.PASSWORD_REQUIRED, passwordField));
        }
        if (length(typedId) > MAX_USER_ID) {
            throw failed(typedId, "INVALID_INPUT", clientIp, AuthFailure.of(HttpStatus.BAD_REQUEST,
                    MessageCatalog.INVALID_INPUT_USER_ID, "userId"));
        }
        if (length(LegacyText.rtrim(rawPassword)) > MAX_PASSWORD) {
            throw failed(typedId, "INVALID_INPUT", clientIp, AuthFailure.of(HttpStatus.BAD_REQUEST,
                    MessageCatalog.INVALID_INPUT_PASSWORD, passwordField));
        }
        String userId = LegacyCase.upper(typedId);
        if (sessionUser != null && !userId.equals(sessionUser)) {
            throw failed(userId, "USER_ID_MISMATCH", clientIp, AuthFailure.of(HttpStatus.BAD_REQUEST,
                    MessageCatalog.USER_ID_MISMATCH, "userId"));
        }
        boolean lockout = fidelity.lockout();
        boolean neutral = fidelity.neutralMessages();
        if (lockout) {
            Optional<Duration> wait = throttle.check(clientIp);
            if (wait.isPresent()) {
                throw failed(userId, "TOO_MANY_ATTEMPTS", clientIp, new AuthFailure(HttpStatus.TOO_MANY_REQUESTS,
                        MessageCatalog.TOO_MANY_ATTEMPTS, "userId", wait.get(), false));
            }
        }
        Optional<AppUser> found;
        try {
            found = users.find(userId);
        } catch (DataAccessException e) {
            log.warn("Cannot read iam.app_user: {}", SafeErrors.describe(e));
            auditQuietly(userId, clientIp);
            throw AuthFailure.of(HttpStatus.SERVICE_UNAVAILABLE, MessageCatalog.UNABLE_TO_VERIFY,
                    "userId");
        }
        if (found.isEmpty()) {
            hasher.burn();
            if (lockout) {
                throttle.recordFailure(clientIp);
            }
            throw failed(userId, "USER_NOT_FOUND", clientIp, neutral ? neutral(passwordField)
                    : AuthFailure.of(HttpStatus.UNAUTHORIZED, MessageCatalog.USER_NOT_FOUND, "userId"));
        }
        AppUser user = found.get();
        LocalDateTime now = now();
        if (lockout && user.lockedAt(now)) {
            hasher.burn();
            throttle.recordFailure(clientIp);
            throw failed(userId, "USER_LOCKED", clientIp, neutral(passwordField));
        }
        String prepared = PasswordHasher.prepare(rawPassword, fidelity.caseSensitivePasswords());
        if (!hasher.matches(prepared, user.passwordHash())) {
            if (lockout) {
                throttle.recordFailure(clientIp);
                if (users.recordFailure(userId, props.lockout().maxFailures(),
                        now.plus(props.lockout().lockDuration()))) {
                    audit.record(EventType.USER_LOCKED, null, userId, "MAX_FAILURES", clientIp);
                }
            }
            throw failed(userId, "WRONG_PASSWORD", clientIp, wrongPassword(passwordField));
        }
        Optional<String> role = Roles.forType(user.userType(), fidelity.onlyKnownUserTypes());
        if (role.isEmpty()) {
            throw failed(userId, "USER_TYPE_NOT_ALLOWED", clientIp, AuthFailure.of(HttpStatus.FORBIDDEN,
                    MessageCatalog.USER_TYPE_NOT_ALLOWED, "userId"));
        }
        return new Authenticated(user, role.get(), prepared);
    }

    /** The new-password rules of D-SEC-07; returns the value to hash. */
    private String checkNewPassword(Authenticated a, String raw) {
        if (LegacyText.isBlank(raw)) {
            throw newPassword(MessageCatalog.NEW_PASSWORD_REQUIRED);
        }
        String trimmed = LegacyText.rtrim(raw);
        boolean caseSensitive = fidelity.caseSensitivePasswords();
        Message length = NewPasswords.lengthProblem(trimmed, caseSensitive);
        if (length != null) {
            throw newPassword(length);
        }
        String prepared = PasswordHasher.prepare(trimmed, caseSensitive);
        if (prepared.equals(a.preparedPassword())) {
            throw newPassword(MessageCatalog.NEW_PASSWORD_SAME);
        }
        if (caseSensitive && NewPasswords.isUserId(trimmed, a.user().userId())) {
            throw newPassword(MessageCatalog.NEW_PASSWORD_IS_USER_ID);
        }
        return prepared;
    }

    private AuthFailure failed(String subject, String reason, String clientIp, AuthFailure failure) {
        audit.record(EventType.SIGN_ON_FAILED, null, subject, reason, clientIp);
        return failure;
    }

    private void auditQuietly(String subject, String clientIp) {
        try {
            audit.record(EventType.SIGN_ON_FAILED, null, subject, "UNABLE_TO_VERIFY", clientIp);
        } catch (DataAccessException e) {
            log.warn("Cannot write the audit row of a failed sign-on: {}", SafeErrors.describe(e));
        }
    }

    private AuthFailure wrongPassword(String field) {
        return fidelity.neutralMessages() ? neutral(field)
                : AuthFailure.of(HttpStatus.UNAUTHORIZED, MessageCatalog.WRONG_PASSWORD, field);
    }

    private static AuthFailure neutral(String field) {
        return AuthFailure.of(HttpStatus.UNAUTHORIZED, MessageCatalog.SIGN_ON_FAILED, field);
    }

    private static AuthFailure newPassword(Message message) {
        return AuthFailure.of(HttpStatus.BAD_REQUEST, message, "newPassword");
    }

    private static int length(String s) {
        return s.codePointCount(0, s.length());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
