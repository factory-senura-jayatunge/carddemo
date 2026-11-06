package org.carddemo.identity;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("carddemo.identity")
public record IdentityProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue("300s") Duration accessTokenTtl,
        @DefaultValue("15m") Duration idleTimeout,
        @DefaultValue("8h") Duration sessionMaxAge,
        @DefaultValue("12") int bcryptStrength,
        @DefaultValue Lockout lockout,
        @DefaultValue Seed seed,
        @DefaultValue Features features) {

    /** {@code private-key-path}: the PKCS#8 PEM that make setup writes (CARDDEMO_JWT_KEY_FILE). */
    public record Jwt(String privateKeyPath) {
    }

    /** Fixed mode of DEF-SGN-002. The rate limits count failed attempts only. */
    public record Lockout(
            @DefaultValue("5") int maxFailures,
            @DefaultValue("15m") Duration lockDuration,
            @DefaultValue("10") int failuresPerUserPerMinute,
            @DefaultValue("30") int failuresPerClientPerMinute) {
    }

    /**
     * D-06. With {@code demoUsers} (profile demo) the ten DUSRSECJ users get {@code demoPassword};
     * otherwise one ADMIN001 with {@code bootstrapAdminPassword} that must be changed.
     */
    public record Seed(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("false") boolean demoUsers,
            String demoPassword,
            String bootstrapAdminPassword) {
    }

    /** What GET /api/features reports. */
    public record Features(
            @DefaultValue("false") boolean authorization,
            @DefaultValue("false") boolean transactionTypes,
            @DefaultValue("BILL") List<String> converted) {
    }
}
