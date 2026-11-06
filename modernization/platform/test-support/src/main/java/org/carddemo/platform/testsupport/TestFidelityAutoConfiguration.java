package org.carddemo.platform.testsupport;

import org.carddemo.platform.fidelity.FidelityAutoConfiguration;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * On the test classpath only: wraps the application's {@link FidelitySwitches} so
 * {@link BothModes} and {@link FidelityOverrides} can change modes without a new context.
 */
@AutoConfiguration(after = FidelityAutoConfiguration.class)
public class TestFidelityAutoConfiguration {

    @Bean
    static BeanPostProcessor fidelityOverridesPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof FidelitySwitches switches && !(bean instanceof OverridableFidelitySwitches)) {
                    return new OverridableFidelitySwitches(switches);
                }
                return bean;
            }
        };
    }
}
