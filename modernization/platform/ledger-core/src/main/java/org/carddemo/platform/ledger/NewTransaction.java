package org.carddemo.platform.ledger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/** A master transaction to insert; the record of CVTRA05Y without its filler. */
public record NewTransaction(String tranId, String typeCd, String catCd, String source, String description,
                             BigDecimal amount, String cardNum, String merchantId, String merchantName,
                             String merchantCity, String merchantZip, LocalDateTime origTs, LocalDateTime procTs,
                             Origin origin, String createdBy) {

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
    }
}
