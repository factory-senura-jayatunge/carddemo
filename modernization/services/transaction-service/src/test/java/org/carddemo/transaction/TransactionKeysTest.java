package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.MessageCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TransactionKeysTest {

    @Test
    void aCursorIsExactlySixteenDigits() {
        assertThat(TransactionKeys.isCursorKey("0000000000000100")).isTrue();
        assertThat(TransactionKeys.isCursorKey("9999999999999999")).isTrue();
        assertThat(TransactionKeys.isCursorKey("100")).isFalse();
        assertThat(TransactionKeys.isCursorKey("000000000000010 ")).isFalse();
        assertThat(TransactionKeys.isCursorKey("00000000000001000")).isFalse();
        assertThat(TransactionKeys.isCursorKey("000000000000010A")).isFalse();
        assertThat(TransactionKeys.isCursorKey("\u0660\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668\u0669\u0660\u0661\u0662\u0663\u0664\u0665"))
                .isFalse();
    }

    @Test
    void theSearchKeyIsPaddedOnTheLeft() {
        assertThat(TransactionKeys.searchStart("1300")).isEqualTo("0000000000001300");
        assertThat(TransactionKeys.searchStart("1300   ")).isEqualTo("0000000000001300");
        assertThat(TransactionKeys.searchStart("0000000000001300")).isEqualTo("0000000000001300");
        assertThat(TransactionKeys.searchStart(null)).isNull();
        assertThat(TransactionKeys.searchStart("")).isNull();
        assertThat(TransactionKeys.searchStart("    ")).isNull();
    }

    @Test
    void anythingButOneToSixteenDigitsIsAnInvalidSearch() {
        for (String typed : new String[] {" 1300", "13 00", "13A0", "-1", "+1", "1.0", "12345678901234567", "1300\u00A0", "\u0661"}) {
            ApiException e = catchThrowableOfType(ApiException.class, () -> TransactionKeys.searchStart(typed));
            assertThat(e).as("[%s]", typed).isNotNull();
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.catalogMessage()).isEqualTo(MessageCatalog.INVALID_SEARCH_TRAN_ID);
            assertThat(e.field()).isEqualTo("searchTranId");
        }
    }

    @Test
    void theSearchIsGivenUnlessOnlySpaces() {
        assertThat(TransactionKeys.isSearchGiven(null)).isFalse();
        assertThat(TransactionKeys.isSearchGiven("   ")).isFalse();
        assertThat(TransactionKeys.isSearchGiven(" 1")).isTrue();
        assertThat(TransactionKeys.isSearchGiven("x")).isTrue();
    }

    @Test
    void aBlankViewKeyIsRequired() {
        for (String typed : new String[] {null, "", "                "}) {
            ApiException e = catchThrowableOfType(ApiException.class, () -> TransactionKeys.viewKey(typed));
            assertThat(e).as("[%s]", typed).isNotNull();
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.catalogMessage()).isEqualTo(MessageCatalog.TRAN_ID_REQUIRED);
            assertThat(e.field()).isEqualTo("tranId");
        }
    }

    @Test
    void theViewKeyPadsDigitsAndKeepsOtherText() {
        assertThat(TransactionKeys.viewKey("100")).contains("0000000000000100");
        assertThat(TransactionKeys.viewKey("100  ")).contains("0000000000000100");
        assertThat(TransactionKeys.viewKey("0000000000000100")).contains("0000000000000100");
        assertThat(TransactionKeys.viewKey(" 100")).contains(" 100");
        assertThat(TransactionKeys.viewKey("ABC")).contains("ABC");
    }

    @Test
    void aViewKeyNoRowCanHaveIsNotRead() {
        assertThat(TransactionKeys.viewKey("00000000000001000")).isEmpty();
        assertThat(TransactionKeys.viewKey("\u00C90000000000000100")).isEmpty();
        assertThat(TransactionKeys.viewKey("\u00C9")).isEmpty();
        assertThat(TransactionKeys.viewKey("1\t")).isEmpty();
        assertThat(TransactionKeys.viewKey("1\u00A0")).isEmpty();
    }
}
