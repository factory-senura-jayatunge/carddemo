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

    /**
     * ENT-AUTH-SUMMARY, {@code CIPAUSMY}, IMS root segment PAUTSUM0 of DBPAUTP0. The five
     * {@code PA-ACCOUNT-STATUS} occurrences are {@code PA-ACCOUNT-STATUS-1} to {@code -5}.
     */
    public static final RecordLayout PENDING_AUTH_SUMMARY = RecordLayout.builder("PENDING-AUTH-SUMMARY", 100)
            .packed("PA-ACCT-ID", "S9(11)")
            .digits("PA-CUST-ID", 9)
            .text("PA-AUTH-STATUS", 1)
            .text("PA-ACCOUNT-STATUS-1", 2)
            .text("PA-ACCOUNT-STATUS-2", 2)
            .text("PA-ACCOUNT-STATUS-3", 2)
            .text("PA-ACCOUNT-STATUS-4", 2)
            .text("PA-ACCOUNT-STATUS-5", 2)
            .packed("PA-CREDIT-LIMIT", "S9(09)V99")
            .packed("PA-CASH-LIMIT", "S9(09)V99")
            .packed("PA-CREDIT-BALANCE", "S9(09)V99")
            .packed("PA-CASH-BALANCE", "S9(09)V99")
            .binary("PA-APPROVED-AUTH-CNT", "S9(04)")
            .binary("PA-DECLINED-AUTH-CNT", "S9(04)")
            .packed("PA-APPROVED-AUTH-AMT", "S9(09)V99")
            .packed("PA-DECLINED-AUTH-AMT", "S9(09)V99")
            .filler(34)
            .build();

    /**
     * ENT-AUTH-DETAIL, {@code CIPAUDTY}, IMS child segment PAUTDTL1 of DBPAUTP0. The key
     * holds the 9-complements of the date ({@code YYDDD}) and time ({@code HHMMSSmmm}).
     */
    public static final RecordLayout PENDING_AUTH_DETAIL = RecordLayout.builder("PENDING-AUTH-DETAILS", 200)
            .packed("PA-AUTH-DATE-9C", "S9(05)")
            .packed("PA-AUTH-TIME-9C", "S9(09)")
            .text("PA-AUTH-ORIG-DATE", 6)
            .text("PA-AUTH-ORIG-TIME", 6)
            .text("PA-CARD-NUM", 16)
            .text("PA-AUTH-TYPE", 4)
            .text("PA-CARD-EXPIRY-DATE", 4)
            .text("PA-MESSAGE-TYPE", 6)
            .text("PA-MESSAGE-SOURCE", 6)
            .text("PA-AUTH-ID-CODE", 6)
            .text("PA-AUTH-RESP-CODE", 2)
            .text("PA-AUTH-RESP-REASON", 4)
            .digits("PA-PROCESSING-CODE", 6)
            .packed("PA-TRANSACTION-AMT", "S9(10)V99")
            .packed("PA-APPROVED-AMT", "S9(10)V99")
            .text("PA-MERCHANT-CATAGORY-CODE", 4)
            .text("PA-ACQR-COUNTRY-CODE", 3)
            .digits("PA-POS-ENTRY-MODE", 2)
            .text("PA-MERCHANT-ID", 15)
            .text("PA-MERCHANT-NAME", 22)
            .text("PA-MERCHANT-CITY", 13)
            .text("PA-MERCHANT-STATE", 2)
            .text("PA-MERCHANT-ZIP", 9)
            .text("PA-TRANSACTION-ID", 15)
            .text("PA-MATCH-STATUS", 1)
            .text("PA-AUTH-FRAUD", 1)
            .text("PA-FRAUD-RPT-DATE", 8)
            .filler(17)
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
