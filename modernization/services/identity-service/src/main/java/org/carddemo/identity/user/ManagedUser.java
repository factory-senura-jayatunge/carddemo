package org.carddemo.identity.user;

import org.carddemo.platform.web.RowVersions;

/** An active user as user administration reads it. The hash never leaves the service. */
public record ManagedUser(String userId, String firstName, String lastName, String userType, String passwordHash,
        byte[] rowVersion) {

    public UserView view() {
        return new UserView(userId, firstName, lastName, userType, RowVersions.tag(rowVersion));
    }
}
