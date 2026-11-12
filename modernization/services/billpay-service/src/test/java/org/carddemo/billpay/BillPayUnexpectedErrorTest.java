package org.carddemo.billpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.TestClock;
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
 * ERR-BILL-004 (architecture-dependent, partial): a failure that no step message covers is the
 * platform's generic 500 problem with the correlation ID and no cause. The repository is wrapped so
 * that its balance reads throw an exception that is not a database failure.
 */
@Import(BillPayUnexpectedErrorTest.FailingReads.class)
class BillPayUnexpectedErrorTest extends BillPayTestBase {

    static final String ACCT = "00000000001";
    static final String DETAIL = "unexpected internal detail";
    static final AtomicBoolean FAIL = new AtomicBoolean();

    @TestConfiguration
    static class FailingReads {
        @Bean
        @Primary
        BillPayRepository failingBillPayRepository(JdbcClient jdbc) {
            return new FailingRepository(jdbc);
        }
    }

    static class FailingRepository extends BillPayRepository {
        FailingRepository(JdbcClient jdbc) {
            super(jdbc);
        }

        @Override
        public Optional<BigDecimal> balance(String acctId) {
            fail();
            return super.balance(acctId);
        }

        @Override
        public Optional<BigDecimal> lockBalance(String acctId) {
            fail();
            return super.lockBalance(acctId);
        }

        private static void fail() {
            if (FAIL.get()) {
                throw new IllegalStateException(DETAIL);
            }
        }
    }

    private final JdbcClient admin = DB.admin();

    @BeforeEach
    void sampleAccount() {
        TestClock.shared().reset();
        DB.fixtures().deleteAll()
                .account(CoreFixtures.Account.of(ACCT, "194.00"))
                .customer("000000001")
                .card("4111111111111111", ACCT, "000000001");
    }

    @AfterEach
    void reset() {
        FAIL.set(false);
    }

    private static void assertGenericProblem(ResultActions call) throws Exception {
        MvcResult result = call.andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(MessageCatalog.INTERNAL_ERROR.text()))
                .andReturn();
        String correlationId = result.getResponse().getHeader("X-Correlation-Id");
        assertThat(correlationId).isNotBlank();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"correlationId\":\"" + correlationId + "\"")
                .doesNotContain(DETAIL, "IllegalStateException", "4111", "194.00");
    }

    @Test
    @DisplayName("BILL ERR-BILL-004: an unexpected error is the generic 500 with a correlation ID; the session survives")
    @SpecItem("ERR-BILL-004")
    void unexpectedErrorIsTheGenericProblem() throws Exception {
        FAIL.set(true);
        assertGenericProblem(preview(ACCT));
        assertGenericProblem(pay(ACCT, "Y", UUID.randomUUID().toString()));
        assertThat(admin.sql("SELECT COUNT(*) FROM core.[transaction]").query(Integer.class).single()).isZero();
        assertThat(admin.sql("SELECT COUNT(*) FROM billpay.payment").query(Integer.class).single()).isZero();

        FAIL.set(false);
        preview(ACCT).andExpect(status().isOk()).andExpect(jsonPath("$.currentBalance").value("194.00"));
    }
}
