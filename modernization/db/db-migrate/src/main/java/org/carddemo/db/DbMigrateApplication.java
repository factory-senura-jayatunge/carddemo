package org.carddemo.db;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.outputroot.OutputRootException;
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
import org.springframework.core.env.Environment;

/**
 * Runs the migrations: {@code java -jar db-migrate-exec.jar [--reset] [--new-instance] [--check-logins]}.
 * It first checks CARDDEMO_VAR_DIR (absolute, no removed output setting). Without options it then
 * creates the database if it is missing, provisions the logins, migrates, validates and creates
 * this database instance's output root, listing what earlier instances left as kept.
 * {@code --reset} drops and recreates the database first. {@code --new-instance} gives the database
 * a new instance ID after the migrations, as the restore runbook requires, so a restored copy never
 * writes into the root of the instance it was copied from. {@code --check-logins} then verifies
 * every service login against its grants: its probes, its UNMASK and its effective SELECT and
 * EXECUTE ({@link LoginCheck}).
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
    Runner runner(Environment environment) {
        return new Runner(environment);
    }

    static final class Runner implements ApplicationRunner, ExitCodeGenerator {

        private final Environment environment;
        private int exitCode = 1;

        Runner(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void run(ApplicationArguments args) throws Exception {
            Path varDir;
            try {
                varDir = OutputRoot.varDir(environment::getProperty);
            } catch (OutputRootException | UncheckedIOException e) {
                System.out.println("db-migrate: FAILED (" + OutputRootStep.failure(OutputRootStep.VAR_DIR_CHECK, e) + ")");
                return;
            }
            DbSettings settings = DbSettings.fromEnvironment(System::getenv);
            DatabaseProvisioner provisioner = new DatabaseProvisioner(settings);
            log.info("db-migrate: {}, var dir {}", settings, varDir);
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
            try (Connection c = provisioner.databaseAdmin().getConnection()) {
                OutputRootStep.run(c, varDir, args.containsOption("new-instance"), System.out::println);
            } catch (OutputRootException | UncheckedIOException e) {
                System.out.println("db-migrate: FAILED (" + OutputRootStep.failure(OutputRootStep.ROOT_STEP, e) + ")");
                return;
            } catch (SQLException e) {
                System.out.println("db-migrate: FAILED (" + OutputRootStep.sqlFailure(e) + ")");
                return;
            }
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
