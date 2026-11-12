package org.carddemo.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The SQL logins of the services. The server login is {@code <prefix><id>} (for database
 * {@code carddemo}: {@code carddemo_billpay}); the database user the owner's
 * {@code R__grants_<owner>.sql} grants to is {@code <id>_svc}. Each login has probes, statements
 * outside its grants that must be refused. A login whose service is not built yet has no grants
 * and is refused everything.
 */
public enum ServiceLogin {
    IDENTITY("identity", List.of("SELECT TOP 0 acct_id FROM core.account",
            // DEF-ACUP-002: the load's ZIP and group provenance is read by batch only.
            "SELECT TOP 0 legacy_zip_group FROM core.account",
            "SELECT TOP 0 zip_group_origin FROM core.account")),
    BILLPAY("billpay", List.of(
            "SELECT TOP 0 cust_id FROM core.customer",
            // INV-004: Bill Pay may change the balance, never another account column.
            "UPDATE core.account SET credit_limit = credit_limit WHERE 1 = 0",
            "DELETE FROM core.[transaction] WHERE 1 = 0",
            // Legacy ID allocation reads the IDs of waiting adds, never their content.
            "SELECT TOP 0 card_num FROM core.daily_tran",
            // DEF-ACUP-002: the load's ZIP and group provenance is read by batch only.
            "SELECT TOP 0 legacy_zip_group FROM core.account",
            "SELECT TOP 0 zip_group_origin FROM core.account")),
    ACCOUNT("account", List.of("SELECT TOP 0 tran_id FROM core.[transaction]",
            // D-DATA-04: no login reads the CVV.
            "SELECT TOP 0 cvv_cd FROM core.card",
            // D-08: account update never writes the balances.
            "UPDATE core.account SET curr_bal = curr_bal WHERE 1 = 0",
            "UPDATE core.account SET curr_cyc_credit = curr_cyc_credit WHERE 1 = 0",
            "UPDATE core.account SET curr_cyc_debit = curr_cyc_debit WHERE 1 = 0",
            // D-11: nor the account's addr_zip and filler, which only the export reads.
            "UPDATE core.account SET addr_zip = addr_zip WHERE 1 = 0",
            "UPDATE core.account SET legacy_filler = legacy_filler WHERE 1 = 0",
            "UPDATE core.account SET acct_id = acct_id WHERE 1 = 0",
            // DEF-ACUP-002: the load's ZIP and group provenance is read by batch only and written by the load only.
            "SELECT TOP 0 legacy_zip_group FROM core.account",
            "SELECT TOP 0 zip_group_origin FROM core.account",
            "UPDATE core.account SET legacy_zip_group = legacy_zip_group WHERE 1 = 0",
            "UPDATE core.account SET zip_group_origin = zip_group_origin WHERE 1 = 0",
            // BR-ACUP-008: the customer's key and country stay as stored.
            "UPDATE core.customer SET cust_id = cust_id WHERE 1 = 0",
            "UPDATE core.customer SET country_cd = country_cd WHERE 1 = 0",
            // D-13, D-12, DEF-CRDU-003: card update never writes the account, the CVV or the filler.
            "UPDATE core.card SET acct_id = acct_id WHERE 1 = 0",
            "UPDATE core.card SET card_num = card_num WHERE 1 = 0",
            "UPDATE core.card SET cvv_cd = '000' WHERE 1 = 0",
            "UPDATE core.card SET legacy_filler = NULL WHERE 1 = 0",
            // The audit is written, never read.
            "SELECT TOP 0 field_names FROM ops.audit")),
    TRANSACTION("transaction", List.of("SELECT TOP 0 cust_id FROM core.customer",
            "SELECT TOP 0 raw_record FROM core.daily_tran",
            // The add writes and replays through procedures only: no insert and no read of the row's owner or key.
            "SELECT TOP 0 created_by FROM core.daily_tran",
            "SELECT TOP 0 idempotency_key FROM core.daily_tran",
            "INSERT INTO core.daily_tran (origin, tran_id, cycle_id, seq) SELECT 'ONLINE_ADD', N'0', 1, 1 WHERE 1 = 0",
            // D-DATA-04: no login reads the CVV.
            "SELECT TOP 0 cvv_cd FROM core.card",
            // A report run's execution and status are batch's: the service requests and reads only.
            "SELECT TOP 0 job_execution_id FROM batch.report_run",
            "UPDATE batch.report_run SET status = status WHERE 1 = 0",
            "DELETE FROM batch.report_run WHERE 1 = 0",
            "INSERT INTO batch.report_run (report_type, start_date, end_date, requested_by, status)"
                    + " SELECT 'CUSTOM', '2022-07-01', '2022-07-01', 'PROBE001', 'RUNNING' WHERE 1 = 0",
            // The download reads a run's committed sha256 and never writes it, and reads no execution context.
            "UPDATE batch.report_run SET output_sha256 = NULL WHERE 1 = 0",
            "SELECT TOP 0 * FROM batch.BATCH_JOB_EXECUTION_CONTEXT",
            "SELECT TOP 0 export_id FROM batch.export_run",
            // DEF-ACUP-002: the load's ZIP and group provenance is read by batch only.
            "SELECT TOP 0 legacy_zip_group FROM core.account",
            "SELECT TOP 0 zip_group_origin FROM core.account")),
    REFERENCE("reference", List.of("SELECT TOP 0 tran_id FROM core.[transaction]",
            // DEF-ACUP-002: the load's ZIP and group provenance is read by batch only.
            "SELECT TOP 0 legacy_zip_group FROM core.account",
            "SELECT TOP 0 zip_group_origin FROM core.account")),
    AUTHORIZATION("authorization", List.of("SELECT TOP 0 tran_id FROM core.[transaction]",
            // D-DATA-04: no login reads the CVV.
            "SELECT TOP 0 cvv_cd FROM core.card",
            "UPDATE pauth.auth_detail SET approved_amt = approved_amt WHERE 1 = 0",
            "DELETE FROM pauth.auth_fraud WHERE 1 = 0",
            // The customer's identifiers are outside the summary's columns.
            "SELECT TOP 0 ssn FROM core.customer",
            "SELECT TOP 0 govt_issued_id FROM core.customer",
            // DEF-ACUP-002: the load's ZIP and group provenance is read by batch only.
            "SELECT TOP 0 legacy_zip_group FROM core.account",
            "SELECT TOP 0 zip_group_origin FROM core.account")),
    INQUIRY("inquiry", List.of("SELECT TOP 0 cust_id FROM core.customer",
            // BR-MQAC-002: the reply never carries the ZIP or the filler; the service reads, never writes.
            "SELECT TOP 0 addr_zip FROM core.account",
            "SELECT TOP 0 legacy_filler FROM core.account",
            "UPDATE core.account SET curr_bal = curr_bal WHERE 1 = 0",
            // DEF-ACUP-002: the load's ZIP and group provenance is read by batch only.
            "SELECT TOP 0 legacy_zip_group FROM core.account",
            "SELECT TOP 0 zip_group_origin FROM core.account")),
    BATCH("batch", List.of("SELECT TOP 0 tran_pk FROM billpay.payment",
            // Fraud reports are kept with no expiry; the purge never reads them.
            "SELECT TOP 0 auth_id FROM pauth.auth_fraud",
            // DEF-ACUP-002: the load inserts the ZIP and group provenance with the row; nothing updates it.
            "UPDATE core.account SET legacy_zip_group = legacy_zip_group WHERE 1 = 0",
            "UPDATE core.account SET zip_group_origin = zip_group_origin WHERE 1 = 0"));

