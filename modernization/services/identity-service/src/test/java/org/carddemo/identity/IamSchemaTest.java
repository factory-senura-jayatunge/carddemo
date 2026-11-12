package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The iam columns after V203 to V206 (data-model.md, Text repertoire and width; identity-service.md,
 * Versions and sections 6 and 7): type, length, collation, nullability, default and mask of every changed
 * column, and the keys, checks and indexes the migrations recreate or add.
 */
class IamSchemaTest {

    static final String BIN2 = "Latin1_General_100_BIN2";
    static final String DEFAULT_COLLATION = "Latin1_General_100_CI_AS_SC_UTF8";

    private final JdbcClient admin = TestDatabase.shared().admin();

    @Test
    void theUserIdFamilyIsNchar8Bin2AndTheTextColumnsAreUnicode() {
        assertThat(columns(admin)).containsAllEntriesOf(expectedColumns());
    }

    @Test
    void theEbcdicSortKeyIsGone() {
        assertThat(admin.sql("SELECT COL_LENGTH('iam.app_user', 'ebcdic_sort_key')").query(Integer.class).optional())
                .isEmpty();
        assertThat(admin.sql("SELECT COUNT(*) FROM sys.indexes WHERE name = 'IX_app_user_ebcdic'")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void theKeysChecksAndIndexesAreRecreatedAsBefore() {
        assertThat(indexes(admin)).contains(
                "iam.app_user|PK_app_user|CLUSTERED|unique|pk|user_id|",
                "iam.app_user|IX_app_user_admins|NONCLUSTERED|-|-|user_type|([deleted_at] IS NULL)",
                "iam.session|IX_session_user|NONCLUSTERED|-|-|user_id,ended_at|",
                "iam.audit_event|IX_audit_event_subject|NONCLUSTERED|-|-|subject_key,occurred_at|");
        assertThat(checks(admin)).extracting(c -> c.substring(0, c.indexOf('|'))).contains(
                "CK_app_user_upper", "CK_app_user_not_blank", "CK_app_user_deleted");
        assertThat(foreignKey(admin, "FK_session_user")).isEqualTo("session.user_id>app_user.user_id|0|NO_ACTION");
    }

    @Test
    void theSignOnStateHasItsOwnTableAndLeftTheUserRow() {
        for (String moved : List.of("failed_attempts", "locked_until", "last_sign_on_at")) {
            assertThat(admin.sql("SELECT COL_LENGTH('iam.app_user', :c)").param("c", moved).query(Integer.class)
                    .optional()).as(moved).isEmpty();
        }
        assertThat(admin.sql("""
                SELECT COUNT(*) FROM sys.objects
                WHERE name IN ('CK_app_user_failed', 'DF_app_user_failed') AND schema_id = SCHEMA_ID('iam')""")
                .query(Integer.class).single()).isZero();
        assertThat(admin.sql("SELECT COL_LENGTH('iam.user_signon_state', 'row_version')").query(Integer.class)
                .optional()).as("no version of its own").isEmpty();
        assertThat(indexes(admin)).filteredOn(i -> i.startsWith("iam.user_signon_state|"))
                .containsExactly("iam.user_signon_state|PK_user_signon_state|CLUSTERED|unique|pk|user_id|");
        assertThat(checks(admin)).filteredOn(c -> c.startsWith("CK_user_signon_state"))
                .containsExactly("CK_user_signon_state_failed|([failed_attempts]>=(0))");
        // Users are deleted logically; the cascade only serves an administrator's cleanup.
        assertThat(foreignKey(admin, "FK_signon_state_user"))
                .isEqualTo("user_signon_state.user_id>app_user.user_id|0|CASCADE");
    }

    @Test
    void theSignOnStateChecksTheCountAndTheUser() {
        new IdentityFixtures(admin).deleteAll();
        try {
            assertThatThrownBy(() -> admin.sql("INSERT INTO iam.user_signon_state (user_id) VALUES (N'NOBODY')")
                    .update()).hasMessageContaining("FK_signon_state_user");
            insertUser("USER0001", false);
            assertThatThrownBy(() -> admin.sql("""
                    INSERT INTO iam.user_signon_state (user_id, failed_attempts) VALUES (N'USER0001', -1)""")
                    .update()).hasMessageContaining("CK_user_signon_state_failed");
            admin.sql("INSERT INTO iam.user_signon_state (user_id) VALUES (N'USER0001')").update();
            admin.sql("DELETE FROM iam.app_user WHERE user_id = N'USER0001'").update();
            assertThat(admin.sql("SELECT COUNT(*) FROM iam.user_signon_state").query(Integer.class).single())
                    .isZero();
        } finally {
            new IdentityFixtures(admin).deleteAll();
        }
    }

    /** {@code table.column>referenced table.column|not trusted|delete action} of a one-column key. */
    static String foreignKey(JdbcClient jdbc, String name) {
        return jdbc.sql("""
                        SELECT OBJECT_NAME(f.parent_object_id) COLLATE DATABASE_DEFAULT + '.'
                               + COL_NAME(c.parent_object_id, c.parent_column_id) COLLATE DATABASE_DEFAULT + '>'
                               + OBJECT_NAME(f.referenced_object_id) COLLATE DATABASE_DEFAULT + '.'
                               + COL_NAME(c.referenced_object_id, c.referenced_column_id) COLLATE DATABASE_DEFAULT
                               + '|' + CAST(f.is_not_trusted AS VARCHAR(1))
                               + '|' + f.delete_referential_action_desc COLLATE DATABASE_DEFAULT
                        FROM sys.foreign_keys f JOIN sys.foreign_key_columns c ON c.constraint_object_id = f.object_id
                        WHERE f.name = :name""").param("name", name).query(String.class).single();
    }

    @Test
    void theChecksStillRefuseWhatTheyRefusedAndAcceptTheLegacyRange() {
        // The API tests share this database and leave their users behind.
        new IdentityFixtures(admin).deleteAll();
        try {
            assertThatThrownBy(() -> insertUser("user0001", false)).hasMessageContaining("CK_app_user_upper");
            assertThatThrownBy(() -> insertUser("", false)).hasMessageContaining("CK_app_user_not_blank");
            assertThatThrownBy(() -> insertUser("USER0001", true)).hasMessageContaining("CK_app_user_deleted");
            // Only a to z count as lower case under BIN2: é, ß, ÿ and a C1 control are stored as given.
            String id = "\u00e9\u00df\u00ff\u0085\0A\u00a0Z";
            insertUser(id, false);
            assertThat(admin.sql("SELECT user_id FROM iam.app_user WHERE user_id = :id").param("id", id)
                    .query(String.class).single()).isEqualTo(id);
        } finally {
            new IdentityFixtures(admin).deleteAll();
        }
    }

    /** {@code deletedWithoutActor} sets deleted_at and leaves deleted_by null. */
    private void insertUser(String userId, boolean deletedWithoutActor) {
        admin.sql("INSERT INTO iam.app_user (user_id, first_name, last_name, password_hash, user_type, deleted_at)"
                        + " VALUES (:id, N'F', N'L', '{bcrypt}x', N'U', "
                        + (deletedWithoutActor ? "SYSUTCDATETIME()" : "NULL") + ")")
                .param("id", userId)
                .update();
    }

    /** Every column V203 to V206 create or change, as {@link #columns} formats it. */
    static Map<String, String> expectedColumns() {
        return Map.ofEntries(
                Map.entry("iam.app_user.user_id", "nchar(8) " + BIN2 + " not null"),
                Map.entry("iam.app_user.first_name", "nvarchar(20) " + DEFAULT_COLLATION + " not null"),
                Map.entry("iam.app_user.last_name", "nvarchar(20) " + DEFAULT_COLLATION + " not null"),
                Map.entry("iam.app_user.user_type", "nchar(1) " + BIN2 + " not null"),
                Map.entry("iam.app_user.created_by", "nchar(8) " + BIN2 + " null"),
                Map.entry("iam.app_user.updated_by", "nchar(8) " + BIN2 + " null"),
                Map.entry("iam.app_user.deleted_by", "nchar(8) " + BIN2 + " null"),
                Map.entry("iam.app_user.deleted_at", "datetime2(3) null"),
                // V205: an Argon2id hash in the {id} format is 105 characters.
                Map.entry("iam.app_user.password_hash", "varchar(255) " + DEFAULT_COLLATION + " not null"),
                Map.entry("iam.app_user.must_change_password", "bit not null default ((0))"),
                Map.entry("iam.app_user.created_at", "datetime2(3) not null default (sysutcdatetime())"),
                Map.entry("iam.user_signon_state.user_id", "nchar(8) " + BIN2 + " not null"),
                Map.entry("iam.user_signon_state.failed_attempts", "smallint not null default ((0))"),
                Map.entry("iam.user_signon_state.locked_until", "datetime2(3) null"),
                Map.entry("iam.user_signon_state.last_sign_on_at", "datetime2(3) null"),
                Map.entry("iam.session.user_id", "nchar(8) " + BIN2 + " not null"),
                Map.entry("iam.audit_event.actor_user_id", "nchar(8) " + BIN2 + " null"),
                Map.entry("iam.audit_event.subject_key", "nvarchar(40) " + DEFAULT_COLLATION + " null"));
    }

    /**
     * Each iam column as {@code type(length) collation null|not null [default (...)] [masked ...]};
     * the length is in characters for the N types.
     */
    static Map<String, String> columns(JdbcClient jdbc) {
        return jdbc.sql("""
                        SELECT s.name COLLATE DATABASE_DEFAULT + '.' + o.name COLLATE DATABASE_DEFAULT + '.' + c.name COLLATE DATABASE_DEFAULT,
                               TYPE_NAME(c.system_type_id) COLLATE DATABASE_DEFAULT
                               + CASE WHEN TYPE_NAME(c.system_type_id) IN ('nchar', 'nvarchar')
                                          THEN '(' + CAST(c.max_length / 2 AS VARCHAR(5)) + ')'
                                      WHEN TYPE_NAME(c.system_type_id) IN ('char', 'varchar', 'binary', 'varbinary')
                                          THEN '(' + CAST(c.max_length AS VARCHAR(5)) + ')'
                                      WHEN TYPE_NAME(c.system_type_id) = 'datetime2'
                                          THEN '(' + CAST(c.scale AS VARCHAR(3)) + ')'
                                      ELSE '' END
                               + COALESCE(' ' + c.collation_name COLLATE DATABASE_DEFAULT, '')
                               + CASE WHEN c.is_nullable = 1 THEN ' null' ELSE ' not null' END
                               + COALESCE(' default ' + d.definition COLLATE DATABASE_DEFAULT, '')
                               + COALESCE(' masked ' + m.masking_function COLLATE DATABASE_DEFAULT, '')
                        FROM sys.columns c
                        JOIN sys.objects o ON o.object_id = c.object_id
                        JOIN sys.schemas s ON s.schema_id = o.schema_id
                        LEFT JOIN sys.default_constraints d ON d.object_id = c.default_object_id
                        LEFT JOIN sys.masked_columns m ON m.object_id = c.object_id AND m.column_id = c.column_id
                        WHERE s.name = 'iam' AND o.type = 'U'""")
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** Each iam index as {@code table|name|type|unique|pk|key columns|filter}. */
    static List<String> indexes(JdbcClient jdbc) {
        return jdbc.sql("""
                        SELECT 'iam.' + o.name COLLATE DATABASE_DEFAULT + '|' + i.name COLLATE DATABASE_DEFAULT + '|'
                               + i.type_desc COLLATE DATABASE_DEFAULT + '|'
                               + CASE WHEN i.is_unique = 1 THEN 'unique' ELSE '-' END + '|'
                               + CASE WHEN i.is_primary_key = 1 THEN 'pk' ELSE '-' END + '|'
                               + (SELECT STRING_AGG(c.name COLLATE DATABASE_DEFAULT, ',') WITHIN GROUP (ORDER BY ic.key_ordinal)
                                  FROM sys.index_columns ic
                                  JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                                  WHERE ic.object_id = i.object_id AND ic.index_id = i.index_id
                                    AND ic.is_included_column = 0) + '|'
                               + COALESCE(i.filter_definition COLLATE DATABASE_DEFAULT, '')
                        FROM sys.indexes i JOIN sys.objects o ON o.object_id = i.object_id
                        WHERE SCHEMA_NAME(o.schema_id) = 'iam' AND i.type > 0""")
                .query(String.class).list();
    }

    /** Each iam check constraint as {@code name|definition}. */
    static List<String> checks(JdbcClient jdbc) {
        return jdbc.sql("""
                        SELECT k.name COLLATE DATABASE_DEFAULT + '|' + k.definition COLLATE DATABASE_DEFAULT FROM sys.check_constraints k
                        WHERE SCHEMA_NAME(k.schema_id) = 'iam'""")
                .query(String.class).list();
    }
}
