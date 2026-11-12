package org.carddemo.batch.load;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.carddemo.batch.data.AccountLayoutException;
import org.carddemo.batch.data.AccountLayoutManifest;
import org.carddemo.batch.data.AccountLayouts;
import org.carddemo.batch.data.ZipGroup;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.entity.CardRecord;
import org.carddemo.platform.legacyformat.entity.CardXrefRecord;
import org.carddemo.platform.legacyformat.entity.CustomerRecord;
import org.carddemo.platform.legacyformat.entity.TcatBalRecord;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.carddemo.platform.legacyformat.record.FixedRecord;
import org.carddemo.platform.legacyformat.record.LegacyFileReader.SourceLine;
import org.carddemo.platform.legacyformat.record.RecordLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The load steps of the core tables, in the order dataLoadJob runs them (parents first). */
final class CoreTableLoaders {

    private static final Logger log = LoggerFactory.getLogger(CoreTableLoaders.class);

    private CoreTableLoaders() {
    }

    /**
     * ACCTDATA (JACCT) into core.account, with DEF-ACUP-002 (D-11, ruling v4). The step reads
     * {@code acctdata.txt} and the supplied layout manifest once each, hashes exactly those bytes,
     * and classifies every record from them ({@link ZipGroup}) before it deletes anything: a
     * manifest or record the rules refuse leaves the table as it was. The hashes go to the job
     * execution context at the first attempt of a job instance; a restart that reads other bytes
     * fails with {@code SOURCE_FILE_CHANGED}. The fields hold one step execution's state:
     * dataLoadJob makes a new instance for each ({@link DataLoadJobConfiguration#perRun}).
     */
    static final class Accounts extends TableLoadTasklet {
        /** Job execution context: SHA-256 (hex) of the {@code acctdata.txt} bytes the first attempt read. */
        static final String ACCTDATA_SHA256_KEY = "dataLoad.acctdataSha256";
        /** Job execution context: SHA-256 (hex) of the supplied manifest the first attempt read, if any. */
        static final String MANIFEST_SHA256_KEY = "dataLoad.accountLayoutManifestSha256";
        /** Step execution context: the load report (contract v2 section 1.8). */
        static final String ACCOUNTS_AS_READ = "accountsAsRead";
        static final String ACCOUNTS_MIGRATED = "accountsMigrated";
        static final String ACCOUNT_LAYOUT_SOURCE = "accountLayoutSource";
        static final String ACCOUNT_LAYOUT_WARNINGS = "accountLayoutWarnings";
        static final String ACCTDATA_SHA256 = "acctdataSha256";
        /** The supplied manifest's SHA-256 (hex), or {@code ""} when none was read. */
        static final String MANIFEST_SHA256 = "accountLayoutManifestSha256";
        static final String GROUP_ID_MODE = "groupIdMode";

        private record Classified(AccountRecord record, ZipGroup.Stored zipGroup) {
        }

        private final JobRepository jobRepository;
        private FidelityMode mode;
        private byte[] acctdata;
        private byte[] manifest;
        private String acctdataSha256;
        private String manifestSha256;
        private AccountLayouts layouts;
        private Map<Integer, Classified> classified = Map.of();
        private long asRead;
        private long migrated;
        private List<Integer> warningLines = List.of();

        Accounts(JdbcClient db, DataLoadProperties p, FidelitySwitches s, JobRepository jobRepository) {
            super(db, p, s);
            this.jobRepository = jobRepository;
        }

        @Override String stepName() { return "loadAccounts"; }
        @Override String fileName() { return "acctdata.txt"; }
        @Override RecordLayout layout() { return Layouts.ACCOUNT; }
        @Override int keyLength() { return 11; }
        @Override String table() { return "core.account"; }

        @Override
        SourceFile readSource(StepContribution contribution, Path dir) {
            mode = groupIdMode();
            acctdata = SourceFile.readBytes(dir, fileName());
            manifest = mode == FidelityMode.FIXED ? suppliedManifest() : null;
            acctdataSha256 = AccountLayouts.sha256(acctdata);
            manifestSha256 = manifest == null ? null : AccountLayouts.sha256(manifest);
            requireSameInputs(contribution.getStepExecution().getJobExecution(), acctdataSha256, manifestSha256);
            return SourceFile.parse(fileName(), layout(), acctdata);
        }

        /**
         * Records the hashes at the first attempt of the job instance, saved before the first
         * write; a later attempt must have read the same bytes.
         */
        private void requireSameInputs(JobExecution job, String fileHash, String manifestHash) {
            ExecutionContext context = job.getExecutionContext();
            if (context.containsKey(ACCTDATA_SHA256_KEY)) {
                if (!fileHash.equals(context.getString(ACCTDATA_SHA256_KEY))) {
                    throw new DataLoadException("SOURCE_FILE_CHANGED: acctdata.txt differs from the first attempt"
                            + " of this job instance");
                }
                String first = context.containsKey(MANIFEST_SHA256_KEY) ? context.getString(MANIFEST_SHA256_KEY) : null;
                if (!Objects.equals(first, manifestHash)) {
                    throw new DataLoadException("SOURCE_FILE_CHANGED: the layout manifest differs from the first"
                            + " attempt of this job instance");
                }
                return;
            }
            context.putString(ACCTDATA_SHA256_KEY, fileHash);
            if (manifestHash != null) {
                context.putString(MANIFEST_SHA256_KEY, manifestHash);
            }
            jobRepository.updateExecutionContext(job);
        }

