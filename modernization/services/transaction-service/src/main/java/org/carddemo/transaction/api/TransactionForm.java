package org.carddemo.transaction.api;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The add form as typed (COTRN02C). Every member is a string; a missing member counts as empty.
 * There is no processing date: the server sets it to today (D-52).
 */
@Schema(name = "TransactionForm")
public record TransactionForm(
        @Schema(description = "Account, 1 to 11 digits; this or cardNum.", examples = "50") String acctId,
        @Schema(description = "Card number, 1 to 16 digits; this or acctId.") String cardNum,
        @Schema(examples = "1") String typeCd,
        @Schema(examples = "1") String catCd,
        @Schema(examples = "POS TERM") String source,
        @Schema(examples = "Purchase at Abshire-Lowe") String description,
        @Schema(description = "Exactly -99999999.99 or +99999999.99.", examples = "-00000012.50") String amount,
        @Schema(description = "YYYY-MM-DD.", examples = "2022-07-06") String origDate,
        @Schema(examples = "123") String merchantId,
        @Schema(examples = "Abshire-Lowe") String merchantName,
        @Schema(examples = "North Enoshaven") String merchantCity,
        @Schema(examples = "72112") String merchantZip,
        @Schema(description = "addTransaction only: Y or y adds; validateTransaction ignores it.", examples = "Y")
        String confirm) {
}
