package org.carddemo.batch.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.carddemo.batch.BatchSwitches;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The account step's reading, validation and load report, without a database: everything up to the
 * DELETE (readSource and prepare) and the report after the inserts. DEF-ACUP-002 contract sections
 * 1.4 to 1.8.
 */
@ExtendWith(OutputCaptureExtension.class)
class AccountLoadRulesTest {

    static final Path SAMPLE_DIR = Path.of(System.getProperty("carddemo.repo.root", "../../.."), "app", "data", "ASCII")
            .toAbsolutePath().normalize();
    private static final String SAMPLE_SHA256 = "c2a97b6a32dc4a87a7aafdf7f72e6712e560412d30b00c5526cca80fc9dfd260";
    private static final int GROUP_FROM = 112;
    private static final int GROUP_TO = 122;

    @TempDir
    Path dir;

    /** Counts the job-context saves; any other repository call is a test failure. */
    private int saves;

    private final JobRepository repository = (JobRepository) Proxy.newProxyInstance(
            JobRepository.class.getClassLoader(), new Class<?>[] {JobRepository.class}, (proxy, method, args) -> {
                if (method.getName().equals("updateExecutionContext") && args[0] instanceof JobExecution) {
                    saves++;
                    return null;
                }
                if (method.getName().equals("toString")) {
                    return "recording repository";
                }
                throw new UnsupportedOperationException(method.getName());
            });

    private final JobExecution job = new JobExecution(1L, new JobInstance(1L, "dataLoadJob"), new JobParameters());

    private CoreTableLoaders.Accounts accounts(FidelityMode mode, Path manifest) {
        FidelitySwitches switches = FidelitySwitches.of(BatchSwitches.ALL, Map.of("DEF-ACUP-002", mode));
        return new CoreTableLoaders.Accounts(null,
                new DataLoadProperties(dir.toString(), false, manifest == null ? null : manifest.toString()), switches,
                repository);
    }

    private StepContribution contribution() {
        return new StepExecution(1L, "loadAccounts", job).createStepContribution();
    }

    /** What execute does before the DELETE, then the report it writes after the inserts. */
    private static ExecutionContext load(CoreTableLoaders.Accounts accounts, StepContribution contribution, Path dir) {
        SourceFile file = accounts.readSource(contribution, dir);
        file.requireAscendingKeys(accounts.keyLength());
        file.requireRepresentable(accounts.droppedFillers());
        accounts.prepare(contribution, file);
        accounts.afterInsert(contribution, file);
        return contribution.getStepExecution().getExecutionContext();
    }

    private List<String> sample() throws IOException {
        return new ArrayList<>(Files.readAllLines(SAMPLE_DIR.resolve("acctdata.txt"), StandardCharsets.ISO_8859_1));
    }

    private Path acctdata(List<String> lines) throws IOException {
        return Files.writeString(dir.resolve("acctdata.txt"), String.join("\n", lines) + "\n", StandardCharsets.ISO_8859_1);
    }

    private Path sampleFile() throws IOException {
        return Files.copy(SAMPLE_DIR.resolve("acctdata.txt"), dir.resolve("acctdata.txt"));
    }

    private static List<String> groupOnLine3(List<String> lines) {
        String l = lines.get(2);
        lines.set(2, l.substring(0, GROUP_FROM) + "B000000000" + l.substring(GROUP_TO));
        return lines;
    }

