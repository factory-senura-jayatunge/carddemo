package org.carddemo.platform.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The user-admin and sign-on forms are copies of one another by password mode and screen. This
 * pins the documented differences, so a later edit of one copy cannot leave the others behind:
 * fixed and legacy differ only in the password length check and the user-ID check of the
 * password; add and update only in the user ID checks (update has none: it is the key) and the
 * password's required check (update keeps the current one when blank).
 */
class UserFormsTest {

    private static final JsonNode FORMS = JsonMapper.builder().build().readTree(RuleTable.resource(RuleTable.RULES))
            .required("forms");

    @Test
    void legacyFormsDifferOnlyInThePasswordLengthAndTheUserIdCheck() {
        for (String[] pair : new String[][] {{"userAdd", "userAddLegacy"}, {"userUpdate", "userUpdateLegacy"}}) {
            ArrayNode fixed = entries(pair[0]);
            int differs = indexOf(fixed, "differs", "password");
            assertThat(fixed.get(differs).get("other").asString()).isEqualTo("userId");
            assertThat(fixed.get(differs).get("fold").asString()).isEqualTo("legacyUpper");
            fixed.remove(differs);
            ArrayNode checks = (ArrayNode) fixed.get(indexOf(fixed, "field", "password")).get("checks");
            ObjectNode length = (ObjectNode) checks.get(checks.size() - 1);
            assertThat(length.get("kind").asString()).isEqualTo("codePoints");
            length.removeAll();
            length.put("kind", "codePoints").put("max", 8).put("code", "NEW_PASSWORD_LENGTH")
                    .put("message", "New Password must be 1 to 8 characters ...");
            assertThat(fixed).as(pair[1]).isEqualTo(entries(pair[1]));
        }
    }

    @Test
    void updateFormsDifferOnlyInTheUserIdChecksAndThePasswordRequiredCheck() {
        for (String[] pair : new String[][] {{"userAdd", "userUpdate"}, {"userAddLegacy", "userUpdateLegacy"}}) {
            ArrayNode add = entries(pair[0]);
            ((ObjectNode) add.get(indexOf(add, "field", "userId"))).remove("checks");
            ArrayNode password = (ArrayNode) add.get(indexOf(add, "field", "password")).get("checks");
            assertThat(password.get(0).get("kind").asString()).isEqualTo("required");
            password.remove(0);
            assertThat(add).as(pair[1]).isEqualTo(entries(pair[1]));
        }
    }

    @Test
    void theModeUnknownPasswordChangeKeepsOnlyTheBlankChecks() {
        ArrayNode change = entries("passwordChange");
        ArrayNode blanks = change.arrayNode();
        for (JsonNode e : change) {
            if (e.has("name")) {
                ObjectNode copy = (ObjectNode) e.deepCopy();
                ArrayNode checks = (ArrayNode) copy.get("checks");
                assertThat(checks.get(0).get("kind").asString()).isEqualTo("required");
                while (checks.size() > 1) {
                    checks.remove(1);
                }
                blanks.add(copy);
            }
        }
        assertThat(blanks).isEqualTo(entries("passwordChangeAnyMode"));
    }

    @Test
    void signOnUsesThePasswordChangeEntriesForTheIdAndTheCurrentPassword() {
        ArrayNode signOn = entries("signOn");
        ArrayNode change = entries("passwordChange");
        assertThat(signOn.get(0)).isEqualTo(change.get(indexOf(change, "field", "userId")));
        ObjectNode current = (ObjectNode) change.get(indexOf(change, "field", "currentPassword")).deepCopy();
        current.put("name", "password").put("label", "Password");
        assertThat(signOn.get(1)).isEqualTo(current);
        assertThat(signOn).hasSize(2);
    }

    @Test
    void blankNamesAndTypesAreNulAndJavaWhitespaceAndTheKeysSpaceAndNul() {
        StringBuilder ws0 = new StringBuilder();
        for (int c = 0; c <= 0xFFFF; c++) {
            if (c == 0 || Character.isWhitespace((char) c)) {
                ws0.append((char) c);
            }
        }
        for (String form : List.of("userAdd", "userUpdate", "userAddLegacy", "userUpdateLegacy")) {
            for (JsonNode e : FORMS.required(form).required("fields")) {
                if (!e.has("name")) {
                    continue;
                }
                String expected = switch (e.get("name").asString()) {
                    case "firstName", "lastName", "userType" -> ws0.toString();
                    default -> " \u0000";
                };
                assertThat(e.get("blankChars").asString()).as(form + " " + e.get("name")).isEqualTo(expected);
            }
        }
        for (String form : List.of("signOn", "passwordChange", "passwordChangeAnyMode")) {
            List<String> blanks = new ArrayList<>();
            FORMS.required(form).required("fields").forEach(e -> {
                if (e.has("name")) {
                    blanks.add(e.get("blankChars").asString());
                }
            });
            assertThat(blanks).as(form).isNotEmpty().containsOnly(" \u0000");
        }
    }

    private static ArrayNode entries(String form) {
        return (ArrayNode) FORMS.required(form).required("fields").deepCopy();
    }

    private static int indexOf(ArrayNode entries, String kind, String field) {
        for (int i = 0; i < entries.size(); i++) {
            JsonNode e = entries.get(i);
            String k = e.path("kind").asString("field");
            String name = k.equals("field") ? e.path("name").asString() : e.path("field").asString();
            if (k.equals(kind) && name.equals(field)) {
                return i;
            }
        }
        throw new AssertionError("no " + kind + " entry for " + field);
    }
}
