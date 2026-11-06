package org.carddemo.platform.legacyformat.layout;

import org.carddemo.platform.legacyformat.record.RecordLayout;

/**
 * The CardDemo record layouts, field for field from the copybooks
 * (ENT-* in {@code docs/modernization/spec/entities.md}). Field names are the
 * copybook names. FILLERs that a target column keeps for byte-exact export
 * ({@code legacy_filler}) are named; the others carry the constant every
 * legacy record holds there.
 */
public final class Layouts {

    private Layouts() {
    }

    /** ENT-ACCOUNT, {@code CVACT01Y}, ACCTDATA. */
    public static final RecordLayout ACCOUNT = RecordLayout.builder("ACCOUNT-RECORD", 300)
            .digits("ACCT-ID", 11)
            .text("ACCT-ACTIVE-STATUS", 1)
            .zoned("ACCT-CURR-BAL", "S9(10)V99")
            .zoned("ACCT-CREDIT-LIMIT", "S9(10)V99")
            .zoned("ACCT-CASH-CREDIT-LIMIT", "S9(10)V99")
            .text("ACCT-OPEN-DATE", 10)
            .text("ACCT-EXPIRAION-DATE", 10)
            .text("ACCT-REISSUE-DATE", 10)
            .zoned("ACCT-CURR-CYC-CREDIT", "S9(10)V99")
            .zoned("ACCT-CURR-CYC-DEBIT", "S9(10)V99")
            .text("ACCT-ADDR-ZIP", 10)
            .text("ACCT-GROUP-ID", 10)
            .namedFiller("ACCT-FILLER", 178, ' ')
            .build();

    /** ENT-CUSTOMER, {@code CVCUS01Y}, CUSTDATA. */
    public static final RecordLayout CUSTOMER = RecordLayout.builder("CUSTOMER-RECORD", 500)
            .digits("CUST-ID", 9)
            .text("CUST-FIRST-NAME", 25)
            .text("CUST-MIDDLE-NAME", 25)
            .text("CUST-LAST-NAME", 25)
            .text("CUST-ADDR-LINE-1", 50)
            .text("CUST-ADDR-LINE-2", 50)
            .text("CUST-ADDR-LINE-3", 50)
            .text("CUST-ADDR-STATE-CD", 2)
            .text("CUST-ADDR-COUNTRY-CD", 3)
            .text("CUST-ADDR-ZIP", 10)
            .text("CUST-PHONE-NUM-1", 15)
            .text("CUST-PHONE-NUM-2", 15)
            .digits("CUST-SSN", 9)
            .text("CUST-GOVT-ISSUED-ID", 20)
            .text("CUST-DOB-YYYY-MM-DD", 10)
            .text("CUST-EFT-ACCOUNT-ID", 10)
            .text("CUST-PRI-CARD-HOLDER-IND", 1)
            .digits("CUST-FICO-CREDIT-SCORE", 3)
            .filler(168)
            .build();

    /** ENT-CARD, {@code CVACT02Y}, CARDDATA. */
    public static final RecordLayout CARD = RecordLayout.builder("CARD-RECORD", 150)
            .text("CARD-NUM", 16)
            .digits("CARD-ACCT-ID", 11)
            .digits("CARD-CVV-CD", 3)
            .text("CARD-EMBOSSED-NAME", 50)
            .text("CARD-EXPIRAION-DATE", 10)
            .text("CARD-ACTIVE-STATUS", 1)
            .namedFiller("CARD-FILLER", 59, ' ')
            .build();

    /** ENT-CARD-XREF, {@code CVACT03Y}, CARDXREF. */
    public static final RecordLayout CARD_XREF = RecordLayout.builder("CARD-XREF-RECORD", 50)
            .text("XREF-CARD-NUM", 16)
            .digits("XREF-CUST-ID", 9)
            .digits("XREF-ACCT-ID", 11)
            .filler(14)
            .build();

    /** ENT-TRANSACTION, {@code CVTRA05Y}, TRANSACT. */
    public static final RecordLayout TRANSACTION = RecordLayout.builder("TRAN-RECORD", 350)
            .text("TRAN-ID", 16)
            .text("TRAN-TYPE-CD", 2)
            .digits("TRAN-CAT-CD", 4)
            .text("TRAN-SOURCE", 10)
            .text("TRAN-DESC", 100)
            .zoned("TRAN-AMT", "S9(09)V99")
            .digits("TRAN-MERCHANT-ID", 9)
            .text("TRAN-MERCHANT-NAME", 50)
            .text("TRAN-MERCHANT-CITY", 50)
            .text("TRAN-MERCHANT-ZIP", 10)
            .text("TRAN-CARD-NUM", 16)
            .text("TRAN-ORIG-TS", 26)
            .text("TRAN-PROC-TS", 26)
            .namedFiller("TRAN-FILLER", 20, ' ')
            .build();

