package org.carddemo.verify.differential;

import java.util.Map;
import java.util.OptionalLong;

import javax.sql.DataSource;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import org.carddemo.db.DbSettings;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JdbcJobRepositoryFactoryBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionException;

/**
 * {@code make verify-batch}'s exporter (see {@link BatchExport}). Reads as the admin login of
 * {@code modernization/.env} (through {@code scripts/with_env.py}), as verify-data does, because
 * it reads the job repository and the card numbers that batch_svc reads unmasked. It only reads.
 * Exit status 0 when the export is written, 1 when the database does not hold one complete,
 * consistent run of the job or a file cannot be written, 2 for a bad command line or missing or
 * invalid database settings. Output names jobs, IDs, counts and setting names only.
 *
 * <p>A database that cannot be reached shows as Spring's {@link TransactionException} (the job
 * repository opens a transaction first) or a {@link DataAccessException}; both are a FAILED line.
 *
 * <p>{@code --load-report N} prints the {@link LoadReport} of {@code dataLoadJob} execution N as
 * one JSON object on stdout and nothing else: exit 0 when printed, 1 when N is not a valid,
 * completed, last load or the database cannot be read, 2 for a bad command line or settings.
 *
 * <p>{@code --refusal} selects {@link RefusalMode}, which reads the arguments alone and has its own
 * closed set of failure lines; none of the handlers here sees it.
 */
public final class ExportBatch {

    /** batch-service's {@code spring.batch.jdbc.table-prefix} (V401). */
    static final String TABLE_PREFIX = "batch.BATCH_";

    static final int BAD_SETTINGS = 2;

    private ExportBatch() {
    }

    public static void main(String[] args) {
        if (RefusalOptions.requested(args)) {
            System.exit(RefusalMode.main(args));
            return;
        }
        ExportOptions options;
        OptionalLong loadReport;
        try {
            loadReport = ExportOptions.loadReport(args);
            options = loadReport.isPresent() ? null : ExportOptions.parse(args);
        } catch (UsageException e) {
            System.err.println("verify-differential: " + e.getMessage());
            System.err.println(ExportOptions.USAGE);
            System.exit(2);
            return;
        }
        if (loadReport.isPresent()) {
            System.exit(loadReport(loadReport.getAsLong()));
            return;
        }
        if (options.fidelityDeprecated()) {
            System.err.println(ExportOptions.FIDELITY_DEPRECATED);
        }
        SQLServerDataSource ds = dataSource();
        if (ds == null) {
            System.exit(BAD_SETTINGS);
            return;
        }
        if (options.v2()) {
            System.exit(exportV2(ds, options));
            return;
        }
        try {
            Map<String, Object> counts = new BatchExport(JdbcClient.create(ds), repository(ds)).run(options);
            System.out.println("verify-differential: " + options.job() + " cycle " + options.cycleId() + " exported "
                    + counts);
            System.exit(0);
        } catch (UsageException e) {
            System.err.println("verify-differential: " + e.getMessage());
            System.exit(2);
        } catch (ExportException e) {
            System.err.println("verify-differential: FAILED: " + e.getMessage());
            System.exit(1);
        } catch (DataAccessException | TransactionException e) {
            System.err.println("verify-differential: FAILED: the database cannot be read (" + e.getClass().getSimpleName()
                    + ")");
            System.exit(1);
        }
    }

    /** @return the admin data source, or {@code null} after one stderr line when the settings are missing or invalid */
    static SQLServerDataSource dataSource() {
        DbSettings settings;
        try {
            settings = DbSettings.fromEnvironment(System::getenv);
        } catch (IllegalStateException e) {
            // DbSettings names the missing variables only.
            System.err.println("verify-differential: FAILED: " + e.getMessage());
            return null;
        } catch (IllegalArgumentException e) {
            // Its message quotes the offending value, which may be the server URL.
            System.err.println("verify-differential: FAILED: the database settings are invalid ("
                    + e.getClass().getSimpleName() + ")");
            return null;
        }
        SQLServerDataSource ds = new SQLServerDataSource();
        ds.setURL(settings.databaseUrl());
        ds.setUser(settings.adminUser());
        ds.setPassword(settings.adminPassword());
        ds.setApplicationIntent("ReadOnly");
        return ds;
    }

    private static int loadReport(long executionId) {
        SQLServerDataSource ds = dataSource();
        if (ds == null) {
            return BAD_SETTINGS;
        }
        byte[] json;
        try {
            json = LoadReport.read(repository(ds), executionId).json();
        } catch (ExportException e) {
            System.err.println("verify-differential: FAILED: " + e.getMessage());
            return 1;
        } catch (DataAccessException | TransactionException e) {
            System.err.println("verify-differential: FAILED: the database cannot be read (" + e.getClass().getSimpleName()
                    + ")");
            return 1;
        }
        return print(json);
    }

    /**
     * The report on stdout, which carries nothing else: logback.xml sends every log line to
     * stderr, so a caller can parse stdout whole.
     */
    static int print(byte[] json) {
        System.out.write(json, 0, json.length);
        System.out.flush();
        return System.out.checkError() ? 1 : 0;
    }

    private static int exportV2(SQLServerDataSource ds, ExportOptions options) {
        try {
            Map<String, Object> counts = new BatchExport(JdbcClient.create(ds), repository(ds)).run(options);
            System.out.println("verify-differential: " + options.job() + " cycle " + options.cycleId() + " exported "
                    + counts);
            return 0;
        } catch (UsageException e) {
            System.err.println("verify-differential: " + e.getMessage());
            return 2;
        } catch (ExportException e) {
            System.err.println("verify-differential: FAILED: " + e.getMessage());
            return 1;
        } catch (DataAccessException | TransactionException e) {
            System.err.println("verify-differential: FAILED: the database cannot be read (" + e.getClass().getSimpleName()
                    + ")");
            return 1;
        }
    }

    /** A read-only view of batch-service's job repository, with Spring Batch's default context serializer. */
    static JobRepository repository(DataSource ds) {
        JdbcJobRepositoryFactoryBean factory = new JdbcJobRepositoryFactoryBean();
        factory.setDataSource(ds);
        factory.setTransactionManager(new JdbcTransactionManager(ds));
        factory.setDatabaseType("SQLSERVER");
        factory.setTablePrefix(TABLE_PREFIX);
        try {
            factory.afterPropertiesSet();
            return factory.getObject();
        } catch (Exception e) {
            throw new ExportException("cannot open the job repository", e);
        }
    }
}
