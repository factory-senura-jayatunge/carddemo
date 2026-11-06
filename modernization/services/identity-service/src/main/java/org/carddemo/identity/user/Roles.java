package org.carddemo.identity.user;

import java.util.Optional;

/** BR-SGN-004 with DEF-SGN-005: the role a stored user type signs on with. */
public final class Roles {

    public static final String ADMIN = "ADMIN";
    public static final String USER = "USER";

    private Roles() {
    }

    /** Empty when the type is refused, which only the fixed mode of DEF-SGN-005 does. */
    public static Optional<String> forType(char userType, boolean onlyKnownTypes) {
        return switch (userType) {
            case AppUser.TYPE_ADMIN -> Optional.of(ADMIN);
            case AppUser.TYPE_USER -> Optional.of(USER);
            default -> onlyKnownTypes ? Optional.empty() : Optional.of(USER);
        };
    }
}
