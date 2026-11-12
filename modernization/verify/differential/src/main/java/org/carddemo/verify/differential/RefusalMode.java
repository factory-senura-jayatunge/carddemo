package org.carddemo.verify.differential;

import static org.carddemo.verify.differential.RefusalCodes.DATABASE_READ_FAILED;
import static org.carddemo.verify.differential.RefusalCodes.REFUSAL_INTERNAL_ERROR;

import java.io.OutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/**
 * {@code verify-differential --refusal ...}: the evidence of a legacy statementJob refusal, as
 * {@code refusal.json} in {@code --output-dir}. It reads as the admin login with
 * {@code ApplicationIntent=ReadOnly} ({@link ExportBatch}), on one connection, and writes nothing
 * else. The steps are {@link RefusalRun}'s.
 *
 * <ul>
 * <li>Completed: {@code refusal.json} is written, nothing is printed, exit status 0.
 * <li>Refused: stdout is empty, stderr is exactly one line {@code verify-differential: FAILED <code>}
 * with a code of {@link RefusalCodes}, no {@code refusal.json} is written, exit status 1. Every
 * throwable that no step maps is {@code refusal-internal-error}, with no message, cause or trace.
 * From the database connection on, nothing else reaches stderr: see {@link #quietly}.
 * <li>A bad command line, or missing or invalid database settings: exit status 2, in the
 * exporter's existing form.
 * </ul>
 */
final class RefusalMode {

    /**
     * The production wiring of step 3. It stays {@code false}, so the mode refuses
     * {@code work-folder-unsupported} after steps 1 and 2 on every platform, until enabling the
     * helper is reviewed and approved as a change of its own. No setting, property or option can
     * change it.
     */
    static final boolean HELPER_ENABLED = false;

    private RefusalMode() {
    }

    /** The mode's exit status; called by {@link ExportBatch#main} before any other mode reads the arguments. */
    static int main(String[] args) {
        return main(args, System.err);
    }

    static int main(String[] args, PrintStream err) {
        try {
            RefusalOptions options;
            try {
                options = RefusalOptions.parse(args);
            } catch (UsageException e) {
                err.println("verify-differential: " + e.getMessage());
                err.println(ExportOptions.USAGE);
                return ExportBatch.BAD_SETTINGS;
            }
            SQLServerDataSource ds = ExportBatch.dataSource();
            if (ds == null) {
                return ExportBatch.BAD_SETTINGS;
            }
            return quietly(err, out -> connected(ds::getConnection, options, out));
        } catch (Throwable t) {
            return failed(err, REFUSAL_INTERNAL_ERROR);
        }
    }

    /** The mode's steps from the database connection on; {@code err} is the only stream for the mode's line. */
    interface Steps {
        int run(PrintStream err);
    }

    interface ConnectionSource {
        Connection open() throws SQLException;
    }

    /**
     * Runs the steps so that only their own line reaches stderr, whatever the driver, Spring or any
     * other code logs or prints meanwhile. {@code System.err} discards for the duration, which also
     * silences Logback's console appender, as it looks {@code System.err} up on every write; a JUL
     * {@link ConsoleHandler} keeps the stream it was created with, so every one that exists is set to
     * {@code OFF}, and one created meanwhile gets the discarding stream. Both are restored on return.
     */
    static int quietly(PrintStream err, Steps steps) {
        PrintStream saved = System.err;
        Map<Handler, Level> levels = new IdentityHashMap<>();
        try {
            silenceConsoleHandlers(levels);
            System.setErr(new PrintStream(OutputStream.nullOutputStream()));
            return steps.run(err);
        } finally {
            System.setErr(saved);
            levels.forEach(Handler::setLevel);
        }
    }

    private static void silenceConsoleHandlers(Map<Handler, Level> levels) {
        LogManager manager = LogManager.getLogManager();
        Set<String> names = new LinkedHashSet<>();
        // The root logger creates its default handlers on first access, still bound to the real stderr.
        names.add("");
        names.addAll(Collections.list(manager.getLoggerNames()));
        for (String name : names) {
            Logger logger = manager.getLogger(name);
            if (logger == null) {
                continue;
            }
            for (Handler handler : logger.getHandlers()) {
                if (handler instanceof ConsoleHandler && !levels.containsKey(handler)) {
                    levels.put(handler, handler.getLevel());
                    handler.setLevel(Level.OFF);
                }
            }
        }
    }

    static int connected(ConnectionSource source, RefusalOptions options, PrintStream err) {
        Connection connection;
        try {
            connection = source.open();
        } catch (SQLException e) {
            return failed(err, DATABASE_READ_FAILED);
        }
        try {
            RefusalReads reads;
            try {
                reads = new JdbcRefusalReads(connection);
            } catch (ExportException | DataAccessException | TransactionException e) {
                return failed(err, DATABASE_READ_FAILED);
            }
            return run(options, reads, System::getenv, err);
        } finally {
            close(connection);
        }
    }

    /** The mode as production wires it, over the given reads and environment. */
    static int run(RefusalOptions options, RefusalReads reads, Function<String, String> environment,
            PrintStream err) {
        return report(new RefusalRun(reads, environment, HELPER_ENABLED,
                new WorkFolderHelper(environment, ProcessUid::current)), options, err);
    }

    static int report(RefusalRun run, RefusalOptions options, PrintStream err) {
        try {
            run.run(options);
            return 0;
        } catch (RefusalException e) {
            return failed(err, e.code());
        } catch (Throwable t) {
            return failed(err, REFUSAL_INTERNAL_ERROR);
        }
    }

    private static int failed(PrintStream err, String code) {
        err.println(RefusalCodes.failedLine(code));
        err.flush();
        return 1;
    }

    /**
     * Every read is done once the connection closes; a failure to close it, an error included,
     * changes no result, and a written {@code refusal.json} is never reported as a refusal.
     */
    static void close(Connection connection) {
        try {
            connection.close();
        } catch (Throwable t) {
            // Nothing to report: see above.
        }
    }
}
