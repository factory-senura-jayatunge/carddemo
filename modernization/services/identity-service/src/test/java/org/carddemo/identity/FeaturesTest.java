package org.carddemo.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.carddemo.identity.ApiClient.Response;
import org.carddemo.identity.fidelity.IdentitySwitches;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.SpecItem;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FeaturesTest extends IdentityTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest
    @EnumSource(FidelityMode.class)
    @SpecItem({"DEF-BILL-014"})
    void aSignedOnUserSeesTheModulesAndTheBillPaySwitch(FidelityMode mode) {
        switches.set(IdentitySwitches.DEF_BILL_014, mode);
        db.demoUsers();
        ApiClient c = client();
        c.signOn("USER0001", "PASSWORD");
        Response r = c.get("/api/features");
        assertThat(r.status()).isEqualTo(200);
        String expected = """
                {"modules":{"authorization":false,"transactionTypes":false},"converted":["BILL"],
                 "uiSwitches":{"DEF-BILL-014":"%s"}}""".formatted(mode == FidelityMode.FIXED ? "fixed" : "legacy");
        assertThat(r.body()).isEqualTo(JSON.readTree(expected));
    }

    @Test
    void withoutASessionItIsUnauthenticated() {
        Response r = client().get("/api/features");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.text("code")).isEqualTo("UNAUTHENTICATED");
        assertThat(r.raw().getContentType()).startsWith("application/problem+json");
    }

    @Test
    void pathsTheServiceDoesNotServeAreRefused() {
        db.demoUsers();
        ApiClient c = client();
        c.signOn("ADMIN001", "PASSWORD");
        assertThat(c.get("/api/auth/users").status()).isEqualTo(401);
        assertThat(c.get("/actuator/env").status()).isEqualTo(401);
    }
}
