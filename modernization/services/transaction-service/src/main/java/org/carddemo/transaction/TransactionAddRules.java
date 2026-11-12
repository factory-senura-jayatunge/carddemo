package org.carddemo.transaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.carddemo.platform.legacyformat.LegacyNumbers;
import org.carddemo.platform.validation.LegacyDateCheck;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;
import org.springframework.http.HttpStatus;

/**
 * The field rules of transaction add that need no read (COTRN02C, contract rows 1, 2, 4, 6 to 17
 * and 20 to 24), in edit order; each method throws the first failure. "Empty" is spaces only, as
 * in the legacy check. "Pad" trims spaces, accepts 1 to n digits and left-pads them with zeros
 * (D-52, DEF-TRNA-006). The required checks of all ten data fields run before any character or
 * length check, so an empty field is reported before a too-long one, as in legacy.
 */
final class TransactionAddRules {

    static final String ACCT_ID = "acctId";
    static final String CARD_NUM = "cardNum";
    static final String CONFIRM = "confirm";
    static final int ACCT_ID_LENGTH = 11;
    static final int CARD_NUM_LENGTH = 16;

    /** A data field: its form member, the label of its texts, and its screen length (NUM-TRNA-005). */
    record Field(String name, String label, int length) {
    }

    static final List<Field> DATA = List.of(
            new Field("typeCd", "Type CD", 2),
            new Field("catCd", "Category CD", 4),
            new Field("source", "Source", 10),
            new Field("description", "Description", 60),
            new Field("amount", "Amount", 12),
            new Field("origDate", "Orig Date", 10),
            new Field("merchantId", "Merchant ID", 9),
            new Field("merchantName", "Merchant Name", 30),
            new Field("merchantCity", "Merchant City", 25),
            new Field("merchantZip", "Merchant Zip", 10));

    /** The free-text fields, which take U+0000 to U+00FF only (a legacy X(n) byte), in edit order. */
    static final List<String> FREE_TEXT = List.of("source", "description", "merchantName", "merchantCity",
            "merchantZip");

    private static final Pattern AMOUNT = Pattern.compile("[+-][0-9]{8}\\.[0-9]{2}");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    /** The typed keys after rows 1, 2 and 4; null for a key that was left empty. */
    record Keys(String acctId, String cardNum) {
    }

    /** The two codes after rows 16 and 17, padded. */
    record Codes(String typeCd, String catCd) {
    }

    private TransactionAddRules() {
    }

    /**
     * Rows 1, 2 and 4. The card's format is checked even when the account is typed, because the
     * account-and-card pair check needs a valid card number.
     */
    static Keys keys(String acctId, String cardNum) {
        boolean noAccount = empty(acctId);
        boolean noCard = empty(cardNum);
        if (noAccount && noCard) {
            throw bad(MessageCatalog.KEY_REQUIRED, ACCT_ID);
        }
        String account = noAccount ? null
                : pad(acctId, ACCT_ID_LENGTH).orElseThrow(() -> bad(MessageCatalog.TRNA_INVALID_ACCT_ID, ACCT_ID));
        String card = noCard ? null
                : pad(cardNum, CARD_NUM_LENGTH).orElseThrow(() -> bad(MessageCatalog.TRNA_INVALID_CARD_NUM, CARD_NUM));
        return new Keys(account, card);
    }

    /** Rows 6 to 15 on the form's values by member name; a missing member is empty. */
    static void dataFields(Map<String, String> values) {
        for (Field f : DATA) {
            if (empty(values.get(f.name()))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.FIELD_REQUIRED, f.name(), f.label());
            }
        }
        for (String name : FREE_TEXT) {
            if (values.get(name).chars().anyMatch(ch -> ch > 0xFF)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.TRNA_UNSUPPORTED_CHARACTER, name,
                        field(name).label());
            }
        }
        for (Field f : DATA) {
            if (TransactionFormats.trimTrailing(values.get(f.name())).length() > f.length()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, MessageCatalog.TRNA_TOO_LONG, f.name(), f.label(),
                        Integer.toString(f.length()));
            }
        }
    }

    /** Rows 16 and 17. */
    static Codes codes(String typeCd, String catCd) {
        String type = pad(typeCd, 2).orElseThrow(() -> bad(MessageCatalog.TYPE_NOT_NUMERIC, "typeCd"));
        String category = pad(catCd, 4).orElseThrow(() -> bad(MessageCatalog.CATEGORY_NOT_NUMERIC, "catCd"));
        return new Codes(type, category);
    }

    /** Rows 20 and 21: the forms of the amount and the original date. */
    static void forms(String amount, String origDate) {
        if (!AMOUNT.matcher(TransactionFormats.trimTrailing(amount)).matches()) {
            throw bad(MessageCatalog.INVALID_AMOUNT, "amount");
        }
        if (!DATE.matcher(TransactionFormats.trimTrailing(origDate)).matches()) {
            throw bad(MessageCatalog.INVALID_ORIG_DATE_FORMAT, "origDate");
        }
    }

    /** Row 22, after {@link #forms}: the amount's value; no message. */
    static BigDecimal amount(String amount) {
        return new BigDecimal(TransactionFormats.trimTrailing(amount));
    }

    /** NUM-TRNA-001: the screen's {@code +99999999.99} re-edit. */
    static String amountDisplay(BigDecimal amount) {
        return LegacyNumbers.editSigned(amount, TransactionFormats.AMOUNT_DIGITS, 2);
    }

    /**
     * Row 23, after {@link #forms}: any CSUTLDTC result but valid is refused, message 2513 (before
     * 1582-10-15, year 0000 included) too (DEF-TRNA-007, DEF-DTCK-004 fixed-only). Returns the date.
     */
    static String origDate(String origDate) {
        String date = TransactionFormats.trimTrailing(origDate);
        if (!LegacyDateCheck.check(date, LegacyDateCheck.DASHED).valid()) {
            throw bad(MessageCatalog.INVALID_ORIG_DATE, "origDate");
        }
        return date;
    }

    /** Row 24. */
    static String merchantId(String merchantId) {
        return pad(merchantId, 9).orElseThrow(() -> bad(MessageCatalog.MERCHANT_ID_NOT_NUMERIC, "merchantId"));
    }

    /** Add step 3: the page sends only {@code Y}; anything but {@code Y} or {@code y} is refused. */
    static void confirm(String confirm) {
        if (!"Y".equals(confirm) && !"y".equals(confirm)) {
            throw bad(MessageCatalog.INVALID_CONFIRM, CONFIRM);
        }
    }

    /** Null or spaces only. */
    static boolean empty(String value) {
        return value == null || value.chars().allMatch(ch -> ch == ' ');
    }

    /** The digits of {@code value} between spaces, left-padded to {@code length}; empty when not 1 to n digits. */
    static Optional<String> pad(String value, int length) {
        String digits = trimSpaces(value);
        if (digits.isEmpty() || digits.length() > length || !digits.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            return Optional.empty();
        }
        return Optional.of("0".repeat(length - digits.length()) + digits);
    }

    private static String trimSpaces(String value) {
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

    private static Field field(String name) {
        return DATA.stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }

    private static ApiException bad(Message message, String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, field);
    }
}
