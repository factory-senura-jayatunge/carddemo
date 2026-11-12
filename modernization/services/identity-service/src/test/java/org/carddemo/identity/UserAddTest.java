package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import org.carddemo.identity.ApiClient.Response;
import org.carddemo.identity.IdentityFixtures.AdminRow;
import org.carddemo.identity.IdentityFixtures.OpsAuditRow;
import org.carddemo.identity.fidelity.IdentitySwitches;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** USRA (COUSR01C): add, contract scenarios S6 to S14 and S29. */
class UserAddTest extends UserAdminTestBase {

    @Autowired
    private JdbcClient jdbc;

    private Response add(ApiClient c, String first, String last, String id, String password, String type) {
        return c.post("/api/users", newUser(first, last, id, password, type));
    }

    @Test
    @SpecItem({"VAL-USRA-001"})
    void s6_requiredFieldsInScreenOrder() {
        db.demoSeed();
        ApiClient c = admin();
        assertProblem(add(c, "", "", "", "", ""), 400, "FIRST_NAME_EMPTY", "First Name can NOT be empty...",
                "firstName");
        assertProblem(add(c, "Ann", " ", "", "", ""), 400, "LAST_NAME_EMPTY", "Last Name can NOT be empty...",
                "lastName");
        assertProblem(add(c, "Ann", "Lee", "   ", "", ""), 400, "USER_ID_EMPTY", "User ID can NOT be empty...",
                "userId");
        assertProblem(add(c, "Ann", "Lee", "alee", "  ", ""), 400, "PASSWORD_EMPTY", "Password can NOT be empty...",
                "password");
        assertProblem(add(c, "Ann", "Lee", "alee", "secret", null), 400, "USER_TYPE_EMPTY",
                "User Type can NOT be empty...", "userType");
        assertThat(db.opsAudit()).isEmpty();
    }

    @Test
    @SpecItem({"BR-USRA-001", "BR-USRA-002", "DEF-USRA-001", "DEF-USRA-006", "Q-USRA-002", "DEF-SGN-011"})
    void s7_fixedAddStoresTheCanonicalIdTheNamesAsTypedAndACaseSensitiveHash() {
        switches.only(IdentitySwitches.DEF_SGN_011, FidelityMode.FIXED);
        db.demoSeed();
        Response r = add(admin(), "José", "Ñúñez", "jsmith", "Secret#2026", "U");
        assertThat(r.status()).as("%s", r.body()).isEqualTo(201);
        JsonNode user = r.body().path("user");
        assertThat(user.path("userId").asString()).isEqualTo("JSMITH");
        assertThat(user.path("firstName").asString()).isEqualTo("José");
        assertThat(user.path("lastName").asString()).isEqualTo("Ñúñez");
        assertThat(user.path("userType").asString()).isEqualTo("U");
        assertThat(user.has("password")).isFalse();
        assertThat(r.text("message")).isEqualTo("User JSMITH has been added ...");
        assertThat(r.text("severity")).isEqualTo("success");
        // The key form (base64url of the UTF-8 ID), which any user ID can travel in.
        assertThat(r.header("Location")).isEqualTo("/api/users/key/SlNNSVRI");
        assertThat(r.header("ETag")).isEqualTo('"' + user.path("version").asString() + '"');
        assertThat(r.raw().getContentAsByteArray()).asString().doesNotContain("Secret", "bcrypt", "argon2");

        AdminRow row = db.adminRow("JSMITH");
        assertThat(row.firstName()).isEqualTo("José");
        assertThat(row.lastName()).isEqualTo("Ñúñez");
        assertThat(row.mustChange()).isFalse();
        assertThat(row.createdBy()).isEqualTo("ADMIN001");
        assertThat(row.passwordHash()).startsWith("{argon2}$argon2id$v=19$m=19456,t=2,p=1$").hasSizeLessThanOrEqualTo(255);
        assertThat(db.hasSignOnState("JSMITH")).as("the add writes the sign-on state row").isTrue();
        assertThat(db.opsAudit()).containsExactly(new OpsAuditRow("identity-service", "ADMIN001", "CREATE", "USER",
                "JSMITH", "firstName,lastName,password,userType"));

        assertThat(client().signOn("jsmith", "Secret#2026").status()).isEqualTo(200);
        assertThat(client().signOn("JSMITH", "SECRET#2026").status()).isEqualTo(401);
    }

