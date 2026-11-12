package org.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.web.MessageCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountUpdateLabelsTest {

    @Test
    @DisplayName("NUM-ACUP-103: FIELD_NOT_EDITABLE fits the message line with every label of the table")
    @SpecItem("NUM-ACUP-103")
    void everyLabelFitsTheMessageLine() {
        Map<String, String> all = AccountUpdateLabels.all();
        assertThat(all).containsEntry("currentBalance", "Current Balance").containsEntry("ficoScore", "FICO Score")
                .containsEntry("openMonth", "Open Date").containsEntry("phone2Line", "Phone Number 2")
                .containsEntry("govtIssuedId", "Government Issued Id").containsKeys("acctId", "custId", "countryCd",
                        "currCycCredit", "currCycDebit");
        int longest = 0;
        for (String label : all.values()) {
            String text = MessageCatalog.FIELD_NOT_EDITABLE.render(label);
            assertThat(text).as(label).isEqualTo(label + " cannot be changed on this screen.");
            assertThat(text.length()).as(text).isLessThanOrEqualTo(75);
            longest = Math.max(longest, text.length());
        }
        assertThat(longest).isEqualTo(54);
        assertThat(MessageCatalog.FIELD_NOT_EDITABLE_UNKNOWN.render()).hasSizeLessThanOrEqualTo(75);
    }

    @Test
    void namesOutsideTheScreenHaveNoLabel() {
        assertThat(AccountUpdateLabels.of("currentBalance")).contains("Current Balance");
        assertThat(AccountUpdateLabels.of("dobDay")).contains("Date of Birth");
        for (String name : new String[] {"cvv", "cvvCd", "ssn", "", "x".repeat(10_000), "<b>123-45-6789</b>",
                "CurrentBalance"}) {
            assertThat(AccountUpdateLabels.of(name)).as(name.length() > 40 ? "long name" : name).isEmpty();
        }
    }
}
