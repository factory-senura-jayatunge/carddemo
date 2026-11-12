package org.carddemo.platform.outputroot;

import java.util.Objects;

/** A refused output root, with the code that the protocol names for the case. */
public final class OutputRootException extends RuntimeException {

    public enum Code {
        /** CARDDEMO_VAR_DIR missing or relative, a bad name, ID or path segment, or no marker row. */
        OUTPUT_ROOT_INVALID,
        /** A removed output setting is still set. */
        OUTPUT_ROOT_LEGACY_SETTING,
        /** The root's marker names another database or instance. */
        OUTPUT_ROOT_MISMATCH,
        /** The root exists without its marker; reported, never repaired. */
        OUTPUT_ROOT_INCOMPLETE,
        /** A managed path is a link, not a directory or regular file, has another owner or is writable by others. */
        OUTPUT_PERMISSIONS
    }

    private final Code code;

    public OutputRootException(Code code, String detail) {
        this(code, detail, null);
    }

    public OutputRootException(Code code, String detail, Throwable cause) {
        super(Objects.requireNonNull(code, "code").name() + ": " + detail, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
