package org.carddemo.batch.categorylisting;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.carddemo.batch.catalogue.CatalogueFailure;
import org.carddemo.platform.legacyformat.Latin1;

/** BR-JPCAT-001, NUM-JPCAT-001: exactly 40 bytes plus LF, without a header or trailer. */
final class CategoryBalanceLine {
    private static final BigDecimal MAX = new BigDecimal("999999999.99");

    private CategoryBalanceLine() { }

    static byte[] format(String account, String type, String category, BigDecimal balance, boolean signed) {
        if (account == null || !account.matches("[0-9]{11}") || type == null || type.length() != 2
                || category == null || !category.matches("[0-9]{4}") || balance == null
                || balance.abs().compareTo(MAX) > 0) {
            throw new CatalogueFailure("LISTING_LAYOUT", "category balance row");
        }
        String amount;
        try {
            amount = balance.abs().setScale(2, RoundingMode.UNNECESSARY).toPlainString();
        } catch (ArithmeticException e) {
            throw new CatalogueFailure("LISTING_LAYOUT", "balance precision");
        }
        amount = "0".repeat(12 - amount.length()) + amount;
        return Latin1.encode(account + " " + type + " " + category + " " + amount
                + (signed && balance.signum() < 0 ? "-" : " ") + "       \n", "PRTCATBL line");
    }
}