        /** The supplied manifest's bytes, read once; {@code null} when none is configured. */
        private byte[] suppliedManifest() {
            String configured = properties.accountLayoutManifest();
            if (configured == null || configured.isBlank()) {
                return null;
            }
            Path path;
            try {
                path = Path.of(configured);
            } catch (InvalidPathException e) {
                throw new DataLoadException("layout manifest not found: " + configured);
            }
            if (!Files.isRegularFile(path)) {
                throw new DataLoadException("layout manifest not found: " + configured);
            }
            try (InputStream in = Files.newInputStream(path)) {
                byte[] bytes = in.readNBytes(AccountLayoutManifest.MAX_BYTES + 1);
                if (bytes.length > AccountLayoutManifest.MAX_BYTES) {
                    throw new DataLoadException("layout manifest is larger than 1 MiB");
                }
                return bytes;
            } catch (IOException e) {
                throw new DataLoadException("layout manifest cannot be read (" + e.getClass().getSimpleName() + ")");
            }
        }

        @Override
        void prepare(StepContribution contribution, SourceFile file) {
            List<AccountRecord> records = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (SourceLine line : file.lines()) {
                AccountRecord a = file.convert(line, AccountRecord::parse);
                requireDate(file, line, "ACCT-OPEN-DATE", a.openDate());
                requireDate(file, line, "ACCT-EXPIRAION-DATE", a.expirationDate());
                requireDate(file, line, "ACCT-REISSUE-DATE", a.reissueDate());
                records.add(a);
                ids.add(a.acctId());
            }
            try {
                layouts = AccountLayouts.resolve(mode, acctdata, ids, manifest);
            } catch (AccountLayoutException e) {
                throw new DataLoadException(e.getMessage());
            }
            Map<Integer, Classified> byLine = new HashMap<>();
            List<Integer> warnings = new ArrayList<>();
            long r = 0;
            long m = 0;
            for (int i = 0; i < records.size(); i++) {
                SourceLine line = file.lines().get(i);
                AccountRecord a = records.get(i);
                FixedRecord view = line.view(layout());
                String zipGroup = view.raw("ACCT-ADDR-ZIP") + view.raw("ACCT-GROUP-ID");
                ZipGroup.Stored stored;
                try {
                    stored = ZipGroup.classify(zipGroup, layouts.layoutOf(a.acctId()));
                } catch (AccountLayoutException e) {
                    throw new DataLoadException(file.where(line) + ": " + e.getMessage());
                }
                if (stored.origin().equals(ZipGroup.MIGRATED)) {
                    m++;
                } else {
                    r++;
                    if (layouts.fixedMode() && ZipGroup.blank(zipGroup.substring(ZipGroup.WIDTH))
                            && !ZipGroup.blank(zipGroup.substring(0, ZipGroup.WIDTH))) {
                        warnings.add(line.lineNumber());
                    }
                }
                byLine.put(line.lineNumber(), new Classified(a, stored));
            }
            classified = byLine;
            asRead = r;
            migrated = m;
            warningLines = warnings;
        }

        @Override
        void insert(SourceFile file, SourceLine line) {
            Classified c = classified.get(line.lineNumber());
            rows.insert(c.record(), c.zipGroup());
        }

        // Counts and line numbers only: no account ID is reported (D-NFR-04).
        @Override
        void afterInsert(StepContribution contribution, SourceFile file) {
            ExecutionContext context = contribution.getStepExecution().getExecutionContext();
            context.putLong(ACCOUNTS_AS_READ, asRead);
            context.putLong(ACCOUNTS_MIGRATED, migrated);
            context.putString(ACCOUNT_LAYOUT_SOURCE, layouts.source().key());
            context.putLong(ACCOUNT_LAYOUT_WARNINGS, warningLines.size());
            context.putString(ACCTDATA_SHA256, acctdataSha256);
            context.putString(MANIFEST_SHA256, manifestSha256 == null ? "" : manifestSha256);
            context.putString(GROUP_ID_MODE, mode.key());
            if (!warningLines.isEmpty()) {
                log.warn("{}: {} records stored as read have a blank 113-122 and a non-blank 103-112: {} {} {}",
                        stepName(), warningLines.size(), file.name(), warningLines.size() == 1 ? "line" : "lines",
                        Customers.ranges(warningLines, " lines"));
            }
        }

