package org.carddemo.batch.cycle;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import org.carddemo.platform.ledger.PostedCategory;
import org.carddemo.platform.legacyformat.layout.Layouts;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * The FILLER CBTRN02C gives a category balance record it creates (DEF-POST-010):
 * 2700-A-CREATE-TCATBAL-REC INITIALIZEs TRAN-CAT-BAL-RECORD, which leaves its FILLER alone, so
 * the new record carries whatever the work area held: the FILLER of the last record that
 * {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD} found (a not-found read moves nothing),
 * else the initial working storage, low-values. The step's execution context holds it with the
 * last seq done, so a restart resumes with the work area the committed chunks left.
 */
final class CategoryFiller {

    static final String KEY = "post.lastFiller";

    /** The work area before any read. */
    static final String LOW_VALUES = "\u0000".repeat(PostedCategory.FILLER_LENGTH);

    /**
     * What a read of a row with no {@code legacy_filler} puts in the work area. Only online
     * posting creates such a row, and no legacy online program writes TCATBALF (COBIL00C never
     * opens it; CBTRN02C is its only writer, CBACT04C reads it): the row has no VSAM record of
     * its own. The record it stands for is the one its export writes, whose FILLER is CVTRA01Y's
     * constant, 22 zeros as in every legacy record; READ INTO would move exactly that.
     */
    static final String UNWRITTEN = String.valueOf(Layouts.TCAT_BAL.field("TCATBAL-FILLER").fill())
            .repeat(PostedCategory.FILLER_LENGTH);

    private static final HexFormat HEX = HexFormat.of();

    private CategoryFiller() {
    }

    /** The work area after a posting changed or created {@code category}. */
    static String after(PostedCategory category) {
        return category.legacyFiller() == null ? UNWRITTEN : category.legacyFiller();
    }

    static String from(ExecutionContext context) {
        if (!context.containsKey(KEY)) {
            return LOW_VALUES;
        }
        // Hex, because the filler may hold low-values and any other Latin-1 byte.
        String filler = new String(HEX.parseHex(context.getString(KEY)), StandardCharsets.ISO_8859_1);
        if (filler.length() != PostedCategory.FILLER_LENGTH) {
            throw new IllegalStateException(KEY + " holds " + filler.length() + " bytes, not "
                    + PostedCategory.FILLER_LENGTH);
        }
        return filler;
    }

    static void put(ExecutionContext context, String filler) {
        context.putString(KEY, HEX.formatHex(filler.getBytes(StandardCharsets.ISO_8859_1)));
    }
}
