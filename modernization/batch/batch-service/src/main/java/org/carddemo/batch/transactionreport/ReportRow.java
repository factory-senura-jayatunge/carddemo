package org.carddemo.batch.transactionreport;

import java.math.BigDecimal;

/**
 * One master transaction of the report with the values its lookups found (BR-TRPT-007). The
 * card number is only compared for the card break; it is never printed, logged or kept.
 */
record ReportRow(String procDate, String tranId, String cardNum, String acctId, String typeCd, String typeDesc,
        String catCd, String catDesc, String source, BigDecimal amount) {

    @Override
    public String toString() {
        return "ReportRow[tranId=" + tranId + "]";
    }
}
