package org.carddemo.batch.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.carddemo.platform.fidelity.FidelityMode;

/**
 * The layout of each account record for one load (DEF-ACUP-002, ruling v4 section 2): which
 * manifest applies to the bytes of {@code acctdata.txt}, chosen by their SHA-256 only, never by a
 * file name or directory.
 *
 * <ul>
 * <li>Legacy mode ignores every manifest: every record is stored as read.
 * <li>Fixed mode with a supplied manifest: its hash must equal the file's, and the built-in one is
 * not consulted.
 * <li>Fixed mode without one: the built-in manifest whose hash equals the file's, else none.
 * </ul>
 *
 * The one built-in manifest covers the ASCII sample {@code app/data/ASCII/acctdata.txt}, all
 * records {@code ACUP_UPDATE}: the population D-11 decided. The EBCDIC copy has no entry.
 */
public final class AccountLayouts {

    /** Where the layouts of a load came from, as the load report names it. */
    public enum Source {
        SUPPLIED("supplied"), BUILTIN("builtin"), NONE("none"), IGNORED("ignored");

        private final String key;

        Source(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    static final List<String> BUILT_IN = List.of("acctdata-ascii.layout.toml");

    private static final AccountLayouts AS_READ = new AccountLayouts(Source.IGNORED, null);

    private final Source source;
    private final AccountLayoutManifest manifest;

    private AccountLayouts(Source source, AccountLayoutManifest manifest) {
        this.source = source;
        this.manifest = manifest;
    }

    /** Every record as read, as in legacy mode. */
    public static AccountLayouts asRead() {
        return AS_READ;
    }

    /**
     * @param acctdata the bytes of {@code acctdata.txt} as read, the same bytes the load classifies
     * @param accountIds the account IDs of those records
     * @param supplied the bytes of the supplied manifest, or {@code null}
     */
    public static AccountLayouts resolve(FidelityMode mode, byte[] acctdata, Set<String> accountIds, byte[] supplied) {
        if (mode != FidelityMode.FIXED) {
            return AS_READ;
        }
        String hash = sha256(acctdata);
        if (supplied != null) {
            AccountLayoutManifest m = AccountLayoutManifest.parse(supplied);
            if (!m.sha256().equals(hash)) {
                throw new AccountLayoutException("layout manifest does not match acctdata.txt");
            }
            m.requireOverridesIn(accountIds);
            return new AccountLayouts(Source.SUPPLIED, m);
        }
        for (AccountLayoutManifest m : builtIn()) {
            if (m.sha256().equals(hash)) {
                m.requireOverridesIn(accountIds);
                return new AccountLayouts(Source.BUILTIN, m);
            }
        }
        return new AccountLayouts(Source.NONE, null);
    }

    public Source source() {
        return source;
    }

    /** Whether the fixed-mode warning applies: legacy mode ignores layouts and warns about nothing. */
    public boolean fixedMode() {
        return source != Source.IGNORED;
    }

    public AccountLayout layoutOf(String acctId) {
        return manifest == null ? AccountLayout.CVACT01Y : manifest.layoutOf(Objects.requireNonNull(acctId));
    }

    /** The built-in manifests, read from the classpath next to this class. */
    public static List<AccountLayoutManifest> builtIn() {
        return BUILT_IN.stream().map(AccountLayouts::resource).toList();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AccountLayoutManifest resource(String name) {
        try (InputStream in = AccountLayouts.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("built-in layout manifest " + name + " is missing");
            }
            return AccountLayoutManifest.parse(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
