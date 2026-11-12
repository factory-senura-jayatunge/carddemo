package org.carddemo.transaction;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import org.carddemo.platform.validation.LegacyDateCheck;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.transaction.api.ReportRequest;
import org.carddemo.transaction.api.ReportType;
import org.springframework.http.HttpStatus;

/**
 * The checks of a report request (CORPT00C), in edit order; each method throws the first failure.
 * Nothing is read or written before all of them pass.
 */
final class ReportRequestRules {

    static final String TYPE_FIELD = "monthly";
    static final String CONFIRM_FIELD = "confirm";

    /** The resolved, inclusive range. */
    record Range(LocalDate start, LocalDate end) {
    }

    private record Part(String name, Message required, Message invalid, int maxDigits, int minDigits, int max) {
    }

    private static final List<Part> PARTS = List.of(
            new Part("startMonth", MessageCatalog.START_MONTH_REQUIRED, MessageCatalog.START_MONTH_INVALID, 2, 1, 12),
            new Part("startDay", MessageCatalog.START_DAY_REQUIRED, MessageCatalog.START_DAY_INVALID, 2, 1, 31),
            new Part("startYear", MessageCatalog.START_YEAR_REQUIRED, MessageCatalog.START_YEAR_INVALID, 4, 4, 9999),
            new Part("endMonth", MessageCatalog.END_MONTH_REQUIRED, MessageCatalog.END_MONTH_INVALID, 2, 1, 12),
            new Part("endDay", MessageCatalog.END_DAY_REQUIRED, MessageCatalog.END_DAY_INVALID, 2, 1, 31),
            new Part("endYear", MessageCatalog.END_YEAR_REQUIRED, MessageCatalog.END_YEAR_INVALID, 4, 4, 9999));

    private ReportRequestRules() {
    }

    /**
     * Steps 2 and 3: the type, then its range. {@code today} is the clock's date at the request,
     * not the cycle's business date (NUM-RPTS-004).
     */
    static Range range(ReportRequest request, LocalDate today) {
        ReportType type = request == null ? null : request.type();
        if (type == null) {
            throw bad(MessageCatalog.REPORT_TYPE_REQUIRED, TYPE_FIELD);
        }
        return switch (type) {
            case MONTHLY -> new Range(today.withDayOfMonth(1), today.with(TemporalAdjusters.lastDayOfMonth()));
            case YEARLY -> new Range(today.withDayOfYear(1), today.with(TemporalAdjusters.lastDayOfYear()));
            case CUSTOM -> custom(request);
        };
    }

    /**
     * BR-RPTS-004: every part required, then each part's form, then each date through the CSUTLDTC
     * model, then the order. A part is trimmed of spaces and its digits padded on the left
     * (NUM-RPTS-001); any other character makes it invalid instead of zero (DEF-RPTS-003), and a
     * year must have four digits (DEF-RPTS-002).
     */
    static Range custom(ReportRequest r) {
        String[] values = {r.startMonth(), r.startDay(), r.startYear(), r.endMonth(), r.endDay(), r.endYear()};
        for (int i = 0; i < PARTS.size(); i++) {
            if (trim(values[i]).isEmpty()) {
                throw bad(PARTS.get(i).required(), PARTS.get(i).name());
            }
        }
        String[] padded = new String[values.length];
        for (int i = 0; i < PARTS.size(); i++) {
            Part part = PARTS.get(i);
            String digits = trim(values[i]);
            if (digits.length() < part.minDigits() || digits.length() > part.maxDigits()
                    || !digits.chars().allMatch(ch -> ch >= '0' && ch <= '9')
                    || Integer.parseInt(digits) > part.max()) {
                throw bad(part.invalid(), part.name());
            }
            padded[i] = "0".repeat(part.maxDigits() - digits.length()) + digits;
        }
        LocalDate start = date(padded[2] + "-" + padded[0] + "-" + padded[1], MessageCatalog.START_DATE_INVALID,
                "startMonth");
        LocalDate end = date(padded[5] + "-" + padded[3] + "-" + padded[4], MessageCatalog.END_DATE_INVALID,
                "endMonth");
        if (start.isAfter(end)) {
            throw bad(MessageCatalog.DATE_RANGE_INVALID, "startMonth");
        }
        return new Range(start, end);
    }

    /**
     * Step 4 (BR-RPTS-005, VAL-RPTS-005): blank asks for the confirmation with the range; {@code Y}
     * or {@code y} continues; anything else is refused, echoing its first character only, as the
     * legacy field holds one.
     */
    static void confirm(String confirm, ReportType type, Range range) {
        if (confirm == null || trim(confirm).isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, MessageCatalog.CONFIRM_REQUIRED, CONFIRM_FIELD,
                    type.label())
                    .with("startDate", range.start().toString())
                    .with("endDate", range.end().toString());
        }
        if (!"Y".equals(confirm) && !"y".equals(confirm)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.CONFIRM_INVALID, CONFIRM_FIELD,
                    confirm.substring(0, Character.charCount(confirm.codePointAt(0))));
        }
    }

    /** Any CSUTLDTC result but valid is refused, 2513 (before 1582-10-15) too (DEF-DTCK-004 fixed-only). */
    private static LocalDate date(String date, Message invalid, String field) {
        if (!LegacyDateCheck.check(date, LegacyDateCheck.DASHED).valid()) {
            throw bad(invalid, field);
        }
        return LocalDate.parse(date);
    }

    private static String trim(String value) {
        if (value == null) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == ' ') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == ' ') {
            end--;
        }
        return value.substring(start, end);
    }

    private static ApiException bad(Message message, String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, field);
    }
}
