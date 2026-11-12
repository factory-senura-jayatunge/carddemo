package org.carddemo.batch;

import java.util.List;

import org.carddemo.platform.fidelity.FidelitySwitch;

/** The spec items batch-service switches between legacy and fixed behaviour. */
public final class BatchSwitches {

    /**
     * D-11, D-DATA-01. INTCALC picks its rates by group, so its legacy comparison
     * needs the accounts as the legacy file holds them. Exports do not depend on it:
     * they follow each row's provenance.
     */
    public static final FidelitySwitch DEF_ACUP_002 = FidelitySwitch.of("DEF-ACUP-002",
            "dataLoadJob stores positions 103-122 of every account record as read and ignores layout manifests",
            "dataLoadJob moves the group ID from the ZIP position to group_id for the records a layout manifest"
                    + " declares ACUP_UPDATE (every record of the ASCII sample, by the built-in manifest)");

    /** D-59: where the prefix of the legacy interest transaction IDs comes from. */
    public static final FidelitySwitch DEF_JINTC_001 = FidelitySwitch.of("DEF-JINTC-001",
            "the INTCALC PARM as given (carddemo.batch.interest.legacy-parm, the JCL literal)",
            "the cycle's interestParm, derived from its business date");

    /** D-59: how an interest transaction gets its ID; NUM-JINTC-001 is covered by this switch. */
    public static final FidelitySwitch DEF_INTC_010 = FidelitySwitch.of("DEF-INTC-010",
            "the 10-character PARM followed by a 6-digit counter that starts at 000001 in each run",
            "NEXT VALUE FOR core.tran_id_seq, a reserved ID");

    /** D-25: interest on credit (negative) category balances. */
    public static final FidelitySwitch DEF_INTC_012 = FidelitySwitch.of("DEF-INTC-012",
            "a negative category balance with a non-zero rate earns negative interest",
            "only balances that are not negative earn interest; truncation is kept");

    /** D-75: retain the listing columns and mark negative balances in position 33. */
    public static final FidelitySwitch DEF_JPCAT_001 = FidelitySwitch.of("DEF-JPCAT-001",
            "category balances print as an absolute value without a sign",
            "negative category balances have a trailing minus at position 33");

    /**
     * D-43, D-44, D-45, D-75: the statement mode of statementJob (CREASTMT). It covers
     * DEF-STMT-001, 002, 003, 004 and 007, which have no switch of their own: the legacy renderer
     * has only their legacy behaviour, the PDF renderer only their decided one.
     */
    public static final FidelitySwitch DEF_STMT_005 = FidelitySwitch.of("DEF-STMT-005",
            "one statement per card, legacy content, in the legacy text and HTML records: the 51-card,"
                    + " 10-transaction table limit (DEF-STMT-001), truncated names, cities, ZIPs and descriptions"
                    + " (DEF-STMT-002), HTML lines cut at a double space or * (DEF-STMT-003), balances of"
                    + " 1,000,000,000.00 or more without the highest digit (DEF-STMT-004), unescaped HTML (DEF-STMT-007)",
            "one PDF per account per period with the fields of D-43: no limit (D-75), full values (D-44, D-75),"
                    + " no HTML (D-45)");

    /** D-46: a transaction outside the report range; FLOW-TRPT-001 is covered by this switch. */
    public static final FidelitySwitch DEF_TRPT_001 = FidelitySwitch.of("DEF-TRPT-001",
            "the first transaction outside the range ends the report without any total",
            "transactions outside the range are skipped and the end-of-file totals are always written");

    /** D-47: what the "Account Total" line totals, and the report order. */
    public static final FidelitySwitch DEF_TRPT_004 = FidelitySwitch.of("DEF-TRPT-004",
            "one total per card number, in card number order",
            "one total per account, in account order (cards and transaction IDs inside it)");

    public static final List<FidelitySwitch> ALL = List.of(
            DEF_ACUP_002, DEF_JINTC_001, DEF_INTC_010, DEF_INTC_012, DEF_JPCAT_001, DEF_STMT_005,
            DEF_TRPT_001, DEF_TRPT_004);

    private BatchSwitches() {
    }
}
