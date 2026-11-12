package org.carddemo.authorization.decision;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.carddemo.authorization.request.Amount;

/**
 * What the decision reads for one request (D-31, D-32, D-33). A row that was not found is
 * {@code false} and its columns are {@code null}.
 *
 * @param cardValid the request's card value is exactly 16 digits
 * @param pending the summary's {@code credit_balance}, or 0 when the account has no summary
 * @param cardExpirationDate {@code core.card.expiration_date}, text in the form {@code YYYY-MM-DD}
 * @param confirmedFraud a {@code pauth.auth_fraud} row for the card has the flag {@code F}
 * @param today the date in the application's zone
 */
public record DecisionFacts(boolean cardValid, boolean xrefFound, boolean accountFound, boolean customerFound,
        boolean cardFound, Amount amount, BigDecimal creditLimit, BigDecimal currBal, BigDecimal pending,
        String cardActiveStatus, String cardExpirationDate, String accountActiveStatus, boolean confirmedFraud,
        LocalDate today) {
}
