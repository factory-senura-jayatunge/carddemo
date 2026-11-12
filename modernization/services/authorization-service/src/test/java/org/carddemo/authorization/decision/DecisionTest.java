package org.carddemo.authorization.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.carddemo.authorization.request.Amount;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DecisionTest {

    static final LocalDate TODAY = LocalDate.of(2024, 1, 2);

    /** Limit 1000.00, posted balance 200.00, pending 50.00: 750.00 available; card and account active. */
    static DecisionFacts facts(String amount) {
        return new DecisionFacts(true, true, true, true, true, Amount.parse(amount), new BigDecimal("1000.00"),
                new BigDecimal("200.00"), new BigDecimal("50.00"), "Y", "2025-12-31", "Y", false, TODAY);
    }

    static DecisionFacts with(DecisionFacts f, String cardStatus, String expiry, String accountStatus, boolean fraud) {
        return new DecisionFacts(f.cardValid(), f.xrefFound(), f.accountFound(), f.customerFound(), f.cardFound(),
                f.amount(), f.creditLimit(), f.currBal(), f.pending(), cardStatus, expiry, accountStatus, fraud, f.today());
    }

    static DecisionFacts missing(DecisionFacts f, boolean valid, boolean xref, boolean account, boolean customer, boolean card) {
        return new DecisionFacts(valid, xref, account, customer, card, f.amount(), f.creditLimit(), f.currBal(),
                f.pending(), f.cardActiveStatus(), f.cardExpirationDate(), f.accountActiveStatus(), f.confirmedFraud(),
                f.today());
    }

    @Test
    @DisplayName("M2: an amount equal to the available credit is approved in full")
    @SpecItem({"BR-AUTH-005", "BR-AUTH-006"})
    void equalityApproves() {
        Decision d = Decision.decide(facts("750.00"));
        assertThat(d.approved()).isTrue();
        assertThat(d.respCode()).isEqualTo("00");
        assertThat(d.reason().code()).isEqualTo("0000");
        assertThat(d.approvedAmt()).isEqualByComparingTo("750.00");
    }

    @Test
    @DisplayName("M3: one cent above the available credit is declined 4100 with 0.00")
    @SpecItem({"BR-AUTH-005", "BR-AUTH-007"})
    void aboveAvailable() {
        Decision d = Decision.decide(facts("750.01"));
        assertThat(d.respCode()).isEqualTo("05");
        assertThat(d.reason()).isEqualTo(Reason.INSUFFICIENT_FUND);
        assertThat(d.approvedAmt()).hasToString("0.00");
    }

    @Test
    @DisplayName("M4, M6, M7: an invalid card or a missing row is 3100, before every other reason")
    @SpecItem({"BR-AUTH-004", "BR-AUTH-007"})
    void unknownCard() {
        DecisionFacts worst = with(facts("abc"), "N", "2000-01-01", "N", true);
        boolean[][] cases = {{false, true, true, true, true}, {true, false, true, true, true},
            {true, true, false, true, true}, {true, true, true, false, true}, {true, true, true, true, false}};
        for (boolean[] c : cases) {
            assertThat(Decision.decide(missing(worst, c[0], c[1], c[2], c[3], c[4])).reason())
                    .as(java.util.Arrays.toString(c)).isEqualTo(Reason.INVALID_CARD);
        }
    }

    @Test
    @DisplayName("M8: an inactive card, or one that expired yesterday, is 4200; expiring today is approved")
    @SpecItem({"BR-AUTH-006", "BR-AUTH-007"})
    void cardNotActive() {
        assertThat(Decision.decide(with(facts("10"), "N", "2025-12-31", "Y", false)).reason()).isEqualTo(Reason.CARD_NOT_ACTIVE);
        assertThat(Decision.decide(with(facts("10"), "Y", "2024-01-01", "Y", false)).reason()).isEqualTo(Reason.CARD_NOT_ACTIVE);
        assertThat(Decision.decide(with(facts("10"), "Y", "2024-01-02", "Y", false)).approved()).isTrue();
    }

    @Test
    @DisplayName("DEF-CRDU-009: an expiry with an impossible day is compared as text, never parsed")
    @SpecItem("BR-AUTH-006")
    void impossibleExpiryDay() {
        assertThat(Decision.decide(with(facts("10"), "Y", "2024-02-30", "Y", false)).approved()).isTrue();
        assertThat(Decision.decide(with(facts("10"), "Y", "2023-12-32", "Y", false)).reason()).isEqualTo(Reason.CARD_NOT_ACTIVE);
    }

    @Test
    @DisplayName("M9: a closed account is 4300")
    @SpecItem("BR-AUTH-007")
    void accountClosed() {
        assertThat(Decision.decide(with(facts("10"), "Y", "2025-12-31", "N", false)).reason()).isEqualTo(Reason.ACCOUNT_CLOSED);
    }

    @Test
    @DisplayName("M10: confirmed fraud on the card is 5100")
    @SpecItem("BR-AUTH-007")
    void fraud() {
        assertThat(Decision.decide(with(facts("10"), "Y", "2025-12-31", "Y", true)).reason()).isEqualTo(Reason.CARD_FRAUD);
    }

    @Test
    @DisplayName("M11: the reasons apply in the order 4100, 4200, 4300, 5100, 9000")
    @SpecItem("BR-AUTH-007")
    void priority() {
        assertThat(Decision.decide(with(facts("750.01"), "N", "2000-01-01", "N", true)).reason()).isEqualTo(Reason.INSUFFICIENT_FUND);
        assertThat(Decision.decide(with(facts("10"), "N", "2025-12-31", "N", true)).reason()).isEqualTo(Reason.CARD_NOT_ACTIVE);
        assertThat(Decision.decide(with(facts("10"), "Y", "2025-12-31", "N", true)).reason()).isEqualTo(Reason.ACCOUNT_CLOSED);
        assertThat(Decision.decide(with(facts("abc"), "Y", "2025-12-31", "Y", true)).reason()).isEqualTo(Reason.CARD_FRAUD);
    }

    @Test
    @DisplayName("M12: an invalid amount is 9000 with 0.00, never 4100")
    @SpecItem({"BR-AUTH-002", "BR-AUTH-007"})
    void invalidAmount() {
        for (String amount : new String[] {"0", "-5.00", "12345678901.00", "abc"}) {
            Decision d = Decision.decide(facts(amount));
            assertThat(d.reason()).as(amount).isEqualTo(Reason.UNKNOWN);
            assertThat(d.approvedAmt()).as(amount).hasToString("0.00");
        }
    }

    @Test
    @DisplayName("M35: without a summary nothing is pending")
    @SpecItem("BR-AUTH-005")
    void noSummary() {
        DecisionFacts f = facts("800.00");
        DecisionFacts noSummary = new DecisionFacts(true, true, true, true, true, f.amount(), f.creditLimit(), f.currBal(),
                BigDecimal.ZERO, "Y", "2025-12-31", "Y", false, TODAY);
        assertThat(Decision.decide(noSummary).approved()).isTrue();
    }

    @Test
    @DisplayName("BR-AUDT-003: the detail screen's reason text, 9999-ERROR for an unknown code")
    @SpecItem("BR-AUDT-003")
    void displayText() {
        assertThat(Reason.displayText("4100")).isEqualTo("4100-INSUFFICNT FUND");
        assertThat(Reason.displayText("5300")).isEqualTo("5300-LOST CARD");
        assertThat(Reason.displayText("1234")).isEqualTo("9999-ERROR");
    }
}
