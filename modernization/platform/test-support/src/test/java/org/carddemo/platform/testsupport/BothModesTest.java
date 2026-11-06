package org.carddemo.platform.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.FidelitySwitch;
import org.carddemo.platform.fidelity.FidelitySwitchCatalog;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.web.CarddemoSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

@SpringBootTest(classes = BothModesTest.App.class, properties = "carddemo.fidelity.DEF-BILL-002=fixed")
@BothModes("DEF-BILL-010")
class BothModesTest {

    static final FidelitySwitch SWITCHED = FidelitySwitch.of("DEF-BILL-010", "positive", "negative");
    static final FidelitySwitch CONFIGURED = FidelitySwitch.of("DEF-BILL-002", "first card", "other");

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({TestClockConfiguration.class, TestTokensConfiguration.class})
    static class App {
        @Bean
        FidelitySwitchCatalog testSwitches() {
            return FidelitySwitchCatalog.of(SWITCHED, CONFIGURED);
        }
    }

    @Autowired
    FidelitySwitches switches;

    @Autowired
    JwtDecoder decoder;

    @Autowired
    Clock clock;

    @Test
    @SpecItem("DEF-BILL-010")
    void theApplicationSeesTheInvocationsMode() {
        FidelityMode mode = FidelityOverrides.current();
        assertThat(switches.mode(SWITCHED)).isEqualTo(mode);
        assertThat(switches.effectiveModes()).containsEntry("DEF-BILL-010", mode);
        assertThat(switches.mode(CONFIGURED)).isEqualTo(FidelityMode.FIXED);
        assertThatThrownBy(() -> switches.mode("DEF-BILL-099")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testTokensPassTheProductionValidators() {
        assertThat(clock).isSameAs(TestClock.shared());
        Jwt jwt = decoder.decode(TestTokens.user("USER0001"));
        assertThat(jwt.getSubject()).isEqualTo("USER0001");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        assertThat(decoder.decode(TestTokens.admin("ADMIN001")).getClaimAsString("role")).isEqualTo("ADMIN");
        assertThatThrownBy(() -> decoder.decode(TestTokens.builder("USER0001").type("JWT").build()))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(TestTokens.builder("USER0001").signedWithUnknownKey().build()))
                .isInstanceOf(JwtException.class);
        String token = TestTokens.user("USER0001");
        try {
            TestClock.shared().advance(Duration.ofMinutes(7));
            assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
        } finally {
            TestClock.shared().reset();
        }
        assertThat(new CarddemoSecurityProperties().getIssuer()).isEqualTo("carddemo-identity");
    }
}
