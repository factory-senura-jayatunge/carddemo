package org.carddemo.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;

import org.carddemo.platform.web.ApiException;
import org.junit.jupiter.api.Test;

class UserKeysTest {

    @Test
    void knownVectors() {
        assertThat(UserKeys.encode("ADMIN001")).isEqualTo("QURNSU4wMDE");
        assertThat(UserKeys.encode("A/B")).isEqualTo("QS9C");
        assertThat(UserKeys.encode("JSMITH")).isEqualTo("SlNNSVRI");
        assertThat(UserKeys.encode(".")).isEqualTo("Lg");
        assertThat(UserKeys.encode("..")).isEqualTo("Li4");
        assertThat(UserKeys.encode("\0")).isEqualTo("AA");
        // ÿ and ß are two UTF-8 bytes each; 0xFF and 0xBF bytes give the URL-safe - and _.
        assertThat(UserKeys.encode("\u00ff\u00ff\u00ff")).isEqualTo("w7_Dv8O_");
        assertThat(UserKeys.encode("")).isEmpty();
    }

    @Test
    void everyCharacterAUserIdMayHoldRoundTripsThroughTheUrlSafeAlphabet() {
        for (char c = 0; c <= 0xFF; c++) {
            for (String id : List.of(String.valueOf(c), "A" + c + "B", ("" + c).repeat(8))) {
                String key = UserKeys.encode(id);
                assertThat(key).matches("[A-Za-z0-9_-]*");
                assertThat(UserKeys.decode(key)).isEqualTo(id);
            }
        }
    }

    @Test
    void decodesTextAboveLatin1SoTheUserIdRulesCanRefuseIt() {
        assertThat(UserKeys.decode("5Lit")).isEqualTo("中");
        assertThat(UserKeys.decode(UserKeys.encode("\uD83D\uDE00"))).isEqualTo("\uD83D\uDE00");
    }

    @Test
    void refusesEverythingThatIsNotTheOneSpellingOfUtf8Bytes() {
        List<String> bad = List.of(
                "QQ==", "QQ=", // padding
                "Q", "QURNS", // a length no encoding gives
                "QS9C+", "QS/C", "QS9C ", "QS%2F", "QS.C", // outside A-Z a-z 0-9 - _
                "QR", "QUJ", // non-zero trailing bits: a second spelling of "A" and "AB"
                "_w", // 0xFF, not UTF-8
                "wIA", // C0 80, the overlong NUL
                "7aCA", // ED A0 80, an encoded surrogate
                "w", "ww"); // a cut-off two-byte sequence
        for (String key : bad) {
            assertThatThrownBy(() -> UserKeys.decode(key)).as(key).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getMessage()).contains("INVALID_REQUEST"));
        }
        assertThatThrownBy(() -> UserKeys.decode(null)).isInstanceOf(ApiException.class);
    }

    @Test
    void javasLenientSpellingsAreTheOnesTheStrictCheckRefuses() {
        // Base64.getUrlDecoder alone accepts these; the test pins why the re-encode check exists.
        assertThat(Base64.getUrlDecoder().decode("QR")).containsExactly('A');
        assertThat(Base64.getUrlDecoder().decode("QQ==")).containsExactly('A');
    }

    @Test
    void atMost1024KeyCharacters() {
        String longest = "QUFB".repeat(256);
        assertThat(longest).hasSize(1024);
        assertThat(UserKeys.decode(longest)).hasSize(768);
        assertThatThrownBy(() -> UserKeys.decode(longest + "QUE")).isInstanceOf(ApiException.class);
    }
}
