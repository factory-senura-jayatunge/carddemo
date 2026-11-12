package org.carddemo.authorization.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestTextTest {

    static AuthorizationRequest with(RequestField field, String value) throws Exception {
        String[] values = RequestParserTest.CSV.split(",", -1);
        values[field.ordinal()] = value;
        return RequestParser.parse(String.join(",", values).getBytes(StandardCharsets.UTF_8), "text/csv");
    }

    @Test
    @DisplayName("BR-AUTH-001: processing code and POS entry mode right-aligned, zero-padded and cut on the left")
    @SpecItem("BR-AUTH-001")
    void digitFields() throws Exception {
        assertThat(with(RequestField.PROCESSING_CODE, "1").stored(RequestField.PROCESSING_CODE)).isEqualTo("000001");
        assertThat(with(RequestField.PROCESSING_CODE, "1234567").stored(RequestField.PROCESSING_CODE)).isEqualTo("234567");
        assertThat(with(RequestField.POS_ENTRY_MODE, "1").stored(RequestField.POS_ENTRY_MODE)).isEqualTo("01");
        assertThat(with(RequestField.POS_ENTRY_MODE, "123").stored(RequestField.POS_ENTRY_MODE)).isEqualTo("23");
        assertThat(with(RequestField.POS_ENTRY_MODE, "").stored(RequestField.POS_ENTRY_MODE)).isEqualTo("00");
        assertThat(with(RequestField.POS_ENTRY_MODE, "123").raw(RequestField.POS_ENTRY_MODE)).isEqualTo("123");
    }

    @Test
    @DisplayName("BR-AUTH-001: other text cut on the right and padded with spaces; nothing trimmed on the left")
    @SpecItem("BR-AUTH-001")
    void textFields() throws Exception {
        assertThat(with(RequestField.MERCHANT_STATE, "  ").stored(RequestField.MERCHANT_STATE)).isEqualTo("  ");
        assertThat(with(RequestField.MERCHANT_STATE, " ILX").stored(RequestField.MERCHANT_STATE)).isEqualTo(" I");
        assertThat(with(RequestField.TRANSACTION_ID, "TRN0000000000012345").stored(RequestField.TRANSACTION_ID))
                .isEqualTo("TRN000000000001");
    }

    @Test
    @DisplayName("Character set: 22 accented characters are stored unchanged")
    @SpecItem("BR-AUTH-001")
    void latin1AtFullWidth() throws Exception {
        AuthorizationRequest r = with(RequestField.MERCHANT_NAME, "É".repeat(22));
        assertThat(r.stored(RequestField.MERCHANT_NAME)).isEqualTo("É".repeat(22));
        assertThat(r.replacedFields()).isEmpty();
    }

    @Test
    @DisplayName("Character set: a code point above U+00FF becomes one ?, an emoji too, before the width")
    @SpecItem("BR-AUTH-001")
    void aboveLatin1() throws Exception {
        AuthorizationRequest city = with(RequestField.MERCHANT_CITY, "中SPRINGFIELD");
        assertThat(city.stored(RequestField.MERCHANT_CITY)).isEqualTo("?SPRINGFIELD ");
        assertThat(city.replacedFields()).containsExactly(RequestField.MERCHANT_CITY);
        AuthorizationRequest emoji = with(RequestField.MERCHANT_NAME, "A😀B");
        assertThat(emoji.stored(RequestField.MERCHANT_NAME)).isEqualTo("A?B" + " ".repeat(19));
        AuthorizationRequest wide = with(RequestField.MERCHANT_STATE, "😀😀😀");
        assertThat(wide.stored(RequestField.MERCHANT_STATE)).isEqualTo("??");
        assertThat(RequestText.toLatin1Range("a\uD800b")).isEqualTo("a?b");
    }

    @Test
    @DisplayName("Character set: NUL, C1 controls and NBSP are kept")
    @SpecItem("BR-AUTH-001")
    void controlsKept() throws Exception {
        String id = "TRN\u0085\u0000\u00A0000000001";
        AuthorizationRequest r = with(RequestField.TRANSACTION_ID, id);
        assertThat(r.stored(RequestField.TRANSACTION_ID)).isEqualTo(id);
        assertThat(r.replacedFields()).isEmpty();
    }

    @Test
    @DisplayName("BR-AUTH-003: the card check runs on the raw value; trailing spaces are allowed")
    @SpecItem("BR-AUTH-003")
    void cardCheck() throws Exception {
        assertThat(with(RequestField.CARD_NUM, "4111111111111111  ").cardNumberValid()).isTrue();
        AuthorizationRequest longer = with(RequestField.CARD_NUM, "41111111111111112");
        assertThat(longer.cardNumberValid()).isFalse();
        assertThat(longer.stored(RequestField.CARD_NUM)).isEqualTo("4111111111111111");
        assertThat(with(RequestField.CARD_NUM, "411111111111111").cardNumberValid()).isFalse();
        assertThat(with(RequestField.CARD_NUM, " 411111111111111").cardNumberValid()).isFalse();
        assertThat(with(RequestField.CARD_NUM, "411111111111111A").cardNumberValid()).isFalse();
        assertThat(with(RequestField.CARD_NUM, "４111111111111111").cardNumberValid()).isFalse();
    }

    @Test
    @DisplayName("D-10: a request's text form masks the card and carries no other value")
    @SpecItem("DEF-AUTH-011")
    void toStringMasks() throws Exception {
        String s = with(RequestField.MERCHANT_NAME, "SECRET").toString();
        assertThat(s).doesNotContain("4111111111111111").doesNotContain("SECRET").contains("************1111");
    }
}
