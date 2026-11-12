package org.carddemo.verify.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.carddemo.batch.data.AccountLayoutException;
import org.carddemo.batch.data.AccountLayoutManifest;
import org.carddemo.batch.data.AccountLayouts;
import org.carddemo.platform.fidelity.FidelityMode;
import org.junit.jupiter.api.Test;

/**
 * Check 6's own layout resolver: the built-in hash against the sample file, every rule of
 * contract 1.4-1.5, and, on a corpus of manifests, the same accept or refuse and the same values
 * as the loader's parser.
 */
class AccountLayoutCheckTest {

    private static final Path SAMPLE = Path.of(System.getProperty("carddemo.repo.root", "../../.."), "app", "data",
            "ASCII", "acctdata.txt").toAbsolutePath().normalize();
    private static final byte[] FILE = "some account file\n".getBytes(StandardCharsets.US_ASCII);
    private static final String FILE_SHA = AccountLayoutCheck.sha256(FILE);
    private static final Set<String> IDS = Set.of("00000000001", "00000000002", "00000000003");

    private static byte[] manifest(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String valid(String defaultLayout, String others) {
        return "schema = 1\nsha256 = \"" + FILE_SHA + "\"\ndefault_layout = \"" + defaultLayout + "\"\n"
                + "other_layout_accounts = [" + others + "]\n";
    }

    private static AccountLayoutCheck.Layouts fixed(String text) {
        return AccountLayoutCheck.resolve(FidelityMode.FIXED, FILE, IDS, manifest(text));
    }

    @Test
    void theBuiltInHashIsTheSampleFilesAndTheLoadersOnlyBuiltIn() throws Exception {
        assertThat(AccountLayoutCheck.sha256(Files.readAllBytes(SAMPLE))).isEqualTo(AccountLayoutCheck.BUILT_IN_SHA256);
        assertThat(AccountLayouts.builtIn()).singleElement().satisfies(m -> {
            assertThat(m.sha256()).isEqualTo(AccountLayoutCheck.BUILT_IN_SHA256);
            assertThat(m.defaultLayout().name()).isEqualTo("ACUP_UPDATE");
            assertThat(m.otherLayoutAccounts()).isEmpty();
        });
    }

    @Test
    void legacyIgnoresEveryManifestAndFixedPicksBuiltInSuppliedOrNone() throws Exception {
        byte[] sample = Files.readAllBytes(SAMPLE);
        AccountLayoutCheck.Layouts legacy = AccountLayoutCheck.resolve(FidelityMode.LEGACY, sample, IDS,
                manifest("not a manifest"));
        assertThat(legacy.source()).isEqualTo("ignored");
        assertThat(legacy.fixedMode()).isFalse();
        assertThat(legacy.update("00000000001")).isFalse();

        AccountLayoutCheck.Layouts builtIn = AccountLayoutCheck.resolve(FidelityMode.FIXED, sample, IDS, null);
        assertThat(builtIn.source()).isEqualTo("builtin");
        assertThat(builtIn.update("00000000001")).isTrue();

        AccountLayoutCheck.Layouts none = AccountLayoutCheck.resolve(FidelityMode.FIXED, FILE, IDS, null);
        assertThat(none.source()).isEqualTo("none");
        assertThat(none.fixedMode()).isTrue();
        assertThat(none.update("00000000001")).isFalse();
    }

    @Test
    void overridesTakeTheOtherLayoutOfTheDefault() {
        AccountLayoutCheck.Layouts update = fixed(valid("ACUP_UPDATE", "\"00000000002\""));
        assertThat(update.source()).isEqualTo("supplied");
        assertThat(update.update("00000000001")).isTrue();
        assertThat(update.update("00000000002")).isFalse();

        AccountLayoutCheck.Layouts asRead = fixed(valid("CVACT01Y", "\"00000000002\", \"00000000003\","));
        assertThat(asRead.update("00000000001")).isFalse();
        assertThat(asRead.update("00000000002")).isTrue();
        assertThat(asRead.update("00000000003")).isTrue();
    }

    @Test
    void everyRefusalHasTheContractsMessage() {
        String other = "schema = 1\nsha256 = \"" + "0".repeat(64) + "\"\ndefault_layout = \"ACUP_UPDATE\"\n";
        assertThatThrownBy(() -> fixed(other)).hasMessage("layout manifest does not match acctdata.txt");
        assertThatThrownBy(() -> fixed(valid("ACUP_UPDATE", "\"0000000001\"")))
                .hasMessage("layout manifest override 1: not 11 digits");
        assertThatThrownBy(() -> fixed(valid("ACUP_UPDATE", "\"00000000001\", \"00000000002\", \"00000000001\"")))
                .hasMessage("layout manifest override 3: listed twice");
        assertThatThrownBy(() -> fixed(valid("ACUP_UPDATE", "\"00000000001\", \"00000000009\"")))
                .hasMessage("layout manifest override 2: not in acctdata.txt");
        assertThatThrownBy(() -> fixed("schema = 1\ndefault_layout = \"ACUP_UPDATE\"\n"))
                .hasMessage("layout manifest: missing sha256");
        assertThatThrownBy(() -> fixed(valid("ACUP_UPDATE", "").replace("schema = 1", "schema = 2")))
                .hasMessage("layout manifest line 1: schema must be 1");
        assertThatThrownBy(() -> fixed(valid("OTHER", "")))
                .hasMessage("layout manifest line 3: default_layout must be CVACT01Y or ACUP_UPDATE");
        assertThatThrownBy(() -> fixed(valid("ACUP_UPDATE", "") + "extra = 1\n"))
                .hasMessage("layout manifest line 5: unknown key");
        assertThatThrownBy(() -> fixed(valid("ACUP_UPDATE", "").replace(FILE_SHA, FILE_SHA.toUpperCase())))
                .hasMessage("layout manifest line 2: sha256 must be 64 lowercase hex digits");
        assertThatThrownBy(() -> AccountLayoutCheck.resolve(FidelityMode.FIXED, FILE, IDS,
                new byte[AccountLayoutCheck.MAX_BYTES + 1])).hasMessage("layout manifest is larger than 1 MiB");
        assertThatThrownBy(() -> AccountLayoutCheck.resolve(FidelityMode.FIXED, FILE, IDS, new byte[] {(byte) 0xC3}))
                .hasMessage("layout manifest is not UTF-8 text");
    }

    /** The loader's verdict and values, or its refusal as {@code null}. */
    private static List<Object> loader(String text) {
        try {
            AccountLayoutManifest m = AccountLayoutManifest.parse(manifest(text));
            return List.of(m.sha256(), m.defaultLayout().name(), m.otherLayoutAccounts());
        } catch (AccountLayoutException e) {
            return null;
        }
    }

    private static List<Object> own(String text) {
        try {
            AccountLayoutCheck.Manifest m = AccountLayoutCheck.parse(manifest(text));
            return List.of(m.sha256(), m.defaultLayout(), m.others());
        } catch (AccountLayoutCheck.Refused e) {
            return null;
        }
    }

    @Test
    void theOwnParserAcceptsAndRefusesWhatTheLoadersDoesWithTheSameValues() {
        String head = "schema = 1\nsha256 = \"" + FILE_SHA + "\"\n";
        String id1 = "\"00000000001\"";
        String id2 = "\"00000000002\"";
        List<String> corpus = List.of(
                head + "default_layout = \"ACUP_UPDATE\"\n",
                head + "default_layout = \"CVACT01Y\"\nother_layout_accounts = [" + id1 + ", " + id2 + "]\n",
                "# comment\n" + head + "default_layout = \"ACUP_UPDATE\" # after\n",
                (head + "default_layout = \"ACUP_UPDATE\"\n").replace("\n", "\r\n"),
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = [\n  " + id1 + ",\n  " + id2 + ",\n]\n",
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = [ ]\n",
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = [" + id1 + " # one\n, " + id2 + "]\n",
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = [\"a#b\"]\n",
                "\tschema = 1\n  sha256 = \"" + FILE_SHA + "\"\ndefault_layout=\"ACUP_UPDATE\"\n",
                head + "default_layout = \"ACUP_UPDATE\"",
                head + "[table]\ndefault_layout = \"ACUP_UPDATE\"\n",
                head + "a.b = 1\ndefault_layout = \"ACUP_UPDATE\"\n",
                head + "\"default_layout\" = \"ACUP_UPDATE\"\n",
                head + "default_layout = \"ACUP\\u005fUPDATE\"\n",
                head + "default_layout = 'ACUP_UPDATE'\n",
                head + "default_layout = \"\"\"ACUP_UPDATE\"\"\"\n",
                head + "default_layout = \"ACUP_UPDATE\"\ndefault_layout = \"ACUP_UPDATE\"\n",
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = [" + id1 + "\n",
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = [" + id1 + " " + id2 + "]\n",
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = [1]\n",
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = " + id1 + "\n",
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = [" + id1 + "] x\n",
                head + "default_layout = \"ACUP_UPDATE\" x\n",
                head + "default_layout = \"ACUP_UPDATE\"\r\n\r",
                head + "default_layout = \"ACUP\rUPDATE\"\n",
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = [[" + id1 + "]]\n",
                head.replace("schema = 1", "schema = \"1\"") + "default_layout = \"ACUP_UPDATE\"\n",
                head.replace("schema = 1", "schema = 1.0") + "default_layout = \"ACUP_UPDATE\"\n",
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = [,]\n",
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = [" + id1 + ",,]\n",
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = [\n]\n",
                head + "default_layout = \"ACUP_UPDATE\"\n= 1\n",
                head + "default_layout = \"ACUP_UPDATE\"\nkey\n",
                head + "default_layout = \"ACUP_UPDATE\"\n\u0001\n",
                head.replace("schema = 1", "schema = 01") + "default_layout = \"ACUP_UPDATE\"\n",
                head.replace("schema = 1", "schema = +01") + "default_layout = \"ACUP_UPDATE\"\n",
                head.replace("schema = 1", "schema = +1") + "default_layout = \"ACUP_UPDATE\"\n",
                "\u2003" + head + "default_layout = \"ACUP_UPDATE\"\n",
                head + "default_layout = \"ACUP_UPDATE\"\u3000\n",
                head + "default_layout\u2002= \"ACUP_UPDATE\"\n",
                head + "default_layout = \"ACUP_UPDATE\"\n\u2028\n",
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = [\n\u2003" + id1 + "\n]\n",
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = [" + id1 + "\u1680]\n",
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = [" + id1 + "]\u205F\n",
                head + "default_layout = \"ACUP_UPDATE\"\nother_layout_accounts = [\u00A0" + id1 + "]\n");
        List<String> disagreements = new ArrayList<>();
        for (int i = 0; i < corpus.size(); i++) {
            List<Object> expected = loader(corpus.get(i));
            List<Object> actual = own(corpus.get(i));
            if (expected == null ? actual != null : !expected.equals(actual)) {
                disagreements.add("case " + i + ": loader " + expected + ", own " + actual);
            }
        }
        assertThat(disagreements).isEmpty();
        assertThat(corpus.stream().filter(t -> loader(t) != null).count()).as("accepted cases").isGreaterThan(5);
        assertThat(corpus.stream().filter(t -> loader(t) == null).count()).as("refused cases").isGreaterThan(25);
        assertThat(own(head.replace("schema = 1", "schema = 01") + "default_layout = \"ACUP_UPDATE\"\n"))
                .as("a leading zero").isNull();
        assertThat(own("\u2003" + head + "default_layout = \"ACUP_UPDATE\"\n")).as("an em space").isNull();
    }
}
