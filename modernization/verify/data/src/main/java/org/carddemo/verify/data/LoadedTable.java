package org.carddemo.verify.data;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.carddemo.batch.data.CoreRows;
import org.carddemo.batch.data.RefRows;
import org.carddemo.platform.legacyformat.Masking;
import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.entity.CardRecord;
import org.carddemo.platform.legacyformat.entity.CardXrefRecord;
import org.carddemo.platform.legacyformat.entity.CustomerRecord;
import org.carddemo.platform.legacyformat.entity.DiscGroupRecord;
import org.carddemo.platform.legacyformat.entity.TcatBalRecord;
import org.carddemo.platform.legacyformat.entity.TranCategoryRecord;
import org.carddemo.platform.legacyformat.entity.TranTypeRecord;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.RecordLayout;

/**
 * The core and reference tables that dataLoadJob fills from a sample file, each with
 * its file. The key and the control totals are read from the table with plain SQL,
 * independently of the {@link CoreRows} and {@link RefRows} mappings that the
 * re-export uses.
 *
 * @param keySql     the record key as the table holds it, in the file's key order
 * @param sums       control totals: source field to table column
 * @param keyDisplay how a key appears in the report (card numbers: last four)
 */
record LoadedTable(String name, String table, String file, RecordLayout layout, int keyLength, String keySql,
                   Map<String, String> sums, UnaryOperator<String> keyDisplay,
                   Function<Rows, List<byte[]>> export) {

    /** The two mappings a re-export reads with. */
    record Rows(CoreRows core, RefRows ref) {
    }

    static final List<LoadedTable> ALL = List.of(
            new LoadedTable("account", "core.account", "acctdata.txt", Layouts.ACCOUNT, 11, "acct_id",
                    Map.of("ACCT-CURR-BAL", "curr_bal", "ACCT-CREDIT-LIMIT", "credit_limit",
                            "ACCT-CASH-CREDIT-LIMIT", "cash_credit_limit", "ACCT-CURR-CYC-CREDIT", "curr_cyc_credit",
                            "ACCT-CURR-CYC-DEBIT", "curr_cyc_debit"),
                    UnaryOperator.identity(), rows -> rows.core().accounts().stream().map(AccountRecord::toBytes).toList()),
            new LoadedTable("customer", "core.customer", "custdata.txt", Layouts.CUSTOMER, 9, "cust_id",
                    Map.of(), UnaryOperator.identity(),
                    rows -> rows.core().customers().stream().map(CustomerRecord::toBytes).toList()),
            new LoadedTable("card", "core.card", "carddata.txt", Layouts.CARD, 16, "card_num",
                    Map.of(), Masking::lastFour, rows -> rows.core().cards().stream().map(CardRecord::toBytes).toList()),
            new LoadedTable("card_xref", "core.card_xref", "cardxref.txt", Layouts.CARD_XREF, 16, "card_num",
                    Map.of(), Masking::lastFour,
                    rows -> rows.core().cardXrefs().stream().map(CardXrefRecord::toBytes).toList()),
            new LoadedTable("tcat_bal", "core.tcat_bal", "tcatbal.txt", Layouts.TCAT_BAL, 17, "acct_id + type_cd + cat_cd",
                    Map.of("TRAN-CAT-BAL", "balance"), UnaryOperator.identity(),
                    rows -> rows.core().categoryBalances().stream().map(TcatBalRecord::toBytes).toList()),
            new LoadedTable("tran_type", "ref.tran_type", "trantype.txt", Layouts.TRAN_TYPE, 2, "type_cd",
                    Map.of(), UnaryOperator.identity(),
                    rows -> rows.ref().transactionTypes().stream().map(TranTypeRecord::toBytes).toList()),
            new LoadedTable("tran_category", "ref.tran_category", "trancatg.txt", Layouts.TRAN_CATEGORY, 6,
                    "type_cd + cat_cd", Map.of(), UnaryOperator.identity(),
                    rows -> rows.ref().categories().stream().map(TranCategoryRecord::toBytes).toList()),
            // group_id is CHAR(10), so the concatenation keeps its trailing blanks as the file key does.
            new LoadedTable("disclosure_group", "ref.disclosure_group", "discgrp.txt", Layouts.DISC_GROUP, 16,
                    "group_id + type_cd + cat_cd", Map.of("DIS-INT-RATE", "int_rate"), UnaryOperator.identity(),
                    rows -> rows.ref().disclosureGroups().stream().map(DiscGroupRecord::toBytes).toList()));
}