    private Path manifest(Path acctdata, String defaultLayout, String... others) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(acctdata)));
        StringBuilder text = new StringBuilder("schema = 1\nsha256 = \"" + hash + "\"\ndefault_layout = \""
                + defaultLayout + "\"\nother_layout_accounts = [");
        for (String o : others) {
            text.append('"').append(o).append("\", ");
        }
        return Files.writeString(dir.resolveSibling(dir.getFileName() + ".toml"), text.append("]\n").toString());
    }

    @Test
    void legacyStoresEveryRecordAsReadAndNeverReadsTheManifest() throws Exception {
        sampleFile();
        ExecutionContext report = load(accounts(FidelityMode.LEGACY, dir.resolve("absent.toml")), contribution(), dir);

        assertThat(report.getLong("accountsAsRead")).isEqualTo(50);
        assertThat(report.getLong("accountsMigrated")).isZero();
        assertThat(report.getString("accountLayoutSource")).isEqualTo("ignored");
        assertThat(report.getLong("accountLayoutWarnings")).isZero();
        assertThat(report.getString("acctdataSha256")).isEqualTo(SAMPLE_SHA256);
        assertThat(report.getString("accountLayoutManifestSha256")).isEmpty();
        assertThat(report.getString("groupIdMode")).isEqualTo("legacy");
        assertThat(job.getExecutionContext().getString("dataLoad.acctdataSha256")).isEqualTo(SAMPLE_SHA256);
        assertThat(job.getExecutionContext().containsKey("dataLoad.accountLayoutManifestSha256")).isFalse();
        assertThat(saves).as("saved before the first write").isOne();
    }

    @Test
    void fixedModeMigratesTheSampleByTheBuiltInManifest(CapturedOutput output) throws Exception {
        sampleFile();
        CoreTableLoaders.Accounts accounts = accounts(FidelityMode.FIXED, null);
        ExecutionContext report = load(accounts, contribution(), dir);

        assertThat(report.getLong("accountsMigrated")).isEqualTo(50);
        assertThat(report.getString("accountLayoutSource")).isEqualTo("builtin");
        assertThat(report.getString("groupIdMode")).isEqualTo("fixed");
        assertThat(accounts.loadDetail()).isEqualTo(" (as read 0, migrated 50, layout manifest builtin)");
        assertThat(output.getAll()).doesNotContain("records stored as read");
    }

    @Test
    void fixedModeStoresOtherBytesAsReadAndWarnsByLineWithoutIds(CapturedOutput output) throws Exception {
        List<String> lines = groupOnLine3(sample());
        acctdata(lines);
        ExecutionContext report = load(accounts(FidelityMode.FIXED, null), contribution(), dir);

        assertThat(report.getString("accountLayoutSource")).isEqualTo("none");
        assertThat(report.getLong("accountsAsRead")).isEqualTo(50);
        assertThat(report.getLong("accountLayoutWarnings")).isEqualTo(49);
        assertThat(output.getAll()).contains("loadAccounts: 49 records stored as read have a blank 113-122 and a"
                + " non-blank 103-112: acctdata.txt lines 1-2, 4-50");
        for (String l : lines) {
            assertThat(output.getAll()).doesNotContain(l.substring(0, 11));
        }
    }

    @Test
    void theWarningListsAtMostOneHundredRangesAndCountsTheRest(CapturedOutput output) throws Exception {
        String template = sample().getFirst();
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 250; i++) {
            String id = String.format("%011d", i);
            String line = id + template.substring(11);
            lines.add(i % 2 == 0 ? line.substring(0, GROUP_FROM) + "B000000000" + line.substring(GROUP_TO) : line);
        }
        acctdata(lines);
        ExecutionContext report = load(accounts(FidelityMode.FIXED, null), contribution(), dir);

        assertThat(report.getLong("accountLayoutWarnings")).isEqualTo(125);
        StringBuilder listed = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            listed.append(i == 0 ? "" : ", ").append(2 * i + 1);
        }
        assertThat(output.getAll()).contains("loadAccounts: 125 records stored as read have a blank 113-122 and a"
                + " non-blank 103-112: acctdata.txt lines " + listed + " and 25 more lines");
    }

    @Test
    void aSuppliedManifestDeclaresTheLayoutsAndIsHashed() throws Exception {
        Path file = acctdata(groupOnLine3(sample()));
        Path manifest = manifest(file, "ACUP_UPDATE", "00000000003");
        ExecutionContext report = load(accounts(FidelityMode.FIXED, manifest), contribution(), dir);

        assertThat(report.getString("accountLayoutSource")).isEqualTo("supplied");
        assertThat(report.getLong("accountsMigrated")).isEqualTo(49);
        assertThat(report.getLong("accountsAsRead")).isOne();
        assertThat(report.getLong("accountLayoutWarnings")).isZero();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(manifest)));
        assertThat(report.getString("accountLayoutManifestSha256")).isEqualTo(hash);
        assertThat(job.getExecutionContext().getString("dataLoad.accountLayoutManifestSha256")).isEqualTo(hash);
    }

    @Test
    void anUpdateLayoutRecordWithAGroupIdFailsNamingTheLineOnly() throws Exception {
        Path file = acctdata(groupOnLine3(sample()));
        Path manifest = manifest(file, "ACUP_UPDATE");
        CoreTableLoaders.Accounts accounts = accounts(FidelityMode.FIXED, manifest);

        assertThatThrownBy(() -> load(accounts, contribution(), dir)).isInstanceOf(DataLoadException.class)
                .hasMessage("acctdata.txt line 3: declared ACUP_UPDATE but positions 113-122 are not blank");
    }

    @Test
    void aMissingOrOversizedManifestFailsBeforeAnythingElse() throws Exception {
        sampleFile();
        Path missing = dir.resolve("none.toml");
        assertThatThrownBy(() -> load(accounts(FidelityMode.FIXED, missing), contribution(), dir))
                .hasMessage("layout manifest not found: " + missing);
        Path big = Files.write(dir.resolve("big.toml"), new byte[(1 << 20) + 1]);
        assertThatThrownBy(() -> load(accounts(FidelityMode.FIXED, big), contribution(), dir))
                .hasMessage("layout manifest is larger than 1 MiB");
        assertThat(saves).isZero();
    }

    @Test
    void aRestartMustReadTheBytesOfTheFirstAttempt() throws Exception {
        Path file = sampleFile();
        Path manifest = manifest(file, "ACUP_UPDATE");
        load(accounts(FidelityMode.FIXED, manifest), contribution(), dir);
        assertThat(saves).isOne();

        // The same bytes again: accepted, and the first attempt's hashes stay.
        load(accounts(FidelityMode.FIXED, manifest), contribution(), dir);
        assertThat(saves).isOne();

        assertThatThrownBy(() -> load(accounts(FidelityMode.FIXED, null), contribution(), dir))
                .hasMessage("SOURCE_FILE_CHANGED: the layout manifest differs from the first attempt of this job instance");
        Files.writeString(manifest, Files.readString(manifest) + "# changed\n");
        assertThatThrownBy(() -> load(accounts(FidelityMode.FIXED, manifest), contribution(), dir))
                .hasMessage("SOURCE_FILE_CHANGED: the layout manifest differs from the first attempt of this job instance");
        acctdata(groupOnLine3(sample()));
        assertThatThrownBy(() -> load(accounts(FidelityMode.FIXED, manifest), contribution(), dir))
                .hasMessage("SOURCE_FILE_CHANGED: acctdata.txt differs from the first attempt of this job instance");
    }

    @Test
    void theAccountFileIsParsedFromTheBytesThatWereHashed() throws Exception {
        sampleFile();
        SourceFile file = accounts(FidelityMode.LEGACY, null).readSource(contribution(), dir);
        assertThat(file.lines()).hasSize(50);
        assertThat(file.name()).isEqualTo("acctdata.txt");
        assertThat(Set.copyOf(file.lines().stream().map(l -> l.record().length).toList()))
                .containsExactly(Layouts.ACCOUNT.length());
    }
}