        @Override
        String loadDetail() {
            return " (as read " + asRead + ", migrated " + migrated + ", layout manifest " + layouts.source().key()
                    + ")";
        }
    }
    /**
     * CUSTDATA (JCUST) into core.customer. Positions 333-500, the FILLER X(168) that a COACTUPC
     * save leaves unset (DEF-ACUP-020, Q-ACUP-010 part (b)), may hold anything: nothing of it is
     * stored, the export writes 168 spaces, and the step counts the records whose 333-500 was not
     * all spaces, by file line, in every mode. Positions 1-332 are the named fields and are kept as
     * read, U+0000 included.
     */
    static final class Customers extends TableLoadTasklet {
        /** Position of CVCUS01Y's FILLER X(168). */
        static final int FILLER_POSITION = 333;
        /** Step execution context: the number of records whose 333-500 was not all spaces. */
        static final String FILLER_NORMALISED = "customerFillerNormalised";
        /** Step execution context: their lines in {@code custdata.txt}, as {@code 3, 7-9}. */
        static final String FILLER_NORMALISED_LINES = "customerFillerNormalisedLines";
        /** At most this many line ranges are listed; the count is always exact. */
        static final int MAX_RANGES = 100;

        Customers(JdbcClient db, DataLoadProperties p, FidelitySwitches s) {
            super(db, p, s);
        }

        @Override
        Set<Integer> droppedFillers() {
            return Set.of(FILLER_POSITION);
        }

        // Line numbers only: neither the customer ID nor the FILLER content is shown (D-NFR-04).
        @Override
        void reportDroppedFillers(StepContribution contribution, SourceFile file, List<Integer> lines) {
            ExecutionContext context = contribution.getStepExecution().getExecutionContext();
            context.putLong(FILLER_NORMALISED, lines.size());
            context.putString(FILLER_NORMALISED_LINES, ranges(lines));
            if (lines.isEmpty()) {
                log.info("{}: customer FILLER normalised: 0", stepName());
            } else {
                log.warn("{}: customer FILLER normalised: {} (positions 333-500 not all spaces, exported as spaces):"
                        + " {} {} {}", stepName(), lines.size(), file.name(), lines.size() == 1 ? "line" : "lines",
                        ranges(lines));
            }
        }

        /** Ascending line numbers as ranges, {@code 3, 7-9}, at most {@link #MAX_RANGES} of them. */
        static String ranges(List<Integer> lines) {
            return ranges(lines, "");
        }

        /** As {@link #ranges(List)}, with {@code more} after the count of lines not listed. */
        static String ranges(List<Integer> lines, String more) {
            List<String> ranges = new ArrayList<>();
            int listed = 0;
            for (int i = 0; i < lines.size() && ranges.size() < MAX_RANGES; ) {
                int j = i;
                while (j + 1 < lines.size() && lines.get(j + 1) == lines.get(j) + 1) {
                    j++;
                }
                ranges.add(i == j ? String.valueOf(lines.get(i)) : lines.get(i) + "-" + lines.get(j));
                listed += j - i + 1;
                i = j + 1;
            }
            String shown = String.join(", ", ranges);
            return listed < lines.size() ? shown + " and " + (lines.size() - listed) + " more" + more : shown;
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
        Cards(JdbcClient db, DataLoadProperties p, FidelitySwitches s) {
            super(db, p, s);
        }

        @Override String stepName() { return "loadCards"; }
        @Override String fileName() { return "carddata.txt"; }
        @Override RecordLayout layout() { return Layouts.CARD; }
        @Override int keyLength() { return 16; }
        @Override String table() { return "core.card"; }

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

        CardXrefs(JdbcClient db, DataLoadProperties p, FidelitySwitches s) {
            super(db, p, s);
        }

        @Override String stepName() { return "loadCardXref"; }
        @Override String fileName() { return "cardxref.txt"; }
        @Override RecordLayout layout() { return Layouts.CARD_XREF; }
        @Override int keyLength() { return 16; }
        @Override String table() { return "core.card_xref"; }

        @Override
        void beforeInsert(SourceFile file) {
            accounts = new HashSet<>(db.sql("SELECT acct_id FROM core.account").query(String.class).list());
            customers = new HashSet<>(db.sql("SELECT cust_id FROM core.customer").query(String.class).list());
        }

        @Override
        void insert(SourceFile file, SourceLine line) {
            CardXrefRecord x = file.convert(line, CardXrefRecord::parse);
            if (!accounts.contains(x.acctId())) {
                throw new DataLoadException(file.where(line) + ": XREF-ACCT-ID names an account that is not loaded");
            }
            if (!customers.contains(x.custId())) {
                throw new DataLoadException(file.where(line) + ": XREF-CUST-ID names a customer that is not loaded");
            }
            rows.insert(x);
        }
    }

    /** TCATBALF (JTCAT) into core.tcat_bal; every account must be loaded. */
    static final class CategoryBalances extends TableLoadTasklet {
        private Set<String> accounts = Set.of();

        CategoryBalances(JdbcClient db, DataLoadProperties p, FidelitySwitches s) {
            super(db, p, s);
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
                throw new DataLoadException(file.where(line) + ": TRANCAT-ACCT-ID names an account that is not loaded");
            }
            rows.insert(t);
        }
    }
}
