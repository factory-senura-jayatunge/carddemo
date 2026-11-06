package org.carddemo.batch.load;

import java.util.HashSet;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.carddemo.platform.legacyformat.Masking;
import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.entity.CardRecord;
import org.carddemo.platform.legacyformat.entity.CardXrefRecord;
import org.carddemo.platform.legacyformat.entity.CustomerRecord;
import org.carddemo.platform.legacyformat.entity.TcatBalRecord;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.carddemo.platform.legacyformat.record.RecordLayout;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The load steps of the core tables, in the order dataLoadJob runs them (parents first). */
final class CoreTableLoaders {

    private CoreTableLoaders() {
    }

    /** ACCTDATA (JACCT) into core.account, with D-11. */
    static final class Accounts extends TableLoadTasklet {
        Accounts(JdbcClient db, DataLoadProperties p) {
            super(db, p);
        }

        @Override String stepName() { return "loadAccounts"; }
        @Override String fileName() { return "acctdata.txt"; }
        @Override RecordLayout layout() { return Layouts.ACCOUNT; }
        @Override int keyLength() { return 11; }
        @Override String table() { return "core.account"; }

        @Override
        void insert(SourceFile file, SourceLine line) {
            AccountRecord a = file.convert(line, AccountRecord::parse);
            // D-11 moves the value at the ZIP position to group_id; a value at the
            // group position as well would have nowhere to go.
            if (!a.groupId().isBlank()) {
                throw new DataLoadException(file.where(line) + ": ACCT-GROUP-ID is not blank; D-11 expects the"
                        + " group ID at the ZIP position only");
            }
            requireDate(file, line, "ACCT-OPEN-DATE", a.openDate());
            requireDate(file, line, "ACCT-EXPIRAION-DATE", a.expirationDate());
            requireDate(file, line, "ACCT-REISSUE-DATE", a.reissueDate());
            rows.insert(a);
        }
    }

    /** CUSTDATA (JCUST) into core.customer. */
    static final class Customers extends TableLoadTasklet {
        Customers(JdbcClient db, DataLoadProperties p) {
            super(db, p);
        }

        @Override String stepName() { return "loadCustomers"; }
        @Override String fileName() { return "custdata.txt"; }
        @Override RecordLayout layout() { return Layouts.CUSTOMER; }
        @Override int keyLength() { return 9; }
        @Override String table() { return "core.customer"; }

        @Override
        void insert(SourceFile file, SourceLine line) {
            CustomerRecord c = file.convert(line, CustomerRecord::parse);
            requireDate(file, line, "CUST-DOB-YYYY-MM-DD", c.dob());
            rows.insert(c);
        }
    }

    /** CARDDATA (JCARD) into core.card. */
    static final class Cards extends TableLoadTasklet {
        Cards(JdbcClient db, DataLoadProperties p) {
            super(db, p);
        }

        @Override String stepName() { return "loadCards"; }
        @Override String fileName() { return "carddata.txt"; }
        @Override RecordLayout layout() { return Layouts.CARD; }
        @Override int keyLength() { return 16; }
        @Override String table() { return "core.card"; }
        @Override UnaryOperator<String> keyDisplay() { return Masking::lastFour; }

        @Override
        void insert(SourceFile file, SourceLine line) {
            CardRecord c = file.convert(line, CardRecord::parse);
            // The column keeps the text as read (DEF-CRDU-009), but its check needs the shape.
            if (!c.expirationDate().matches("\\d{4}-\\d{2}-\\d{2}")) {
                throw new DataLoadException(file.where(line) + ": CARD-EXPIRAION-DATE is not YYYY-MM-DD");
            }
            rows.insert(c);
        }
    }

    /** CARDXREF (JXREF) into core.card_xref; every account and customer it names must be loaded. */
    static final class CardXrefs extends TableLoadTasklet {
        private Set<String> accounts = Set.of();
        private Set<String> customers = Set.of();

        CardXrefs(JdbcClient db, DataLoadProperties p) {
            super(db, p);
        }

        @Override String stepName() { return "loadCardXref"; }
        @Override String fileName() { return "cardxref.txt"; }
        @Override RecordLayout layout() { return Layouts.CARD_XREF; }
        @Override int keyLength() { return 16; }
        @Override String table() { return "core.card_xref"; }
        @Override UnaryOperator<String> keyDisplay() { return Masking::lastFour; }

        @Override
        void beforeInsert(SourceFile file) {
            accounts = new HashSet<>(db.sql("SELECT acct_id FROM core.account").query(String.class).list());
            customers = new HashSet<>(db.sql("SELECT cust_id FROM core.customer").query(String.class).list());
        }

        @Override
        void insert(SourceFile file, SourceLine line) {
            CardXrefRecord x = file.convert(line, CardXrefRecord::parse);
            String card = Masking.lastFour(x.cardNum());
            if (!accounts.contains(x.acctId())) {
                throw new DataLoadException(file.where(line) + ": card " + card + " names account " + x.acctId()
                        + ", which is not loaded");
            }
            if (!customers.contains(x.custId())) {
                throw new DataLoadException(file.where(line) + ": card " + card + " names customer " + x.custId()
                        + ", which is not loaded");
            }
            rows.insert(x);
        }
    }

    /** TCATBALF (JTCAT) into core.tcat_bal; every account must be loaded. */
    static final class CategoryBalances extends TableLoadTasklet {
        private Set<String> accounts = Set.of();

        CategoryBalances(JdbcClient db, DataLoadProperties p) {
            super(db, p);
        }

        @Override String stepName() { return "loadCategoryBalances"; }
        @Override String fileName() { return "tcatbal.txt"; }
        @Override RecordLayout layout() { return Layouts.TCAT_BAL; }
        @Override int keyLength() { return 17; }
        @Override String table() { return "core.tcat_bal"; }

        @Override
        void beforeInsert(SourceFile file) {
            accounts = new HashSet<>(db.sql("SELECT acct_id FROM core.account").query(String.class).list());
        }

        @Override
        void insert(SourceFile file, SourceLine line) {
            TcatBalRecord t = file.convert(line, TcatBalRecord::parse);
            if (!accounts.contains(t.acctId())) {
                throw new DataLoadException(file.where(line) + ": account " + t.acctId() + " is not loaded");
            }
            rows.insert(t);
        }
    }
}
