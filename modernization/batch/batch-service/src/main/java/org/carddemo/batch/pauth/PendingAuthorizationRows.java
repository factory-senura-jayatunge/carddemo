package org.carddemo.batch.pauth;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.carddemo.platform.legacyformat.LegacyFormatException;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.RecordLayout;
import org.springframework.jdbc.core.SqlParameterValue;

/**
 * One summary or detail segment of the HD unload decoded into the columns of
 * {@code pauth.auth_summary} or {@code pauth.auth_detail} (data-model.md, loader mapping).
 * Errors name the field, never its content.
 */
final class PendingAuthorizationRows {

    static final String ROOT = "PAUTSUM0";
    static final String CHILD = "PAUTDTL1";
    static final Map<String, RecordLayout> SEGMENTS = Map.of(
            ROOT, Layouts.PENDING_AUTH_SUMMARY, CHILD, Layouts.PENDING_AUTH_DETAIL);

    private static final Set<String> RESPONSES = Set.of("00", "05");
    // M needs the matched transaction columns (V503), which an unload cannot carry.
    private static final Set<String> MATCH_STATUSES = Set.of("P", "D", "E");
    private static final Set<String> FRAUD_FLAGS = Set.of(" ", "F", "R");
    private static final Pattern FRAUD_DATE = Pattern.compile("(\\d{2})/(\\d{2})/(\\d{2})");

    private PendingAuthorizationRows() {
    }

    record Summary(String acctId, String custId, BigDecimal creditLimit, BigDecimal cashLimit,
            BigDecimal creditBalance, BigDecimal cashBalance, int approvedCount, int declinedCount,
            BigDecimal approvedAmount, BigDecimal declinedAmount) {
    }

    record Detail(LocalDateTime processedAt, int date9c, int time9c, String origDate, String origTime,
            String cardNum, String authType, String cardExpiryDate, String messageType, String messageSource,
            String authIdCode, String respCode, String respReason, String processingCode,
            BigDecimal transactionAmt, BigDecimal approvedAmt, String merchantCategoryCode,
            String acqrCountryCode, String posEntryMode, String merchantId, String merchantName,
            String merchantCity, String merchantState, String merchantZip, String transactionId,
            String matchStatus, String fraud, LocalDate fraudReportDate) {
    }

    /** The columns of {@code pauth.auth_summary} the load writes; the others are generated. */
    static Map<String, Object> summaryColumns(Summary s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("acct_id", s.acctId());
        row.put("cust_id", s.custId());
        row.put("credit_limit", s.creditLimit());
        row.put("cash_limit", s.cashLimit());
        row.put("credit_balance", s.creditBalance());
        row.put("cash_balance", s.cashBalance());
        row.put("approved_auth_cnt", s.approvedCount());
        row.put("declined_auth_cnt", s.declinedCount());
        row.put("approved_auth_amt", s.approvedAmount());
        row.put("declined_auth_amt", s.declinedAmount());
        return row;
    }