    /** No login changes the database instance marker; only db-migrate, as the owner, rotates it. */
    static final List<String> MARKER_WRITE_PROBES = List.of(
            "INSERT INTO ops.database_instance (id, instance_id) SELECT 1, NEWID() WHERE 1 = 0",
            "UPDATE ops.database_instance SET instance_id = instance_id WHERE 1 = 0",
            "DELETE FROM ops.database_instance WHERE 1 = 0");
    /** Only the logins that resolve the output root read the marker. */
    static final String MARKER_READ_PROBE = "SELECT TOP 0 instance_id FROM ops.database_instance";

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
        List<String> probes = new ArrayList<>(forbiddenProbes);
        probes.addAll(MARKER_WRITE_PROBES);
        if (!readsOutputRoot()) {
            probes.add(MARKER_READ_PROBE);
        }
        return List.copyOf(probes);
    }

    /**
     * Every UNMASK the login holds, as {@code <schema>.<table>(<column>)}: all column grants, so a
     * masked column added later stays masked, and none on {@code core.card}, whose only masked column
     * is the CVV (D-DATA-04). {@link LoginCheck} compares the catalog with this exactly.
     */
    public Set<String> unmaskedColumns() {
        return switch (this) {
            case BATCH -> Set.of("core.customer(ssn)", "core.customer(govt_issued_id)", "core.customer(dob)",
                    "core.customer(eft_account_id)", "core.transaction(card_num)", "core.daily_tran(card_num)");
            case ACCOUNT -> Set.of("core.customer(ssn)", "core.customer(govt_issued_id)", "core.customer(dob)",
                    "core.customer(eft_account_id)");
            case TRANSACTION -> Set.of("core.transaction(card_num)");
            case AUTHORIZATION -> Set.of("pauth.auth_detail(card_num)", "pauth.processed_message(reply_body)",
                    "pauth.outbox(body)");
            default -> Set.of();
        };
    }

    /** batch-service writes under the output root and transaction-service serves report files from it. */
    public boolean readsOutputRoot() {
        return this == BATCH || this == TRANSACTION;
    }
}
