package org.carddemo.authorization.decision;

import java.math.BigDecimal;
import org.carddemo.authorization.request.Amount;

/**
 * The decision for one request (BR-AUTH-004 to BR-AUTH-007, fixed under D-31, D-32 and D-33).
 * Approved in full when no decline condition holds; otherwise the first condition that holds, in
 * the order 3100, 4100, 4200, 4300, 5100, 5200, 9000, gives the reason.
 */
public record Decision(Reason reason, BigDecimal approvedAmt) {

    public boolean approved() {
        return reason == Reason.APPROVED;
    }

    public String respCode() {
        return approved() ? "00" : "05";
    }

    public static Decision decide(DecisionFacts f) {
        if (!f.cardValid() || !f.xrefFound() || !f.accountFound() || !f.customerFound() || !f.cardFound()) {
            return decline(Reason.INVALID_CARD);
        }
        Amount amount = f.amount();
        BigDecimal available = f.creditLimit().subtract(f.currBal()).subtract(f.pending());
        if (amount.valid() && amount.value().compareTo(available) > 0) {
            return decline(Reason.INSUFFICIENT_FUND);
        }
        // The expiry is text that card update may hold with an impossible day (DEF-CRDU-009); in
        // YYYY-MM-DD the text order is the date order, so it is compared as text, never parsed.
        if (!"Y".equals(f.cardActiveStatus()) || f.cardExpirationDate().compareTo(f.today().toString()) < 0) {
            return decline(Reason.CARD_NOT_ACTIVE);
        }
        if (!"Y".equals(f.accountActiveStatus())) {
            return decline(Reason.ACCOUNT_CLOSED);
        }
        if (f.confirmedFraud()) {
            return decline(Reason.CARD_FRAUD);
        }
        if (!amount.valid()) {
            return decline(Reason.UNKNOWN);
        }
        return new Decision(Reason.APPROVED, amount.value());
    }

    private static Decision decline(Reason reason) {
        return new Decision(reason, Amount.ZERO);
    }
}
