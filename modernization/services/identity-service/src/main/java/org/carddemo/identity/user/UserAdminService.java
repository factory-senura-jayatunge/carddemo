package org.carddemo.identity.user;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.carddemo.identity.fidelity.IdentityFidelity;
import org.carddemo.identity.session.SessionRepository;
import org.carddemo.identity.session.SessionRepository.EndReason;
import org.carddemo.identity.signon.LegacyText;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.AuditLog;
import org.carddemo.platform.web.AuditLog.Action;
import org.carddemo.platform.web.Cursors;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.Page;
import org.carddemo.platform.web.PageRequest;
import org.carddemo.platform.web.RowVersions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * User administration (COUSR00C to COUSR03C as the identity-service contract builds them): the
 * list, add, fetch, update and logical delete of {@code iam.app_user}. The caller is an
 * administrator; {@code actor} is the user ID of its token. Checks run in the contract's order and
 * the first failure ends the request.
 */
@Service
public class UserAdminService {

    public static final int SCREEN_ROWS = 10;
    static final String SUBJECT_TYPE = "USER";
    static final int NAME_MAX = 20;
    static final List<String> CREATE_FIELDS = List.of("firstName", "lastName", "password", "userType");

    /** What the add form sends. */
    public record NewUser(String firstName, String lastName, String userId, String password, String userType) {
    }

    /** What the edit form sends; a blank password keeps the current one. */
    public record UserChange(String firstName, String lastName, String userType, String password) {
    }

    /** A list page, its keys already page tokens, and the message line (null when none). */
    public record UserList(Page<ListedUser> page, Message message) {
    }

    private final UserRepository users;
    private final SessionRepository sessions;
    private final UserAdminLocks locks;
    private final PasswordHasher hasher;
    private final IdentityFidelity fidelity;
    private final AuditLog audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    public UserAdminService(UserRepository users, SessionRepository sessions, UserAdminLocks locks,
            PasswordHasher hasher, IdentityFidelity fidelity, @Qualifier("opsAuditLog") AuditLog audit, Clock clock,
            TransactionTemplate tx) {
        this.users = users;
        this.sessions = sessions;
        this.locks = locks;
        this.hasher = hasher;
        this.fidelity = fidelity;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    /**
     * USRL: active users in {@code user_id} order. {@code start} is a case-insensitive prefix
     * filter; the page tokens carry it, so {@code after} and {@code before} stay within it.
     */
    public UserList list(String start, String after, String before, String size) {
        PageRequest request = PageRequest.of(start, after, before, pageSize(size), SCREEN_ROWS);
        String prefix;
        String key = null;
        switch (request.kind()) {
            case FIRST -> prefix = "";
            case START -> prefix = UserIds.searchPrefix(start, "start");
            default -> {
                PageKey k = PageKey.decode(request.key());
                prefix = k.prefix();
                key = k.userId();
            }
        }
        String pattern = like(prefix);
        Page<ListedUser> page;
        try {
            page = switch (request.kind()) {
                case FIRST, START -> Page.forward(users.list(pattern, null, null, request.fetchSize()),
                        request.size(), ListedUser::userId, false);
                case AFTER -> Page.forward(users.list(pattern, key, null, request.fetchSize()),
                        request.size(), ListedUser::userId, true);
                case BEFORE -> Page.backward(users.list(pattern, null, key, request.fetchSize()),
                        request.size(), ListedUser::userId);
            };
        } catch (DataAccessException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, UserMessages.USER_LOOKUP_FAILED, "start", e);
        }
        Message message = listMessage(request.kind(), page);
        String p = prefix;
        return new UserList(page.withKeys(id -> new PageKey(p, id).encode()), message);
    }

