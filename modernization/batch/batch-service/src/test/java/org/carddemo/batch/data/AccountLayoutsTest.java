package org.carddemo.batch.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.carddemo.platform.fidelity.FidelityMode;
import org.junit.jupiter.api.Test;

/** Which manifest applies to the bytes of acctdata.txt: by SHA-256 only, per mode (contract 1.3, 1.4). */
class AccountLayoutsTest {

    static final Path SAMPLE = Path.of(System.getProperty("carddemo.repo.root", "../../.."), "app", "data", "ASCII",
            "acctdata.txt").toAbsolutePath().normalize();

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Set<String> ids(byte[] acctdata) {
        return new String(acctdata, StandardCharsets.ISO_8859_1).lines().map(l -> l.substring(0, 11))
                .collect(Collectors.toSet());
    }

    @Test
    void theOneBuiltInManifestIsTheAsciiSampleAllUpdateLayout() throws Exception {
        byte[] sample = Files.readAllBytes(SAMPLE);
        assertThat(AccountLayouts.builtIn()).singleElement().satisfies(m -> {
            assertThat(m.sha256()).isEqualTo(sha256(sample))
                    .isEqualTo("c2a97b6a32dc4a87a7aafdf7f72e6712e560412d30b00c5526cca80fc9dfd260");
            assertThat(m.defaultLayout()).isEqualTo(AccountLayout.ACUP_UPDATE);
            assertThat(m.otherLayoutAccounts()).isEmpty();
        });
    }

    @Test
    void legacyIgnoresEveryManifestAndFixedLooksUpTheBuiltInByHash() throws Exception {
        byte[] sample = Files.readAllBytes(SAMPLE);
        byte[] garbage = "not a manifest".getBytes(StandardCharsets.US_ASCII);

        AccountLayouts legacy = AccountLayouts.resolve(FidelityMode.LEGACY, sample, ids(sample), garbage);
        assertThat(legacy.source()).isEqualTo(AccountLayouts.Source.IGNORED);
        assertThat(legacy.fixedMode()).isFalse();
        assertThat(legacy.layoutOf("00000000001")).isEqualTo(AccountLayout.CVACT01Y);

        AccountLayouts builtin = AccountLayouts.resolve(FidelityMode.FIXED, sample, ids(sample), null);
        assertThat(builtin.source()).isEqualTo(AccountLayouts.Source.BUILTIN);
        assertThat(ids(sample)).allMatch(id -> builtin.layoutOf(id) == AccountLayout.ACUP_UPDATE);

        byte[] changed = sample.clone();
        changed[changed.length - 2] ^= 1;
        AccountLayouts none = AccountLayouts.resolve(FidelityMode.FIXED, changed, ids(sample), null);
        assertThat(none.source()).isEqualTo(AccountLayouts.Source.NONE);
        assertThat(none.fixedMode()).isTrue();
        assertThat(none.layoutOf("00000000001")).isEqualTo(AccountLayout.CVACT01Y);
    }

    @Test
    void aSuppliedManifestMustMatchTheBytesAndReplacesTheBuiltIn() throws Exception {
        byte[] sample = Files.readAllBytes(SAMPLE);
        String own = "schema = 1\nsha256 = \"" + sha256(sample) + "\"\ndefault_layout = \"CVACT01Y\"\n"
                + "other_layout_accounts = [\"00000000002\"]\n";

        AccountLayouts supplied = AccountLayouts.resolve(FidelityMode.FIXED, sample, ids(sample),
                own.getBytes(StandardCharsets.US_ASCII));
        assertThat(supplied.source()).isEqualTo(AccountLayouts.Source.SUPPLIED);
        assertThat(supplied.layoutOf("00000000001")).isEqualTo(AccountLayout.CVACT01Y);
        assertThat(supplied.layoutOf("00000000002")).isEqualTo(AccountLayout.ACUP_UPDATE);

        String other = own.replace(sha256(sample), "0".repeat(64));
        assertThatThrownBy(() -> AccountLayouts.resolve(FidelityMode.FIXED, sample, ids(sample),
                other.getBytes(StandardCharsets.US_ASCII))).hasMessage("layout manifest does not match acctdata.txt");

        // A syntax error is reported before the hash, the hash before the overrides.
        String both = other.replace("schema = 1", "schema = 2");
        assertThatThrownBy(() -> AccountLayouts.resolve(FidelityMode.FIXED, sample, ids(sample),
                both.getBytes(StandardCharsets.US_ASCII))).hasMessage("layout manifest line 1: schema must be 1");
        String unknownAndOther = other.replace("00000000002", "99999999999");
        assertThatThrownBy(() -> AccountLayouts.resolve(FidelityMode.FIXED, sample, ids(sample),
                unknownAndOther.getBytes(StandardCharsets.US_ASCII)))
                .hasMessage("layout manifest does not match acctdata.txt");
        String unknown = own.replace("00000000002", "99999999999");
        assertThatThrownBy(() -> AccountLayouts.resolve(FidelityMode.FIXED, sample, ids(sample),
                unknown.getBytes(StandardCharsets.US_ASCII))).hasMessage("layout manifest override 1: not in acctdata.txt");
    }

    @Test
    void theHashCoversEveryByteLineEndsIncluded() throws Exception {
        String lf = IntStream.range(0, 3).mapToObj(i -> "x").collect(Collectors.joining("\n"));
        assertThat(AccountLayouts.sha256(lf.getBytes(StandardCharsets.US_ASCII)))
                .isNotEqualTo(AccountLayouts.sha256(lf.replace("\n", "\r\n").getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo(sha256(lf.getBytes(StandardCharsets.US_ASCII)));
    }
}
