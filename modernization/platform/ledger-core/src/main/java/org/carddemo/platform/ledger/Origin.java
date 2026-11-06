package org.carddemo.platform.ledger;

/** Which writer created a transaction row ({@code core.transaction.origin}). */
public enum Origin {
    LOADED, POSTED, INTEREST, ONLINE_ADD, BILL_PAY
}