    /** USRU and USRD fetch: the active user, read without a lock. */
    public UserView fetch(String rawUserId) {
        String userId = UserIds.canonical(rawUserId, "userId");
        Optional<ManagedUser> found;
        try {
            found = users.fetch(userId);
        } catch (DataAccessException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, UserMessages.USER_LOOKUP_FAILED, "userId", e);
        }
        return found.map(ManagedUser::view).orElseThrow(() -> notFound("userId"));
    }

    /** USRA. */
    public UserView add(String actor, NewUser input) {
        String firstName = name(input.firstName(), "firstName", "First Name", UserMessages.FIRST_NAME_EMPTY);
        String lastName = name(input.lastName(), "lastName", "Last Name", UserMessages.LAST_NAME_EMPTY);
        String userId = UserIds.canonical(input.userId(), "userId");
        if (LegacyText.isBlank(input.password())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, UserMessages.PASSWORD_EMPTY, "password");
        }
        String prepared = newPassword(input.password(), userId);
        String userType = userType(input.userType());
        String hash = hasher.hash(prepared);
        try {
            return tx.execute(t -> {
                locks.requireWritesAllowed();
                AppUser user = new AppUser(userId, firstName, lastName, hash, userType.charAt(0), false, 0, null);
                byte[] version;
                try {
                    version = users.addOrReuse(user, now(), actor).orElseThrow(UserAdminService::idExists);
                } catch (DuplicateKeyException e) {
                    // Mapped here: the template redacts what leaves the callback to a plain DataAccessException.
                    throw idExists();
                }
                audit.record(actor, Action.CREATE, SUBJECT_TYPE, userId, CREATE_FIELDS);
                return new UserView(userId, firstName, lastName, userType, RowVersions.tag(version));
            });
        } catch (DataAccessException | TransactionException | UserAdminLocks.OpenLevelAtCommit e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, UserMessages.USER_ADD_FAILED, "firstName", e);
        }
    }

    /** USRU save. */
    public UserView update(String actor, String rawUserId, String ifMatch, UserChange input) {
        String userId = UserIds.canonical(rawUserId, "userId");
        String expected = RowVersions.requireIfMatch(ifMatch);
        String firstName = name(input.firstName(), "firstName", "First Name", UserMessages.FIRST_NAME_EMPTY);
        String lastName = name(input.lastName(), "lastName", "Last Name", UserMessages.LAST_NAME_EMPTY);
        boolean passwordGiven = !LegacyText.isBlank(input.password());
        String prepared = passwordGiven ? newPassword(input.password(), userId) : null;
        String userType = userType(input.userType());
        String hash = passwordGiven ? hasher.hash(prepared) : null;
        try {
            return tx.execute(t -> {
                locks.requireWritesAllowed();
                ManagedUser current = lockedRead(userId, "firstName");
                if (!RowVersions.matches(expected, current.rowVersion())) {
                    throw RowVersions.conflict(null, current.view(), current.rowVersion());
                }
                if (passwordGiven && hasher.matches(prepared, current.passwordHash())) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.NEW_PASSWORD_SAME, "password");
                }
                List<String> changed = new ArrayList<>();
                if (!firstName.equals(UserText.withoutTrailingSpaces(current.firstName()))) {
                    changed.add("firstName");
                }
                if (!lastName.equals(UserText.withoutTrailingSpaces(current.lastName()))) {
                    changed.add("lastName");
                }
                if (passwordGiven) {
                    changed.add("password");
                }
                boolean typeChanged = !userType.equals(current.userType());
                if (typeChanged) {
                    changed.add("userType");
                }
                if (changed.isEmpty()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, UserMessages.USER_NO_CHANGE, "firstName");
                }
                LocalDateTime now = now();
                byte[] version = users.update(userId, current.rowVersion(), firstName, lastName, userType, hash,
                        actor, now).orElseThrow(() -> RowVersions.conflict(null, current.view(), current.rowVersion()));
                if (typeChanged) {
                    sessions.endAllFor(userId, now, EndReason.USER_REFUSED);
                } else if (passwordGiven) {
                    sessions.endAllFor(userId, now, EndReason.PASSWORD_CHANGE);
                }
                audit.record(actor, Action.UPDATE, SUBJECT_TYPE, userId, changed);
                return new UserView(userId, firstName, lastName, userType, RowVersions.tag(version));
            });
        } catch (DataAccessException | TransactionException | UserAdminLocks.OpenLevelAtCommit e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, UserMessages.USER_UPDATE_FAILED, "firstName", e);
        }
    }

    /** USRD: the logical delete; returns the canonical user ID. */
    public String delete(String actor, String rawUserId, String ifMatch) {
        String userId = UserIds.canonical(rawUserId, "userId");
        String expected = RowVersions.requireIfMatch(ifMatch);
        if (userId.equals(actor)) {
            throw new ApiException(HttpStatus.CONFLICT, UserMessages.CANNOT_DELETE_SELF, "userId");
        }
        try {
            tx.executeWithoutResult(t -> {
                locks.requireWritesAllowed();
                locks.lockAdmins();
                ManagedUser current = lockedRead(userId, "userId");
                if (!RowVersions.matches(expected, current.rowVersion())) {
                    throw RowVersions.conflict(null, current.view(), current.rowVersion());
                }
                if (String.valueOf(AppUser.TYPE_ADMIN).equals(current.userType())
                        && users.otherActiveAdmins(userId) == 0) {
                    throw new ApiException(HttpStatus.CONFLICT, UserMessages.LAST_ADMIN, "userId");
                }
                LocalDateTime now = now();
                if (!users.markDeleted(userId, current.rowVersion(), actor, now)) {
                    throw RowVersions.conflict(null, current.view(), current.rowVersion());
                }
                sessions.endAllFor(userId, now, EndReason.USER_REFUSED);
                audit.record(actor, Action.DELETE, SUBJECT_TYPE, userId, List.of());
            });
        } catch (DataAccessException | TransactionException | UserAdminLocks.OpenLevelAtCommit e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, UserMessages.USER_DELETE_FAILED, "userId", e);
        }
        return userId;
    }

    private ManagedUser lockedRead(String userId, String failureField) {
        Optional<ManagedUser> found;
        try {
            found = users.fetchForUpdate(userId);
        } catch (DataAccessException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, UserMessages.USER_LOOKUP_FAILED, failureField, e);
        }
        return found.orElseThrow(() -> notFound("userId"));
    }

    /** First or last name: required, U+0000 to U+00FF, at most 20 without the trailing pad; kept as typed. */
    private static String name(String raw, String field, String label, Message empty) {
        if (UserText.blank(raw)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, empty, field);
        }
        if (!UserText.latin1(raw)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, UserMessages.UNSUPPORTED_CHARACTER, field, label);
        }
        String stored = UserText.withoutTrailingSpaces(raw);
        if (stored.length() > NAME_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST, UserMessages.TOO_LONG, field, label,
                    Integer.toString(NAME_MAX));
        }
        return stored;
    }

    /** D-SEC-07 for a password that is present: length, then not the user ID (fixed). Returns the value to hash. */
    private String newPassword(String raw, String userId) {
        String trimmed = LegacyText.rtrim(raw);
        boolean caseSensitive = fidelity.caseSensitivePasswords();
        Message length = NewPasswords.lengthProblem(trimmed, caseSensitive);
        if (length != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, length, "password");
        }
        if (caseSensitive && NewPasswords.isUserId(trimmed, userId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.NEW_PASSWORD_IS_USER_ID, "password");
        }
        return PasswordHasher.prepare(trimmed, caseSensitive);
    }

    /** Required, then exactly A or U (D-53): {@code a} is refused, not upper-cased. */
    private static String userType(String raw) {
        if (UserText.blank(raw)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, UserMessages.USER_TYPE_EMPTY, "userType");
        }
        String type = LegacyText.rtrim(raw);
        if (!type.equals("A") && !type.equals("U")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, UserMessages.USER_TYPE_INVALID, "userType");
        }
        return type;
    }

    private static Integer pageSize(String size) {
        if (size == null || size.isBlank()) {
            return null;
        }
        if (!size.matches("[0-9]{1,9}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_PAGE_SIZE, null,
                    Integer.toString(SCREEN_ROWS));
        }
        return Integer.valueOf(size);
    }

    private static Message listMessage(PageRequest.Kind kind, Page<ListedUser> page) {
        return switch (kind) {
            case FIRST, START -> page.items().isEmpty() ? UserMessages.USER_LIST_NONE
                    : page.hasNext() ? null : UserMessages.USER_LIST_BOTTOM;
            case AFTER -> page.hasNext() ? null : UserMessages.USER_LIST_BOTTOM;
            case BEFORE -> page.hasPrevious() ? null : UserMessages.USER_LIST_TOP;
        };
    }

    /** A LIKE pattern for the IDs that start with {@code prefix}, escape character {@code \}. */
    static String like(String prefix) {
        StringBuilder b = new StringBuilder(prefix.length() + 2);
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (c == '\\' || c == '%' || c == '_' || c == '[') {
                b.append('\\');
            }
            b.append(c);
        }
        return b.append('%').toString();
    }

    private static ApiException idExists() {
        return new ApiException(HttpStatus.CONFLICT, UserMessages.USER_ID_EXISTS, "firstName");
    }

    private static ApiException notFound(String field) {
        return new ApiException(HttpStatus.NOT_FOUND, UserMessages.USER_ID_NOT_FOUND, field);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }

    /**
     * The content of a page token: the search prefix and the row's user ID, as
     * {@code <prefix length>:<prefix><user ID>}. A token whose parts are not canonical, or whose ID
     * does not start with its prefix, is not one this service made.
     */
    record PageKey(String prefix, String userId) {

        String encode() {
            return Cursors.encode(prefix.length() + ":" + prefix + userId);
        }

        static PageKey decode(String token) {
            String key = Cursors.decode(token);
            if (key.length() < 3 || key.charAt(0) < '0' || key.charAt(0) > '9' || key.charAt(1) != ':') {
                throw invalid();
            }
            int length = key.charAt(0) - '0';
            String rest = key.substring(2);
            if (length > UserIds.MAX_LENGTH || rest.length() <= length) {
                throw invalid();
            }
            String prefix = rest.substring(0, length);
            String userId = rest.substring(length);
            try {
                if (!UserIds.searchPrefix(prefix, "start").equals(prefix)
                        || !UserIds.canonical(userId, "userId").equals(userId) || !userId.startsWith(prefix)) {
                    throw invalid();
                }
            } catch (ApiException e) {
                throw invalid();
            }
            return new PageKey(prefix, userId);
        }

        private static ApiException invalid() {
            return new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.INVALID_CURSOR, null);
        }
    }
}
