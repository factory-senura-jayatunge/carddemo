package org.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.TestTokens;
import org.carddemo.platform.web.MessageCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ERR-ACVW-004 (architecture-dependent): an error the view does not expect is the platform's
 * generic 500 problem, and the session survives it. The repository is wrapped so that its account
 * read throws an exception that is not a database failure.
 */
@Import(AccountViewUnexpectedErrorTest.FailingReads.class)
class AccountViewUnexpectedErrorTest extends AccountTestBase {

    static final String ACCT = "00000000001";
    static final String DETAIL = "unexpected internal detail";
    static final AtomicBoolean FAIL = new AtomicBoolean();

    @TestConfiguration
    static class FailingReads {
        @Bean
        @Primary
        AccountViewRepository failingAccountViewRepository(JdbcClient jdbc) {
            return new FailingRepository(jdbc);
        }
    }

    static class FailingRepository extends AccountViewRepository {
        FailingRepository(JdbcClient jdbc) {
            super(jdbc);
        }

        @Override
        public Optional<AccountRow> account(String acctId) {
            if (FAIL.get()) {
                throw new IllegalStateException(DETAIL);
            }
            return super.account(acctId);
        }
    }

    @BeforeEach
    void sampleAccount() {
        DB.fixtures().deleteAll().account(CoreFixtures.Account.of(ACCT, "194.00")).customer("000000001")
                .card("9680294154603697", ACCT, "000000001");
    }

    @AfterEach
    void reset() {
        FAIL.set(false);
    }

    @Test
    @DisplayName("ACVW ERR-ACVW-004: an unexpected error is the generic 500 with a correlation ID; the session survives")
    @SpecItem("ERR-ACVW-004")
    void unexpectedErrorIsTheGenericProblem() throws Exception {
        String token = TestTokens.user("USER0001");
        FAIL.set(true);
        MvcResult failed = mvc.perform(get("/api/accounts/{a}", ACCT).header("Authorization", bearer(token)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(MessageCatalog.INTERNAL_ERROR.text()))
                .andReturn();
        String body = failed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String correlationId = failed.getResponse().getHeader("X-Correlation-Id");
        assertThat(correlationId).isNotBlank();
        assertThat(body).contains("\"correlationId\":\"" + correlationId + "\"")
                .doesNotContain(DETAIL, "IllegalStateException", "3697", "acctId\":\"" + ACCT);

        FAIL.set(false);
        mvc.perform(get("/api/accounts/{a}", ACCT).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acctId").value(ACCT));
    }
}
