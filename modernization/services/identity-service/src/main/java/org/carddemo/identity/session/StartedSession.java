package org.carddemo.identity.session;

import java.time.Duration;
import java.util.UUID;

import org.carddemo.identity.user.AppUser;

/** A started or refreshed session: the user, its role and the two cookie values with their lifetimes. */
public record StartedSession(AppUser user, String role, UUID sessionId, String accessToken, Duration accessMaxAge,
        String refreshToken, Duration refreshMaxAge) {
}
