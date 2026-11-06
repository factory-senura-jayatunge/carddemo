package org.carddemo.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import org.carddemo.db.ServiceLogin;
import org.carddemo.identity.fidelity.IdentitySwitches;
import org.carddemo.identity.signon.AttemptThrottle;
import org.carddemo.identity.user.UserRepository;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.FidelitySwitch;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * One application context for the API tests: the real service on its own migrated database,
 * connected as {@code carddemo_identity} so every test runs under the service's grants, with a
 * clock and fidelity switches the tests set.
 */
@SpringBootTest(properties = {
        // Strength 12 costs ~250 ms a hash; PasswordHasherTest checks the default.
        "carddemo.identity.bcrypt-strength=4",
        "carddemo.identity.seed.enabled=false"})
@AutoConfigureMockMvc
@Import({IdentityTestBase.TestClock.Config.class, IdentityTestBase.TestSwitches.Config.class})
public abstract class IdentityTestBase {

    public static final Instant START = Instant.parse("2026-03-02T09:00:00Z");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.shared().register(registry, ServiceLogin.IDENTITY);
        registry.add("carddemo.identity.jwt.private-key-path", TestKeys::path);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected TestClock clock;

    @Autowired
    protected TestSwitches switches;

    @Autowired
    protected AttemptThrottle throttle;

    @MockitoSpyBean
    protected UserRepository userRepository;

    protected final IdentityFixtures db = new IdentityFixtures(TestDatabase.shared().admin());

    @BeforeEach
    protected void resetState() {
        db.deleteAll();
        clock.set(START);
        throttle.reset();
        switches.all(FidelityMode.LEGACY);
    }

    protected ApiClient client() {
        return new ApiClient(mvc);
    }

    /** A clock the tests move. */
    public static final class TestClock extends Clock {

        private final AtomicReference<Instant> now = new AtomicReference<>(START);

        public void set(Instant instant) {
            now.set(instant);
        }

        public void advance(Duration d) {
            now.updateAndGet(i -> i.plus(d));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @TestConfiguration(proxyBeanMethods = false)
        public static class Config {
            @Bean
            @Primary
            TestClock testClock() {
                return new TestClock();
            }
        }
    }

    /** The service's switches, set per test. Replaces the platform bean, so ledger values do not apply. */
    public static final class TestSwitches implements FidelitySwitches {

        private volatile FidelitySwitches current = FidelitySwitches.allLegacy(IdentitySwitches.ALL);

        /** Every switch of the service in one mode: the two columns of the scenario tables. */
        public void all(FidelityMode mode) {
            FidelitySwitches s = FidelitySwitches.allLegacy(IdentitySwitches.ALL);
            for (FidelitySwitch sw : IdentitySwitches.ALL) {
                s = s.with(sw, mode);
            }
            current = s;
        }

        public void set(FidelitySwitch sw, FidelityMode mode) {
            current = current.with(sw, mode);
        }

        @Override
        public FidelityMode mode(String itemId) {
            return current.mode(itemId);
        }

        @Override
        public java.util.Map<String, FidelityMode> effectiveModes() {
            return current.effectiveModes();
        }

        @Override
        public java.util.Collection<FidelitySwitch> declared() {
            return current.declared();
        }

        @Override
        public FidelitySwitches with(FidelitySwitch fidelitySwitch, FidelityMode mode) {
            return current.with(fidelitySwitch, mode);
        }

        @TestConfiguration(proxyBeanMethods = false)
        public static class Config {
            @Bean
            @Primary
            TestSwitches testSwitches() {
                return new TestSwitches();
            }
        }
    }
}
