package org.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.validation.FieldProblem;
import org.carddemo.platform.validation.RuleTable;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.MessageCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CardViewRulesTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    static final String BLANK_ACCT = "";
    static final String INVALID_ACCT = "123";
    static final String VALID_ACCT = "00000000050";
    static final String BLANK_CARD = "";
    static final String INVALID_CARD = "ABCD";
    static final String VALID_CARD = "0500024453765740";

    /** The code, message and field of the keys' 400, or null when they pass. */
    private static String[] outcome(String acctId, String cardNum) {
        try {
            CardKeys.require(acctId, cardNum, TODAY);
            return null;
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(400);
            return new String[] {e.code(), e.text(), e.field()};
        }
    }

    @Test
    @DisplayName("VAL-CRDV-006: the decision table, all nine cells, one message each")
    @SpecItem({"VAL-CRDV-006", "VAL-CRDV-001", "VAL-CRDV-002", "VAL-CRDV-003", "VAL-CRDV-004", "VAL-CRDV-005"})
    void decisionTable() {
        String[] noInput = {"NO_INPUT", "No input received", "acctId"};
        String[] acctRequired = {"ACCT_ID_REQUIRED", "Account number not provided", "acctId"};
        String[] acctInvalid = {"INVALID_ACCT_ID", "Account number must be a non zero 11 digit number", "acctId"};
        String[] cardRequired = {"CARD_NUM_REQUIRED", "Card number not provided", "cardNum"};
        String[] cardInvalid = {"INVALID_CARD_NUM", "Card number must be a non zero 16 digit number", "cardNum"};
        Object[][] cells = {
            {BLANK_ACCT, BLANK_CARD, noInput},
            {BLANK_ACCT, INVALID_CARD, acctRequired},
            {BLANK_ACCT, VALID_CARD, acctRequired},
            {INVALID_ACCT, BLANK_CARD, acctInvalid},
            {INVALID_ACCT, INVALID_CARD, acctInvalid},
            {INVALID_ACCT, VALID_CARD, acctInvalid},
            {VALID_ACCT, BLANK_CARD, cardRequired},
            {VALID_ACCT, INVALID_CARD, cardInvalid},
            {VALID_ACCT, VALID_CARD, null},
        };
        for (Object[] cell : cells) {
            assertThat(outcome((String) cell[0], (String) cell[1])).as("%s / %s", cell[0], cell[1])
                    .isEqualTo((String[]) cell[2]);
        }
    }

    @Test
    @DisplayName("DEF-CRDV-007: the format check runs before the zero test")
    @SpecItem("DEF-CRDV-007")
    void formatBeforeZero() {
        assertThat(outcome("0000000005A", VALID_CARD)[0]).isEqualTo("INVALID_ACCT_ID");
        assertThat(outcome("00000000000", VALID_CARD)[0]).isEqualTo("ACCT_ID_REQUIRED");
        assertThat(outcome(VALID_ACCT, "000000000000000A")[0]).isEqualTo("INVALID_CARD_NUM");
        assertThat(outcome(VALID_ACCT, "0000000000000000")[0]).isEqualTo("CARD_NUM_REQUIRED");
        assertThat(outcome("00000000000", "0000000000000000")[0]).isEqualTo("NO_INPUT");
        assertThat(outcome(null, null)[0]).isEqualTo("NO_INPUT");
        assertThatCode(() -> CardKeys.require("00000000001", "4111111111111111", TODAY)).doesNotThrowAnyException();
    }

    /** The page runs the rule table's texts; the API answers with the catalogue's. They must be the same. */
    @Test
    @DisplayName("VAL-CRDV-006: each rule's text is its catalogue text")
    @SpecItem("VAL-CRDV-006")
    void ruleTextsAreTheCatalogueTexts() {
        String[][] inputs = {{"", ""}, {"", VALID_CARD}, {"1", VALID_CARD}, {VALID_ACCT, ""}, {VALID_ACCT, "1"}};
        for (String[] in : inputs) {
            Map<String, String> input = new HashMap<>(Map.of("acctId", in[0], "cardNum", in[1]));
            FieldProblem problem = RuleTable.standard().form(CardKeys.FORM).validate(input, TODAY).first().orElseThrow();
            assertThat(CardKeys.message(problem).text()).as(problem.code()).isEqualTo(problem.message());
            assertThat(CardKeys.message(problem).code()).isEqualTo(problem.code());
        }
    }

    @Test
    @DisplayName("NUM-CRDV-001: month and year by position in the stored text, without a check")
    @SpecItem("NUM-CRDV-001")
    void expiryByPosition() {
        assertThat(CardViewService.expiryMonth("2023-03-09")).isEqualTo("03");
        assertThat(CardViewService.expiryYear("2023-03-09")).isEqualTo("2023");
        assertThat(CardViewService.expiryMonth("2025-11-30")).isEqualTo("11");
        assertThat(CardViewService.expiryMonth("2025-13-99")).isEqualTo("13");
        assertThat(CardViewService.expiryYear("0000-00-00")).isEqualTo("0000");
    }

    @Test
    @DisplayName("ERR-CRDV-003: only a positive BIGINT is a card ID")
    @SpecItem("ERR-CRDV-003")
    void cardIds() {
        assertThat(CardViewService.cardId("1")).isEqualTo(1L);
        assertThat(CardViewService.cardId("9223372036854775807")).isEqualTo(Long.MAX_VALUE);
        for (String bad : new String[] {null, "", "0", "-1", "+1", " 1", "abc", "9223372036854775808",
            "99999999999999999999"}) {
            assertThatThrownBy(() -> CardViewService.cardId(bad)).as(bad)
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("CARD_NOT_FOUND"));
        }
    }

    @Test
    @DisplayName("CRDV texts fit the 75-character message line, the unmask text with the card's own field list")
    void textsFitTheMessageLine() {
        String unmask = MessageCatalog.INVALID_UNMASK_FIELD.render(String.join(", ", CardViewService.UNMASKABLE));
        assertThat(unmask).isEqualTo("Unmask one or more of: cardNum");
        for (String t : new String[] {unmask, MessageCatalog.CARD_KEY_NO_INPUT.render(),
            MessageCatalog.CARD_KEY_ACCT_ID_REQUIRED.render(), MessageCatalog.CARD_KEY_INVALID_ACCT_ID.render(),
            MessageCatalog.CARD_NUM_REQUIRED.render(), MessageCatalog.INVALID_CARD_NUM.render(),
            MessageCatalog.CARD_NOT_FOUND.render(), MessageCatalog.CARD_LOOKUP_FAILED.render(),
            MessageCatalog.CARD_DISPLAYED.render(), MessageCatalog.CARD_KEYS_PROMPT.render(),
            MessageCatalog.UNMASK_AUDIT_FAILED.render()}) {
            assertThat(t.length()).as(t).isLessThanOrEqualTo(75);
        }
    }
}
