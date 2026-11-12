package org.carddemo.verify.differential;

import java.util.List;

/**
 * The closed code set of the refusal mode: these 14 literals are the whole set. A refusal prints
 * exactly {@code verify-differential: FAILED <code>} and exits 1. The helper's
 * {@code HELPER_CODES} tuple and the driver's copy are compared with this file by static tests,
 * so each code stays one {@code static final String} literal on a line of its own.
 */
final class RefusalCodes {

    static final String EXECUTION_REFUSED = "execution-refused";
    static final String EXECUTION_NOT_FINAL = "execution-not-final";
    static final String EXECUTION_CHANGED = "execution-changed";
    static final String DATABASE_READ_FAILED = "database-read-failed";
    static final String INSTANCE_READ_REFUSED = "instance-read-refused";
    static final String VAR_DIR_REFUSED = "var-dir-refused";
    static final String DESTINATION_REFUSED = "destination-refused";
    static final String REFUSAL_WRITE_FAILED = "refusal-write-failed";
    static final String WORK_FOLDER_UNSUPPORTED = "work-folder-unsupported";
    static final String WORK_FOLDER_INPUT = "work-folder-input";
    static final String INSTANCE_ROOT_REFUSED = "instance-root-refused";
    static final String WORK_FOLDER_REFUSED = "work-folder-refused";
    static final String WORK_FOLDER_HELPER_FAILED = "work-folder-helper-failed";
    static final String REFUSAL_INTERNAL_ERROR = "refusal-internal-error";

    /** Every code, in the order of the ruling's table. */
    static final List<String> ALL = List.of(EXECUTION_REFUSED, EXECUTION_NOT_FINAL, EXECUTION_CHANGED,
            DATABASE_READ_FAILED, INSTANCE_READ_REFUSED, VAR_DIR_REFUSED, DESTINATION_REFUSED, REFUSAL_WRITE_FAILED,
            WORK_FOLDER_UNSUPPORTED, WORK_FOLDER_INPUT, INSTANCE_ROOT_REFUSED, WORK_FOLDER_REFUSED,
            WORK_FOLDER_HELPER_FAILED, REFUSAL_INTERNAL_ERROR);

    /** The only codes a {@code refuse} line of the helper may carry; any other is a helper failure. */
    static final List<String> HELPER = List.of(WORK_FOLDER_UNSUPPORTED, WORK_FOLDER_INPUT, INSTANCE_ROOT_REFUSED,
            WORK_FOLDER_REFUSED);

    /** The mode's only failure line, without its line feed. */
    static String failedLine(String code) {
        if (code == null || !ALL.contains(code)) {
            throw new IllegalArgumentException("not a refusal code");
        }
        return "verify-differential: FAILED " + code;
    }

    private RefusalCodes() {
    }
}
