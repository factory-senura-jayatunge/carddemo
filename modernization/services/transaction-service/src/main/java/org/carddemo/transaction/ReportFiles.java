package org.carddemo.transaction;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.carddemo.platform.outputroot.OutputRoot;

/**
 * The files of batch-service's {@code transactionReportJob}: {@code reports/tranrept/<runId>/TRANREPT.txt}
 * under the database instance's output root in CARDDEMO_VAR_DIR (batch-execution-contract.md 4.10).
 * Read only: the root is resolved again for each download, by the run's number only, and never
 * created or repaired here.
 */
public class ReportFiles {

    static final String FILE_NAME = "TRANREPT.txt";

    private final Path varDir;

    public ReportFiles(Path varDir) {
        this.varDir = Objects.requireNonNull(varDir, "varDir");
    }

    /**
     * The run's file; empty when the root, the run's folder or the file is missing. A root of
     * another instance or without its marker, or a path that is a link or open to others, is an
     * {@code OutputRootException}.
     */
    public Optional<Path> find(OutputRoot.Instance instance, long runId) {
        return OutputRoot.resolve(varDir, instance)
                .flatMap(root -> OutputRoot.existingFile(root, "reports", "tranrept", Long.toString(runId), FILE_NAME));
    }
}
