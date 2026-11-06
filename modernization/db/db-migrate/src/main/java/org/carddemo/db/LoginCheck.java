package org.carddemo.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Each service login can connect and is refused each of its probe statements. */
public final class LoginCheck {

    /** SQL Server error 229: "The ... permission was denied on the object ..."; 230 is the column-level form. */
    private static final List<Integer> PERMISSION_DENIED = List.of(229, 230);

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
        return failures;
    }
}
