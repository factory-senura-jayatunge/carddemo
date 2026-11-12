package org.carddemo.authorization.reply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthorizationReplyTest {

    static final String CARD = "4111111111111111";
    static final String TRAN = "TRN000000000001";

    @Test
    @DisplayName("M1: the approved reply is 63 characters, the amount edited -ZZZZZZZZZ9.99")
    @SpecItem({"NUM-AUTH-003", "BR-AUTH-007"})
    void approvedCsv() {
        AuthorizationReply reply = new AuthorizationReply(CARD, TRAN, "000123", "00", "0000", new BigDecimal("50.00"));
        assertThat(reply.toCsv()).isEqualTo("4111111111111111,TRN000000000001,000123,00,0000,         50.00,")
                .hasSize(AuthorizationReply.CSV_LENGTH);
    }

    @Test
    @DisplayName("M3: a decline carries 0.00 and the reason")
    @SpecItem("NUM-AUTH-003")
    void declinedCsv() {
        AuthorizationReply reply = new AuthorizationReply(CARD, TRAN, "000000", "05", "4100", BigDecimal.ZERO);
        assertThat(reply.toCsv()).isEqualTo("4111111111111111,TRN000000000001,000000,05,4100,          0.00,");
    }

    @Test
    @DisplayName("NUM-AUTH-003: the widest approved amount fills the edit")
    @SpecItem("NUM-AUTH-003")
    void widestAmount() {
        AuthorizationReply reply = new AuthorizationReply(CARD, TRAN, "999999", "00", "0000",
                new BigDecimal("9999999999.99"));
        assertThat(reply.toCsv()).endsWith(",0000, 9999999999.99,").hasSize(63);
    }

    @Test
    @DisplayName("M17: the JSON reply has the same six values, trailing spaces trimmed, the amount a decimal string")
    @SpecItem("NUM-AUTH-003")
    void json() {
        AuthorizationReply reply = new AuthorizationReply("1234            ", "TRN1           ", "000123", "00", "0000",
                new BigDecimal("50"));
        assertThat(reply.toJson()).isEqualTo("{\"cardNum\":\"1234\",\"transactionId\":\"TRN1\",\"authIdCode\":\"000123\","
                + "\"respCode\":\"00\",\"respReason\":\"0000\",\"approvedAmt\":\"50.00\"}");
    }

    @Test
    @DisplayName("M33: control characters and commas in the transaction ID come back unchanged, by position")
    @SpecItem("NUM-AUTH-003")
    void storedReplyRoundTrip() {
        String tran = "A,\u0085\u0000B,,,       ";
        AuthorizationReply reply = new AuthorizationReply(CARD, tran, "000042", "00", "0000", new BigDecimal("0.01"));
        String csv = reply.toCsv();
        assertThat(csv).hasSize(63).contains(tran);
        assertThat(AuthorizationReply.fromCsv(csv)).isEqualTo(reply);
    }

    @Test
    void notAStoredReply() {
        assertThatThrownBy(() -> AuthorizationReply.fromCsv("x".repeat(63))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthorizationReply(CARD, "short", "000000", "05", "3100", BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("M28: the reply's text form masks the card number")
    void toStringMasksTheCard() {
        AuthorizationReply reply = new AuthorizationReply(CARD, TRAN, "000123", "00", "0000", new BigDecimal("50.00"));
        assertThat(reply.toString()).doesNotContain(CARD).doesNotContain(TRAN);
    }
}
