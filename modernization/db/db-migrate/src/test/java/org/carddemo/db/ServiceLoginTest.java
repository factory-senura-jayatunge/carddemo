package org.carddemo.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The probes and grants each login is checked against, without a database. */
class ServiceLoginTest {

    private static final List<String> PROVENANCE = List.of("legacy_zip_group", "zip_group_origin");

    @Test
    void theProvenanceColumnsAreRefusedToEveryReaderButBatchAndUpdatedByNone() {
        for (ServiceLogin login : ServiceLogin.values()) {
            if (login == ServiceLogin.BATCH) {
                continue;
            }
            for (String column : PROVENANCE) {
                assertThat(login.forbiddenProbes()).as(login.userName())
                        .contains("SELECT TOP 0 " + column + " FROM core.account");
            }
        }
        for (ServiceLogin login : List.of(ServiceLogin.ACCOUNT, ServiceLogin.BATCH)) {
            for (String column : PROVENANCE) {
                assertThat(login.forbiddenProbes()).as(login.userName())
                        .contains("UPDATE core.account SET " + column + " = " + column + " WHERE 1 = 0");
            }
        }
        assertThat(ServiceLogin.BATCH.forbiddenProbes()).noneMatch(p -> p.startsWith("SELECT") && p.contains("zip_group"));
    }

    @Test
    void inquiryIsRefusedWhatTheAccountReplyNeverCarriesAndEveryWrite() {
        assertThat(ServiceLogin.INQUIRY.forbiddenProbes()).contains(
                "SELECT TOP 0 cust_id FROM core.customer",
                "SELECT TOP 0 addr_zip FROM core.account",
                "SELECT TOP 0 legacy_filler FROM core.account",
                "UPDATE core.account SET curr_bal = curr_bal WHERE 1 = 0",
                "SELECT TOP 0 legacy_zip_group FROM core.account");
        assertThat(ServiceLogin.INQUIRY.forbiddenProbes()).noneMatch(p -> p.startsWith("SELECT TOP 0 acct_id "));
        assertThat(ServiceLogin.INQUIRY.unmaskedColumns()).isEmpty();
    }

    @Test
    void batchUnmasksTheCustomerPiiAndTheCardNumbersItCopiesAndNeverTheCvv() {
        assertThat(ServiceLogin.BATCH.unmaskedColumns()).containsExactlyInAnyOrder("core.customer(ssn)",
                "core.customer(govt_issued_id)", "core.customer(dob)", "core.customer(eft_account_id)",
                "core.transaction(card_num)", "core.daily_tran(card_num)");
        for (ServiceLogin login : ServiceLogin.values()) {
            assertThat(login.unmaskedColumns()).as(login.userName()).noneMatch(c -> c.startsWith("core.card("));
            assertThat(login.unmaskedColumns()).as(login.userName()).allMatch(c -> c.matches("\\w+\\.\\w+\\(\\w+\\)"));
        }
    }

    @Test
    void everyProbeIsOneStatementThatChangesNoRow() {
        for (ServiceLogin login : ServiceLogin.values()) {
            for (String probe : login.forbiddenProbes()) {
                assertThat(probe).as(login.userName()).doesNotContain(";");
                if (probe.startsWith("SELECT")) {
                    assertThat(probe).as(login.userName()).startsWith("SELECT TOP 0 ");
                } else {
                    assertThat(probe).as(login.userName()).contains("WHERE 1 = 0");
                }
            }
            assertThat(login.forbiddenProbes()).as(login.userName()).doesNotHaveDuplicates();
        }
    }
}
