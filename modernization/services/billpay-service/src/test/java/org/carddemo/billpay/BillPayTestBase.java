package org.carddemo.billpay;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestClockConfiguration;
import org.carddemo.platform.testsupport.TestDatabase;
import org.carddemo.platform.testsupport.TestTokens;
import org.carddemo.platform.testsupport.TestTokensConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The whole service on MockMvc, as the Bill Pay database login, with test tokens and the test clock. */
@SpringBootTest(classes = BillPayApplication.class, properties = "carddemo.ledger.dir=target/no-ledger")
@AutoConfigureMockMvc
@Import({TestClockConfiguration.class, TestTokensConfiguration.class})
abstract class BillPayTestBase {

    static final TestDatabase DB = TestDatabase.shared();
    static final String USER = "USER0001";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.BILLPAY);
    }

    @Autowired
    MockMvc mvc;

    static String bearer(String token) {
        return "Bearer " + token;
    }

    ResultActions preview(String acctId) throws Exception {
        return preview(acctId, TestTokens.user(USER));
    }

    ResultActions preview(String acctId, String token) throws Exception {
        return mvc.perform(get("/api/billpay/accounts/{acctId}", acctId).header("Authorization", bearer(token)));
    }

    ResultActions pay(String acctId, String confirm, String key) throws Exception {
        return pay(acctId, confirm, key, TestTokens.user(USER));
    }

    ResultActions pay(String acctId, String confirm, String key, String token) throws Exception {
        return mvc.perform(paymentRequest(acctId, confirm, key).header("Authorization", bearer(token)));
    }

    static MockHttpServletRequestBuilder paymentRequest(String acctId, String confirm, String key) {
        MockHttpServletRequestBuilder request = post("/api/billpay/accounts/{acctId}/payments", acctId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirm == null ? "{}" : "{\"confirm\":\"" + confirm + "\"}");
        return key == null ? request : request.header("Idempotency-Key", key);
    }
}