    @Test
    @SpecItem({"BR-USRA-001", "DEF-USRA-009"})
    void s8_onlyAToZAreUpperCased() {
        db.demoSeed();
        ApiClient c = admin();
        Response r = add(c, "E", "A", "éa", "secret", "U");
        assertThat(r.status()).as("%s", r.body()).isEqualTo(201);
        assertThat(r.body().path("user").path("userId").asString()).isEqualTo("éA");
        Response other = add(c, "E", "A", "ßÿµb", "secret", "U");
        assertThat(other.body().path("user").path("userId").asString()).isEqualTo("ßÿµB");
        assertThat(c.get("/api/users/{id}", "éa").status()).isEqualTo(200);
        assertThat(c.get("/api/users/{id}", "ÉA").status()).isEqualTo(404);
    }

    @Test
    @SpecItem({"DEF-USRA-009"})
    void s9_userIdSpacesAndLength() {
        db.demoSeed();
        ApiClient c = admin();
        assertProblem(add(c, "A", "B", "AB CD", "secret", "U"), 400, "USER_ID_INVALID",
                "User ID must not contain spaces...", "userId");
        assertProblem(add(c, "A", "B", "ABCDEFGHI", "secret", "U"), 400, "TOO_LONG",
                "User ID must not be longer than 8 characters.", "userId");
        assertProblem(add(c, "A", "B", "AB中", "secret", "U"), 400, "UNSUPPORTED_CHARACTER",
                "User ID contains a character that is not supported.", "userId");
        Response padded = add(c, "A", "B", "  user9 \u0000 ", "secret", "U");
        assertThat(padded.status()).as("%s", padded.body()).isEqualTo(201);
        assertThat(padded.body().path("user").path("userId").asString()).isEqualTo("USER9");
    }

    @Test
    @SpecItem({"VAL-USRA-001"})
    void s10_namesKeepEveryLatin1CharacterAndRefuseTheRest() {
        db.demoSeed();
        ApiClient c = admin();
        assertProblem(add(c, "中", "B", "N1", "secret", "U"), 400, "UNSUPPORTED_CHARACTER",
                "First Name contains a character that is not supported.", "firstName");
        assertProblem(add(c, "A", "😀", "N1", "secret", "U"), 400, "UNSUPPORTED_CHARACTER",
                "Last Name contains a character that is not supported.", "lastName");
        assertProblem(add(c, "x".repeat(21), "B", "N1", "secret", "U"), 400, "TOO_LONG",
                "First Name must not be longer than 20 characters.", "firstName");

        Response kept = add(c, "A\u0000B", "C\u00A0D", "N1", "secret", "U");
        assertThat(kept.status()).as("%s", kept.body()).isEqualTo(201);
        assertThat(db.adminRow("N1").firstName()).isEqualTo("A\u0000B");
        assertThat(db.adminRow("N1").lastName()).isEqualTo("C\u00A0D");

        Response padded = add(c, "y".repeat(20) + "   ", "  Lead", "N2", "secret", "U");
        assertThat(padded.status()).as("%s", padded.body()).isEqualTo(201);
        assertThat(db.adminRow("N2").firstName()).isEqualTo("y".repeat(20));
        assertThat(db.adminRow("N2").lastName()).isEqualTo("  Lead");
    }

    @Test
    @SpecItem({"DEF-USRA-002"})
    void s11_userTypeIsExactlyAOrU() {
        db.demoSeed();
        ApiClient c = admin();
        assertProblem(add(c, "A", "B", "T1", "secret", "a"), 400, "USER_TYPE_INVALID", "User Type must be A or U...",
                "userType");
        assertProblem(add(c, "A", "B", "T1", "secret", "X"), 400, "USER_TYPE_INVALID", "User Type must be A or U...",
                "userType");
        assertThat(add(c, "A", "B", "T1", "secret", "A").status()).isEqualTo(201);
    }

