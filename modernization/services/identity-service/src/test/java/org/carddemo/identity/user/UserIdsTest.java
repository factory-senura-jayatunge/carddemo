package org.carddemo.identity.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.Cursors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The canonical user ID (D-53, BR-SGN-001), the list's LIKE pattern and its page tokens. */
class UserIdsTest {

    private static String code(Runnable r) {
        try {
            r.run();
            return null;
        } catch (ApiException e) {
            return e.code() + "|" + e.field() + "|" + e.text();
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "user0001|USER0001",
            "'  user0001'|USER0001",
            "éa|éA",
            "ßÿµz|ßÿµZ",
            "a\tb|A\tB",
            "a\u00A0b|A\u00A0B",
            "abcdefgh|ABCDEFGH"})
    @SpecItem({"BR-USRA-001", "DEF-USRA-009"})
    void canonicalForm(String raw, String expected) {
        assertThat(UserIds.canonical(raw, "userId")).isEqualTo(expected);
    }

    @Test
    @SpecItem({"BR-USRA-001"})
    void thePadAndLowValuesAtTheEndAreRemoved() {
        assertThat(UserIds.canonical("ab \u0000 \u0000", "userId")).isEqualTo("AB");
    }

    @Test
    @SpecItem({"VAL-USRA-001", "DEF-USRA-009"})
    void refusals() {
        assertThat(code(() -> UserIds.canonical(null, "userId")))
                .isEqualTo("USER_ID_EMPTY|userId|User ID can NOT be empty...");
        assertThat(code(() -> UserIds.canonical(" \u0000 ", "userId")))
                .isEqualTo("USER_ID_EMPTY|userId|User ID can NOT be empty...");
        assertThat(code(() -> UserIds.canonical("ab cd", "userId")))
                .isEqualTo("USER_ID_INVALID|userId|User ID must not contain spaces...");
        assertThat(code(() -> UserIds.canonical("abcdefghi", "userId")))
                .isEqualTo("TOO_LONG|userId|User ID must not be longer than 8 characters.");
        assertThat(code(() -> UserIds.canonical("a\u0100", "userId")))
                .isEqualTo("UNSUPPORTED_CHARACTER|userId|User ID contains a character that is not supported.");
        assertThat(code(() -> UserIds.canonical("😀", "userId")))
                .isEqualTo("UNSUPPORTED_CHARACTER|userId|User ID contains a character that is not supported.");
    }

    @Test
    void searchPrefixAllowsBlankAndSpacesInside() {
        assertThat(UserIds.searchPrefix("  ", "start")).isEmpty();
        assertThat(UserIds.searchPrefix(null, "start")).isEmpty();
        assertThat(UserIds.searchPrefix("us", "start")).isEqualTo("US");
    }

    @Test
    void likeEscapesTheWildcards() {
        assertThat(UserAdminService.like("")).isEqualTo("%");
        assertThat(UserAdminService.like("A%B_C[D\\")).isEqualTo("A\\%B\\_C\\[D\\\\%");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"|ADMIN001", "AD|ADMIN001", "ABCDEFGH|ABCDEFGH", "X|X:Y"})
    void pageKeysRoundTrip(String prefix, String userId) {
        String p = prefix == null ? "" : prefix;
        UserAdminService.PageKey key = new UserAdminService.PageKey(p, userId);
        assertThat(UserAdminService.PageKey.decode(key.encode())).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN001", "1:XADMIN001", "2:AD", "9:ABCDEFGHIJ", "0:admin001", "0: ADMIN01",
            "0:AB CD", "a:ADMIN001", "1ADMIN001"})
    void forgedPageKeysAreInvalidCursors(String content) {
        String token = Cursors.encode(content);
        assertThatThrownBy(() -> UserAdminService.PageKey.decode(token))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_CURSOR"));
    }

    @Test
    void newPasswordRule() {
        assertThat(NewPasswords.lengthProblem("12345678", false)).isNull();
        assertThat(NewPasswords.lengthProblem("123456789", false).key()).isEqualTo("NEW_PASSWORD_LENGTH_LEGACY");
        assertThat(NewPasswords.lengthProblem("1234567", true).key()).isEqualTo("NEW_PASSWORD_LENGTH_FIXED");
        assertThat(NewPasswords.lengthProblem("12345678", true)).isNull();
        // Code points count, not bytes: 64 × é is 128 bytes and 64 emoji are 256 (D-SEC-07).
        assertThat(NewPasswords.lengthProblem("é".repeat(64), true)).isNull();
        assertThat(NewPasswords.lengthProblem("\uD83D\uDE00".repeat(64), true)).isNull();
        assertThat(NewPasswords.lengthProblem("é".repeat(65), true).key()).isEqualTo("NEW_PASSWORD_LENGTH_FIXED");
        assertThat(NewPasswords.lengthProblem("\uD83D\uDE00".repeat(65), true).key())
                .isEqualTo("NEW_PASSWORD_LENGTH_FIXED");
        assertThat(NewPasswords.lengthProblem("pass\u0007word", true).key()).as("a control character")
                .isEqualTo("NEW_PASSWORD_LENGTH_FIXED");
        assertThat(NewPasswords.lengthProblem("pass  word  in", true)).as("spaces inside").isNull();
        assertThat(NewPasswords.isUserId("jsmith", "JSMITH")).isTrue();
        assertThat(NewPasswords.isUserId("éa", "ÉA")).as("only a-z change case").isFalse();
    }
}
