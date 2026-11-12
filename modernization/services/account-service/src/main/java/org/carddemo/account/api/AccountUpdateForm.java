package org.carddemo.account.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Pii;
import org.carddemo.platform.web.Severity;

/**
 * Account update (COACTUPC). Date, SSN, ZIP and phone values also come in their parts, as the
 * screen edits them. With no card on the account the customer fields, {@code cardId} and
 * {@code custId} are null and {@code version} is the account's alone (D-61).
 */
@Schema(name = "AccountUpdateForm")
public record AccountUpdateForm(
        @Schema(examples = "00000000001") String acctId,
        @Schema(types = {"string", "null"}, description = "The card whose cross-reference row named the customer.")
        String cardId,
        String activeStatus,
        @Schema(examples = "2014") String openYear,
        @Schema(examples = "11") String openMonth,
        @Schema(examples = "20") String openDay,
        String expiryYear,
        String expiryMonth,
        String expiryDay,
        String reissueYear,
        String reissueMonth,
        String reissueDay,
        @Schema(examples = "2020.00") String creditLimit,
        @Schema(examples = "+      2,020.00") String creditLimitDisplay,
        String cashCreditLimit,
        String cashCreditLimitDisplay,
        @Schema(description = "Read-only (D-08).") String currentBalance,
        String currentBalanceDisplay,
        @Schema(description = "Read-only (D-08).") String currCycCredit,
        String currCycCreditDisplay,
        @Schema(description = "Read-only (D-08).") String currCycDebit,
        String currCycDebitDisplay,
        @Schema(description = "core.account.group_id (D-11).") String groupId,
        @Schema(types = {"string", "null"}) String custId,
        @Schema(implementation = String.class, types = {"string", "null"}, description = "Masked; write-only through ssn1, ssn2, ssn3.")
        Pii ssn,
        @Schema(implementation = String.class, types = {"string", "null"}, description = "Masked; write-only through dobYear, dobMonth, dobDay.")
        Pii dob,
        @Schema(types = {"string", "null"}) String ficoScore,
        @Schema(types = {"string", "null"}) String firstName,
        @Schema(types = {"string", "null"}) String middleName,
        @Schema(types = {"string", "null"}) String lastName,
        @Schema(types = {"string", "null"}) String addrLine1,
        @Schema(types = {"string", "null"}) String addrLine2,
        @Schema(types = {"string", "null"}) String city,
        @Schema(types = {"string", "null"}) String stateCd,
        @Schema(types = {"string", "null"}) String zip,
        @Schema(types = {"string", "null"}) String zip5,
        @Schema(types = {"string", "null"}) String zipPlus4,
        @Schema(types = {"string", "null"}, description = "Read-only (BR-ACUP-008).") String countryCd,
        @Schema(types = {"string", "null"}) String phone1,
        @Schema(types = {"string", "null"}) String phone1Area,
        @Schema(types = {"string", "null"}) String phone1Prefix,
        @Schema(types = {"string", "null"}) String phone1Line,
        @Schema(types = {"string", "null"}) String phone2,
        @Schema(types = {"string", "null"}) String phone2Area,
        @Schema(types = {"string", "null"}) String phone2Prefix,
        @Schema(types = {"string", "null"}) String phone2Line,
        @Schema(implementation = String.class, types = {"string", "null"}, description = "Masked; write-only.")
        Pii govtIssuedId,
        @Schema(implementation = String.class, types = {"string", "null"}, description = "Masked; write-only.")
        Pii eftAccountId,
        @Schema(types = {"string", "null"}) String priCardHolderInd,
        @Schema(description = "Same value as the ETag, without quotes.",
                examples = "00000000000007d1.00000000000007d2") String version,
        @Schema(types = {"string", "null"}) String message,
        @Schema(types = {"string", "null"}) Severity severity) {
}
