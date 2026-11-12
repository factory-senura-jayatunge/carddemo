package org.carddemo.verify.differential;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@code refusal.json}: exactly six keys, in this order, each value an integer, a boolean or a
 * literal of a closed set. A value outside its set is a defect of the mode and is never written.
 */
final class RefusalJson {

    static final String FILE = "refusal.json";
    static final List<String> WORK_FOLDERS = List.of("absent", "empty", "nonempty", "not-a-directory");

    /** What the refusal mode found; the driver decides the match. */
    record Refusal(long jobExecutionId, String exitStatus, String exitCode, boolean completedRunRow,
            boolean finalFolderExists, String workFolder) {
    }

    private RefusalJson() {
    }

    static byte[] of(Refusal r) {
        if (r.jobExecutionId() < 1 || !(in(ExitTokens.EXIT_STATUSES, r.exitStatus())
                || ExitTokens.UNRECOGNISED.equals(r.exitStatus())) || !in(ExitTokens.EXIT_CODES, r.exitCode())
                || !in(WORK_FOLDERS, r.workFolder())) {
            throw new IllegalStateException("a refusal.json value is outside its closed set");
        }
        String body = "{\n"
                + "  \"job_execution_id\": " + r.jobExecutionId() + ",\n"
                + "  \"exit_status\": \"" + r.exitStatus() + "\",\n"
                + "  \"exit_code\": \"" + r.exitCode() + "\",\n"
                + "  \"completed_run_row\": " + r.completedRunRow() + ",\n"
                + "  \"final_folder_exists\": " + r.finalFolderExists() + ",\n"
                + "  \"work_folder\": \"" + r.workFolder() + "\"\n"
                + "}\n";
        return body.getBytes(StandardCharsets.US_ASCII);
    }

    /** {@code List.of}'s {@code contains(null)} throws; a null is simply outside the set. */
    private static boolean in(List<String> set, String value) {
        return value != null && set.contains(value);
    }
}
