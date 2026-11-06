package org.carddemo.db;

import java.util.List;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Runs the migrations: {@code java -jar db-migrate-exec.jar [--reset] [--check-logins]}.
 * Without options it creates the database if it is missing, then provisions the logins,
 * migrates and validates. {@code --reset} drops and recreates the database first.
 * {@code --check-logins} then verifies every service login against its grants.
 */
@SpringBootApplication
public class DbMigrateApplication {

    private static final Logger log = LoggerFactory.getLogger(DbMigrateApplication.class);

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(DbMigrateApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        System.exit(SpringApplication.exit(app.run(args)));
    }

    @Bean
    Runner runner() {
        return new Runner();
    }

    static final class Runner implements ApplicationRunner, ExitCodeGenerator {

        private int exitCode = 1;

        @Override
        public void run(ApplicationArguments args) throws Exception {
            DbSettings settings = DbSettings.fromEnvironment(System::getenv);
            DatabaseProvisioner provisioner = new DatabaseProvisioner(settings);
            log.info("db-migrate: {}", settings);
            if (args.containsOption("reset")) {
                provisioner.recreateDatabase();
                log.info("db-migrate: database {} dropped and created", settings.databaseName());
            } else if (provisioner.ensureDatabase()) {
                log.info("db-migrate: database {} created", settings.databaseName());
            }
            provisioner.provisionLogins();
            MigrateResult result = CarddemoMigrations.migrate(provisioner.databaseAdmin());
            var current = CarddemoMigrations.flyway(provisioner.databaseAdmin()).info().current();
            log.info("db-migrate: {} migration(s) applied, schema version {}",
                    result.migrationsExecuted, current == null ? "none" : current.getVersion());
            if (args.containsOption("check-logins")) {
                List<String> failures = new LoginCheck(provisioner).run();
                failures.forEach(f -> log.error("db-migrate: {}", f));
                if (!failures.isEmpty()) {
                    exitCode = 1;
                    System.out.println("db-migrate: FAILED (" + failures.size() + " login check(s))");
                    return;
                }
                log.info("db-migrate: every service login is limited to its grants");
            }
            exitCode = 0;
            System.out.println("db-migrate: OK");
        }

        @Override
        public int getExitCode() {
            return exitCode;
        }
    }
}
