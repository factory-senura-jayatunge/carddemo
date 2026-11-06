package org.carddemo.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Where and how db-migrate connects.
 *
 * @param serverUrl JDBC URL of the server, without databaseName
 * @param adminUser login that may create databases and logins (sa locally)
 * @param databaseName the application database, e.g. carddemo
 * @param loginPrefix prefix of the service logins, by default {@code <databaseName>_}
 * @param passwords password of each service login
 */
public record DbSettings(String serverUrl, String adminUser, String adminPassword, String databaseName,
                         String loginPrefix, Map<ServiceLogin, String> passwords) {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,99}$");

    public DbSettings {
        Objects.requireNonNull(serverUrl, "serverUrl");
        Objects.requireNonNull(adminUser, "adminUser");
        Objects.requireNonNull(adminPassword, "adminPassword");
        requireName("database name", databaseName);
        requireName("login prefix", loginPrefix + "x");
        if (serverUrl.toLowerCase().contains("databasename=")) {
            throw new IllegalArgumentException("the server URL must not name a database: " + serverUrl);
        }
        passwords = Collections.unmodifiableMap(new EnumMap<>(passwords));
    }

    /**
     * Reads MSSQL_SA_PASSWORD, MSSQL_HOST_PORT, CARDDEMO_DB_NAME, CARDDEMO_DB_SERVER_URL,
     * CARDDEMO_DB_ADMIN_USER, CARDDEMO_DB_ADMIN_PASSWORD, CARDDEMO_DB_LOGIN_PREFIX and the
     * CARDDEMO_DB_&lt;SERVICE&gt;_PASSWORD variables.
     */
    public static DbSettings fromEnvironment(Function<String, String> env) {
        List<String> missing = new ArrayList<>();
        String port = orDefault(env.apply("MSSQL_HOST_PORT"), "21433");
        String url = orDefault(env.apply("CARDDEMO_DB_SERVER_URL"),
                "jdbc:sqlserver://127.0.0.1:" + port + ";encrypt=true;trustServerCertificate=true");
        String adminUser = orDefault(env.apply("CARDDEMO_DB_ADMIN_USER"), "sa");
        String adminPassword = orDefault(env.apply("CARDDEMO_DB_ADMIN_PASSWORD"), env.apply("MSSQL_SA_PASSWORD"));
        if (blank(adminPassword)) {
            missing.add("MSSQL_SA_PASSWORD (or CARDDEMO_DB_ADMIN_PASSWORD)");
        }
        String db = orDefault(env.apply("CARDDEMO_DB_NAME"), "carddemo");
        String prefix = orDefault(env.apply("CARDDEMO_DB_LOGIN_PREFIX"), db + "_");
        Map<ServiceLogin, String> passwords = new EnumMap<>(ServiceLogin.class);
        for (ServiceLogin login : ServiceLogin.values()) {
            String value = env.apply(login.passwordVariable());
            if (blank(value)) {
                missing.add(login.passwordVariable());
            } else {
                passwords.put(login, value);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("not set: " + String.join(", ", missing)
                    + "; run through modernization/scripts/with_env.py (values come from modernization/.env)");
        }
        return new DbSettings(url, adminUser, adminPassword, db, prefix, passwords);
    }

    public String databaseUrl() {
        return serverUrl + (serverUrl.endsWith(";") ? "" : ";") + "databaseName=" + databaseName;
    }

    public String loginName(ServiceLogin login) {
        return login.loginName(loginPrefix);
    }

    public String password(ServiceLogin login) {
        String value = passwords.get(login);
        if (value == null) {
            throw new IllegalStateException("no password for login " + loginName(login));
        }
        return value;
    }

    static void requireName(String what, String value) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " '" + value + "' must be a letter followed by letters, digits or _");
        }
    }

    private static String orDefault(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public String toString() {
        return "DbSettings[" + serverUrl + ", db=" + databaseName + ", admin=" + adminUser + ", prefix=" + loginPrefix + "]";
    }
}
