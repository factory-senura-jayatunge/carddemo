package org.carddemo.db;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Consumer;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.outputroot.OutputRootException;

/**
 * db-migrate's part of the output-root protocol (batch-execution-contract.md section 4.10): after
 * the migrations it creates this instance's root, so every target that migrates ends with the root
 * and its marker in place, and lists what it keeps from earlier instances and layouts.
 */
final class OutputRootStep {

    /** The rotation of 4.10.1, run as the database owner after a restore or a copy of the database. */
    static final String ROTATE_SQL = "UPDATE ops.database_instance SET instance_id = NEWID(),"
            + " created_at = SYSUTCDATETIME() WHERE id = 1";

    /** The fixed reasons of {@link #failure}: which of db-migrate's two output-root steps failed. */
    static final String VAR_DIR_CHECK = "CARDDEMO_VAR_DIR check";
    static final String ROOT_STEP = "output root step";

    private OutputRootStep() {
    }

    /**
     * Rotates the marker when {@code newInstance}, then reads it on {@code connection}, ensures the
     * root under {@code varDir} and reports the instance and every kept entry to {@code out}. The
     * ready line names the instance, not the root: the kept listing is the only console line that
     * may hold a path below CARDDEMO_VAR_DIR (batch-execution-contract.md 4.10).
     */
    static Path run(Connection connection, Path varDir, boolean newInstance, Consumer<String> out) throws SQLException {
        if (newInstance) {
            try (Statement statement = connection.createStatement()) {
                int rows = statement.executeUpdate(ROTATE_SQL);
                if (rows != 1) {
                    throw new OutputRootException(OutputRootException.Code.OUTPUT_ROOT_INVALID,
                            "ops.database_instance has no row 1 to rotate");
                }
            }
        }
        OutputRoot.Instance instance = OutputRoot.read(connection);
        Path root = OutputRoot.ensure(varDir, instance);
        out.accept("db-migrate: output root ready (" + (newInstance ? "new instance " : "instance ") + instance.instanceText() + ")");
        for (OutputRoot.Kept kept : OutputRoot.kept(varDir, instance)) {
            out.accept("db-migrate: kept, no longer read: " + kept.kind() + " " + kept.path()
                    + " (" + kept.files() + (kept.files() == 1 ? " file)" : " files)"));
        }
        return root;
    }

    /**
     * The failure line for an output-root refusal or an I/O error of {@code step}: the code and the
     * fixed reason, or the I/O exception's class. The module's messages name paths below
     * CARDDEMO_VAR_DIR, and no path below it may reach the console except the kept listing
     * (batch-execution-contract.md 4.10, Messages outside the root), so none is printed.
     */
    static String failure(String step, RuntimeException e) {
        if (e instanceof OutputRootException refusal) {
            // The legacy-setting message names the setting only, never its value (contract section 4),
            // and the operator needs that name to remove it.
            return refusal.code() == OutputRootException.Code.OUTPUT_ROOT_LEGACY_SETTING
                    ? refusal.getMessage() : refusal.code().name() + ": " + step;
        }
        Throwable cause = e instanceof UncheckedIOException && e.getCause() != null ? e.getCause() : e;
        return "OUTPUT_ROOT_IO_FAILED: " + step + ", " + cause.getClass().getSimpleName();
    }

    /**
     * The failure line for a SQL error of {@link #run}: the exception's class, error number and
     * SQLState only. The driver's message can quote statement text and server detail, so it is
     * never printed, as for every other db-migrate failure.
     */
    static String sqlFailure(SQLException e) {
        return "OUTPUT_ROOT_SQL_FAILED: " + e.getClass().getSimpleName() + ", error " + e.getErrorCode()
                + ", SQLState " + (e.getSQLState() == null ? "none" : e.getSQLState());
    }
}
