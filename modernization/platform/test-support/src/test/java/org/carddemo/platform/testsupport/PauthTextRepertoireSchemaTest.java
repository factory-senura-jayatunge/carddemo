package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.carddemo.db.ServiceLogin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V505 and V508: the authorization tables hold every character of U+0000 to U+00FF at the legacy widths. V509: the
 * fraud copies have their detail columns' collation.
 */
class PauthTextRepertoireSchemaTest {

    private static final String ACCT = "00000000001";
    private static final String CARD = "4111111111111111";
    private static final String BIN2 = "Latin1_General_100_BIN2";

    /** The request's text fields on the detail: width and whether the column is variable. */
    private static final Map<String, String> DETAIL_TEXT = ordered(
            "orig_date", "nchar(6)", "orig_time", "nchar(6)", "auth_type", "nchar(4)",
            "card_expiry_date", "nchar(4)", "message_type", "nchar(6)", "message_source", "nchar(6)",
            "processing_code", "nchar(6)", "merchant_category_code", "nchar(4)", "acqr_country_code", "nchar(3)",
            "pos_entry_mode", "nchar(2)", "merchant_id", "nchar(15)", "merchant_name", "nvarchar(22)",
            "merchant_city", "nvarchar(13)", "merchant_state", "nchar(2)", "merchant_zip", "nvarchar(9)",
            "transaction_id", "nchar(15)");

    /** The fraud copy has no request date and time, and keeps pos_entry_mode as a number. */
    private static final Map<String, String> FRAUD_TEXT = ordered(
            "auth_type", "nchar(4)", "card_expiry_date", "nchar(4)", "message_type", "nchar(6)",
            "message_source", "nchar(6)", "processing_code", "nchar(6)", "merchant_category_code", "nchar(4)",
            "acqr_country_code", "nchar(3)", "merchant_id", "nchar(15)", "merchant_name", "nvarchar(22)",
            "merchant_city", "nchar(13)", "merchant_state", "nchar(2)", "merchant_zip", "nchar(9)",
            "transaction_id", "nchar(15)");

    private final TestDatabase db = TestDatabase.shared();
    private final JdbcClient admin = db.admin();

    @BeforeEach
    void accountWithASummary() {
        db.fixtures().deleteAll()
                .account(CoreFixtures.Account.of(ACCT, "0.00"))
                .customer("000000001")
                .card(CARD, ACCT, "000000001");
        admin.sql("DELETE FROM pauth.processed_message").update();
        admin.sql("DELETE FROM pauth.outbox").update();
        admin.sql("""
                INSERT INTO pauth.auth_summary (acct_id, cust_id, credit_limit, cash_limit, credit_balance, cash_balance,
                    approved_auth_cnt, declined_auth_cnt, approved_auth_amt, declined_auth_amt)
                VALUES (:acct, '000000001', 1000.00, 500.00, 0, 0, 0, 0, 0, 0)""").param("acct", ACCT).update();
    }

    @Test
    void theTextColumnsAreUnicodeOfTheSameWidthAndCollation() {
        String database = admin.sql("SELECT CONVERT(SYSNAME, DATABASEPROPERTYEX(DB_NAME(), 'Collation'))")
                .query(String.class).single();
        DETAIL_TEXT.forEach((name, type) -> assertThat(describe("pauth.auth_detail", name))
                .as("auth_detail." + name).isEqualTo(type + " not null " + database + " unmasked"));
        FRAUD_TEXT.forEach((name, type) -> assertThat(describe("pauth.auth_fraud", name))
                .as("auth_fraud." + name).isEqualTo(type + " null " + database + " unmasked"));
        assertThat(describe("pauth.auth_fraud", "pos_entry_mode")).startsWith("smallint(2) null");
        assertThat(describe("pauth.auth_detail", "fraud_changed_by")).isEqualTo("nchar(8) null " + BIN2 + " unmasked");
        assertThat(describe("pauth.auth_fraud", "reported_by")).isEqualTo("nchar(8) not null " + BIN2 + " unmasked");
        assertThat(describe("pauth.processed_message", "reply_body"))
                .isEqualTo("nchar(63) not null " + BIN2 + " masked default()");
        assertThat(describe("pauth.outbox", "body")).isEqualTo("nvarchar(1000) not null " + BIN2 + " masked default()");
        // V508: the loaded authorization codes are stored as unloaded, with each column's collation kept.
        assertThat(describe("pauth.auth_detail", "auth_id_code")).isEqualTo("nchar(6) not null " + database + " unmasked");
        assertThat(describe("pauth.auth_detail", "auth_resp_reason")).isEqualTo("nchar(4) not null " + BIN2 + " unmasked");
        assertThat(describe("pauth.auth_fraud", "auth_id_code")).isEqualTo("nchar(6) null " + database + " unmasked");
        // V509: the fraud copies have the detail's collation.
        assertThat(describe("pauth.auth_fraud", "auth_resp_reason")).isEqualTo("nchar(4) null " + BIN2 + " unmasked");
        assertThat(describe("pauth.auth_fraud", "auth_resp_code")).isEqualTo("char(2) null " + BIN2 + " unmasked");
        assertThat(describe("pauth.auth_fraud", "match_status")).isEqualTo("char(1) null " + BIN2 + " unmasked");
        // Unchanged neighbours: the card number keeps its mask, the codes only the service sets stay CHAR.
        assertThat(describe("pauth.auth_detail", "card_num")).startsWith("char(16) not null " + BIN2 + " masked partial(");
        assertThat(describe("pauth.auth_detail", "auth_resp_code")).isEqualTo("char(2) not null " + BIN2 + " unmasked");
        assertThat(describe("pauth.auth_detail", "match_status")).isEqualTo("char(1) not null " + BIN2 + " unmasked");
    }

