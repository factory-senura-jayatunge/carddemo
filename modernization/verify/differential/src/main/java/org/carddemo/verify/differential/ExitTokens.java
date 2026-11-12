package org.carddemo.verify.differential;

import java.util.List;

/**
 * The two closed sets that {@code refusal.json} copies from statementJob's execution. Each raw
 * column is compared with exact {@link String#equals} or {@link String#startsWith}, with no trim
 * and no case folding, and only a literal of this class is returned: the raw {@code EXIT_CODE} and
 * {@code EXIT_MESSAGE} are never written, printed, logged or kept.
 */
final class ExitTokens {

    static final String UNRECOGNISED = "unrecognised";
    static final String TABLE_LIMIT = "STATEMENT_TABLE_LIMIT";
    static final List<String> EXIT_STATUSES = List.of("COMPLETED", "COMPLETED_WITH_REJECTS", "FAILED", "STOPPED",
            "UNKNOWN", "NOOP", "EXECUTING");
    static final List<String> EXIT_CODES = List.of(TABLE_LIMIT, UNRECOGNISED);

    private ExitTokens() {
    }

    /** {@code exit_status}: the execution's exit code when it is exactly one of the seven, else {@code unrecognised}. */
    static String exitStatus(String exitCode) {
        if (exitCode != null) {
            for (String status : EXIT_STATUSES) {
                if (status.equals(exitCode)) {
                    return status;
                }
            }
        }
        return UNRECOGNISED;
    }

    /**
     * {@code exit_code}: {@code STATEMENT_TABLE_LIMIT} when the exit message begins with exactly that
     * code, followed by its end or by a character outside {@code [A-Z0-9_]}; else {@code unrecognised}.
     */
    static String exitCode(String exitMessage) {
        if (exitMessage == null || !exitMessage.startsWith(TABLE_LIMIT)) {
            return UNRECOGNISED;
        }
        if (exitMessage.length() == TABLE_LIMIT.length()) {
            return TABLE_LIMIT;
        }
        char next = exitMessage.charAt(TABLE_LIMIT.length());
        boolean codeCharacter = (next >= 'A' && next <= 'Z') || (next >= '0' && next <= '9') || next == '_';
        return codeCharacter ? UNRECOGNISED : TABLE_LIMIT;
    }
}
