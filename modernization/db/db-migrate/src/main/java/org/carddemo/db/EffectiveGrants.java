package org.carddemo.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * What each service user can actually read and execute, and what it is meant to. Under EXECUTE AS
 * USER, {@code fn_my_permissions} gives the effective permission whether it comes from an object,
 * column, schema, role or PUBLIC grant, so a stray grant on an operator's database shows up even
 * where no probe looks. A table read through a column list must show no object-level SELECT and
 * exactly its columns; an object-level read is {@code *}; anything not listed is not readable or
 * executable at all. UNMASK is not checked here: {@code fn_my_permissions} does not report column
 * UNMASK, so it stays on {@link LoginCheck#unmaskFailures}.
 */
public final class EffectiveGrants {

    /** An object-level SELECT. */
    public static final Set<String> ALL = Set.of("*");

    /** R__grants_inquiry.sql: the eleven core.account columns of the account inquiry reply. */
    public static final List<String> INQUIRY_ACCOUNT_COLUMNS = List.of("acct_id", "active_status", "curr_bal",
            "credit_limit", "cash_credit_limit", "open_date", "expiration_date", "reissue_date", "curr_cyc_credit",
            "curr_cyc_debit", "group_id");

    /** The 14 core.account columns the online readers share; never the DEF-ACUP-002 provenance. */
    private static final Set<String> ACCOUNT_READ = columns("acct_id active_status curr_bal credit_limit"
            + " cash_credit_limit open_date expiration_date reissue_date curr_cyc_credit curr_cyc_debit addr_zip group_id"
            + " legacy_filler row_version");

    /** Table and view types whose SELECT is checked; procedure and function types whose EXECUTE is. */
    private static final Set<String> READABLE = Set.of("U", "V");
    private static final Set<String> EXECUTABLE = Set.of("P", "PC", "FN", "FS");

    /** login -> object -> readable columns or {@link #ALL}; batch's schema grant is added by {@link #expectedSelect}. */
    private static final Map<ServiceLogin, Map<String, Set<String>>> SELECT = Map.of(
            ServiceLogin.IDENTITY, Map.of(
                    "iam.app_user", ALL, "iam.session", ALL, "iam.user_signon_state", ALL, "ops.batch_window", ALL),
            ServiceLogin.BILLPAY, Map.of(
                    "core.account", ACCOUNT_READ,
                    "core.card_xref", ALL,
                    "core.transaction", ALL,
                    "core.daily_tran", columns("tran_id cycle_id origin"),
                    "core.tcat_bal", ALL,
                    "ops.batch_window", ALL,
                    "billpay.payment", ALL),
            ServiceLogin.ACCOUNT, Map.of(
                    "core.account", ACCOUNT_READ,
                    "core.customer", ALL,
                    // D-DATA-04: no cvv_cd.
                    "core.card", columns("card_num card_id acct_id embossed_name expiration_date active_status"
                            + " legacy_filler row_version"),
                    "core.card_xref", ALL,
                    "ops.batch_window", ALL),
            ServiceLogin.TRANSACTION, Map.of(
                    "core.transaction", columns("tran_id in_master type_cd cat_cd source description amount merchant_id"
                            + " merchant_name merchant_city merchant_zip orig_ts proc_ts card_num"),
                    // No raw_record, created_by or idempotency_key, and no card number of a waiting add.
                    "core.daily_tran", columns("tran_id cycle_id origin type_cd cat_cd source description amount"
                            + " merchant_id merchant_name merchant_city merchant_zip orig_ts proc_ts"),
                    "batch.report_run", columns("report_run_id report_type start_date end_date requested_by requested_at"
                            + " status started_at finished_at failure_code row_version output_sha256"),
                    "batch.v_report_run_stalled", ALL,
                    "core.card_xref", ALL,
                    "ref.tran_type", ALL,
                    "ref.tran_category", ALL,
                    "ops.batch_window", ALL,
                    "ops.database_instance", ALL),
            ServiceLogin.REFERENCE, Map.of(),
            ServiceLogin.AUTHORIZATION, Map.of(
                    "core.card_xref", ALL,
                    "core.account", ACCOUNT_READ,
                    "core.customer", columns("cust_id first_name middle_name last_name addr_line_1 addr_line_2"
                            + " addr_line_3 state_cd zip phone_1"),
                    "core.card", columns("card_num acct_id active_status expiration_date"),
                    "pauth.auth_summary", ALL,
                    "pauth.auth_detail", ALL,
                    "pauth.auth_fraud", ALL,
                    "pauth.processed_message", ALL,
                    "pauth.outbox", ALL),
            ServiceLogin.INQUIRY, Map.of(
                    "core.account", Set.copyOf(INQUIRY_ACCOUNT_COLUMNS)),
            ServiceLogin.BATCH, batchSelect());

    /** The online writes that go through procedures only (R__grants_transaction.sql); no other login executes any. */
    private static final Map<ServiceLogin, Set<String>> EXECUTE = Map.of(
            ServiceLogin.TRANSACTION, Set.of("batch.report_run_request", "batch.report_run_trigger_failed",
                    "core.daily_tran_online_add", "core.daily_tran_key_lookup"));

    /**
     * The database's objects as {@code schema.name -> sys.objects type}, and per login the effective
     * SELECT (object -> columns or {@link #ALL}) and EXECUTE.
     */
    public record Effective(Map<String, String> objects, Map<ServiceLogin, Map<String, Set<String>>> select,
            Map<ServiceLogin, Set<String>> execute) {

        public Map<String, Set<String>> select(ServiceLogin login) {
            return select.getOrDefault(login, Map.of());
        }

        public Set<String> execute(ServiceLogin login) {
            return execute.getOrDefault(login, Set.of());
        }
    }

    private EffectiveGrants() {
    }

    /**
     * What the login must read, given the database's objects: batch's GRANT ... ON SCHEMA::batch
     * covers every table and view of the schema, those added later too.
     */
    public static Map<String, Set<String>> expectedSelect(ServiceLogin login, Map<String, String> objects) {
        Map<String, Set<String>> expected = new TreeMap<>(SELECT.get(login));
        if (login == ServiceLogin.BATCH) {
            objects.forEach((name, type) -> {
                if (name.startsWith("batch.") && READABLE.contains(type)) {
                    expected.put(name, ALL);
                }
            });
        }
        return expected;
    }

    public static Set<String> expectedExecute(ServiceLogin login) {
        return new TreeSet<>(EXECUTE.getOrDefault(login, Set.of()));
    }

    /** One line per difference between {@link #read} and the expected grants; empty when they agree. */
    public static List<String> failures(Connection admin) throws SQLException {
        return failures(read(admin));
    }

    public static List<String> failures(Effective effective) {
        List<String> failures = new ArrayList<>();
        for (ServiceLogin login : ServiceLogin.values()) {
            String user = login.userName();
            Map<String, Set<String>> expected = expectedSelect(login, effective.objects());
            Set<String> expectedExecute = expectedExecute(login);
            Set<String> named = new TreeSet<>(expected.keySet());
            named.addAll(expectedExecute);
            for (String object : named) {
                if (!effective.objects().containsKey(object)) {
                    failures.add(user + " is meant to use " + object + ", which does not exist");
                }
            }
            Map<String, Set<String>> actual = effective.select(login);
            Set<String> objects = new TreeSet<>(expected.keySet());
            objects.addAll(actual.keySet());
            for (String object : objects) {
                Set<String> want = new TreeSet<>(expected.getOrDefault(object, Set.of()));
                Set<String> have = new TreeSet<>(actual.getOrDefault(object, Set.of()));
                if (!want.equals(have)) {
                    failures.add(user + " can SELECT " + describe(have) + " of " + object + " instead of "
                            + describe(want));
                }
            }
            Set<String> executes = effective.execute(login);
            if (!expectedExecute.equals(new TreeSet<>(executes))) {
                failures.add(user + " can EXECUTE " + new TreeSet<>(executes) + " instead of " + expectedExecute);
            }
        }
        return failures;
    }

    /** Reads every service user's effective SELECT and EXECUTE through {@code admin}, which may impersonate them. */
    public static Effective read(Connection admin) throws SQLException {
        String users = Arrays.stream(ServiceLogin.values()).map(l -> "(N'" + l.userName() + "')")
                .collect(Collectors.joining(", "));
        // The object list is read before EXECUTE AS: an impersonated user sees only the metadata it may.
        // The CATCH reverts so a failure never leaves the admin connection impersonating a service user.
        String sql = """
                SET NOCOUNT ON;
                DECLARE @objects TABLE (quoted NVARCHAR(300) NOT NULL, name NVARCHAR(300) NOT NULL, type VARCHAR(2) NOT NULL);
                INSERT INTO @objects
                SELECT QUOTENAME(SCHEMA_NAME(o.schema_id)) + N'.' + QUOTENAME(o.name), SCHEMA_NAME(o.schema_id) + N'.' + o.name,
                       RTRIM(o.type)
                FROM sys.objects AS o WHERE RTRIM(o.type) IN (%s) AND o.is_ms_shipped = 0;
                DECLARE @result TABLE (user_name SYSNAME NOT NULL, object_name NVARCHAR(300) NOT NULL,
                                       permission_name NVARCHAR(128) NOT NULL, column_name SYSNAME NULL);
                DECLARE @self SYSNAME = USER_NAME();
                DECLARE @user SYSNAME;
                BEGIN TRY
                    DECLARE users CURSOR LOCAL FAST_FORWARD FOR SELECT u FROM (VALUES %s) AS v(u);
                    OPEN users;
                    FETCH NEXT FROM users INTO @user;
                    WHILE @@FETCH_STATUS = 0
                    BEGIN
                        EXECUTE AS USER = @user;
                        INSERT INTO @result
                        SELECT @user, o.name, p.permission_name, NULLIF(p.subentity_name, N'')
                        FROM @objects AS o CROSS APPLY fn_my_permissions(o.quoted, N'OBJECT') AS p
                        WHERE (p.permission_name = N'SELECT' AND o.type IN (%s))
                           OR (p.permission_name = N'EXECUTE' AND o.type IN (%s));
                        REVERT;
                        FETCH NEXT FROM users INTO @user;
                    END;
                    CLOSE users;
                    DEALLOCATE users;
                END TRY
                BEGIN CATCH
                    IF USER_NAME() <> @self REVERT;
                    THROW;
                END CATCH;
                SELECT name, type FROM @objects;
                SELECT user_name, object_name, permission_name, column_name FROM @result;
                """.formatted(inList(allTypes()), users,
                inList(READABLE), inList(EXECUTABLE));
        Map<String, ServiceLogin> byUser = Arrays.stream(ServiceLogin.values())
                .collect(Collectors.toMap(ServiceLogin::userName, l -> l));
        Map<String, String> objects = new TreeMap<>();
        Map<ServiceLogin, Map<String, Set<String>>> objectLevel = new TreeMap<>();
        Map<ServiceLogin, Map<String, Set<String>>> columnLevel = new TreeMap<>();
        Map<ServiceLogin, Set<String>> execute = new TreeMap<>();
        try (Statement s = admin.createStatement()) {
            boolean isResultSet = s.execute(sql);
            int resultSets = 0;
            while (isResultSet || s.getUpdateCount() != -1) {
                if (isResultSet) {
                    try (ResultSet rs = s.getResultSet()) {
                        while (rs.next()) {
                            if (resultSets == 0) {
                                objects.put(rs.getString(1), rs.getString(2));
                                continue;
                            }
                            ServiceLogin login = byUser.get(rs.getString(1));
                            String object = rs.getString(2);
                            String column = rs.getString(4);
                            if (rs.getString(3).equals("EXECUTE")) {
                                if (column == null) {
                                    execute.computeIfAbsent(login, l -> new TreeSet<>()).add(object);
                                }
                            } else {
                                (column == null ? objectLevel : columnLevel).computeIfAbsent(login, l -> new TreeMap<>())
                                        .computeIfAbsent(object, o -> new TreeSet<>()).add(column == null ? "*" : column);
                            }
                        }
                    }
                    resultSets++;
                }
                isResultSet = s.getMoreResults();
            }
            if (resultSets != 2) {
                throw new SQLException("the effective grant query returned " + resultSets + " result sets instead of 2");
            }
        }
        // An object-level SELECT lists every column as well; it is reported as "*" alone.
        Map<ServiceLogin, Map<String, Set<String>>> select = new TreeMap<>(columnLevel);
        objectLevel.forEach((login, granted) -> granted.keySet().forEach(
                o -> select.computeIfAbsent(login, l -> new TreeMap<>()).put(o, ALL)));
        return new Effective(objects, select, execute);
    }

    private static String describe(Set<String> columns) {
        if (columns.isEmpty()) {
            return "nothing";
        }
        return columns.equals(ALL) ? "every column" : "columns " + columns;
    }

    private static List<String> allTypes() {
        List<String> types = new ArrayList<>(READABLE);
        types.addAll(EXECUTABLE);
        return types;
    }

    private static String inList(Collection<String> types) {
        return types.stream().sorted().map(t -> "'" + t + "'").collect(Collectors.joining(", "));
    }

    private static Set<String> columns(String names) {
        return Set.of(names.split(" "));
    }

    private static Map<String, Set<String>> batchSelect() {
        Map<String, Set<String>> m = new TreeMap<>();
        for (String o : List.of("core.account", "core.customer", "core.card", "core.card_xref", "core.tcat_bal",
                "core.transaction", "core.daily_tran", "core.daily_reject", "core.v_daily_reject", "ref.tran_type",
                "ref.tran_category", "ref.disclosure_group", "ref.us_state", "ref.us_state_zip_prefix",
                "ref.nanp_area_code", "pauth.auth_summary", "pauth.auth_detail", "pauth.processed_message",
                "pauth.outbox", "ops.batch_window", "ops.database_instance")) {
            m.put(o, ALL);
        }
        return Map.copyOf(m);
    }
}
