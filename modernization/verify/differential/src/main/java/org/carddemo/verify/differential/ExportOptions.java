package org.carddemo.verify.differential;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalLong;

import org.carddemo.batch.BatchSwitches;
import org.carddemo.platform.fidelity.FidelityMode;

/**
 * The command line of the exporter.
 *
 * <ul>
 * <li>v1: {@code --export DIR --job POSTTRAN|INTCALC --cycle-id N --fidelity DEF-ACUP-002=legacy|fixed
 * [--output-dir DIR]}. The DEF-ACUP-002 mode is required, never defaulted: the account records are
 * put back together in the mode the accounts were loaded in.
 * <li>v2, selected by {@code --load-execution-id L}: the accounts follow the per-row rule and the
 * counts are schema 2. {@code --fidelity} is optional, its syntax still checked, and ignored with a
 * warning ({@link #fidelityDeprecated()}).
 * </ul>
 *
 * {@code --output-dir} is batch-service's output folder; when given, the job's own reject file must
 * equal the export. {@code groupIdMode} is {@code null} in a v2 command line without {@code --fidelity}.
 */
record ExportOptions(Path exportDir, Job job, long cycleId, FidelityMode groupIdMode, Optional<Path> outputDir,
        OptionalLong loadExecutionId) {

    /** The legacy job whose outputs are exported. */
    enum Job { POSTTRAN, INTCALC }

    static final String USAGE = "usage: verify-differential --export DIR --job POSTTRAN|INTCALC --cycle-id N"
            + " --fidelity " + BatchSwitches.DEF_ACUP_002.itemId() + "=legacy|fixed [--output-dir DIR]\n"
            + "       verify-differential --export DIR --job POSTTRAN|INTCALC --cycle-id N --load-execution-id L"
            + " [--output-dir DIR]\n"
            + "       verify-differential --load-report N\n"
            + RefusalOptions.USAGE;

    private static final java.util.regex.Pattern CANONICAL_ID = java.util.regex.Pattern.compile("0|[1-9][0-9]*");

    static final String FIDELITY_DEPRECATED = "verify-differential: warning: --fidelity is deprecated and ignored;"
            + " account records are exported per row (counts schemaVersion 2)";

    ExportOptions(Path exportDir, Job job, long cycleId, FidelityMode groupIdMode, Optional<Path> outputDir) {
        this(exportDir, job, cycleId, groupIdMode, outputDir, OptionalLong.empty());
    }

    /** Whether the counts are schema 2 and the accounts per row. */
    boolean v2() {
        return loadExecutionId.isPresent();
    }

    /** Whether the deprecated {@code --fidelity} was given in a v2 command line. */
    boolean fidelityDeprecated() {
        return v2() && groupIdMode != null;
    }

    static ExportOptions parse(String[] args) {
        Path exportDir = null;
        Job job = null;
        Long cycleId = null;
        FidelityMode mode = null;
        Path outputDir = null;
        Long loadExecutionId = null;
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (i + 1 >= args.length) {
                throw new UsageException("missing value for " + option);
            }
            String value = args[++i];
            switch (option) {
                case "--export" -> exportDir = Path.of(value);
                case "--job" -> {
                    try {
                        job = Job.valueOf(value);
                    } catch (IllegalArgumentException e) {
                        throw new UsageException("--job must be POSTTRAN or INTCALC");
                    }
                }
                case "--cycle-id" -> cycleId = id(option, value);
                case "--load-execution-id" -> loadExecutionId = executionId(option, value);
                case "--fidelity" -> {
                    String prefix = BatchSwitches.DEF_ACUP_002.itemId() + "=";
                    if (!value.startsWith(prefix)) {
                        throw new UsageException("--fidelity takes " + prefix + "legacy|fixed");
                    }
                    try {
                        mode = FidelityMode.parse(value.substring(prefix.length()));
                    } catch (IllegalArgumentException e) {
                        throw new UsageException(e.getMessage());
                    }
                }
                case "--output-dir" -> outputDir = Path.of(value);
                case "--load-report" -> throw new UsageException("--load-report takes no other option");
                default -> throw new UsageException("unknown option " + option);
            }
        }
        if (loadExecutionId == null) {
            if (exportDir == null || job == null || cycleId == null || mode == null) {
                throw new UsageException("--export, --job, --cycle-id and --fidelity are required");
            }
        } else if (exportDir == null || job == null || cycleId == null) {
            throw new UsageException("--export, --job and --cycle-id are required");
        }
        return new ExportOptions(exportDir, job, cycleId, mode, Optional.ofNullable(outputDir),
                loadExecutionId == null ? OptionalLong.empty() : OptionalLong.of(loadExecutionId));
    }

    /** {@code --load-report N}, the only argument of the report mode; empty when the arguments are an export. */
    static OptionalLong loadReport(String[] args) {
        boolean asked = false;
        for (String a : args) {
            asked |= a.equals("--load-report");
        }
        if (!asked) {
            return OptionalLong.empty();
        }
        if (args.length != 2 || !args[0].equals("--load-report")) {
            throw new UsageException("--load-report takes no other option");
        }
        return OptionalLong.of(executionId("--load-report", args[1]));
    }

    /**
     * A job execution ID in canonical decimal: {@code 0} or digits without a leading zero, no sign,
     * within a signed 64-bit value. Spring Batch's SQL Server sequences start at 0, so the first
     * execution of a fresh job repository is 0.
     */
    static long executionId(String option, String value) {
        if (!CANONICAL_ID.matcher(value).matches()) {
            throw new UsageException(option + " must be 0 or a whole number without sign or leading zero");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new UsageException(option + " must be at most " + Long.MAX_VALUE);
        }
    }

    static long id(String option, String value) {
        long id;
        try {
            id = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new UsageException(option + " must be a number");
        }
        if (id < 1) {
            throw new UsageException(option + " must be at least 1");
        }
        return id;
    }
}
