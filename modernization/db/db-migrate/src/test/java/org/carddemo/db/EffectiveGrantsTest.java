package org.carddemo.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** How {@link EffectiveGrants} compares what each user can do with what it is meant to, without a database. */
class EffectiveGrantsTest {

    private static final Set<String> PROCEDURES = Set.of("batch.report_run_request",
            "batch.report_run_trigger_failed", "core.daily_tran_online_add", "core.daily_tran_key_lookup");

    @Test
    void exactlyTheExpectedGrantsPass() {
        assertThat(EffectiveGrants.failures(granted(objects()))).isEmpty();
    }

    @Test
    void anExtraColumnOrAnObjectLevelReadOfAColumnTableIsReported() {
        Map<String, String> objects = objects();
        EffectiveGrants.Effective e = granted(objects);
        Set<String> inquiry = new TreeSet<>(e.select(ServiceLogin.INQUIRY).get("core.account"));
        inquiry.add("addr_zip");
        e.select().get(ServiceLogin.INQUIRY).put("core.account", inquiry);
        e.select().get(ServiceLogin.AUTHORIZATION).put("core.card", EffectiveGrants.ALL);
        assertThat(EffectiveGrants.failures(e)).containsExactlyInAnyOrder(
                "inquiry_svc can SELECT columns " + inquiry + " of core.account instead of columns "
                        + new TreeSet<>(EffectiveGrants.INQUIRY_ACCOUNT_COLUMNS),
                "authorization_svc can SELECT every column of core.card instead of columns"
                        + " [acct_id, active_status, card_num, expiration_date]");
    }

    @Test
    void aStrayReadOrExecuteAndAMissingGrantAreReported() {
        Map<String, String> objects = objects();
        EffectiveGrants.Effective e = granted(objects);
        e.select().get(ServiceLogin.REFERENCE).put("core.customer", EffectiveGrants.ALL);
        e.select().get(ServiceLogin.IDENTITY).remove("iam.session");
        e.execute().get(ServiceLogin.BATCH).add("core.daily_tran_online_add");
        e.execute().get(ServiceLogin.TRANSACTION).remove("core.daily_tran_key_lookup");
        assertThat(EffectiveGrants.failures(e)).containsExactlyInAnyOrder(
                "reference_svc can SELECT every column of core.customer instead of nothing",
                "identity_svc can SELECT nothing of iam.session instead of every column",
                "batch_svc can EXECUTE [core.daily_tran_online_add] instead of []",
                "transaction_svc can EXECUTE [batch.report_run_request, batch.report_run_trigger_failed,"
                        + " core.daily_tran_online_add] instead of [batch.report_run_request,"
                        + " batch.report_run_trigger_failed, core.daily_tran_key_lookup, core.daily_tran_online_add]");
    }

    @Test
    void batchReadsEveryTableAndViewOfItsSchemaButNoProcedure() {
        Map<String, String> objects = objects();
        objects.put("batch.added_later", "U");
        assertThat(EffectiveGrants.expectedSelect(ServiceLogin.BATCH, objects))
                .containsEntry("batch.added_later", EffectiveGrants.ALL)
                .containsEntry("batch.v_report_run_stalled", EffectiveGrants.ALL)
                .doesNotContainKeys(PROCEDURES.toArray(String[]::new));
        assertThat(EffectiveGrants.expectedSelect(ServiceLogin.TRANSACTION, objects)).doesNotContainKey("batch.added_later");
        EffectiveGrants.Effective e = granted(objects());
        objects.forEach((name, type) -> e.objects().put(name, type));
        assertThat(EffectiveGrants.failures(e))
                .containsExactly("batch_svc can SELECT nothing of batch.added_later instead of every column");
    }

    /** The download checks the served bytes against the run's committed hash, and reads no execution context. */
    @Test
    void transactionReadsTheReportHashAndNoExecutionContext() {
        Map<String, Set<String>> select = EffectiveGrants.expectedSelect(ServiceLogin.TRANSACTION, objects());
        assertThat(select.get("batch.report_run")).contains("output_sha256").doesNotContain("job_execution_id");
        assertThat(select).doesNotContainKey("batch.BATCH_JOB_EXECUTION_CONTEXT");
    }

    @Test
    void aGrantOnAnObjectThatDoesNotExistIsReported() {
        Map<String, String> objects = objects();
        objects.remove("ref.us_state");
        objects.remove("core.daily_tran_key_lookup");
        EffectiveGrants.Effective e = granted(objects);
        e.select().get(ServiceLogin.BATCH).remove("ref.us_state");
        e.execute().get(ServiceLogin.TRANSACTION).remove("core.daily_tran_key_lookup");
        assertThat(EffectiveGrants.failures(e)).contains(
                "batch_svc is meant to use ref.us_state, which does not exist",
                "transaction_svc is meant to use core.daily_tran_key_lookup, which does not exist");
    }

    /** Every object some login is meant to use, as a table, view or procedure. */
    private static Map<String, String> objects() {
        Map<String, String> objects = new TreeMap<>();
        for (ServiceLogin login : ServiceLogin.values()) {
            EffectiveGrants.expectedSelect(login, Map.of()).keySet()
                    .forEach(o -> objects.put(o, o.contains(".v_") ? "V" : "U"));
        }
        objects.put("batch.report_run", "U");
        objects.put("batch.export_run", "U");
        PROCEDURES.forEach(p -> objects.put(p, "P"));
        return objects;
    }

    /** Mutable effective grants equal to the expected ones. */
    private static EffectiveGrants.Effective granted(Map<String, String> objects) {
        Map<ServiceLogin, Map<String, Set<String>>> select = new HashMap<>();
        Map<ServiceLogin, Set<String>> execute = new HashMap<>();
        for (ServiceLogin login : ServiceLogin.values()) {
            select.put(login, new TreeMap<>(EffectiveGrants.expectedSelect(login, objects)));
            execute.put(login, new TreeSet<>(EffectiveGrants.expectedExecute(login)));
        }
        return new EffectiveGrants.Effective(new TreeMap<>(objects), select, execute);
    }
}
