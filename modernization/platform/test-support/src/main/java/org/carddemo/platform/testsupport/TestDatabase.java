package org.carddemo.platform.testsupport;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.sql.DataSource;
import org.carddemo.db.CarddemoMigrations;
import org.carddemo.db.DatabaseProvisioner;
import org.carddemo.db.DbSettings;
import org.carddemo.db.ServiceLogin;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One freshly migrated CardDemo database per test JVM, with the real migrations, logins and
 * grants.
 *
 * <p>The server is the one named by {@code CARDDEMO_TEST_MSSQL_URL} (the compose SQL Server;
 * credentials {@code CARDDEMO_TEST_MSSQL_USER}, default sa, and {@code CARDDEMO_TEST_MSSQL_PASSWORD}),
 * or, when that is unset, a Testcontainers SQL Server with the compose file's image (CI). The
 * database is {@code <CARDDEMO_TEST_MSSQL_DB>_<module>} (base default {@code carddemo_test}), so
 * Maven modules and agents that share one server never touch each other's data. It is dropped
 * and created again when the JVM first asks for it. Service logins get random passwords.
 */
public final class TestDatabase {

    private static final String DEFAULT_IMAGE =
            "mcr.microsoft.com/mssql/server:2025-CU10-ubuntu-24.04@sha256:245f6b1c8903edae52cb20fa31419cefcde1a49938bb8c3447099011fc871ca3";

    private static volatile TestDatabase shared;

    private final DbSettings settings;
    private final HikariDataSource admin;
    private final Map<ServiceLogin, HikariDataSource> services = new ConcurrentHashMap<>();

    private TestDatabase(DbSettings settings) {
        this.settings = settings;
        this.admin = pool(settings.databaseUrl(), settings.adminUser(), settings.adminPassword(), "admin");
    }

    /** The database of this JVM, created and migrated on the first call. */
    public static TestDatabase shared() {
        TestDatabase db = shared;
        if (db == null) {
            synchronized (TestDatabase.class) {
                db = shared;
                if (db == null) {
                    db = start(System::getenv);
                    shared = db;
                }
            }
        }
        return db;
    }

    static TestDatabase start(Function<String, String> env) {
        String url = env.apply("CARDDEMO_TEST_MSSQL_URL");
        String user = orDefault(env.apply("CARDDEMO_TEST_MSSQL_USER"), "sa");
        String password = env.apply("CARDDEMO_TEST_MSSQL_PASSWORD");
        if (blank(url)) {
            MSSQLServerContainer container = Container.INSTANCE;
            url = "jdbc:sqlserver://" + container.getHost() + ":" + container.getMappedPort(1433)
                    + ";encrypt=true;trustServerCertificate=true";
            user = container.getUsername();
            password = container.getPassword();
        } else if (blank(password)) {
            throw new IllegalStateException("CARDDEMO_TEST_MSSQL_URL is set but CARDDEMO_TEST_MSSQL_PASSWORD is not;"
                    + " run the build through modernization/scripts/with_env.py");
        }
        String database = databaseName(orDefault(env.apply("CARDDEMO_TEST_MSSQL_DB"), "carddemo_test"),
                System.getProperty("carddemo.test.module", ""));
        Map<ServiceLogin, String> passwords = new EnumMap<>(ServiceLogin.class);
        for (ServiceLogin login : ServiceLogin.values()) {
            passwords.put(login, randomPassword());
        }
        DbSettings settings = new DbSettings(url, user, password, database, database + "_", passwords);
        DatabaseProvisioner provisioner = new DatabaseProvisioner(settings);
        try {
            provisioner.recreateDatabase();
            provisioner.provisionLogins();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create test database " + database + " on " + url, e);
        }
        TestDatabase db = new TestDatabase(settings);
        CarddemoMigrations.migrate(db.admin);
        return db;
    }

    static String databaseName(String base, String module) {
        String suffix = module.isBlank() ? "" : "_" + module;
        return (base + suffix).replaceAll("[^A-Za-z0-9_]", "_").toLowerCase(Locale.ROOT);
    }

    public DbSettings settings() {
        return settings;
    }

    public String jdbcUrl() {
        return settings.databaseUrl();
    }

    public String databaseName() {
        return settings.databaseName();
    }

    /** Admin access (owner of every table, sees unmasked data) for fixtures and assertions. */
    public DataSource adminDataSource() {
        return admin;
    }

    public JdbcClient admin() {
        return JdbcClient.create(admin);
    }

    /**
     * A pool that connects as the service's own login, so the test runs under its grants.
     *
     * <p>The pool lives as long as the JVM. The returned wrapper is not {@link AutoCloseable}, so a
     * Spring context that registers it as a bean cannot close it for the tests that follow.
     */
    public DataSource dataSource(ServiceLogin login) {
        return new DelegatingDataSource(services.computeIfAbsent(login, l ->
                pool(settings.databaseUrl(), settings.loginName(l), settings.password(l), l.id())));
    }

    public String loginName(ServiceLogin login) {
        return settings.loginName(login);
    }

    public String password(ServiceLogin login) {
        return settings.password(login);
    }

    /** Points a Spring test's {@code spring.datasource.*} at this database as the given service login. */
    public void register(DynamicPropertyRegistry registry, ServiceLogin login) {
        registry.add("spring.datasource.url", this::jdbcUrl);
        registry.add("spring.datasource.username", () -> loginName(login));
        registry.add("spring.datasource.password", () -> password(login));
    }

    public CoreFixtures fixtures() {
        return new CoreFixtures(admin());
    }

    private static HikariDataSource pool(String url, String user, String password, String name) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setPoolName("test-" + name);
        return new HikariDataSource(config);
    }

    private static String randomPassword() {
        String alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder("Tt9-");
        for (int i = 0; i < 24; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String orDefault(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** Started once per JVM on first use; Testcontainers' reaper removes it when the JVM ends. */
    private static final class Container {
        static final MSSQLServerContainer INSTANCE = start();

        private static MSSQLServerContainer start() {
            DockerImageName image = DockerImageName.parse(System.getProperty("carddemo.mssql.image", DEFAULT_IMAGE))
                    .asCompatibleSubstituteFor("mcr.microsoft.com/mssql/server");
            MSSQLServerContainer container = new MSSQLServerContainer(image)
                    .acceptLicense()
                    .withEnv("MSSQL_MEMORY_LIMIT_MB", "2048");
            container.start();
            return container;
        }
    }
}
