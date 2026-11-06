package org.carddemo.platform.fidelity;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(FidelityProperties.class)
public class FidelityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FidelitySwitches.class);

    @Bean
    @ConditionalOnMissingBean
    public FidelitySwitches fidelitySwitches(ObjectProvider<FidelitySwitchCatalog> catalogs, FidelityProperties properties) {
        List<FidelitySwitch> declared = catalogs.orderedStream().flatMap(c -> c.switches().stream()).toList();
        Set<String> ids = declared.stream().map(FidelitySwitch::itemId).collect(Collectors.toSet());
        Map<String, FidelityMode> modes = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        Optional<Path> ledger = FidelityLedger.locate(properties.getLedger().getDir(), Path.of(""));
        if (ledger.isPresent()) {
            // The ledger covers every unit; only the switches this process declares apply here.
            FidelityLedger.read(ledger.get()).forEach((id, mode) -> {
                if (ids.contains(id)) {
                    modes.put(id, mode);
                    sources.put(id, "ledger");
                }
            });
        } else if (!declared.isEmpty()) {
            log.warn("fidelity: no {} found (carddemo.ledger.dir={}); switches without a property are legacy",
                    FidelityLedger.FILE_NAME, properties.getLedger().getDir());
        }
        properties.modes().forEach((key, mode) -> {
            String id = SpecItemIds.normalizeKey(key);
            modes.remove(id);
            modes.put(key, mode);
            sources.put(id, "property");
        });
        FidelitySwitches switches = FidelitySwitches.of(declared, modes);
        switches.effectiveModes().forEach((id, mode) ->
                log.info("fidelity {}={} ({})", id, mode.key(), sources.getOrDefault(id, "default")));
        return switches;
    }
}
