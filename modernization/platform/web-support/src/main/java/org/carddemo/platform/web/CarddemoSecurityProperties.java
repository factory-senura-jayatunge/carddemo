package org.carddemo.platform.web;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code carddemo.security.*}: how services validate identity-service tokens and set cookies. */
@ConfigurationProperties(prefix = "carddemo.security")
public class CarddemoSecurityProperties {

    public static final String ACCESS_TOKEN_COOKIE = "cd_at";
    public static final String CSRF_COOKIE = "XSRF-TOKEN";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";

    /** The identity-service JWKS endpoint; keys are fetched on the first token and cached. */
    private URI jwksUri = URI.create("http://127.0.0.1:28081/api/auth/.well-known/jwks.json");

    /** Required {@code iss} claim. */
    private String issuer = "carddemo-identity";

    /** Required {@code aud} value. */
    private String audience = "carddemo-api";

    /** {@code Secure} on the cookies this process sets; off only for plain http on 127.0.0.1 (the demo profile). */
    private boolean cookieSecure = true;

    public URI getJwksUri() {
        return jwksUri;
    }

    public void setJwksUri(URI jwksUri) {
        this.jwksUri = jwksUri;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getAudience() {
        return audience;
    }

    public void setAudience(String audience) {
        this.audience = audience;
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }
}
