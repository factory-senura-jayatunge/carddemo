package org.carddemo.verify.differential;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The command line of the refusal mode:
 * {@code --refusal --job CREASTMT --cycle-id N --load-execution-id L --job-execution-id E --output-dir DIR}.
 * Each option is given once, in any order. {@code N} and {@code E} are whole numbers from 1 and
 * {@code L} from 0, in canonical decimal. {@code --output-dir} is only where {@code refusal.json}
 * is written: unlike the POSTTRAN export's {@code --output-dir}, it is never read, and the instance
 * root comes from the configuration, never from the command line. A bad command line is a usage
 * error (exit status 2), and its message names options only, never a value.
 */
record RefusalOptions(long cycleId, long loadExecutionId, long jobExecutionId, Path outputDir) {

    static final String FLAG = "--refusal";
    static final String JOB = "CREASTMT";
    static final String USAGE = "       verify-differential --refusal --job CREASTMT --cycle-id N --load-execution-id L"
            + " --job-execution-id E --output-dir DIR";

    private static final List<String> OPTIONS = List.of("--job", "--cycle-id", "--load-execution-id",
            "--job-execution-id", "--output-dir");
    private static final Pattern CANONICAL = Pattern.compile("0|[1-9][0-9]*");

    /** Whether the command line asks for the refusal mode; then no other mode reads it. */
    static boolean requested(String[] args) {
        for (String a : args) {
            if (FLAG.equals(a)) {
                return true;
            }
        }
        return false;
    }

    static RefusalOptions parse(String[] args) {
        boolean flag = false;
        boolean job = false;
        Long cycleId = null;
        Long loadExecutionId = null;
        Long jobExecutionId = null;
        Path outputDir = null;
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (FLAG.equals(option)) {
                if (flag) {
                    throw new UsageException(FLAG + " is given twice");
                }
                flag = true;
                continue;
            }
            if (!OPTIONS.contains(option)) {
                throw new UsageException(FLAG + " takes only " + String.join(", ", OPTIONS));
            }
            if (i + 1 >= args.length) {
                throw new UsageException("missing value for " + option);
            }
            String value = args[++i];
            switch (option) {
                case "--job" -> {
                    once(job, option);
                    if (!JOB.equals(value)) {
                        throw new UsageException("--job must be " + JOB + " with " + FLAG);
                    }
                    job = true;
                }
                case "--cycle-id" -> {
                    once(cycleId != null, option);
                    cycleId = whole(option, value, 1);
                }
                case "--load-execution-id" -> {
                    once(loadExecutionId != null, option);
                    loadExecutionId = whole(option, value, 0);
                }
                case "--job-execution-id" -> {
                    once(jobExecutionId != null, option);
                    jobExecutionId = whole(option, value, 1);
                }
                default -> {
                    once(outputDir != null, option);
                    outputDir = path(option, value);
                }
            }
        }
        if (!flag || !job || cycleId == null || loadExecutionId == null || jobExecutionId == null || outputDir == null) {
            throw new UsageException(FLAG + " needs " + String.join(", ", OPTIONS));
        }
        return new RefusalOptions(cycleId, loadExecutionId, jobExecutionId, outputDir);
    }

    private static void once(boolean seen, String option) {
        if (seen) {
            throw new UsageException(option + " is given twice");
        }
    }

    private static long whole(String option, String value, long min) {
        if (!CANONICAL.matcher(value).matches()) {
            throw new UsageException(option + " must be a whole number without sign or leading zero");
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new UsageException(option + " must be at most " + Long.MAX_VALUE);
        }
        if (parsed < min) {
            throw new UsageException(option + " must be at least " + min);
        }
        return parsed;
    }

    private static Path path(String option, String value) {
        if (value.isEmpty()) {
            throw new UsageException(option + " must not be empty");
        }
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new UsageException(option + " is not a path");
        }
    }
}
