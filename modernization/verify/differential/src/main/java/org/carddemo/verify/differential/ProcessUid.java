package org.carddemo.verify.differential;

import java.util.OptionalLong;

import com.sun.security.auth.module.UnixSystem;

/**
 * The process's user ID from the operating system, never from {@code user.name}, which comes from
 * the environment or a launcher. {@link UnixSystem#getUid()} is {@code getuid(2)}; the exporter is
 * never installed setuid, so it is also the effective user ID that owns the files it creates.
 */
final class ProcessUid {

    private ProcessUid() {
    }

    /** @return the user ID, or empty when the platform has no such source */
    static OptionalLong current() {
        try {
            return OptionalLong.of(new UnixSystem().getUid());
        } catch (LinkageError | RuntimeException e) {
            return OptionalLong.empty();
        }
    }
}
