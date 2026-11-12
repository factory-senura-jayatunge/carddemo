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
 * ERR-CRDV-002 (architecture-dependent): the abend becomes the platform's generic 500 problem, with
 * no stack trace, and the session survives it. The repository is wrapped so that its read by ID
 * throws an exception that is not a database failure.
 */
@Import(CardViewUnexpectedErrorTest.FailingReads.class)
class CardViewUnexpectedErrorTest extends AccountTestBase {

    static final String ACCT = "00000000050";
    static final String CARD = "0500024453765740";
    static final String DETAIL = "unexpected internal detail";
    static final AtomicBoolean FAIL = new AtomicBoolean();

    @TestConfiguration
    static class FailingReads {
        @Bean
        @Primary
        CardViewRepository failingCardViewRepository(JdbcClient jdbc) {
            return new FailingRepository(jdbc);
        }
    }

    static class FailingRepository extends CardViewRepository {
        FailingRepository(JdbcClient jdbc) {
            super(jdbc);
        }

        @Override
        public Optional<StoredCard> byId(long cardId) {
            if (FAIL.get()) {
                throw new IllegalStateException(DETAIL);
            }
            return super.byId(cardId);
        }
    }

    @BeforeEach
    void sampleCard() {
        DB.fixtures().deleteAll().account(CoreFixtures.Account.of(ACCT, "0.00")).customer("000000050")
                .card(CARD, ACCT, "000000050");
    }

    @AfterEach
    void reset() {
        FAIL.set(false);
    }

    @Test
    @DisplayName("CRDV ERR-CRDV-002: an unexpected error is the generic 500 with a correlation ID; the session survives")
    @SpecItem({"ERR-CRDV-002", "DEF-CRDV-009"})
    void unexpectedErrorIsTheGenericProblem() throws Exception {
        String token = TestTokens.user("USER0001");
        String cardId = DB.admin().sql("SELECT CAST(card_id AS VARCHAR(20)) FROM core.card WHERE card_num = :c")
                .param("c", CARD).query(String.class).single();
        FAIL.set(true);
        MvcResult failed = mvc.perform(get("/api/cards/{c}", cardId).header("Authorization", bearer(token)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(MessageCatalog.INTERNAL_ERROR.text()))
                .andReturn();
        String body = failed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String correlationId = failed.getResponse().getHeader("X-Correlation-Id");
        assertThat(correlationId).isNotBlank();
        assertThat(body).contains("\"correlationId\":\"" + correlationId + "\"")
                .doesNotContain(DETAIL, "IllegalStateException", "at org.", "5740", "TEST CUSTOMER");

        FAIL.set(false);
        mvc.perform(get("/api/cards/{c}", cardId).header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardId").value(cardId));
    }
}
