package org.carddemo.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.carddemo.db.ServiceLogin;
import org.carddemo.identity.fidelity.IdentitySwitches;
import org.carddemo.identity.signon.AttemptThrottle;
import org.carddemo.identity.user.UserRepository;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.FidelitySwitch;
import org.carddemo.platform.testsupport.FidelityOverrides;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.AfterEach;
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
 * clock the tests move and switch modes they set through {@link FidelityOverrides}.
 */
@SpringBootTest(properties = {
        // Strength 12 costs ~250 ms a hash; PasswordHasherTest checks the default.
        "carddemo.identity.bcrypt-strength=4",
        "carddemo.identity.seed.enabled=false"})
@AutoConfigureMockMvc
@Import(IdentityTestBase.TestClock.Config.class)
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

    protected final Switches switches = new Switches();

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

    @AfterEach
    protected void clearOverrides() {
        FidelityOverrides.clear();
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

    /**
     * Sets switch modes through test-support's overrides, over the ledger values the application
     * loaded. Each call replaces the previous overrides.
     */
    public static final class Switches {

        /** Every switch of the service in one mode: the two columns of the scenario tables. */
        public void all(FidelityMode mode) {
            FidelityOverrides.set(IdentitySwitches.ALL.stream().map(FidelitySwitch::itemId).toList(), mode);
        }

        /** Only this switch; the others keep their ledger value. */
        public void only(FidelitySwitch sw, FidelityMode mode) {
            FidelityOverrides.set(List.of(sw.itemId()), mode);
        }
    }
}
