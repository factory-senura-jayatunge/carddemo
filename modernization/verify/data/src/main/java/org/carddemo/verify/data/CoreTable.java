package org.carddemo.verify.data;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.carddemo.batch.data.CoreRows;
import org.carddemo.platform.legacyformat.Masking;
import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.entity.CardRecord;
import org.carddemo.platform.legacyformat.entity.CardXrefRecord;
import org.carddemo.platform.legacyformat.entity.CustomerRecord;
import org.carddemo.platform.legacyformat.entity.TcatBalRecord;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.RecordLayout;

/**
 * The five core tables that dataLoadJob fills, each with its sample file. The key and
 * the control totals are read from the table with plain SQL, independently of the
 * {@link CoreRows} mapping that the re-export uses.
 *
 * @param keySql     the record key as the table holds it, in the file's key order
 * @param sums       control totals: source field to table column
 * @param keyDisplay how a key appears in the report (card numbers: last four)
 */
record CoreTable(String name, String table, String file, RecordLayout layout, int keyLength, String keySql,
                 Map<String, String> sums, UnaryOperator<String> keyDisplay,
                 Function<CoreRows, List<byte[]>> export) {

    static final List<CoreTable> ALL = List.of(
            new CoreTable("account", "core.account", "acctdata.txt", Layouts.ACCOUNT, 11, "acct_id",
                    Map.of("ACCT-CURR-BAL", "curr_bal", "ACCT-CREDIT-LIMIT", "credit_limit",
                            "ACCT-CASH-CREDIT-LIMIT", "cash_credit_limit", "ACCT-CURR-CYC-CREDIT", "curr_cyc_credit",
                            "ACCT-CURR-CYC-DEBIT", "curr_cyc_debit"),
                    UnaryOperator.identity(), rows -> rows.accounts().stream().map(AccountRecord::toBytes).toList()),
            new CoreTable("customer", "core.customer", "custdata.txt", Layouts.CUSTOMER, 9, "cust_id",
                    Map.of(), UnaryOperator.identity(),
                    rows -> rows.customers().stream().map(CustomerRecord::toBytes).toList()),
            new CoreTable("card", "core.card", "carddata.txt", Layouts.CARD, 16, "card_num",
                    Map.of(), Masking::lastFour, rows -> rows.cards().stream().map(CardRecord::toBytes).toList()),
            new CoreTable("card_xref", "core.card_xref", "cardxref.txt", Layouts.CARD_XREF, 16, "card_num",
                    Map.of(), Masking::lastFour,
                    rows -> rows.cardXrefs().stream().map(CardXrefRecord::toBytes).toList()),
            new CoreTable("tcat_bal", "core.tcat_bal", "tcatbal.txt", Layouts.TCAT_BAL, 17, "acct_id + type_cd + cat_cd",
                    Map.of("TRAN-CAT-BAL", "balance"), UnaryOperator.identity(),
                    rows -> rows.categoryBalances().stream().map(TcatBalRecord::toBytes).toList()));
}
