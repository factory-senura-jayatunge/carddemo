package org.carddemo.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

class RowVersionsTest {

    private static final byte[] A = {0, 0, 0, 0, 0, 0, 0x07, (byte) 0xd1};
    private static final byte[] B = {0, 0, 0, 0, 0, 0, 0x07, (byte) 0xff};

    @Test
    void versionsAreHexJoinedByDots() {
        assertThat(RowVersions.tag(A)).isEqualTo("00000000000007d1");
        assertThat(RowVersions.tag(A, B)).isEqualTo("00000000000007d1.00000000000007ff");
        assertThat(RowVersions.etag(A, B)).isEqualTo("\"00000000000007d1.00000000000007ff\"");
        assertThatThrownBy(() -> RowVersions.tag(new byte[4])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingIfMatchIs428() {
        for (String missing : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> RowVersions.requireIfMatch(missing))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
                        assertThat(e.code()).isEqualTo("PRECONDITION_REQUIRED");
                    });
        }
    }

    @Test
    void onlyOneOfOurStrongTagsIsAccepted() {
        assertThat(RowVersions.requireIfMatch(" \"00000000000007d1.00000000000007ff\" "))
                .isEqualTo("00000000000007d1.00000000000007ff");
        for (String bad : new String[] {"*", "W/\"00000000000007d1\"", "00000000000007d1", "\"00000000000007D1\"",
                "\"00000000000007d1\", \"00000000000007ff\"", "\"7d1\"", "\""}) {
            assertThatThrownBy(() -> RowVersions.requireIfMatch(bad))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_IF_MATCH"));
        }
    }

    @Test
    void aStaleVersionIs409WithTheFreshValues() {
        assertThat(RowVersions.matches("00000000000007d1", A)).isTrue();
        assertThat(RowVersions.matches("00000000000007d1", B)).isFalse();
        ApiException e = RowVersions.conflict("acctId", Map.of("creditLimit", "5000.00"), B);
        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.text()).isEqualTo("Record changed by some one else. Please review");
        assertThat(e.field()).isEqualTo("acctId");
        assertThat(e.extras()).containsEntry("current", Map.of("creditLimit", "5000.00"));
        assertThat(e.headers().getFirst(HttpHeaders.ETAG)).isEqualTo("\"00000000000007ff\"");
    }
}
