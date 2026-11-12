package org.carddemo.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Each service login can connect and is refused each of its probe statements, holds exactly the
 * UNMASK grants its {@link ServiceLogin#unmaskedColumns()} names, and can effectively SELECT and
 * EXECUTE exactly what {@link EffectiveGrants} lists, whatever grant, role or PUBLIC permission it
 * comes from. A masked column reads masked rather than refused, so UNMASK is checked in the catalog
 * instead of by a probe.
 */
public final class LoginCheck {

    /** SQL Server error 229: "The ... permission was denied on the object ..."; 230 is the column-level form. */
    private static final List<Integer> PERMISSION_DENIED = List.of(229, 230);

    /** Every UNMASK of a user, at any level: database, schema, table or column. */
    static final String UNMASK_SQL = """
            SELECT CASE p.class
                       WHEN 0 THEN N'DATABASE'
                       WHEN 3 THEN N'SCHEMA::' + SCHEMA_NAME(p.major_id)
                       WHEN 1 THEN OBJECT_SCHEMA_NAME(p.major_id) + N'.' + OBJECT_NAME(p.major_id)
                            + CASE WHEN p.minor_id = 0 THEN N'' ELSE N'(' + COL_NAME(p.major_id, p.minor_id) + N')' END
                       ELSE N'class ' + CAST(p.class AS NVARCHAR(10))
                   END
            FROM sys.database_permissions AS p
            JOIN sys.database_principals AS u ON u.principal_id = p.grantee_principal_id
            WHERE u.name = ? AND p.permission_name = N'UNMASK' AND p.state IN ('G', 'W')""";

    private final DatabaseProvisioner provisioner;

    public LoginCheck(DatabaseProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    /** Returns one line per failure; empty when every login behaves as granted. */
    public List<String> run() {
        List<String> failures = new ArrayList<>();
        for (ServiceLogin login : ServiceLogin.values()) {
            try (Connection c = provisioner.service(login).getConnection(); Statement s = c.createStatement()) {
                s.execute("SELECT 1");
                for (String probe : login.forbiddenProbes()) {
                    try {
                        s.execute(probe);
                        failures.add(login.userName() + " was allowed: " + probe);
                    } catch (SQLException e) {
                        if (!PERMISSION_DENIED.contains(e.getErrorCode())) {
                            failures.add(login.userName() + " probe failed with error " + e.getErrorCode()
                                    + " instead of a permission error: " + e.getMessage());
                        }
                    }
                }
            } catch (SQLException e) {
                failures.add(login.userName() + " cannot connect: " + e.getMessage());
            }
        }
        try (Connection admin = provisioner.databaseAdmin().getConnection()) {
            failures.addAll(unmaskFailures(admin));
        } catch (SQLException e) {
            failures.add("the UNMASK grants cannot be read: " + e.getMessage());
        }
        try (Connection admin = provisioner.databaseAdmin().getConnection()) {
            failures.addAll(EffectiveGrants.failures(admin));
        } catch (SQLException e) {
            failures.add("the effective SELECT and EXECUTE grants cannot be read: " + e.getMessage());
        }
        return failures;
    }

    /** One line per login whose UNMASK grants differ from {@link ServiceLogin#unmaskedColumns()}. */
    public static List<String> unmaskFailures(Connection admin) throws SQLException {
        List<String> failures = new ArrayList<>();
        try (PreparedStatement ps = admin.prepareStatement(UNMASK_SQL)) {
            for (ServiceLogin login : ServiceLogin.values()) {
                ps.setString(1, login.userName());
                Set<String> held = new TreeSet<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        held.add(rs.getString(1));
                    }
                }
                if (!held.equals(new TreeSet<>(login.unmaskedColumns()))) {
                    failures.add(login.userName() + " holds UNMASK on " + held + " instead of "
                            + new TreeSet<>(login.unmaskedColumns()));
                }
            }
        }
        return failures;
    }
}
