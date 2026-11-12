package org.carddemo.batch.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The strict TOML subset and the messages of build-data's manifest contract, section 1. */
class AccountLayoutManifestTest {

    private static final String HASH = "0123456789abcdef".repeat(4);

    private static AccountLayoutManifest parse(String text) {
        return AccountLayoutManifest.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void refused(String text, String message) {
        assertThatThrownBy(() -> parse(text)).isInstanceOf(AccountLayoutException.class).hasMessage(message);
    }

    @Test
    void theAcceptedSubsetParses() {
        AccountLayoutManifest m = parse("# layout of the stress file\r\n"
                + "schema = 1\r\n"
                + "sha256 = \"" + HASH + "\"   # after the file is written\n"
                + "\n"
                + "default_layout = \"ACUP_UPDATE\"\n"
                + "other_layout_accounts = [\n"
                + "  \"00000000001\", \"00000000002\",  # first two\n"
                + "  \"00000000005\",\n"
                + "]\n");
        assertThat(m.sha256()).isEqualTo(HASH);
        assertThat(m.defaultLayout()).isEqualTo(AccountLayout.ACUP_UPDATE);
        assertThat(m.otherLayoutAccounts()).containsExactly("00000000001", "00000000002", "00000000005");
        assertThat(m.layoutOf("00000000002")).isEqualTo(AccountLayout.CVACT01Y);
        assertThat(m.layoutOf("00000000003")).isEqualTo(AccountLayout.ACUP_UPDATE);
        assertThat(parse("schema = 1\nsha256 = \"" + HASH + "\"\ndefault_layout = \"CVACT01Y\"").otherLayoutAccounts())
                .isEmpty();
    }

    @Test
    void everythingOutsideTheSubsetIsRefusedNamingTheLine() {
        String head = "schema = 1\nsha256 = \"" + HASH + "\"\n";
        refused(head + "[layouts]\n", "layout manifest line 3: tables are not supported");
        refused(head + "default.layout = \"CVACT01Y\"\n", "layout manifest line 3: dotted keys are not supported");
        refused(head + "\"default_layout\" = \"CVACT01Y\"\n", "layout manifest line 3: quoted keys are not supported");
        refused(head + "default_layout = \"CVACT\\u0030\"\n", "layout manifest line 3: escapes are not supported");
        refused(head + "default_layout = 'CVACT01Y'\n",
                "layout manifest line 3: only strings, integers and arrays of strings are supported");
        refused(head + "default_layout = \"CVACT01Y\"\nschema = 1\n", "layout manifest line 4: key given twice");
        refused(head + "default_layout = \"CVACT01Y\"\npath = \"x\"\n", "layout manifest line 4: unknown key");
        refused(head + "default_layout = \"CVACT01Y\" x\n", "layout manifest line 3: only a comment may follow a value");
        refused(head + "default_layout = \"CVACT01Y\"\nother_layout_accounts = [1]\n",
                "layout manifest line 4: an array holds only strings");
        refused(head + "default_layout = \"CVACT01Y\"\nother_layout_accounts = [\"1\"\n",
                "layout manifest line 5: an array is not closed");
        refused(head + "default_layout = \"CVACT01Y\"\r", "layout manifest line 3: a carriage return must be followed by a line feed");
    }

    @Test
    void keysAndTypesAreChecked() {
        refused("schema = 2\nsha256 = \"" + HASH + "\"\ndefault_layout = \"CVACT01Y\"\n",
                "layout manifest line 1: schema must be 1");
        refused("schema = \"1\"\nsha256 = \"" + HASH + "\"\ndefault_layout = \"CVACT01Y\"\n",
                "layout manifest line 1: schema must be 1");
        refused("schema = 1\nsha256 = \"" + HASH.toUpperCase() + "\"\ndefault_layout = \"CVACT01Y\"\n",
                "layout manifest line 2: sha256 must be 64 lowercase hex digits");
        refused("schema = 1\ndefault_layout = \"CVACT01Y\"\n", "layout manifest: missing sha256");
        refused("schema = 1\nsha256 = \"" + HASH + "\"\n", "layout manifest: missing default_layout");
        refused("schema = 1\nsha256 = \"" + HASH + "\"\ndefault_layout = \"cvact01y\"\n",
                "layout manifest line 3: default_layout must be CVACT01Y or ACUP_UPDATE");
        refused("schema = 1\nsha256 = \"" + HASH + "\"\ndefault_layout = \"CVACT01Y\"\nother_layout_accounts = \"1\"\n",
                "layout manifest line 4: other_layout_accounts must be an array of strings");
    }

    @Test
    void aManifestOverOneMebibyteIsRefusedBeforeItIsParsed() {
        byte[] big = new byte[AccountLayoutManifest.MAX_BYTES + 1];
        assertThatThrownBy(() -> AccountLayoutManifest.parse(big)).hasMessage("layout manifest is larger than 1 MiB");
    }

    @Test
    void overridesAreCheckedInArrayOrderByPosition() {
        Set<String> file = Set.of("00000000001", "00000000002");
        AccountLayoutManifest m = new AccountLayoutManifest(HASH, AccountLayout.CVACT01Y,
                List.of("00000000001", "0000000002", "00000000001"));
        assertThatThrownBy(() -> m.requireOverridesIn(file)).hasMessage("layout manifest override 2: not 11 digits");
        assertThatThrownBy(() -> new AccountLayoutManifest(HASH, AccountLayout.CVACT01Y,
                List.of("00000000001", "00000000001")).requireOverridesIn(file))
                .hasMessage("layout manifest override 2: listed twice");
        assertThatThrownBy(() -> new AccountLayoutManifest(HASH, AccountLayout.CVACT01Y,
                List.of("00000000002", "00000000009")).requireOverridesIn(file))
                .hasMessage("layout manifest override 2: not in acctdata.txt")
                .message().doesNotContain("00000000009");
    }
}
