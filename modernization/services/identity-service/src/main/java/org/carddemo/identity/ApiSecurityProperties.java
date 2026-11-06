package org.carddemo.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The {@code carddemo.security} keys every service shares; identity-service is the issuer. */
@ConfigurationProperties("carddemo.security")
public record ApiSecurityProperties(
        @DefaultValue("carddemo-identity") String issuer,
        @DefaultValue("carddemo-api") String audience,
        @DefaultValue("true") boolean cookieSecure) {
}