    /** ENT-DAILY-TRAN, {@code CVTRA06Y}, DALYTRAN (same positions as ENT-TRANSACTION). */
    public static final RecordLayout DAILY_TRAN = RecordLayout.builder("DALYTRAN-RECORD", 350)
            .text("DALYTRAN-ID", 16)
            .text("DALYTRAN-TYPE-CD", 2)
            .digits("DALYTRAN-CAT-CD", 4)
            .text("DALYTRAN-SOURCE", 10)
            .text("DALYTRAN-DESC", 100)
            .zoned("DALYTRAN-AMT", "S9(09)V99")
            .digits("DALYTRAN-MERCHANT-ID", 9)
            .text("DALYTRAN-MERCHANT-NAME", 50)
            .text("DALYTRAN-MERCHANT-CITY", 50)
            .text("DALYTRAN-MERCHANT-ZIP", 10)
            .text("DALYTRAN-CARD-NUM", 16)
            .text("DALYTRAN-ORIG-TS", 26)
            .text("DALYTRAN-PROC-TS", 26)
            .namedFiller("DALYTRAN-FILLER", 20, ' ')
            .build();

    /** ENT-DAILY-REJECT, CBTRN02C's reject record, DALYREJS. */
    public static final RecordLayout DAILY_REJECT = RecordLayout.builder("REJECT-RECORD", 430)
            .namedFiller("REJECT-TRAN-DATA", 350, ' ')
            .digits("WS-VALIDATION-FAIL-REASON", 4)
            .text("WS-VALIDATION-FAIL-REASON-DESC", 76)
            .build();

    /** ENT-TCAT-BAL, {@code CVTRA01Y}, TCATBALF. The FILLER holds 22 zeros in the sample. */
    public static final RecordLayout TCAT_BAL = RecordLayout.builder("TRAN-CAT-BAL-RECORD", 50)
            .digits("TRANCAT-ACCT-ID", 11)
            .text("TRANCAT-TYPE-CD", 2)
            .digits("TRANCAT-CD", 4)
            .zoned("TRAN-CAT-BAL", "S9(09)V99")
            .namedFiller("TCATBAL-FILLER", 22, '0')
            .build();

    /** ENT-DISC-GROUP, {@code CVTRA02Y}, DISCGRP. The FILLER holds 28 zeros. */
    public static final RecordLayout DISC_GROUP = RecordLayout.builder("DIS-GROUP-RECORD", 50)
            .text("DIS-ACCT-GROUP-ID", 10)
            .text("DIS-TRAN-TYPE-CD", 2)
            .digits("DIS-TRAN-CAT-CD", 4)
            .zoned("DIS-INT-RATE", "S9(04)V99")
            .filler(28, '0')
            .build();

    /** ENT-TRAN-TYPE, {@code CVTRA03Y}, TRANTYPE. The FILLER holds 8 zeros. */
    public static final RecordLayout TRAN_TYPE = RecordLayout.builder("TRAN-TYPE-RECORD", 60)
            .text("TRAN-TYPE", 2)
            .text("TRAN-TYPE-DESC", 50)
            .filler(8, '0')
            .build();

    /** ENT-TRAN-CATEGORY, {@code CVTRA04Y}, TRANCATG. The FILLER holds 4 zeros. */
    public static final RecordLayout TRAN_CATEGORY = RecordLayout.builder("TRAN-CAT-RECORD", 60)
            .text("TRAN-TYPE-CD", 2)
            .digits("TRAN-CAT-CD", 4)
            .text("TRAN-CAT-TYPE-DESC", 50)
            .filler(4, '0')
            .build();

    /** ENT-USER-SECURITY, {@code CSUSR01Y}, USRSEC. */
    public static final RecordLayout USER_SECURITY = RecordLayout.builder("SEC-USER-DATA", 80)
            .text("SEC-USR-ID", 8)
            .text("SEC-USR-FNAME", 20)
            .text("SEC-USR-LNAME", 20)
            .text("SEC-USR-PWD", 8)
            .text("SEC-USR-TYPE", 1)
            .text("SEC-USR-FILLER", 23)
            .build();
}
