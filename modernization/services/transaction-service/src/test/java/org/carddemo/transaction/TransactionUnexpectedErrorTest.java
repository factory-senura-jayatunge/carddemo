package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.carddemo.platform.fidelity.SpecItem;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * ERR-TRNL-004 and ERR-TRNV-003 (architecture-dependent): the abend becomes the platform's generic
 * 500 problem, with no stack trace, and the session survives it. The repositories are wrapped so
 * that their reads throw an exception that is not a database failure.
 */
@Import(TransactionUnexpectedErrorTest.FailingReads.class)
class TransactionUnexpectedErrorTest extends TransactionTestBase {

    static final String TRAN = "0000000000000100";
    static final String DETAIL = "unexpected internal detail";
    static final AtomicBoolean FAIL = new AtomicBoolean();

    @TestConfiguration
    static class FailingReads {
        @Bean
        @Primary
        TransactionListRepository failingListRepository(JdbcClient jdbc) {
            return new TransactionListRepository(jdbc) {
                @Override
                public List<ListedTransaction> forward(String key, boolean inclusive, int rows) {
                    if (FAIL.get()) {
                        throw new IllegalStateException(DETAIL);
                    }
                    return super.forward(key, inclusive, rows);
                }
            };
        }

        @Bean
        @Primary
        TransactionViewRepository failingViewRepository(JdbcClient jdbc) {
            return new TransactionViewRepository(jdbc) {
                @Override
                public Optional<StoredTransaction> byId(String tranId) {
                    if (FAIL.get()) {
                        throw new IllegalStateException(DETAIL);
                    }
                    return super.byId(tranId);
                }
            };
        }
    }

    @BeforeEach
    void oneTransaction() {
        DB.fixtures().deleteAll();
        SampleTransactions.insertDefault(admin, TRAN, "1.00");
    }

    @AfterEach
    void reset() {
        FAIL.set(false);
    }

    @Test
    @DisplayName("TRNL ERR-TRNL-004, TRNV ERR-TRNV-003: an unexpected error is the generic 500 with a correlation ID")
    @SpecItem({"ERR-TRNL-004", "ERR-TRNV-003"})
    void unexpectedErrorIsTheGenericProblem() throws Exception {
        String token = TestTokens.user(USER);
        FAIL.set(true);
        for (ResultActions call : List.of(
                mvc.perform(get("/api/transactions").header("Authorization", bearer(token))),
                mvc.perform(get("/api/transactions/{t}", TRAN).header("Authorization", bearer(token))))) {
            MvcResult failed = call.andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value(MessageCatalog.INTERNAL_ERROR.text()))
                    .andReturn();
            String body = failed.getResponse().getContentAsString(StandardCharsets.UTF_8);
            String correlationId = failed.getResponse().getHeader("X-Correlation-Id");
            assertThat(correlationId).isNotBlank();
            assertThat(body).contains("\"correlationId\":\"" + correlationId + "\"")
                    .doesNotContain(DETAIL, "IllegalStateException", "at org.", SampleTransactions.F25_CARD);
        }

        FAIL.set(false);
        mvc.perform(get("/api/transactions").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].tranId").value(TRAN));
        mvc.perform(get("/api/transactions/{t}", TRAN).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tranId").value(TRAN));
    }
}
