package org.carddemo.verify.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.carddemo.batch.data.CoreRows;
import org.carddemo.identity.IdentityProperties;
import org.carddemo.identity.fidelity.IdentityFidelity;
import org.carddemo.identity.fidelity.IdentitySwitches;
import org.carddemo.identity.user.PasswordHasher;
import org.carddemo.identity.user.UserRepository;
import org.carddemo.identity.user.UserSeeder;
import org.carddemo.platform.fidelity.FidelityLedger;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.FidelitySwitch;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.legacyformat.Masking;
import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.entity.CardRecord;
import org.carddemo.platform.legacyformat.entity.CardXrefRecord;
import org.carddemo.platform.legacyformat.entity.CustomerRecord;
import org.carddemo.platform.legacyformat.entity.TcatBalRecord;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.carddemo.platform.testsupport.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class DataReconciliationTest {

    private static final TestDatabase DB = TestDatabase.shared();
    private static final Path ROOT = Path.of(System.getProperty("carddemo.repo.root", "../../..")).toAbsolutePath().normalize();
    private static final Path SAMPLE = ROOT.resolve("app/data/ASCII");
    private static final Path USERS = ROOT.resolve("app/jcl/DUSRSECJ.jcl");
    /** The public demo password of the DUSRSECJ users (README); verify-data never compares passwords. */
    private static final String DEMO_PASSWORD = "PASSWORD";

    private final JdbcClient admin = DB.admin();

    @BeforeEach
    void loadTheSample() {
        DB.fixtures().deleteAll();
        CoreRows rows = new CoreRows(admin);
        lines("acctdata.txt", Layouts.ACCOUNT.length()).forEach(l -> rows.insert(AccountRecord.parse(l.record())));
        lines("custdata.txt", Layouts.CUSTOMER.length()).forEach(l -> rows.insert(CustomerRecord.parse(l.record())));
        lines("carddata.txt", Layouts.CARD.length()).forEach(l -> rows.insert(CardRecord.parse(l.record())));
        lines("cardxref.txt", Layouts.CARD_XREF.length()).forEach(l -> rows.insert(CardXrefRecord.parse(l.record())));
        lines("tcatbal.txt", Layouts.TCAT_BAL.length()).forEach(l -> rows.insert(TcatBalRecord.parse(l.record())));
        seedUsersAsIdentityServiceDoes();
    }

    /** What identity-service does at its first start in profile demo (D-06), with the ledger's switch modes. */
    private void seedUsersAsIdentityServiceDoes() {
        admin.sql("DELETE FROM iam.session").update();
        admin.sql("DELETE FROM iam.audit_event").update();
        admin.sql("DELETE FROM iam.app_user").update();
        // As FidelityAutoConfiguration: the ledger covers every unit, only identity's switches apply.
        Map<String, FidelityMode> ledger = new HashMap<>(
                FidelityLedger.locate(null, ROOT).map(FidelityLedger::read).orElseThrow());
        ledger.keySet().retainAll(IdentitySwitches.ALL.stream().map(FidelitySwitch::itemId).toList());
        FidelitySwitches switches = FidelitySwitches.of(IdentitySwitches.ALL, ledger);
        IdentityProperties props = new IdentityProperties(null, null, null, null, 4, null, null,
                new IdentityProperties.Seed(true, true, DEMO_PASSWORD, null), null);
        int added = new UserSeeder(new UserRepository(admin), new PasswordHasher(4), new IdentityFidelity(switches),
                props, Clock.systemUTC()).seedIfEmpty();
        assertThat(added).isEqualTo(10);
    }

    private static List<SourceLine> lines(String file, int length) {
        return LegacyFileReader.read(SAMPLE.resolve(file), length);
    }

    private Report reconcile() {
        return new DataReconciliation(admin, SAMPLE, USERS).run();
    }

    private static String failure(Report report, String subject) {
        return report.failures().stream().filter(l -> l.subject().equals(subject)).map(Report.Line::detail)
                .findFirst().orElse(null);
    }

    @Test
    void theLoadedSampleReconciles() {
        Report report = reconcile();

        assertThat(report.failures()).isEmpty();
        assertThat(report.lines()).filteredOn(l -> l.outcome() == Report.Outcome.OK)
                .extracting(Report.Line::subject)
                .contains("core.account", "core.customer", "core.card", "core.card_xref", "core.tcat_bal",
                        "card has xref", "card account = xref", "tcat_bal account exists", "iam.app_user");
        assertThat(report.lines()).filteredOn(l -> l.subject().equals("core.account"))
                .extracting(Report.Line::detail).first().asString().contains("50 rows").contains("5 control totals");
    }

    @Test
    void aChangedBalanceFailsTheTotalAndNamesTheKeyAndField() {
        admin.sql("UPDATE core.account SET curr_bal = curr_bal + 1 WHERE acct_id = '00000000007'").update();

        String detail = failure(reconcile(), "core.account");

        assertThat(detail).contains("total ACCT-CURR-BAL differs")
                .contains("1 records re-export differently: 00000000007 [ACCT-CURR-BAL]");
    }

    @Test
    void aMissingCrossReferenceIsReportedWithTheLastFourDigitsOnly() throws Exception {
        String card = Files.readAllLines(SAMPLE.resolve("cardxref.txt")).get(4).substring(0, 16);
        admin.sql("DELETE FROM core.card_xref WHERE card_num = :c").param("c", card).update();

        Report report = reconcile();

        assertThat(failure(report, "core.card_xref")).contains("count 49, file 50")
                .contains("missing keys " + Masking.lastFour(card)).doesNotContain(card);
        assertThat(failure(report, "card has xref")).contains("1 violations").doesNotContain(card);
        report.lines().forEach(l -> assertThat(l.detail()).doesNotContain(card));
    }

    @Test
    void aCardOnAnotherAccountThanItsCrossReferenceFails() throws Exception {
        String card = Files.readAllLines(SAMPLE.resolve("carddata.txt")).get(0).substring(0, 16);
        admin.sql("UPDATE core.card SET acct_id = '99999999999' WHERE card_num = :c").param("c", card).update();

        Report report = reconcile();

        assertThat(failure(report, "card account = xref")).contains("1 violations");
        assertThat(failure(report, "core.card")).contains("[CARD-ACCT-ID]").doesNotContain(card);
    }

    @Test
    void theSeedUsersComeFromTheInlineDataOfDusrsecj() {
        var users = DataReconciliation.seedUsers(USERS);

        assertThat(users).hasSize(10);
        assertThat(users.getFirst().userId()).isEqualTo("ADMIN001");
        assertThat(users.getFirst().userType()).isEqualTo("A");
        assertThat(users.getLast().userId()).isEqualTo("USER0005");
        assertThat(users.getLast().userType()).isEqualTo("U");
    }

    @Test
    void aMissingUserAndAChangedNameFailAndNameTheUsersAndFields() {
        admin.sql("DELETE FROM iam.app_user WHERE user_id = 'USER0005'").update();
        admin.sql("UPDATE iam.app_user SET last_name = 'OTHER' WHERE user_id = 'ADMIN002'").update();

        assertThat(failure(reconcile(), "iam.app_user")).contains("missing users USER0005")
                .contains("ADMIN002 [last_name]");
    }

    @Test
    void anEmptyUserTableFailsRatherThanSkips() {
        admin.sql("DELETE FROM iam.app_user").update();

        assertThat(failure(reconcile(), "iam.app_user")).contains("missing users ADMIN001");
    }
}