    @Test
    void thePendingApprovalIndexIsRecreatedAsBefore() {
        assertThat(admin.sql("""
                        SELECT STRING_AGG(c.name + ':' + CAST(ic.is_included_column AS CHAR(1)), ',')
                                   WITHIN GROUP (ORDER BY ic.is_included_column, ic.key_ordinal, c.name)
                               + ' ' + MAX(i.filter_definition)
                        FROM sys.indexes i
                        JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                        JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                        WHERE i.object_id = OBJECT_ID('pauth.auth_detail') AND i.name = 'IX_auth_detail_pending'""")
                .query(String.class).single())
                .isEqualTo("card_num:0,approved_amt:0,merchant_id:1,processed_at:1 ([match_status]='P')");
    }

    @Test
    void theAuthorizationLoginStoresFullWidthValuesOfTheRangeUnchanged() {
        JdbcClient authorization = JdbcClient.create(db.dataSource(ServiceLogin.AUTHORIZATION));
        Map<String, String> values = new LinkedHashMap<>();
        DETAIL_TEXT.forEach((name, type) -> values.put(name, fill(name, width(type))));

        StringBuilder columns = new StringBuilder();
        StringBuilder params = new StringBuilder();
        values.keySet().forEach(name -> {
            columns.append(", ").append(name);
            params.append(", :").append(name);
        });
        var insert = authorization.sql("""
                INSERT INTO pauth.auth_detail (acct_id, processed_at, auth_date_9c, auth_time_9c, card_num,
                    auth_id_code, auth_resp_code, auth_resp_reason, transaction_amt, approved_amt, match_status"""
                + columns + ") OUTPUT inserted.auth_id VALUES (:acct, '2022-07-06T10:00:00', 77814, 899999999, :card,"
                + " '000001', '00', '0000', 12.34, 12.34, 'P'" + params + ")")
                .param("acct", ACCT).param("card", CARD);
        for (Map.Entry<String, String> value : values.entrySet()) {
            insert = insert.param(value.getKey(), value.getValue());
        }
        long authId = insert.query(Long.class).single();

        Map<String, Object> stored = authorization.sql("SELECT" + columns.substring(1)
                + " FROM pauth.auth_detail WHERE auth_id = :id").param("id", authId).query().singleRow();
        assertThat(stored).containsExactlyInAnyOrderEntriesOf(values);

        // The authorization code comes from the IDENTITY after the insert (D-32).
        assertThat(authorization.sql("UPDATE pauth.auth_detail SET auth_id_code = RIGHT('00000' + CAST(auth_id AS VARCHAR(20)), 6)"
                + " WHERE auth_id = :id").param("id", authId).update()).isEqualTo(1);

        // The fraud copy is an INSERT ... SELECT from the detail, as the fraud service writes it.
        String user = "\u00c9\u00e0\u00ff\u00b5ADM1";
        authorization.sql("""
                UPDATE pauth.auth_detail SET auth_fraud = 'F', fraud_rpt_date = '2022-07-06', fraud_changed_by = :user
                WHERE auth_id = :id""").param("user", user).param("id", authId).update();
        String fraudColumns = String.join(", ", FRAUD_TEXT.keySet());
        authorization.sql("INSERT INTO pauth.auth_fraud (auth_id, card_num, auth_ts, auth_fraud, fraud_rpt_date, acct_id,"
                + " reported_by, pos_entry_mode, " + fraudColumns + ") SELECT auth_id, card_num, processed_at, auth_fraud,"
                + " fraud_rpt_date, acct_id, fraud_changed_by, 1, " + fraudColumns
                + " FROM pauth.auth_detail WHERE auth_id = :id").param("id", authId).update();
        Map<String, Object> fraud = authorization.sql("SELECT " + fraudColumns
                + " FROM pauth.auth_fraud WHERE auth_id = :id").param("id", authId).query().singleRow();
        FRAUD_TEXT.forEach((name, type) -> assertThat(fraud.get(name)).as("auth_fraud." + name)
                .isEqualTo(pad(values.get(name), width(type))));
        assertThat(authorization.sql("SELECT reported_by FROM pauth.auth_fraud WHERE auth_id = :id").param("id", authId)
                .query(String.class).single()).isEqualTo(user);

        // The service changes only the fraud columns, also of the widened table.
        assertThatThrownBy(() -> authorization.sql("UPDATE pauth.auth_detail SET merchant_name = N'x' WHERE auth_id = :id")
                .param("id", authId).update()).hasMessageContaining("permission was denied");
        admin.sql("DELETE FROM pauth.auth_fraud WHERE auth_id = :id").param("id", authId).update();
    }

