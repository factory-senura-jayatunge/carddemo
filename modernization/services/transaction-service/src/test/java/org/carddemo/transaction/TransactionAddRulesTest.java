package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.carddemo.platform.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;

/** The read-free checks of transaction add, in their edit order (COTRN02C rows 1, 2, 4, 6 to 17, 20 to 24). */
class TransactionAddRulesTest {

    /** The contract's valid data. */
    static Map<String, String> valid() {
        Map<String, String> values = new HashMap<>();
        values.put("typeCd", "01");
        values.put("catCd", "0001");
        values.put("source", "POS TERM");
        values.put("description", "Test purchase");
        values.put("amount", "-00000012.50");
        values.put("origDate", "2022-07-06");
        values.put("merchantId", "123456789");
        values.put("merchantName", "Shop");
        values.put("merchantCity", "Town");
        values.put("merchantZip", "12345");
        return values;
    }

    static void refused(Executable check, String code, String message, String field) {
        assertThatThrownBy(check::execute).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo(code);
            assertThat(e.text()).isEqualTo(message);
            assertThat(e.field()).isEqualTo(field);
        });
    }

    @Test
    void keysAreRequiredAndPadded() {
        refused(() -> TransactionAddRules.keys(null, "   "), "KEY_REQUIRED",
                "Account or Card Number must be entered...", "acctId");
        assertThat(TransactionAddRules.keys("50", null))
                .isEqualTo(new TransactionAddRules.Keys("00000000050", null));
        assertThat(TransactionAddRules.keys(" 50 ", "  ").acctId()).isEqualTo("00000000050");
        assertThat(TransactionAddRules.keys(null, "1234").cardNum()).isEqualTo("0000000000001234");
        refused(() -> TransactionAddRules.keys("5O", null), "INVALID_ACCT_ID", "Account ID must be Numeric...",
                "acctId");
        refused(() -> TransactionAddRules.keys("123456789012", null), "INVALID_ACCT_ID",
                "Account ID must be Numeric...", "acctId");
        refused(() -> TransactionAddRules.keys("5 0", null), "INVALID_ACCT_ID", "Account ID must be Numeric...",
                "acctId");
    }

    /** S41: the card's format (row 4) is checked before the account's cards are read (row 3). */
    @Test
    void theCardFormatIsCheckedWhenTheAccountIsTyped() {
        refused(() -> TransactionAddRules.keys("00000000050", "12A"), "INVALID_CARD_NUM",
                "Card Number must be Numeric...", "cardNum");
    }

    /** S4, S40, S41: every required check of the ten fields runs before any character or length check. */
    @Test
    void everyRequiredCheckComesFirst() {
        refused(() -> TransactionAddRules.dataFields(Map.of()), "FIELD_REQUIRED", "Type CD can NOT be empty...",
                "typeCd");
        Map<String, String> values = valid();
        values.put("description", "D".repeat(61));
        values.put("merchantZip", "   ");
        refused(() -> TransactionAddRules.dataFields(values), "FIELD_REQUIRED", "Merchant Zip can NOT be empty...",
                "merchantZip");
        Map<String, String> typeEmpty = valid();
        typeEmpty.put("typeCd", "");
        typeEmpty.put("description", "\u4e2d");
        refused(() -> TransactionAddRules.dataFields(typeEmpty), "FIELD_REQUIRED", "Type CD can NOT be empty...",
                "typeCd");
    }

    /** S40: a character above U+00FF in each free-text field, and before the length check. */
    @Test
    void freeTextTakesLatin1Only() {
        Map<String, String> labels = Map.of("source", "Source", "description", "Description", "merchantName",
                "Merchant Name", "merchantCity", "Merchant City", "merchantZip", "Merchant Zip");
        for (String name : TransactionAddRules.FREE_TEXT) {
            Map<String, String> values = valid();
            values.put(name, "\u4e2d");
            refused(() -> TransactionAddRules.dataFields(values), "UNSUPPORTED_CHARACTER",
                    labels.get(name) + " contains a character that is not supported.", name);
        }
        Map<String, String> longText = valid();
        longText.put("description", "\u4e2d" + "x".repeat(60));
        refused(() -> TransactionAddRules.dataFields(longText), "UNSUPPORTED_CHARACTER",
                "Description contains a character that is not supported.", "description");
    }

    /** S39: U+00C9, U+00A0 and U+0085 are Latin-1 and pass. */
    @Test
    void latin1TextPasses() {
        Map<String, String> values = valid();
        values.put("description", "\u00c9".repeat(60));
        values.put("merchantName", "A\u00a0B\u0085C");
        TransactionAddRules.dataFields(values);
    }

    /** S35, NUM-TRNA-005: the screen lengths, trailing spaces not counted. */
    @Test
    void fieldsHaveTheirScreenLengths() {
        Map<String, String> values = valid();
        values.put("description", "D".repeat(61));
        refused(() -> TransactionAddRules.dataFields(values), "TOO_LONG",
                "Description must not be longer than 60 characters.", "description");
        values.put("description", "D".repeat(60) + "   ");
        TransactionAddRules.dataFields(values);
        values.put("merchantName", "N".repeat(31));
        refused(() -> TransactionAddRules.dataFields(values), "TOO_LONG",
                "Merchant Name must not be longer than 30 characters.", "merchantName");
        values.put("merchantName", "Shop");
        values.put("merchantCity", "C".repeat(26));
        refused(() -> TransactionAddRules.dataFields(values), "TOO_LONG",
                "Merchant City must not be longer than 25 characters.", "merchantCity");
    }

    /** S9, S16: codes and merchant ID padded, a letter refused. */
    @Test
    void codesAndMerchantIdArePadded() {
        assertThat(TransactionAddRules.codes("1", "5")).isEqualTo(new TransactionAddRules.Codes("01", "0005"));
        refused(() -> TransactionAddRules.codes("A1", "0001"), "TYPE_NOT_NUMERIC", "Type CD must be Numeric...",
                "typeCd");
        refused(() -> TransactionAddRules.codes("01", "00A1"), "CATEGORY_NOT_NUMERIC",
                "Category CD must be Numeric...", "catCd");
        assertThat(TransactionAddRules.merchantId("123")).isEqualTo("000000123");
        refused(() -> TransactionAddRules.merchantId("12A"), "MERCHANT_ID_NOT_NUMERIC",
                "Merchant ID must be Numeric...", "merchantId");
    }

    /** S11, S13: the forms of the amount and of the original date. */
    @Test
    void amountAndDateForms() {
        TransactionAddRules.forms("-00000012.50", "2022-07-06");
        TransactionAddRules.forms("+99999999.99", "2022-07-06");
        refused(() -> TransactionAddRules.forms("12.50", "2022-07-06"), "INVALID_AMOUNT",
                "Amount should be in format -99999999.99", "amount");
        refused(() -> TransactionAddRules.forms("-0000012.50", "2022-07-06"), "INVALID_AMOUNT",
                "Amount should be in format -99999999.99", "amount");
        refused(() -> TransactionAddRules.forms("-00000012.50", "2022/07/06"), "INVALID_ORIG_DATE_FORMAT",
                "Orig Date should be in format YYYY-MM-DD", "origDate");
    }

    /** S12, S14, S15: CEEDAYS's result 2513 (before 1582-10-15) is refused too; no limit after today. */
    @Test
    void originalDateIsACalendarDate() {
        for (String date : new String[] {"2022-02-30", "1500-01-01", "0000-01-01", "2022-13-01"}) {
            refused(() -> TransactionAddRules.origDate(date), "INVALID_ORIG_DATE", "Orig Date - Not a valid date...",
                    "origDate");
        }
        assertThat(TransactionAddRules.origDate("2026-11-08")).isEqualTo("2026-11-08");
        assertThat(TransactionAddRules.origDate("2024-02-29")).isEqualTo("2024-02-29");
    }

    /** S17, S18, NUM-TRNA-001: the screen's re-edit; a negative zero shows positive. */
    @Test
    void amountIsReEdited() {
        assertThat(TransactionAddRules.amountDisplay(TransactionAddRules.amount("-00000012.50")))
                .isEqualTo("-00000012.50");
        assertThat(TransactionAddRules.amountDisplay(TransactionAddRules.amount("-00000000.00")))
                .isEqualTo("+00000000.00");
        assertThat(TransactionAddRules.amountDisplay(new BigDecimal("99999999.99"))).isEqualTo("+99999999.99");
    }

    /** S19: only Y or y adds. */
    @Test
    void onlyYConfirms() {
        TransactionAddRules.confirm("Y");
        TransactionAddRules.confirm("y");
        for (String value : new String[] {"X", "", " ", null, "N", "YES"}) {
            refused(() -> TransactionAddRules.confirm(value), "INVALID_CONFIRM",
                    "Invalid value. Valid values are (Y/N)...", "confirm");
        }
    }

    /** NUM-TRNA-003: IDs stay strings; leading zeros are kept, never parsed into a number. */
    @Test
    void padKeepsDigitsAsText() {
        assertThat(TransactionAddRules.pad("0000000000000001", 16)).contains("0000000000000001");
        assertThat(TransactionAddRules.pad("9999999999999999", 16)).contains("9999999999999999");
        assertThat(TransactionAddRules.pad("", 2)).isEmpty();
        assertThat(TransactionAddRules.pad("-1", 2)).isEmpty();
        assertThat(TransactionAddRules.pad("\u0661", 2)).as("a non-ASCII digit").isEmpty();
    }
}
