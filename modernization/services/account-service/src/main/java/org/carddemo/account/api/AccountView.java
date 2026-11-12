package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Severity;

/**
 * Account view (COACTVWC). With no card on the account, {@code crossReference} and
 * {@code customer} are null and {@code code} is {@code NO_CARD_LINKED} (D-61).
 */
@Schema(name = "AccountView")
public record AccountView(
        @Schema(examples = "00000000001") String acctId,
        String activeStatus,
        @Schema(description = "YYYY-MM-DD, or \"\" when not stored.", examples = "2014-11-20") String openDate,
        String expirationDate,
        String reissueDate,
        @Schema(description = "Decimal string, scale 2.", examples = "2020.00") String creditLimit,
        @Schema(description = "Legacy edit +ZZZ,ZZZ,ZZZ.99, widened at a billion or more (NUM-ACVW-001).",
                examples = "+      2,020.00") String creditLimitDisplay,
        String cashCreditLimit,
        String cashCreditLimitDisplay,
        String currentBalance,
        String currentBalanceDisplay,
        String currCycCredit,
        String currCycCreditDisplay,
        String currCycDebit,
        String currCycDebitDisplay,
        String groupId,
        @Schema(types = {"object", "null"}) CrossReference crossReference,
        @Schema(types = {"object", "null"}) CustomerView customer,
        @Schema(types = {"string", "null"}, examples = "NO_CARD_LINKED") String code,
        @Schema(types = {"string", "null"}) String message,
        @Schema(types = {"string", "null"}) Severity severity,
        @Schema(types = {"string", "null"}) String field) {
}
