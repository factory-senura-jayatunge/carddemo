package org.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.web.MessageCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountFormatsTest {

    @Test
    @DisplayName("NUM-ACVW-001: the legacy edit below a billion, the widened edit from a billion")
    @SpecItem({"NUM-ACVW-001", "DEF-ACVW-005"})
    void amountDisplay() {
        String[][] cases = {
            {"2020.00", "+      2,020.00"}, {"194.00", "+        194.00"}, {"0.00", "+           .00"},
            {"0.05", "+           .05"}, {"-25.50", "-         25.50"}, {"999999999.99", "+999,999,999.99"},
            {"-999999999.99", "-999,999,999.99"}, {"1000000000.00", "+1,000,000,000.00"},
            {"1234567890.12", "+1,234,567,890.12"}, {"-9999999999.99", "-9,999,999,999.99"},
        };
        for (String[] c : cases) {
            assertThat(AccountFormats.amountDisplay(new BigDecimal(c[0]))).as(c[0]).isEqualTo(c[1]);
        }
        assertThat(AccountFormats.amount(new BigDecimal("2020"))).isEqualTo("2020.00");
        assertThat(AccountFormats.amount(new BigDecimal("-9999999999.99"))).isEqualTo("-9999999999.99");
    }

    @Test
    @DisplayName("NUM-ACVW-002, NUM-ACVW-003: dates, the SSN display form and the FICO score")
    @SpecItem({"NUM-ACVW-002", "NUM-ACVW-003", "NUM-ACVW-004"})
    void datesSsnAndFico() {
        assertThat(AccountFormats.date(LocalDate.of(2014, 11, 20))).isEqualTo("2014-11-20");
        assertThat(AccountFormats.date(null)).isEmpty();
        assertThat(AccountFormats.ssnDisplay("020973888")).isEqualTo("020-97-3888");
        assertThat(AccountFormats.fico(274)).isEqualTo("274");
        assertThat(AccountFormats.fico(7)).isEqualTo("007");
    }

    @Test
    @DisplayName("ACUP, ACVW: the group loses only the trailing blanks of its NCHAR(10) padding")
    @SpecItem("BR-ACUP-108")
    void groupIdPadding() {
        assertThat(AccountFormats.groupId("A000000000")).isEqualTo("A000000000");
        assertThat(AccountFormats.groupId("GOLD      ")).isEqualTo("GOLD");
        assertThat(AccountFormats.groupId("          ")).isEmpty();
        assertThat(AccountFormats.groupId("")).isEmpty();
        assertThat(AccountFormats.groupId(" \u00C0\u00C9\u0085      ")).isEqualTo(" \u00C0\u00C9\u0085");
        assertThat(AccountFormats.groupId("AB\t       ")).isEqualTo("AB\t");
        assertThat(AccountFormats.groupId("AB\u00A0 \u0000  ")).isEqualTo("AB\u00A0 \u0000");
    }

    /**
     * The screen's message line holds 75 characters, and every text an endpoint returns counts,
     * including the ones only an API client can reach today. Placeholders take their longest value.
     */
    @Test
    @DisplayName("NUM-ACVW-007: every text the account view shows fits the 75-character message line")
    @SpecItem("NUM-ACVW-007")
    void textsFitTheMessageLine() {
        List<String> texts = List.of(MessageCatalog.ACVW_ACCT_ID_REQUIRED.render(),
                MessageCatalog.ACVW_INVALID_ACCT_ID.render(), MessageCatalog.ACCOUNT_ID_NOT_FOUND.render("99999999999"),
                MessageCatalog.CUSTOMER_NOT_FOUND.render("999999999"), MessageCatalog.NO_CARD_LINKED.render(),
                MessageCatalog.ACCOUNT_DATA_READ_FAILED.render(), MessageCatalog.XREF_DATA_READ_FAILED.render(),
                MessageCatalog.CUSTOMER_LOOKUP_FAILED.render(), MessageCatalog.ACCOUNT_VIEW_PROMPT.render(),
                MessageCatalog.UNMASK_AUDIT_FAILED.render(), MessageCatalog.INVALID_KEY.render(),
                MessageCatalog.INVALID_UNMASK_FIELD.render(String.join(", ", AccountViewService.UNMASKABLE)));
        assertThat(texts).contains("Unmask one or more of: ssn, dob, govtIssuedId, eftAccountId, cardNum");
        assertThat(texts).allSatisfy(t -> assertThat(t.length()).as(t).isLessThanOrEqualTo(75));
    }
}
