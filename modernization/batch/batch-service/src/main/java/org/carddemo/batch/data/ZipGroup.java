package org.carddemo.batch.data;

import org.carddemo.platform.legacyformat.entity.AccountRecord;

/**
 * Positions 103-122 of an account record under DEF-ACUP-002 (ruling v4, data-model.md "Group ID
 * at the ZIP position"): how a record is stored, and what an export writes back.
 *
 * <p>A 10-character field is <em>blank</em> when it is all U+0020 or all U+0000, and in no other
 * case: a mix of spaces and NULs is data. "Without trailing spaces" removes U+0020 only; a NUL is
 * never trimmed. Values read back from the database are compared here, in Java: SQL {@code =}
 * ignores trailing spaces and {@code addr_zip} has a case-insensitive collation.
 */
public final class ZipGroup {

    /** Width of {@code ACCT-ADDR-ZIP} and of {@code ACCT-GROUP-ID}. */
    public static final int WIDTH = 10;
    /** {@code zip_group_origin} of a record stored as read. */
    public static final String AS_READ = "R";
    /** {@code zip_group_origin} of a record migrated per D-11. */
    public static final String MIGRATED = "M";

    /** What the load stores for one record. */
    public record Stored(String addrZip, String groupId, String legacyZipGroup, String origin) {
    }

    /** What an export writes at 103-122, and whether it is the load-time text unchanged. */
    public record Exported(String zipGroup, boolean restored) {
    }

    private ZipGroup() {
    }

    /**
     * The 20 characters at 103-122 as read. {@link AccountRecord#parse} keeps both fields without
     * their trailing spaces only, so padding them back with spaces restores the text exactly.
     */
    public static String asRead(AccountRecord a) {
        return pad(a.addrZip()) + pad(a.groupId());
    }

    public static boolean blank(String field) {
        if (field.length() != WIDTH) {
            throw new IllegalArgumentException("a ZIP or group field has " + WIDTH + " characters");
        }
        return field.chars().allMatch(c -> c == ' ') || field.chars().allMatch(c -> c == 0);
    }

    /** Whether a record declared in {@code layout} can be stored: an update-layout record needs a blank 113-122. */
    public static boolean acceptable(String zipGroup, AccountLayout layout) {
        return layout == AccountLayout.CVACT01Y || blank(group(zipGroup));
    }

    /**
     * The stored values: as read for {@link AccountLayout#CVACT01Y}, the D-11 move for
     * {@link AccountLayout#ACUP_UPDATE}. The full 20 characters are kept either way.
     *
     * @throws AccountLayoutException when an update-layout record has data at 113-122; the caller
     *         adds the file line
     */
    public static Stored classify(String zipGroup, AccountLayout layout) {
        requireWidth(zipGroup);
        if (!acceptable(zipGroup, layout)) {
            throw new AccountLayoutException("declared ACUP_UPDATE but positions 113-122 are not blank");
        }
        return layout == AccountLayout.CVACT01Y
                ? new Stored(stripTrailingSpaces(zip(zipGroup)), stripTrailingSpaces(group(zipGroup)), zipGroup, AS_READ)
                : new Stored("", migratedGroup(zipGroup), zipGroup, MIGRATED);
    }

    /**
     * The export of one row, with no mode (ruling v4 section 4): the load-time text when the row's
     * current ZIP and group equal the values the load stored from it, else the {@code CVACT01Y}
     * layout. {@code groupId} may come back from its {@code NCHAR(10)} column padded with spaces.
     */
    public static Exported export(String addrZip, String groupId, String legacyZipGroup, String origin) {
        String zip = stripTrailingSpaces(addrZip == null ? "" : addrZip);
        String group = stripTrailingSpaces(groupId == null ? "" : groupId);
        if (legacyZipGroup != null && origin != null) {
            requireWidth(legacyZipGroup);
            Stored loaded = switch (origin) {
                case AS_READ -> classify(legacyZipGroup, AccountLayout.CVACT01Y);
                case MIGRATED -> new Stored("", migratedGroup(legacyZipGroup), legacyZipGroup, MIGRATED);
                default -> throw new IllegalArgumentException("zip_group_origin is R or M");
            };
            if (zip.equals(loaded.addrZip()) && group.equals(loaded.groupId())) {
                return new Exported(legacyZipGroup, true);
            }
        }
        return new Exported(pad(zip) + pad(group), false);
    }

    /** A value without its trailing U+0020; NULs and leading spaces are kept. */
    public static String stripTrailingSpaces(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    static String zip(String zipGroup) {
        return zipGroup.substring(0, WIDTH);
    }

    static String group(String zipGroup) {
        return zipGroup.substring(WIDTH, 2 * WIDTH);
    }

    private static String migratedGroup(String zipGroup) {
        String moved = zip(zipGroup);
        return blank(moved) ? "" : stripTrailingSpaces(moved);
    }

    private static String pad(String field) {
        if (field.length() > WIDTH) {
            throw new IllegalArgumentException("a ZIP or group value is longer than " + WIDTH + " characters");
        }
        return field + " ".repeat(WIDTH - field.length());
    }

    private static void requireWidth(String zipGroup) {
        if (zipGroup.length() != 2 * WIDTH) {
            throw new IllegalArgumentException("positions 103-122 hold " + 2 * WIDTH + " characters");
        }
    }
}
