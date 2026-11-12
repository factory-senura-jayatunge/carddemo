package org.carddemo.authorization;

import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestClockConfiguration;
import org.carddemo.platform.testsupport.TestDatabase;
import org.carddemo.platform.testsupport.TestTokensConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole service as the authorization database login, with test tokens and the test clock,
 * without the broker: the listener tests bring their own virtual host.
 */
@SpringBootTest(classes = AuthorizationApplication.class,
        properties = {"carddemo.ledger.dir=target/no-ledger", "carddemo.authorization.listener.enabled=false"})
@Import({TestClockConfiguration.class, TestTokensConfiguration.class})
public abstract class AuthorizationTestBase {

    protected static final TestDatabase DB = TestDatabase.shared();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.AUTHORIZATION);
    }
}
