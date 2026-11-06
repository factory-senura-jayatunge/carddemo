package org.carddemo.batch;

import org.carddemo.platform.fidelity.FidelitySwitch;
import org.carddemo.platform.fidelity.FidelitySwitchCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class BatchFidelityConfiguration {

    @Bean
    FidelitySwitchCatalog batchSwitchCatalog() {
        return FidelitySwitchCatalog.of(BatchSwitches.ALL.toArray(FidelitySwitch[]::new));
    }
}
