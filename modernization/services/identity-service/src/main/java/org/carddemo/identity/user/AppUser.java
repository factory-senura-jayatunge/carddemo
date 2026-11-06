package org.carddemo.identity.user;

import java.time.LocalDateTime;

/** One row of iam.app_user (ENT-USER-SECURITY), without the audit columns. */
public record AppUser(
        String userId,
        String firstName,
        String lastName,
        String passwordHash,
        char userType,
        boolean mustChangePassword,
        int failedAttempts,
        LocalDateTime lockedUntil) {

    public static final char TYPE_ADMIN = 'A';
    public static final char TYPE_USER = 'U';

    public boolean lockedAt(LocalDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** BR-SGN-004: the admin menu for type A, the main menu for every other type. */
    public String home() {
        return userType == TYPE_ADMIN ? "/admin" : "/";
    }
}
