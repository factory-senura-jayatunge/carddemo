package org.carddemo.authorization.reply;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.carddemo.authorization.request.BodyForm;
import org.carddemo.platform.legacyformat.NumericEdit;
import org.carddemo.platform.legacyformat.Pic;
import org.carddemo.platform.web.Pii;
import tools.jackson.databind.json.JsonMapper;

/**
 * The authorization reply (ENT-AUTH-RESPONSE, NUM-AUTH-003). The card number and transaction ID
 * are the request's stored values; the amount is the approved amount. A CSV request gets the
 * 63-character text, a JSON request the same six values as an object with trailing spaces trimmed
 * and the amount as a decimal string (architecture rulings §1, question 5).
 * {@link #toString()} masks the card number, so a reply never reaches a log line.
 */
public record AuthorizationReply(String cardNum, String transactionId, String authIdCode, String respCode,
        String reason, BigDecimal approvedAmt) {

    public static final int CSV_LENGTH = 63;
    private static final NumericEdit AMOUNT = NumericEdit.of("-ZZZZZZZZZ9.99");
    private static final Pic APPROVED_AMT = Pic.signed(10, 2);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public AuthorizationReply {
        requireWidth(cardNum, 16, "cardNum");
        requireWidth(transactionId, 15, "transactionId");
        requireWidth(authIdCode, 6, "authIdCode");
        requireWidth(respCode, 2, "respCode");
        requireWidth(reason, 4, "reason");
        approvedAmt = approvedAmt.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static void requireWidth(String value, int width, String name) {
        if (value == null || value.length() != width) {
            throw new IllegalArgumentException(name + " must be " + width + " characters");
        }
    }

    /** {@code <card 16>,<transaction ID 15>,<code 6>,<resp 2>,<reason 4>,<amount 14>,}: 63 characters. */
    public String toCsv() {
        return cardNum + ',' + transactionId + ',' + authIdCode + ',' + respCode + ',' + reason + ','
                + AMOUNT.format(approvedAmt, APPROVED_AMT) + ',';
    }

    /** The reply in the request's form: the 63-character text for CSV, the object for JSON. */
    public String body(BodyForm form) {
        return form == BodyForm.CSV ? toCsv() : toJson();
    }

    /** The reply's {@code content_type}, which follows the request's form. */
    public static String contentType(BodyForm form) {
        return form == BodyForm.CSV ? "text/csv" : "application/json";
    }

    public String toJson() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("cardNum", stripTrailingSpaces(cardNum));
        body.put("transactionId", stripTrailingSpaces(transactionId));
        body.put("authIdCode", stripTrailingSpaces(authIdCode));
        body.put("respCode", respCode);
        body.put("respReason", reason);
        body.put("approvedAmt", approvedAmt.toPlainString());
        return JSON.writeValueAsString(body);
    }

    /**
     * The reply back from its stored 63-character form ({@code processed_message.reply_body}), by
     * position: the transaction ID may itself hold commas.
     */
    public static AuthorizationReply fromCsv(String csv) {
        if (csv.length() != CSV_LENGTH || csv.charAt(16) != ',' || csv.charAt(32) != ',' || csv.charAt(39) != ','
                || csv.charAt(42) != ',' || csv.charAt(47) != ',' || csv.charAt(62) != ',') {
            throw new IllegalArgumentException("not a stored reply");
        }
        return new AuthorizationReply(csv.substring(0, 16), csv.substring(17, 32), csv.substring(33, 39),
                csv.substring(40, 42), csv.substring(43, 47), new BigDecimal(csv.substring(48, 62).strip()));
    }

    private static String stripTrailingSpaces(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    @Override
    public String toString() {
        return "AuthorizationReply[card=" + Pii.cardNumber(cardNum) + ", resp=" + respCode + ", reason=" + reason + "]";
    }
}
