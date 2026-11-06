package org.carddemo.platform.fidelity;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code carddemo.fidelity.<ITEM-ID>: legacy|fixed} and {@code carddemo.ledger.dir}
 * (environment {@code CARDDEMO_LEDGER_DIR}), the directory of {@code fidelity.toml}.
 */
@ConfigurationProperties(prefix = "carddemo")
public class FidelityProperties {

    private Map<String, String> fidelity = new LinkedHashMap<>();

    private final Ledger ledger = new Ledger();

    public Map<String, String> getFidelity() {
        return fidelity;
    }

    public void setFidelity(Map<String, String> fidelity) {
        this.fidelity = fidelity;
    }

    public Ledger getLedger() {
        return ledger;
    }

    Map<String, FidelityMode> modes() {
        Map<String, FidelityMode> result = new LinkedHashMap<>();
        fidelity.forEach((key, value) -> {
            try {
                result.put(key, FidelityMode.parse(value));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("carddemo.fidelity." + key + ": " + e.getMessage(), e);
            }
        });
        return result;
    }

    public static class Ledger {

        /** Directory of fidelity.toml; default: the nearest modernization/ledger above the working directory. */
        private String dir;

        public String getDir() {
            return dir;
        }

        public void setDir(String dir) {
            this.dir = dir;
        }
    }
}
