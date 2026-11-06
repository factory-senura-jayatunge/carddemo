package org.carddemo.db;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Creates the database and the service logins. Flyway cannot do either: CREATE and ALTER
 * DATABASE are not allowed in a transaction, and login passwords are secrets that must not end
 * up in a migration script or its checksum.
 */
public final class DatabaseProvisioner {

    /** Database default collation; key columns use Latin1_General_100_BIN2 (byte order, as in VSAM). */
    public static final String COLLATION = "Latin1_General_100_CI_AS_SC_UTF8";

    private final DbSettings settings;

    public DatabaseProvisioner(DbSettings settings) {
        this.settings = settings;
    }

    public static DataSource dataSource(String url, String user, String password) {
        SQLServerDataSource ds = new SQLServerDataSource();
        ds.setURL(url);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    public DataSource serverAdmin() {
        return dataSource(settings.serverUrl(), settings.adminUser(), settings.adminPassword());
    }

    public DataSource databaseAdmin() {
        return dataSource(settings.databaseUrl(), settings.adminUser(), settings.adminPassword());
    }

    public DataSource service(ServiceLogin login) {
        return dataSource(settings.databaseUrl(), settings.loginName(login), settings.password(login));
    }

    /** Creates the database if it does not exist. Returns true when it was created. */
    public boolean ensureDatabase() throws SQLException {
        try (Connection c = serverAdmin().getConnection()) {
            if (exists(c)) {
                return false;
            }
            create(c);
            return true;
        }
    }

    /** Drops the database if it exists and creates it empty. */
    public void recreateDatabase() throws SQLException {
        try (Connection c = serverAdmin().getConnection(); Statement s = c.createStatement()) {
            if (exists(c)) {
                String db = quoted(settings.databaseName());
                s.execute("ALTER DATABASE " + db + " SET SINGLE_USER WITH ROLLBACK IMMEDIATE");
                s.execute("DROP DATABASE " + db);
            }
            create(c);
        }
    }

    /**
     * Creates or updates each service login with its password and maps the database user
     * {@code <id>_svc} to it. Runs on every migrate, so a password changed in .env takes effect.
     */
    public void provisionLogins() throws SQLException {
        try (Connection c = databaseAdmin().getConnection()) {
            for (ServiceLogin login : ServiceLogin.values()) {
                String name = settings.loginName(login);
                String password = settings.password(login);
                boolean loginExists;
                try (PreparedStatement ps = c.prepareStatement("SELECT SUSER_ID(?)")) {
                    ps.setString(1, name);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        loginExists = rs.getObject(1) != null;
                    }
                }
                String sql = (loginExists ? "ALTER LOGIN " : "CREATE LOGIN ") + quoted(name)
                        + " WITH PASSWORD = " + literal(password)
                        + (loginExists ? "" : ", DEFAULT_DATABASE = " + quoted(settings.databaseName()));
                try (Statement s = c.createStatement()) {
                    s.execute(sql);
                }
                boolean userExists;
                try (PreparedStatement ps = c.prepareStatement("SELECT DATABASE_PRINCIPAL_ID(?)")) {
                    ps.setString(1, login.userName());
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        userExists = rs.getObject(1) != null;
                    }
                }
                try (Statement s = c.createStatement()) {
                    s.execute((userExists ? "ALTER USER " + quoted(login.userName()) + " WITH LOGIN = "
                            : "CREATE USER " + quoted(login.userName()) + " FOR LOGIN ") + quoted(name));
                }
            }
        }
    }

    private boolean exists(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT DB_ID(?)")) {
            ps.setString(1, settings.databaseName());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1) != null;
            }
        }
    }

    private void create(Connection c) throws SQLException {
        String db = quoted(settings.databaseName());
        try (Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + db + " COLLATE " + COLLATION);
            // WRITES_BLOCKED keeps reads working while batch writes (D-BAT-05): readers use row versions.
            s.execute("ALTER DATABASE " + db + " SET READ_COMMITTED_SNAPSHOT ON WITH ROLLBACK IMMEDIATE");
            s.execute("ALTER DATABASE " + db + " SET ALLOW_SNAPSHOT_ISOLATION ON");
        }
    }

    static String quoted(String name) {
        return "[" + name.replace("]", "]]") + "]";
    }

    static String literal(String value) {
        return "N'" + value.replace("'", "''") + "'";
    }
}
