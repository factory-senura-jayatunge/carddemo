package org.carddemo.platform.fidelity;

import java.util.Optional;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.support.ParameterInfo;

/** Kept apart from {@link SpecItemExtension} so tests without junit-jupiter-params still load the extension. */
final class ParameterizedMode {

    private ParameterizedMode() {
    }

    static Optional<FidelityMode> of(ExtensionContext context) {
        for (ExtensionContext c = context; c != null; c = c.getParent().orElse(null)) {
            ParameterInfo info = ParameterInfo.get(c);
            if (info != null) {
                for (Object argument : info.getArguments().toList()) {
                    if (argument instanceof FidelityMode mode) {
                        return Optional.of(mode);
                    }
                }
            }
        }
        return Optional.empty();
    }
}
