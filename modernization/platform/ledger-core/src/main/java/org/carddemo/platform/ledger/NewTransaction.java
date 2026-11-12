package org.carddemo.platform.ledger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A master transaction to insert; the record of CVTRA05Y without its filler. {@code cycleId} is
 * the cycle that posted the row: posting and interest name it, so that a cycle rerun can remove
 * exactly what the cycle wrote; the online writers leave it {@code null}.
 */
public record NewTransaction(String tranId, String typeCd, String catCd, String source, String description,
                             BigDecimal amount, String cardNum, String merchantId, String merchantName,
                             String merchantCity, String merchantZip, LocalDateTime origTs, LocalDateTime procTs,
                             Origin origin, String createdBy, Integer cycleId) {

    public NewTransaction {
        Objects.requireNonNull(tranId, "tranId");
        Objects.requireNonNull(typeCd, "typeCd");
        Objects.requireNonNull(catCd, "catCd");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(cardNum, "cardNum");
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(merchantName, "merchantName");
        Objects.requireNonNull(merchantCity, "merchantCity");
        Objects.requireNonNull(merchantZip, "merchantZip");
        Objects.requireNonNull(origTs, "origTs");
        Objects.requireNonNull(procTs, "procTs");
        Objects.requireNonNull(origin, "origin");
        if (cycleId == null && (origin == Origin.POSTED || origin == Origin.INTEREST)) {
            throw new IllegalArgumentException("a " + origin + " transaction names the cycle that wrote it");
        }
        if (cycleId != null && cycleId < 1) {
            throw new IllegalArgumentException("cycleId must be a batch.cycle ID, at least 1");
        }
    }

    /** A transaction no cycle wrote: the online writers' form. */
    public NewTransaction(String tranId, String typeCd, String catCd, String source, String description,
                          BigDecimal amount, String cardNum, String merchantId, String merchantName,
                          String merchantCity, String merchantZip, LocalDateTime origTs, LocalDateTime procTs,
                          Origin origin, String createdBy) {
        this(tranId, typeCd, catCd, source, description, amount, cardNum, merchantId, merchantName, merchantCity,
                merchantZip, origTs, procTs, origin, createdBy, null);
    }
}