    @Test
    void theAuthorizationCodesHoldEveryCharacterOfTheRangeOnTheDetailAndItsFraudCopy() {
        JdbcClient authorization = JdbcClient.create(db.dataSource(ServiceLogin.AUTHORIZATION));
        List<String> codes = range(6);
        List<String> reasons = range(4);
        String textColumns = String.join(", ", DETAIL_TEXT.keySet());
        StringBuilder textParams = new StringBuilder();
        DETAIL_TEXT.keySet().forEach(name -> textParams.append(", :").append(name));

        Map<Long, List<String>> expected = new LinkedHashMap<>();
        for (int i = 0; i < reasons.size(); i++) {
            String code = codes.get(i % codes.size());
            var insert = authorization.sql("""
                    INSERT INTO pauth.auth_detail (acct_id, processed_at, auth_date_9c, auth_time_9c, card_num,
                        auth_id_code, auth_resp_code, auth_resp_reason, transaction_amt, approved_amt, match_status"""
                    + ", " + textColumns + ") OUTPUT inserted.auth_id VALUES (:acct, '2022-07-06T10:00:00', 77814, :time, :card,"
                    + " :code, '00', :reason, 12.34, 12.34, 'P'" + textParams + ")")
                    .param("acct", ACCT).param("card", CARD).param("time", 899999999 - i)
                    .param("code", code).param("reason", reasons.get(i));
            for (Map.Entry<String, String> column : DETAIL_TEXT.entrySet()) {
                insert = insert.param(column.getKey(), fill(column.getKey(), width(column.getValue())));
            }
            expected.put(insert.query(Long.class).single(), List.of(code, reasons.get(i)));
        }
        authorization.sql("""
                INSERT INTO pauth.auth_fraud (auth_id, card_num, auth_ts, auth_fraud, fraud_rpt_date, acct_id, reported_by,
                    auth_id_code, auth_resp_reason)
                SELECT auth_id, card_num, processed_at, 'F', '2022-07-06', acct_id, 'ADMIN001', auth_id_code, auth_resp_reason
                FROM pauth.auth_detail WHERE acct_id = :acct""").param("acct", ACCT).update();

        for (String table : List.of("pauth.auth_detail", "pauth.auth_fraud")) {
            Map<Long, List<String>> stored = new LinkedHashMap<>();
            authorization.sql("SELECT auth_id, auth_id_code, auth_resp_reason FROM " + table + " ORDER BY auth_id")
                    .query().listOfRows().forEach(row -> stored.put(((Number) row.get("auth_id")).longValue(),
                            List.of((String) row.get("auth_id_code"), (String) row.get("auth_resp_reason"))));
            assertThat(stored).as(table).containsExactlyEntriesOf(expected);
        }
    }

