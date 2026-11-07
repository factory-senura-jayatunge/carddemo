package org.carddemo.verify.data;

import java.nio.file.Path;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import org.carddemo.batch.BatchSwitches;
import org.carddemo.db.DbSettings;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code make verify-data}. Reads as the admin login of {@code modernization/.env}
 * (through {@code scripts/with_env.py}), because the check spans the core and iam
 * schemas, which no single service may read, and must not depend on the grants it
 * checks. It only reads. Exit status 0 when every check passes, 1 otherwise.
 *
 * <p>Options: {@code --source-dir DIR} (default {@code CARDDEMO_LOAD_SOURCE_DIR}, else
 * {@code ../app/data/ASCII}), {@code --users-jcl FILE} (default {@code ../app/jcl/DUSRSECJ.jcl}),
 * {@code --fidelity DEF-ACUP-002=legacy|fixed} (the mode the accounts were loaded in; default as
 * {@link GroupIdMode} resolves it).
 */
public final class VerifyData {

    private VerifyData() {
    }

    public static void main(String[] args) {
        String sourceDir = System.getenv().getOrDefault("CARDDEMO_LOAD_SOURCE_DIR", "../app/data/ASCII");
        String usersJcl = "../app/jcl/DUSRSECJ.jcl";
        String groupIdMode = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--source-dir" -> sourceDir = value(args, ++i);
                case "--users-jcl" -> usersJcl = value(args, ++i);
                case "--fidelity" -> {
                    String setting = value(args, ++i);
                    String prefix = BatchSwitches.DEF_ACUP_002.itemId() + "=";
                    if (!setting.startsWith(prefix)) {
                        usage();
                    }
                    groupIdMode = setting.substring(prefix.length());
                }
                default -> usage();
            }
        }
        GroupIdMode mode;
        try {
            mode = GroupIdMode.resolve(groupIdMode, System.getenv(), Path.of(""));
        } catch (IllegalArgumentException e) {
            System.err.println("verify-data: " + e.getMessage());
            System.exit(2);
            return;
        }
        DbSettings settings = DbSettings.fromEnvironment(System::getenv);
        SQLServerDataSource ds = new SQLServerDataSource();
        ds.setURL(settings.databaseUrl());
        ds.setUser(settings.adminUser());
        ds.setPassword(settings.adminPassword());
        ds.setApplicationIntent("ReadOnly");
        Report report = new DataReconciliation(JdbcClient.create(ds), Path.of(sourceDir), Path.of(usersJcl), mode).run();
        System.out.println("database " + settings.databaseName() + ", sample files " + Path.of(sourceDir).toAbsolutePath().normalize());
        report.print(System.out);
        System.exit(report.ok() ? 0 : 1);
    }

    private static void usage() {
        System.err.println("usage: verify-data [--source-dir DIR] [--users-jcl FILE] [--fidelity DEF-ACUP-002=legacy|fixed]");
        System.exit(2);
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            System.err.println("missing value for " + args[i - 1]);
            System.exit(2);
        }
        return args[i];
    }
}
