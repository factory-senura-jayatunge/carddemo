package org.carddemo.batch.statement;

import org.carddemo.batch.BatchSwitches;
import org.carddemo.platform.fidelity.FidelitySwitches;

/**
 * How statementJob reads its one code switch, {@link BatchSwitches#DEF_STMT_005} (switches.md;
 * batch.md, "Build rulings", Q1). DEF-STMT-001, 002, 003, 004 and 007 are covered by it and have
 * no switch of their own.
 */
final class StatementSwitches {

    private StatementSwitches() {
    }

    /** The mode a new run captures with. */
    static StatementRuns.Mode mode(FidelitySwitches switches) {
        return switches.isFixed(BatchSwitches.DEF_STMT_005) ? StatementRuns.Mode.FIXED : StatementRuns.Mode.LEGACY;
    }
}
