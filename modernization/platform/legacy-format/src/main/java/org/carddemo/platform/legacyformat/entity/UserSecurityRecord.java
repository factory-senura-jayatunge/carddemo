package org.carddemo.platform.legacyformat.entity;

import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FixedRecord;

/**
 * ENT-USER-SECURITY without its password. The plain-text password of the
 * legacy record is deliberately not carried: user seeding hashes it in
 * identity-service, and nothing else may hold it.
 */
public record UserSecurityRecord(String userId, String firstName, String lastName, String userType) {

    public static UserSecurityRecord parse(byte[] data) {
        FixedRecord r = FixedRecord.of(Layouts.USER_SECURITY, data);
        return new UserSecurityRecord(r.text("SEC-USR-ID"), r.text("SEC-USR-FNAME"), r.text("SEC-USR-LNAME"),
                r.text("SEC-USR-TYPE"));
    }

    @Override
    public String toString() {
        return "UserSecurityRecord[" + userId + "]";
    }
}
