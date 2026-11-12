package org.carddemo.verify.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.carddemo.batch.data.AccountLayouts;
import org.carddemo.batch.data.CoreRows;
import org.carddemo.batch.data.RefRows;
import org.carddemo.batch.data.ZipGroup;
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
import org.carddemo.platform.legacyformat.entity.DiscGroupRecord;
import org.carddemo.platform.legacyformat.entity.TcatBalRecord;
import org.carddemo.platform.legacyformat.entity.TranCategoryRecord;
import org.carddemo.platform.legacyformat.entity.TranTypeRecord;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.LegacyFileReader;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.carddemo.platform.testsupport.TestDatabase;
import org.carddemo.platform.validation.RuleTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class DataReconciliationTest {

    private static final TestDatabase DB = TestDatabase.shared();
    private static final Path ROOT = Path.of(System.getProperty("carddemo.repo.root", "../../..")).toAbsolutePath().normalize();
    private static final Path SAMPLE = ROOT.resolve("app/data/ASCII");
    private static final Path USERS = ROOT.resolve("app/jcl/DUSRSECJ.jcl");
    /** The public demo password of the DUSRSECJ users (README); verify-data never compares passwords. */
    private static final String DEMO_PASSWORD = "PASSWORD";

    /** The mode of DEF-ACUP-002 that dataLoadJob reads from the ledger. */
    private static final FidelityMode LEDGER_MODE = GroupIdMode.resolve(null, Map.of(), ROOT).mode();

    private final JdbcClient admin = DB.admin();

    @BeforeEach
    void loadTheSample() {
        DB.fixtures().deleteAll();
        loadReferenceTables();
        CoreRows rows = new CoreRows(admin);
        loadAccounts(rows, LEDGER_MODE);
        lines("custdata.txt", Layouts.CUSTOMER.length()).forEach(l -> rows.insert(CustomerRecord.parse(l.record())));
        lines("carddata.txt", Layouts.CARD.length()).forEach(l -> rows.insert(CardRecord.parse(l.record())));
        lines("cardxref.txt", Layouts.CARD_XREF.length()).forEach(l -> rows.insert(CardXrefRecord.parse(l.record())));
        lines("tcatbal.txt", Layouts.TCAT_BAL.length()).forEach(l -> rows.insert(TcatBalRecord.parse(l.record())));
        seedUsersAsIdentityServiceDoes();
    }

    /** As dataLoadJob's reference steps: the three files, then the validators' lookup lists. */
    private void loadReferenceTables() {
        for (String table : List.of("ref.disclosure_group", "ref.tran_category", "ref.tran_type",
                "ref.us_state_zip_prefix", "ref.us_state", "ref.nanp_area_code")) {
            admin.sql("DELETE FROM " + table).update();
        }
        RefRows ref = new RefRows(admin);
        lines("trantype.txt", Layouts.TRAN_TYPE.length()).forEach(l -> ref.insert(TranTypeRecord.parse(l.record())));
        lines("trancatg.txt", Layouts.TRAN_CATEGORY.length())
                .forEach(l -> ref.insert(TranCategoryRecord.parse(l.record())));
        lines("discgrp.txt", Layouts.DISC_GROUP.length()).forEach(l -> ref.insert(DiscGroupRecord.parse(l.record())));
        RuleTable rules = RuleTable.standard();
        rules.list("usStateCodes").forEach(ref::insertState);
        rules.list("usStateZipPrefixes").forEach(ref::insertStateZipPrefix);
        rules.list("nanpGeneralPurposeAreaCodes").forEach(ref::insertAreaCode);
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
        TransactionTemplate tx = new TransactionTemplate(new JdbcTransactionManager(DB.adminDataSource()));
        int added = new UserSeeder(new UserRepository(admin), new PasswordHasher(4), new IdentityFidelity(switches),
                props, Clock.systemUTC(), tx).seedIfEmpty();
        assertThat(added).isEqualTo(10);
    }

    /** As dataLoadJob's account step: each record stored in the layout the mode and the built-in manifest give it. */
    private static void loadAccounts(CoreRows rows, FidelityMode mode) {
        List<SourceLine> source = lines("acctdata.txt", Layouts.ACCOUNT.length());
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(SAMPLE.resolve("acctdata.txt"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        Set<String> ids = source.stream().map(l -> new String(l.record(), 0, 11, StandardCharsets.ISO_8859_1))
                .collect(Collectors.toSet());
        AccountLayouts layouts = AccountLayouts.resolve(mode, bytes, ids, null);
        for (SourceLine l : source) {
            AccountRecord a = AccountRecord.parse(l.record());
            rows.insert(a, ZipGroup.classify(ZipGroup.asRead(a), layouts.layoutOf(a.acctId())));
        }
    }

    private static FidelityMode other(FidelityMode mode) {
        return mode == FidelityMode.LEGACY ? FidelityMode.FIXED : FidelityMode.LEGACY;
    }

    private static List<SourceLine> lines(String file, int length) {
        return LegacyFileReader.read(SAMPLE.resolve(file), length);
    }

    private Report reconcile() {
        return reconcile(LEDGER_MODE);
    }

    private Report reconcile(FidelityMode groupIdMode) {
        return new DataReconciliation(admin, SAMPLE, USERS, groupIdMode).run();
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
                        "ref.tran_type", "ref.tran_category", "ref.disclosure_group", "ref.us_state",
                        "ref.us_state_zip_prefix", "ref.nanp_area_code", "card has xref", "card account = xref",
                        "tcat_bal account exists", "DEFAULT disc group", "iam.app_user");
        assertThat(report.lines()).filteredOn(l -> l.subject().equals("ref.disclosure_group"))
                .extracting(Report.Line::detail).first().asString().contains("51 rows").contains("1 control total");
        assertThat(report.lines()).filteredOn(l -> l.subject().equals("ref.us_state_zip_prefix"))
                .extracting(Report.Line::detail).first().asString().contains("240 codes");
        assertThat(report.lines()).filteredOn(l -> l.subject().equals("core.account"))
                .extracting(Report.Line::detail).first().asString().contains("50 rows").contains("5 control totals");
        assertThat(report.lines()).filteredOn(l -> l.subject().equals("core.customer FILLER"))
                .extracting(Report.Line::detail).containsExactly("customer FILLER normalised: 0");
    }

    /**
     * DEF-ACUP-020: a source whose customers hold residue at 333-500 reconciles with the loaded
     * table, whose export writes spaces there; verify-data counts the lines itself.
     */
    @Test
    void customerResidueAt333To500IsCountedAndComparedAsSpaces(@TempDir Path dir) throws Exception {
        for (String name : List.of("acctdata.txt", "carddata.txt", "cardxref.txt", "tcatbal.txt", "trantype.txt",
                "trancatg.txt", "discgrp.txt")) {
            Files.copy(SAMPLE.resolve(name), dir.resolve(name));
        }
        List<String> customers = new ArrayList<>(Files.readAllLines(SAMPLE.resolve("custdata.txt"),
                StandardCharsets.ISO_8859_1));
        String residue = "Residue text left by a save";
        customers.set(1, (customers.get(1) + " ".repeat(500)).substring(0, 332) + "\0".repeat(168));
        customers.set(4, (customers.get(4) + " ".repeat(500)).substring(0, 332) + (residue + " ".repeat(168)).substring(0, 168));
        Files.writeString(dir.resolve("custdata.txt"), String.join("\n", customers) + "\n", StandardCharsets.ISO_8859_1);

        Report report = new DataReconciliation(admin, dir, USERS, LEDGER_MODE).run();

        assertThat(report.failures()).isEmpty();
        assertThat(report.lines()).filteredOn(l -> l.subject().equals("core.customer FILLER"))
                .extracting(Report.Line::detail).containsExactly("customer FILLER normalised: 2 (positions 333-500 not"
                        + " all spaces, compared as spaces): custdata.txt lines 2, 5");
        for (int i : new int[] {1, 4}) {
            String id = customers.get(i).substring(0, 9);
            report.lines().forEach(l -> assertThat(l.detail()).doesNotContain(id));
        }
        report.lines().forEach(l -> assertThat(l.detail()).doesNotContain(residue).doesNotContain("\0"));
    }

    @Test
    void theLedgerLoadsTheAccountsWithTheGroupIdMoved() {
        assertThat(LEDGER_MODE).isEqualTo(FidelityMode.FIXED);
        assertThat(reconcile().lines()).filteredOn(l -> l.subject().equals("DEF-ACUP-002"))
                .extracting(Report.Line::detail).first().asString().startsWith("fixed (given)");
    }

    @Test
    void accountsLoadedInTheOtherModeReconcileWhenVerifiedInThatMode() {
        admin.sql("DELETE FROM core.tcat_bal").update();
        admin.sql("DELETE FROM core.card_xref").update();
        admin.sql("DELETE FROM core.card").update();
        admin.sql("DELETE FROM core.account").update();
        CoreRows rows = new CoreRows(admin);
        loadAccounts(rows, other(LEDGER_MODE));
        lines("carddata.txt", Layouts.CARD.length()).forEach(l -> rows.insert(CardRecord.parse(l.record())));
        lines("cardxref.txt", Layouts.CARD_XREF.length()).forEach(l -> rows.insert(CardXrefRecord.parse(l.record())));
        lines("tcatbal.txt", Layouts.TCAT_BAL.length()).forEach(l -> rows.insert(TcatBalRecord.parse(l.record())));

        assertThat(reconcile(other(LEDGER_MODE)).failures()).isEmpty();
        Report wrongMode = reconcile(LEDGER_MODE);
        assertThat(wrongMode.failures()).as("verified in the wrong mode: the re-export has no mode, check 6 fails")
                .extracting(Report.Line::subject).containsExactly(DataReconciliation.ZIP_GROUP);
        assertThat(failure(wrongMode, DataReconciliation.ZIP_GROUP)).startsWith("50 records differ: acctdata.txt line 1 [")
                .contains("zip_group_origin");
    }

    @Test
    void theLedgerModeStoresEveryAccountMigratedAndCheck6SaysSo() {
        assertThat(reconcile().lines()).filteredOn(l -> l.subject().equals(DataReconciliation.ZIP_GROUP))
                .extracting(Report.Line::outcome, Report.Line::detail)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(Report.Outcome.OK, "50 records of acctdata.txt match"
                        + " addr_zip, group_id, zip_group_origin and legacy_zip_group (as read 0, migrated 50, layout"
                        + " manifest " + AccountLayouts.Source.BUILTIN.key() + ")"));
    }

    @Test
    void aChangedStoredGroupFailsCheck6ByLineAndColumnOnly() {
        String account = "00000000007";
        int line = lines("acctdata.txt", Layouts.ACCOUNT.length()).stream()
                .filter(l -> new String(l.record(), 0, 11, StandardCharsets.ISO_8859_1).equals(account))
                .findFirst().orElseThrow().lineNumber();
        admin.sql("UPDATE core.account SET legacy_zip_group = REPLICATE(N'9', 20) WHERE acct_id = :a")
                .param("a", account).update();

        Report report = reconcile();

        assertThat(failure(report, DataReconciliation.ZIP_GROUP))
                .startsWith("1 records differ: acctdata.txt line " + line + " [legacy_zip_group]");
        assertThat(failure(report, "core.account")).as("the row no longer restores its load-time text")
                .contains("acctdata.txt line " + line);
        report.lines().forEach(l -> assertThat(l.detail()).doesNotContain(account));
    }

    @Test
    void aSuppliedManifestThatIsMissingFailsCheck6(@TempDir Path dir) {
        Path missing = dir.resolve("absent.layout.toml");

        Report report = new DataReconciliation(admin, SAMPLE, USERS, LEDGER_MODE, missing).run();

        assertThat(failure(report, DataReconciliation.ZIP_GROUP)).isEqualTo("layout manifest not found: " + missing);
    }

    @Test
    void theModeComesFromTheOptionThenTheLedger() {
        assertThat(GroupIdMode.resolve("legacy", Map.of(), ROOT)).isEqualTo(new GroupIdMode(FidelityMode.LEGACY, "--fidelity"));
        assertThat(GroupIdMode.resolve(null, Map.of("CARDDEMO_FIDELITY_DEF_ACUP_002", "legacy"), ROOT))
                .as("no environment variable").isEqualTo(new GroupIdMode(LEDGER_MODE, "ledger"));
        assertThat(GroupIdMode.resolve(null, Map.of(), Path.of("/"))).isEqualTo(new GroupIdMode(FidelityMode.LEGACY, "default"));
    }

    @Test
    void aChangedBalanceFailsTheTotalAndNamesTheLineAndField() {
        String account = "00000000007";
        int line = lines("acctdata.txt", Layouts.ACCOUNT.length()).stream()
                .filter(l -> new String(l.record(), 0, 11, StandardCharsets.ISO_8859_1).equals(account))
                .findFirst().orElseThrow().lineNumber();
        admin.sql("UPDATE core.account SET curr_bal = curr_bal + 1 WHERE acct_id = :a").param("a", account).update();

        Report report = reconcile();

        assertThat(failure(report, "core.account")).contains("total ACCT-CURR-BAL differs")
                .contains("1 records re-export differently: acctdata.txt line " + line + " [ACCT-CURR-BAL]");
        report.lines().forEach(l -> assertThat(l.detail()).doesNotContain(account));
    }

    @Test
    void aMissingCrossReferenceIsReportedByItsFileLineOnly() throws Exception {
        String card = Files.readAllLines(SAMPLE.resolve("cardxref.txt")).get(4).substring(0, 16);
        int cardLine = Files.readAllLines(SAMPLE.resolve("carddata.txt")).stream().map(l -> l.substring(0, 16)).toList()
                .indexOf(card) + 1;
        admin.sql("DELETE FROM core.card_xref WHERE card_num = :c").param("c", card).update();

        Report report = reconcile();

        assertThat(failure(report, "core.card_xref")).contains("count 49, file 50")
                .contains("missing keys at cardxref.txt line 5");
        assertThat(failure(report, "card has xref")).isEqualTo("1 violations: carddata.txt line " + cardLine);
        report.lines().forEach(l -> assertThat(l.detail()).doesNotContain(card).doesNotContain(Masking.lastFour(card)));
    }

    @Test
    void aCardOnAnotherAccountThanItsCrossReferenceFails() throws Exception {
        String card = Files.readAllLines(SAMPLE.resolve("carddata.txt")).get(0).substring(0, 16);
        admin.sql("UPDATE core.card SET acct_id = '99999999999' WHERE card_num = :c").param("c", card).update();

        Report report = reconcile();

        assertThat(failure(report, "card account = xref")).contains("1 violations");
        assertThat(failure(report, "core.card")).contains("[CARD-ACCT-ID]").doesNotContain(card)
                .doesNotContain(Masking.lastFour(card));
    }

    @Test
    void aChangedRateFailsTheTotalAndNamesTheGroupKey() {
        admin.sql("UPDATE ref.disclosure_group SET int_rate = 9.99 WHERE group_id = 'ZEROAPR' AND type_cd = '01'"
                + " AND cat_cd = '0001'").update();

        assertThat(failure(reconcile(), "ref.disclosure_group")).contains("total DIS-INT-RATE differs")
                .contains("1 records re-export differently: ZEROAPR   010001 [DIS-INT-RATE]");
    }

    @Test
    void withoutTheDefaultGroupTheCheckFails() {
        admin.sql("DELETE FROM ref.disclosure_group WHERE group_id = 'DEFAULT'").update();

        Report report = reconcile();

        assertThat(failure(report, "DEFAULT disc group")).contains("no DEFAULT group");
        assertThat(failure(report, "ref.disclosure_group")).contains("count 34, file 51");
    }

    @Test
    void aMissingOrExtraLookupCodeIsNamed() {
        admin.sql("DELETE FROM ref.us_state WHERE state_cd = 'PR'").update();
        admin.sql("INSERT INTO ref.nanp_area_code (area_code) VALUES ('999')").update();

        Report report = reconcile();

        assertThat(failure(report, "ref.us_state")).isEqualTo("missing codes PR");
        assertThat(failure(report, "ref.nanp_area_code")).isEqualTo("codes not in lookups.json 999");
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
