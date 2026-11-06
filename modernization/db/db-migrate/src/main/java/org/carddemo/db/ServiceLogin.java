package org.carddemo.db;

import java.util.List;
import java.util.Locale;

/**
 * The SQL logins of the services. The server login is {@code <prefix><id>} (for database
 * {@code carddemo}: {@code carddemo_billpay}); the database user the owner's
 * {@code R__grants_<owner>.sql} grants to is {@code <id>_svc}. Each login has probes, statements
 * outside its grants that must be refused. A login whose service is not built yet has no grants
 * and is refused everything.
 */
public enum ServiceLogin {
    IDENTITY("identity", List.of("SELECT TOP 0 acct_id FROM core.account")),
    BILLPAY("billpay", List.of(
            "SELECT TOP 0 cust_id FROM core.customer",
            // INV-004: Bill Pay may change the balance, never another account column.
            "UPDATE core.account SET credit_limit = credit_limit WHERE 1 = 0",
            "DELETE FROM core.[transaction] WHERE 1 = 0")),
    ACCOUNT("account", List.of("SELECT TOP 0 tran_id FROM core.[transaction]")),
    TRANSACTION("transaction", List.of("SELECT TOP 0 cust_id FROM core.customer")),
    REFERENCE("reference", List.of("SELECT TOP 0 tran_id FROM core.[transaction]")),
    AUTHORIZATION("authorization", List.of("SELECT TOP 0 tran_id FROM core.[transaction]")),
    INQUIRY("inquiry", List.of("SELECT TOP 0 cust_id FROM core.customer")),
    BATCH("batch", List.of("SELECT TOP 0 tran_pk FROM billpay.payment"));

    private final String id;
    private final List<String> forbiddenProbes;

    ServiceLogin(String id, List<String> forbiddenProbes) {
        this.id = id;
        this.forbiddenProbes = forbiddenProbes;
    }

    public String id() {
        return id;
    }

    /** The database user the grants name. */
    public String userName() {
        return id + "_svc";
    }

    public String loginName(String prefix) {
        return prefix + id;
    }

    /** The environment variable that holds the login's password, e.g. CARDDEMO_DB_BILLPAY_PASSWORD. */
    public String passwordVariable() {
        return "CARDDEMO_DB_" + id.toUpperCase(Locale.ROOT) + "_PASSWORD";
    }

    public List<String> forbiddenProbes() {
        return forbiddenProbes;
    }
}
