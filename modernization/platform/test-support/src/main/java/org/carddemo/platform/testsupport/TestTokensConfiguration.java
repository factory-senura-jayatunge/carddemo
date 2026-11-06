package org.carddemo.platform.testsupport;

import java.time.Clock;
import org.carddemo.platform.web.CarddemoSecurityProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Replaces the JWKS decoder with one that trusts {@link TestTokens}. Import it into a Spring test. */
@TestConfiguration(proxyBeanMethods = false)
public class TestTokensConfiguration {

    @Bean
    public JwtDecoder jwtDecoder(CarddemoSecurityProperties properties, Clock clock) {
        return TestTokens.decoder(properties, clock);
    }
}
