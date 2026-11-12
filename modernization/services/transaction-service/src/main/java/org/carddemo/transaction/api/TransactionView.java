package org.carddemo.transaction.api;

import io.swagger.v3.oas.annotations.media.Schema;
import org.carddemo.platform.web.Pii;

/** A master transaction; text values with trailing spaces removed, nothing cut (DEF-TRNV-006). */
@Schema(name = "TransactionView")
public record TransactionView(
        @Schema(examples = "0000000000683580") String tranId,
        @Schema(implementation = String.class, description = "Masked (DEF-TRNV-004, D-09).",
                examples = "************7065") Pii cardNum,
        @Schema(examples = "01") String typeCd,
        @Schema(description = "As stored, leading zeros kept (NUM-TRNV-003).", examples = "0001") String catCd,
        @Schema(examples = "POS TERM") String source,
        @Schema(description = "Up to 100 characters.", examples = "Purchase at Abshire-Lowe") String description,
        @Schema(description = "Decimal string, scale 2.", examples = "504.77") String amount,
        @Schema(description = "+99999999.99, widened to +999999999.99 from 100,000,000.00 (NUM-TRNV-001,"
                + " DEF-TRNV-002).", examples = "+00000504.77") String amountDisplay,
        @Schema(description = "YYYY-MM-DD HH:MM:SS.ffffff (DEF-TRNV-006).", examples = "2022-06-10 19:27:53.000000")
        String origTs,
        @Schema(description = "YYYY-MM-DD HH:MM:SS.ffffff (DEF-TRNV-006).", examples = "2022-06-10 19:27:53.000000")
        String procTs,
        @Schema(examples = "800000000") String merchantId,
        @Schema(examples = "Abshire-Lowe") String merchantName,
        @Schema(examples = "North Enoshaven") String merchantCity,
        @Schema(examples = "72112") String merchantZip) {
}