    @Test
    void theRepliesHoldTheRangeAndStayMaskedForALoginWithoutUnmask() {
        JdbcClient authorization = JdbcClient.create(db.dataSource(ServiceLogin.AUTHORIZATION));
        String reply = fill("reply", 63);
        String body = fill("body", 1000);
        authorization.sql("""
                INSERT INTO pauth.processed_message (msg_key, key_kind, reply_body)
                VALUES (HASHBYTES('SHA2_256', 'M|V505'), 'M', :reply)""").param("reply", reply).update();
        authorization.sql("""
                INSERT INTO pauth.outbox (exchange_name, routing_key, content_type, expiration_ms, body)
                VALUES ('x', 'r', 'text/plain', 1000, :body)""").param("body", body).update();

        assertThat(authorization.sql("SELECT reply_body FROM pauth.processed_message").query(String.class).single())
                .as("authorization_svc keeps UNMASK").isEqualTo(reply);
        assertThat(authorization.sql("SELECT body FROM pauth.outbox").query(String.class).single()).isEqualTo(body);

        admin.sql("CREATE USER v505_mask_probe WITHOUT LOGIN").update();
        try {
            admin.sql("GRANT SELECT ON OBJECT::pauth.processed_message (reply_body) TO v505_mask_probe").update();
            admin.sql("GRANT SELECT ON OBJECT::pauth.outbox (body) TO v505_mask_probe").update();
            List<String> seen = admin.sql("""
                    SET NOCOUNT ON;
                    EXECUTE AS USER = 'v505_mask_probe';
                    SELECT (SELECT reply_body FROM pauth.processed_message) AS r UNION ALL SELECT (SELECT body FROM pauth.outbox);
                    REVERT;""").query(String.class).list();
            // default() answers four x's; SQL Server 2025 gives them in lower case for the Unicode types.
            assertThat(seen).hasSize(2).allSatisfy(masked -> assertThat(masked).isEqualToIgnoringCase("XXXX"));
        } finally {
            admin.sql("DROP USER v505_mask_probe").update();
        }
    }

    /**
     * A value of the given width made of characters from U+0080 up, with the controls and pads the ruling
     * names: NUL, tab, U+0085, U+00A0 and ÿ. Each column gets a different value.
     */
    private static String fill(String seed, int width) {
        String alphabet = "\u00ff\u0000\u0009\u0085\u00a0\u00c9\u00e0\u0080\u009f" + (char) (0x00c0 + Math.abs(seed.hashCode()) % 32);
        StringBuilder value = new StringBuilder(width);
        for (int i = 0; value.length() < width; i++) {
            value.append(alphabet.charAt((i + seed.length()) % alphabet.length()));
        }
        // A trailing pad would be indistinguishable from NCHAR's own padding; end on a letter.
        value.setCharAt(width - 1, '\u00ff');
        return value.toString();
    }

    /**
     * U+0000 to U+00FF in order, cut into values of the given width; the last one is completed with letters. A
     * value never ends in a space, which NCHAR's padding would hide.
     */
    private static List<String> range(int width) {
        StringBuilder all = new StringBuilder(256);
        for (char c = 0; c <= 0xff; c++) {
            all.append(c);
        }
        while (all.length() % width != 0) {
            all.append('A');
        }
        List<String> values = new ArrayList<>();
        for (int i = 0; i < all.length(); i += width) {
            values.add(all.substring(i, i + width));
        }
        return values;
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }

    private static int width(String type) {
        return Integer.parseInt(type.substring(type.indexOf('(') + 1, type.indexOf(')')));
    }

    /** Type with the width in characters, nullability, collation and mask, e.g. {@code nchar(6) not null ... unmasked}. */
    private String describe(String table, String name) {
        return admin.sql("""
                        SELECT TYPE_NAME(c.system_type_id) COLLATE DATABASE_DEFAULT + '('
                            + CAST(CASE WHEN TYPE_NAME(c.system_type_id) IN ('nchar', 'nvarchar') THEN c.max_length / 2
                                        ELSE c.max_length END AS VARCHAR(5)) + ')'
                            + CASE WHEN c.is_nullable = 1 THEN ' null' ELSE ' not null' END
                            + ISNULL(' ' + c.collation_name COLLATE DATABASE_DEFAULT, '')
                            + CASE WHEN m.is_masked = 1 THEN ' masked ' + m.masking_function COLLATE DATABASE_DEFAULT
                                   ELSE ' unmasked' END
                        FROM sys.columns c
                        LEFT JOIN sys.masked_columns m ON m.object_id = c.object_id AND m.column_id = c.column_id
                        WHERE c.object_id = OBJECT_ID(:table) AND c.name = :name""")
                .param("table", table).param("name", name).query(String.class).single();
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