    /**
     * The columns of {@code pauth.auth_detail} the load writes. The others are generated or stay
     * NULL: {@code fraud_changed_by} and the V503 match columns, which an unload cannot carry.
     */
    static Map<String, Object> detailColumns(String acctId, Detail d) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("acct_id", acctId);
        row.put("processed_at", d.processedAt());
        row.put("auth_date_9c", d.date9c());
        row.put("auth_time_9c", d.time9c());
        row.put("orig_date", d.origDate());
        row.put("orig_time", d.origTime());
        row.put("card_num", d.cardNum());
        row.put("auth_type", d.authType());
        row.put("card_expiry_date", d.cardExpiryDate());
        row.put("message_type", d.messageType());
        row.put("message_source", d.messageSource());
        row.put("auth_id_code", d.authIdCode());
        row.put("auth_resp_code", d.respCode());
        row.put("auth_resp_reason", d.respReason());
        row.put("processing_code", d.processingCode());
        row.put("transaction_amt", d.transactionAmt());
        row.put("approved_amt", d.approvedAmt());
        row.put("merchant_category_code", d.merchantCategoryCode());
        row.put("acqr_country_code", d.acqrCountryCode());
        row.put("pos_entry_mode", d.posEntryMode());
        row.put("merchant_id", d.merchantId());
        row.put("merchant_name", d.merchantName());
        row.put("merchant_city", d.merchantCity());
        row.put("merchant_state", d.merchantState());
        row.put("merchant_zip", d.merchantZip());
        row.put("transaction_id", d.transactionId());
        row.put("match_status", d.matchStatus());
        row.put("auth_fraud", d.fraud());
        // Typed, because a NULL date gives the driver no type to infer.
        row.put("fraud_rpt_date", new SqlParameterValue(Types.DATE, d.fraudReportDate()));
        return row;
    }

    static Summary summary(FixedRecord r) {
        BigDecimal acct = r.decimal("PA-ACCT-ID");
        if (acct.signum() < 0) {
            throw invalid("PA-ACCT-ID", "is negative");
        }
        return new Summary(String.format("%011d", acct.longValueExact()), r.digits("PA-CUST-ID"),
                r.decimal("PA-CREDIT-LIMIT"), r.decimal("PA-CASH-LIMIT"), r.decimal("PA-CREDIT-BALANCE"),
                r.decimal("PA-CASH-BALANCE"), r.decimal("PA-APPROVED-AUTH-CNT").intValueExact(),
                r.decimal("PA-DECLINED-AUTH-CNT").intValueExact(), r.decimal("PA-APPROVED-AUTH-AMT"),
                r.decimal("PA-DECLINED-AUTH-AMT"));
    }

    static Detail detail(FixedRecord r) {
        int date9c = r.decimal("PA-AUTH-DATE-9C").intValueExact();
        int time9c = r.decimal("PA-AUTH-TIME-9C").intValueExact();
        return new Detail(processedAt(date9c, time9c), date9c, time9c,
                r.raw("PA-AUTH-ORIG-DATE"), r.raw("PA-AUTH-ORIG-TIME"), r.raw("PA-CARD-NUM"),
                r.raw("PA-AUTH-TYPE"), r.raw("PA-CARD-EXPIRY-DATE"), r.raw("PA-MESSAGE-TYPE"),
                r.raw("PA-MESSAGE-SOURCE"), r.raw("PA-AUTH-ID-CODE"),
                oneOf(r, "PA-AUTH-RESP-CODE", RESPONSES), r.raw("PA-AUTH-RESP-REASON"),
                r.digits("PA-PROCESSING-CODE"), r.decimal("PA-TRANSACTION-AMT"), r.decimal("PA-APPROVED-AMT"),
                r.raw("PA-MERCHANT-CATAGORY-CODE"), r.raw("PA-ACQR-COUNTRY-CODE"), r.digits("PA-POS-ENTRY-MODE"),
                r.raw("PA-MERCHANT-ID"), r.text("PA-MERCHANT-NAME"), r.text("PA-MERCHANT-CITY"),
                r.raw("PA-MERCHANT-STATE"), r.text("PA-MERCHANT-ZIP"), r.raw("PA-TRANSACTION-ID"),
                oneOf(r, "PA-MATCH-STATUS", MATCH_STATUSES), oneOf(r, "PA-AUTH-FRAUD", FRAUD_FLAGS),
                fraudReportDate(r.raw("PA-FRAUD-RPT-DATE")));
    }

    /**
     * The key holds 99999 - {@code YYDDD} and 999999999 - {@code HHMMSSmmm}; years are 20YY
     * (data-model.md, loader mapping).
     */
    static LocalDateTime processedAt(int date9c, int time9c) {
        int yyddd = 99_999 - date9c;
        if (date9c < 0 || yyddd < 0) {
            throw invalid("PA-AUTH-DATE-9C", "is not the complement of a YYDDD date");
        }
        LocalDate day;
        try {
            day = LocalDate.ofYearDay(2000 + yyddd / 1000, yyddd % 1000);
        } catch (DateTimeException e) {
            throw invalid("PA-AUTH-DATE-9C", "is not the complement of a valid YYDDD date");
        }
        int t = 999_999_999 - time9c;
        if (time9c < 0 || t < 0) {
            throw invalid("PA-AUTH-TIME-9C", "is not the complement of an HHMMSSmmm time");
        }
        LocalTime time;
        try {
            time = LocalTime.of(t / 10_000_000, t / 100_000 % 100, t / 1000 % 100, t % 1000 * 1_000_000);
        } catch (DateTimeException e) {
            throw invalid("PA-AUTH-TIME-9C", "is not the complement of a valid HHMMSSmmm time");
        }
        return day.atTime(time);
    }

    /** {@code MM/DD/YY} with year 20YY; blank is no date. */
    static LocalDate fraudReportDate(String raw) {
        if (raw.isBlank()) {
            return null;
        }
        Matcher m = FRAUD_DATE.matcher(raw);
        if (!m.matches()) {
            throw invalid("PA-FRAUD-RPT-DATE", "is neither blank nor MM/DD/YY");
        }
        try {
            return LocalDate.of(2000 + Integer.parseInt(m.group(3)), Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)));
        } catch (DateTimeException e) {
            throw invalid("PA-FRAUD-RPT-DATE", "is not a valid MM/DD/YY date");
        }
    }

    private static String oneOf(FixedRecord r, String field, Set<String> allowed) {
        String value = r.raw(field);
        if (!allowed.contains(value)) {
            throw invalid(field, "is not one of the values the table accepts");
        }
        return value;
    }

    private static LegacyFormatException invalid(String field, String what) {
        return new LegacyFormatException(field + " " + what);
    }
}