    @Test
    @SpecItem({"DEF-USRA-003", "DEF-SGN-011"})
    void s12_passwordLengthFollowsTheSignOnSwitch() {
        db.demoSeed();
        ApiClient legacy = admin();
        assertProblem(add(legacy, "A", "B", "P1", "TOOLONG99", "U"), 400, "NEW_PASSWORD_LENGTH",
                "New Password must be 1 to 8 characters ...", "password");
        assertThat(add(legacy, "A", "B", "P1", "secret", "U").status()).isEqualTo(201);
        assertThat(client().signOn("p1", "SECRET").status()).isEqualTo(200);
        assertThat(client().signOn("p1", "secret").status()).isEqualTo(200);

        switches.only(IdentitySwitches.DEF_SGN_011, FidelityMode.FIXED);
        ApiClient fixed = admin();
        assertProblem(add(fixed, "A", "B", "P2", "short", "U"), 400, "NEW_PASSWORD_LENGTH",
                "New Password must be 8 to 64 characters ...", "password");
        assertProblem(add(fixed, "A", "B", "P2", "x".repeat(65), "U"), 400, "NEW_PASSWORD_LENGTH",
                "New Password must be 8 to 64 characters ...", "password");
        assertProblem(add(fixed, "A", "B", "P2", "tab\there1", "U"), 400, "NEW_PASSWORD_LENGTH",
                "New Password must be 8 to 64 characters ...", "password");
    }

    @Test
    @SpecItem({"DEF-USRA-003", "DEF-SGN-011"})
    void s13_fixedRefusesThePasswordThatIsTheUserId() {
        switches.only(IdentitySwitches.DEF_SGN_011, FidelityMode.FIXED);
        db.demoSeed();
        assertProblem(add(admin(), "A", "B", "jdoe1234", "JDoe1234", "U"), 400, "NEW_PASSWORD_IS_USER_ID",
                "New Password must not be the User ID ...", "password");
    }

    @Test
    @SpecItem({"VAL-USRA-002"})
    void s14_anExistingIdKeepsTheCursorOnFirstName() {
        db.demoSeed();
        assertProblem(add(admin(), "A", "B", "admin001", "secret", "U"), 409, "USER_ID_EXISTS",
                "User ID already exist...", "firstName");
        assertThat(db.opsAudit()).isEmpty();
    }

    @Test
    @SpecItem({"VAL-USRA-002"})
    void s14_aKeyViolationInsideTheAddIsTheExistingIdText() {
        db.demoSeed();
        ApiClient c = admin();
        // A second state row in the add's own transaction: a real primary key violation from the
        // driver, raised inside the transaction callback as a concurrent insert of the ID would be.
        doAnswer(inv -> {
            Object version = inv.callRealMethod();
            jdbc.sql("INSERT INTO iam.user_signon_state (user_id) VALUES (:id)")
                    .param("id", "RACE1")
                    .update();
            return version;
        }).when(userRepository).insert(any(), any(), anyString());
        assertProblem(add(c, "A", "B", "race1", "secret", "U"), 409, "USER_ID_EXISTS", "User ID already exist...",
                "firstName");
        assertThat(userRepository.find("RACE1")).isEmpty();
        assertThat(db.hasSignOnState("RACE1")).isFalse();
        assertThat(db.opsAudit()).isEmpty();
    }

    @Test
    @SpecItem({"ERR-USRA-001"})
    void s29_aDatabaseFailureGivesTheAddText() {
        db.demoSeed();
        ApiClient c = admin();
        doThrow(new DataAccessResourceFailureException("down")).when(userRepository).insert(any(), any(), anyString());
        assertProblem(add(c, "A", "B", "F1", "secret", "U"), 500, "USER_ADD_FAILED", "Unable to Add User...",
                "firstName");
        assertThat(db.opsAudit()).isEmpty();
    }
}
