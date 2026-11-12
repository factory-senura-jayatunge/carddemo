package org.carddemo.authorization.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.carddemo.authorization.request.UnparsableRequestException.Reason;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestParserTest {

    static final String CSV = "240405,143015,4111111111111111,SALE,0426,0100  ,POS   ,000000,50.00,5411,840,01,"
            + "MERCH0000000001,GROCERY STORE,SPRINGFIELD,IL,62701,TRN000000000001";

    static final String JSON = """
            {"authDate":"240405","authTime":"143015","cardNum":"4111111111111111","authType":"SALE",
             "cardExpiryDate":"0426","messageType":"0100","messageSource":"POS","processingCode":"0",
             "transactionAmt":"50.00","merchantCategoryCode":"5411","acqrCountryCode":"840","posEntryMode":"1",
             "merchantId":"MERCH0000000001","merchantName":"GROCERY STORE","merchantCity":"SPRINGFIELD",
             "merchantState":"IL","merchantZip":"62701","transactionId":"TRN000000000001"}""";

    static AuthorizationRequest csv(String body) throws UnparsableRequestException {
        return RequestParser.parse(body.getBytes(StandardCharsets.UTF_8), "text/csv");
    }

    static Reason unparsable(byte[] body, String contentType) {
        try {
            RequestParser.parse(body, contentType);
        } catch (UnparsableRequestException e) {
            return e.reason();
        }
        throw new AssertionError("parsed");
    }

    @Test
    @DisplayName("BR-AUTH-001: 18 values in the legacy order, widths kept, the amount parsed")
    @SpecItem("BR-AUTH-001")
    void csvInOrder() throws Exception {
        AuthorizationRequest r = csv(CSV);
        assertThat(r.form()).isEqualTo(BodyForm.CSV);
        assertThat(r.stored(RequestField.CARD_NUM)).isEqualTo("4111111111111111");
        assertThat(r.stored(RequestField.MESSAGE_TYPE)).isEqualTo("0100  ");
        assertThat(r.stored(RequestField.MERCHANT_NAME)).isEqualTo("GROCERY STORE         ");
        assertThat(r.stored(RequestField.MERCHANT_ZIP)).isEqualTo("62701    ");
        assertThat(r.stored(RequestField.TRANSACTION_ID)).isEqualTo("TRN000000000001");
        assertThat(r.amount().valid()).isTrue();
        assertThat(r.amount().value()).hasToString("50.00");
        assertThat(r.cardNumberValid()).isTrue();
        assertThat(r.replacedFields()).isEmpty();
    }

    @Test
    @DisplayName("BR-AUTH-001: values after the 18th are ignored; a comma in a value shifts the later fields")
    @SpecItem("BR-AUTH-001")
    void extraValuesAndShift() throws Exception {
        assertThat(csv(CSV + ",EXTRA,MORE").stored(RequestField.TRANSACTION_ID)).isEqualTo("TRN000000000001");
        AuthorizationRequest shifted = csv(CSV.replace("GROCERY STORE", "GROCERY, STORE"));
        assertThat(shifted.stored(RequestField.MERCHANT_NAME)).isEqualTo("GROCERY               ");
        assertThat(shifted.stored(RequestField.MERCHANT_CITY)).isEqualTo(" STORE       ");
        assertThat(shifted.stored(RequestField.TRANSACTION_ID)).isEqualTo("62701          ");
    }

    @Test
    @DisplayName("ERR-AUTH-003, DEF-AUTH-009: fewer than 18 values, oversized, undecodable or unknown type is unparsable")
    @SpecItem({"ERR-AUTH-003", "DEF-AUTH-009"})
    void unparsableBodies() {
        String seventeen = CSV.substring(0, CSV.lastIndexOf(','));
        assertThat(unparsable(seventeen.getBytes(StandardCharsets.UTF_8), "text/csv")).isEqualTo(Reason.TOO_FEW_VALUES);
        byte[] big = (CSV + ",".repeat(501 - CSV.length())).getBytes(StandardCharsets.UTF_8);
        assertThat(big).hasSize(501);
        assertThat(unparsable(big, "text/csv")).isEqualTo(Reason.OVERSIZED);
        byte[] exactly500 = (CSV + ",".repeat(500 - CSV.length())).getBytes(StandardCharsets.UTF_8);
        assertThat(exactly500).hasSize(500);
        assertThat(unparsable(CSV.getBytes(StandardCharsets.UTF_8), "application/xml")).isEqualTo(Reason.UNKNOWN_CONTENT_TYPE);
        assertThat(unparsable(CSV.getBytes(StandardCharsets.UTF_8), "text/csv; charset=iso-8859-1"))
                .isEqualTo(Reason.UNKNOWN_CONTENT_TYPE);
        byte[] bad = CSV.getBytes(StandardCharsets.UTF_8);
        bad[40] = (byte) 0xC3;
        bad[41] = (byte) 0x28;
        assertThat(unparsable(bad, "text/csv")).isEqualTo(Reason.UNDECODABLE);
    }

    @Test
    @DisplayName("D-MSG-02: the body form follows content_type; none, text/plain and a UTF-8 charset are CSV")
    @SpecItem("BR-AUTH-001")
    void formByContentType() throws Exception {
        byte[] body = CSV.getBytes(StandardCharsets.UTF_8);
        assertThat(RequestParser.parse(body, null).form()).isEqualTo(BodyForm.CSV);
        assertThat(RequestParser.parse(body, "").form()).isEqualTo(BodyForm.CSV);
        assertThat(RequestParser.parse(body, "text/plain").form()).isEqualTo(BodyForm.CSV);
        assertThat(RequestParser.parse(body, "Text/CSV; charset=\"UTF-8\"").form()).isEqualTo(BodyForm.CSV);
        assertThat(RequestParser.parse(JSON.getBytes(StandardCharsets.UTF_8), "application/json").form())
                .isEqualTo(BodyForm.JSON);
    }

    @Test
    @DisplayName("D-MSG-02: the JSON form gives the same stored values as the CSV form")
    @SpecItem("BR-AUTH-001")
    void jsonForm() throws Exception {
        AuthorizationRequest r = RequestParser.parse(JSON.getBytes(StandardCharsets.UTF_8), "application/json");
        assertThat(r.stored(RequestField.MESSAGE_SOURCE)).isEqualTo("POS   ");
        assertThat(r.stored(RequestField.PROCESSING_CODE)).isEqualTo("000000");
        assertThat(r.stored(RequestField.POS_ENTRY_MODE)).isEqualTo("01");
        assertThat(r.amount().value()).hasToString("50.00");
        AuthorizationRequest number = RequestParser.parse(
                JSON.replace("\"transactionAmt\":\"50.00\"", "\"transactionAmt\":50.5").getBytes(StandardCharsets.UTF_8),
                "application/json");
        assertThat(number.raw(RequestField.TRANSACTION_AMT)).isEqualTo("50.5");
        assertThat(number.amount().value()).hasToString("50.50");
        AuthorizationRequest extra = RequestParser.parse(
                JSON.replace("{\"authDate\"", "{\"other\":{\"a\":[1,2]},\"authDate\"").getBytes(StandardCharsets.UTF_8),
                "application/json");
        assertThat(extra.stored(RequestField.AUTH_DATE)).isEqualTo("240405");
    }

    @Test
    @DisplayName("ERR-AUTH-003: invalid JSON, a missing name, null, another type, a duplicate or trailing content")
    @SpecItem("ERR-AUTH-003")
    void invalidJson() {
        String[] bodies = {
            "{", "[]", "\"x\"", JSON.replace("\"authType\":\"SALE\",", ""), JSON.replace("\"SALE\"", "null"),
            JSON.replace("\"SALE\"", "12"), JSON.replace("\"cardNum\":\"4111111111111111\"", "\"cardNum\":4111111111111111"),
            JSON.replace("{\"authDate\"", "{\"authType\":\"X\",\"authDate\""), JSON + " {}", JSON + " x",
        };
        for (String body : bodies) {
            assertThat(unparsable(body.getBytes(StandardCharsets.UTF_8), "application/json")).as(body)
                    .isEqualTo(Reason.INVALID_JSON);
        }
        byte[] big = (JSON + " ".repeat(2049 - JSON.length())).getBytes(StandardCharsets.UTF_8);
        assertThat(unparsable(big, "application/json")).isEqualTo(Reason.OVERSIZED);
    }

    @Test
    @DisplayName("Unparsable reasons carry no body text")
    void reasonHasNoBody() {
        assertThatThrownBy(() -> csv("4111111111111111")).isInstanceOf(UnparsableRequestException.class)
                .hasMessage("TOO_FEW_VALUES");
    }
}
