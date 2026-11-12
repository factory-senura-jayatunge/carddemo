package org.carddemo.account;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestClockConfiguration;
import org.carddemo.platform.testsupport.TestDatabase;
import org.carddemo.platform.testsupport.TestTokensConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The whole service on MockMvc, as the account database login, with test tokens and the test clock. */
@SpringBootTest(classes = AccountApplication.class, properties = "carddemo.ledger.dir=target/no-ledger")
@AutoConfigureMockMvc
@Import({TestClockConfiguration.class, TestTokensConfiguration.class})
abstract class AccountTestBase {

    static final TestDatabase DB = TestDatabase.shared();

    /**
     * Refuses every read of core.account by the login. The login holds SELECT on the account's columns, and
     * in SQL Server a table-level DENY does not override a column-level GRANT, so the denial is on the one
     * column every account read names.
     */
    static final String ACCOUNT_READ = "SELECT ON OBJECT::core.account (acct_id)";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.ACCOUNT);
    }

    @Autowired
    MockMvc mvc;

    static String bearer(String token) {
        return "Bearer " + token;
    }
}
