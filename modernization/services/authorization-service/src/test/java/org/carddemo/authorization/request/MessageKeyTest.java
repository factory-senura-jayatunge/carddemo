package org.carddemo.authorization.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessageKeyTest {

    @Test
    @DisplayName("DEF-AUTH-006: duplicates by message ID, else by card, transaction ID, date and time")
    @SpecItem("DEF-AUTH-006")
    void keys() throws Exception {
        AuthorizationRequest r = RequestParserTest.csv(RequestParserTest.CSV);
        MessageKey byId = MessageKey.of("msg-1", r);
        assertThat(byId.kind()).isEqualTo('M');
        assertThat(byId).isEqualTo(MessageKey.of("msg-1", r)).isNotEqualTo(MessageKey.of("msg-2", r));
        MessageKey byContent = MessageKey.of("", r);
        assertThat(byContent.kind()).isEqualTo('K');
        assertThat(byContent).isEqualTo(MessageKey.of(null, r));
        assertThat(byContent.hash()).hasSize(32);
        String other = RequestParserTest.CSV.replace("143015", "143016");
        assertThat(MessageKey.of(null, RequestParserTest.csv(other))).isNotEqualTo(byContent);
        String expected = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                "K|4111111111111111TRN000000000001240405143015".getBytes(StandardCharsets.UTF_8)));
        assertThat(HexFormat.of().formatHex(byContent.hash())).isEqualTo(expected);
        assertThat(byContent.toString()).doesNotContain("4111");
    }
}
