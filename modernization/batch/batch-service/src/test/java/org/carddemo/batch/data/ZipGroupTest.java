package org.carddemo.batch.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** DEF-ACUP-002 ruling v4, positions 103-122: blank fields, the two layouts and the per-row export rule. */
class ZipGroupTest {

    private static final String NUL10 = "\0".repeat(10);
    private static final String SPACES10 = " ".repeat(10);

    @Test
    void aFieldIsBlankWhenItIsAllSpacesOrAllNulsAndNotWhenMixed() {
        assertThat(ZipGroup.blank(SPACES10)).isTrue();
        assertThat(ZipGroup.blank(NUL10)).isTrue();
        assertThat(ZipGroup.blank("\0" + " ".repeat(9))).isFalse();
        assertThat(ZipGroup.blank("A" + " ".repeat(9))).isFalse();
        assertThatThrownBy(() -> ZipGroup.blank("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyTrailingSpacesAreStripped() {
        assertThat(ZipGroup.stripTrailingSpaces(" A\0  ")).isEqualTo(" A\0");
        assertThat(ZipGroup.stripTrailingSpaces(NUL10)).isEqualTo(NUL10);
        assertThat(ZipGroup.stripTrailingSpaces(SPACES10)).isEmpty();
    }

    @Test
    void theCvact01yLayoutStoresBothFieldsAsReadAndKeepsTheTwentyCharacters() {
        String read = "12345     " + "G1\0       ";
        assertThat(ZipGroup.classify(read, AccountLayout.CVACT01Y))
                .isEqualTo(new ZipGroup.Stored("12345", "G1\0", read, "R"));
    }

    @Test
    void theUpdateLayoutMovesTheZipPositionToTheGroupAndNeedsABlankGroupPosition() {
        assertThat(ZipGroup.classify("A000000000" + SPACES10, AccountLayout.ACUP_UPDATE))
                .isEqualTo(new ZipGroup.Stored("", "A000000000", "A000000000" + SPACES10, "M"));
        assertThat(ZipGroup.classify("A0\0       " + NUL10, AccountLayout.ACUP_UPDATE))
                .isEqualTo(new ZipGroup.Stored("", "A0\0", "A0\0       " + NUL10, "M"));
        assertThat(ZipGroup.classify(NUL10 + SPACES10, AccountLayout.ACUP_UPDATE).groupId()).isEmpty();
        assertThatThrownBy(() -> ZipGroup.classify("A000000000" + "B000000000", AccountLayout.ACUP_UPDATE))
                .isInstanceOf(AccountLayoutException.class)
                .hasMessage("declared ACUP_UPDATE but positions 113-122 are not blank");
        assertThatThrownBy(() -> ZipGroup.classify("A000000000" + "\0" + " ".repeat(9), AccountLayout.ACUP_UPDATE))
                .isInstanceOf(AccountLayoutException.class);
    }

    @Test
    void anUnchangedRowExportsItsLoadTimeTextWhateverItsOrigin() {
        String asRead = "A000000000" + SPACES10;
        assertThat(ZipGroup.export("A000000000", SPACES10, asRead, "R"))
                .isEqualTo(new ZipGroup.Exported(asRead, true));
        assertThat(ZipGroup.export("", "A000000000", asRead, "M"))
                .isEqualTo(new ZipGroup.Exported(asRead, true));
        String nuls = "A0\0       " + NUL10;
        assertThat(ZipGroup.export("", "A0\0       ", nuls, "M")).isEqualTo(new ZipGroup.Exported(nuls, true));
        String blankMigrated = NUL10 + SPACES10;
        assertThat(ZipGroup.export("", SPACES10, blankMigrated, "M"))
                .isEqualTo(new ZipGroup.Exported(blankMigrated, true));
    }

    @Test
    void aChangedOrUnprovenancedRowIsRebuiltInTheCvact01yLayout() {
        String asRead = "A000000000" + SPACES10;
        assertThat(ZipGroup.export("", "GOLD", asRead, "M"))
                .isEqualTo(new ZipGroup.Exported(SPACES10 + "GOLD      ", false));
        assertThat(ZipGroup.export("A000000001", "", asRead, "R"))
                .isEqualTo(new ZipGroup.Exported("A000000001" + SPACES10, false));
        assertThat(ZipGroup.export("12345", "G1", null, null))
                .isEqualTo(new ZipGroup.Exported("12345     G1        ", false));
        // Compared in Java: a trailing NUL is data and is never trimmed.
        assertThat(ZipGroup.export("", "A00000000", "A00000000\0" + SPACES10, "M").restored()).isFalse();
        assertThat(ZipGroup.export("", "A00000000\0", "A00000000\0" + SPACES10, "M").restored()).isTrue();
    }

    @Test
    void aValueChangedAndChangedBackIsRestoredAgain() {
        String asRead = "A000000000" + SPACES10;
        assertThat(ZipGroup.export("", "GOLD", asRead, "M").restored()).isFalse();
        assertThat(ZipGroup.export("", "A000000000", asRead, "M").restored()).isTrue();
    }
}
