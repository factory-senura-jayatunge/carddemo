package org.carddemo.verify.data;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.carddemo.batch.BatchSwitches;
import org.carddemo.platform.fidelity.FidelityLedger;
import org.carddemo.platform.fidelity.FidelityMode;

/**
 * The mode of DEF-ACUP-002 the accounts were loaded in, resolved as batch-service resolves it:
 * an explicit setting ({@code --fidelity DEF-ACUP-002=MODE}, the counterpart of batch-service's
 * {@code --carddemo.fidelity.DEF-ACUP-002=MODE}), else the decision ledger
 * ({@code CARDDEMO_LEDGER_DIR}, else the nearest {@code modernization/ledger}), else legacy.
 *
 * <p>There is deliberately no environment variable: {@code CARDDEMO_FIDELITY_DEF_ACUP_002} in a
 * shared environment reaches every service, and the services that do not declare the switch
 * refuse to start.
 */
record GroupIdMode(FidelityMode mode, String source) {

    static GroupIdMode resolve(String explicit, Map<String, String> env, Path workingDir) {
        if (explicit != null) {
            return new GroupIdMode(FidelityMode.parse(explicit), "--fidelity");
        }
        Optional<Path> ledger = FidelityLedger.locate(env.get("CARDDEMO_LEDGER_DIR"), workingDir);
        if (ledger.isPresent()) {
            FidelityMode mode = FidelityLedger.read(ledger.get()).get(BatchSwitches.DEF_ACUP_002.itemId());
            if (mode != null) {
                return new GroupIdMode(mode, "ledger");
            }
        }
        return new GroupIdMode(FidelityMode.LEGACY, "default");
    }

    String describe() {
        return mode.key() + " (" + source + "): " + (mode == FidelityMode.FIXED
                ? BatchSwitches.DEF_ACUP_002.fixed() : BatchSwitches.DEF_ACUP_002.legacy());
    }
}
