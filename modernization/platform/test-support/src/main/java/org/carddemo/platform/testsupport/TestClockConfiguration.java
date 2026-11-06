package org.carddemo.platform.testsupport;

import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Makes {@link TestClock#shared()} the application clock. Import it into a Spring test. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    @Bean
    public Clock clock() {
        return TestClock.shared();
    }
}
