package org.carddemo.authorization;

import org.carddemo.platform.testsupport.TestClockConfiguration;
import org.carddemo.platform.testsupport.TestTokensConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole service without a database or a broker, for what is decided before a repository is
 * called: the security chain and the OpenAPI export. The data source points at a closed port, so a
 * test that reaches the database fails instead of touching one.
 */
@SpringBootTest(classes = AuthorizationApplication.class, properties = {
    "carddemo.ledger.dir=target/no-ledger",
    "carddemo.authorization.listener.enabled=false",
    "spring.datasource.url=jdbc:sqlserver://127.0.0.1:1;databaseName=none;encrypt=false;loginTimeout=1",
    "spring.datasource.username=none",
    "spring.datasource.password=none",
})
@AutoConfigureMockMvc
@Import({TestClockConfiguration.class, TestTokensConfiguration.class})
public abstract class AuthorizationWebTestBase {

    @Autowired
    protected MockMvc mvc;
}
