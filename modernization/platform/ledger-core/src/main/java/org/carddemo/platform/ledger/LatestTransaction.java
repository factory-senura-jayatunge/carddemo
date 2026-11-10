package org.carddemo.platform.ledger;

import java.math.BigDecimal;

/**
 * The fields BR-TRNA-006 copies. The card number is not copied, so it is not read. The dates are
 * the first 10 characters of the original and processing timestamps, {@code YYYY-MM-DD}.
 *
 * @param waiting true when the row is an online add waiting for the next cycle
 */
public record LatestTransaction(String tranId, String typeCd, String catCd, String source, String description,
                                BigDecimal amount, String merchantId, String merchantName, String merchantCity,
                                String merchantZip, String origDate, String procDate, boolean waiting) {
}
