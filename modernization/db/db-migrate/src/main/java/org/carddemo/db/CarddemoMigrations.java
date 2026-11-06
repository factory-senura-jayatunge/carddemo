package org.carddemo.db;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/** The Flyway configuration of the CardDemo database; db-migrate and test-support both use it. */
public final class CarddemoMigrations {

    /** modernization/db/migrations, packaged into the db-migrate jar. */
    public static final String LOCATION = "classpath:db/migration/carddemo";

    private CarddemoMigrations() {
    }

    public static Flyway flyway(DataSource databaseAdmin) {
        return Flyway.configure()
                .dataSource(databaseAdmin)
                .locations(LOCATION)
                .validateMigrationNaming(true)
                .placeholderReplacement(false)
                .cleanDisabled(true)
                .load();
    }

    public static MigrateResult migrate(DataSource databaseAdmin) {
        Flyway flyway = flyway(databaseAdmin);
        MigrateResult result = flyway.migrate();
        flyway.validate();
        return result;
    }
}
