package org.carddemo.batch.execution;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code PUBLICATION_MISMATCH} (batch-execution-contract.md section 4.8): a working path or a
 * target is not what the publication expects, so nothing is published and nothing is replaced.
 *
 * <p>The publication and the clean-up throw it with a {@link Reason} only, as
 * {@code PUBLICATION_MISMATCH: <reason>: <fixed text>}, the text holding at most counts and line
 * numbers. A name in an output folder can be personal data (a statement file is
 * {@code <acct_id>.pdf}), so no message names an entry, a file or a path (section 4.8, Logs).
 */
public final class PublicationException extends RuntimeException {

    public static final String CODE = "PUBLICATION_MISMATCH";

    /** The closed set of reasons; a {@code %d} in a text is a count or a line number. */
    public enum Reason {
        NO_SHA256("no SHA-256 in lower-case hex"),
        INTENT_NO_WORK_PATH("the intent names no working path"),
        INTENT_INCOMPLETE("the job execution context holds an incomplete intent"),
        WORK_EXISTS("the working path exists already; it is not this execution's and is left in place"),
        FILE_MISSING("a managed file is missing"),
        NOTHING_TO_PUBLISH("neither the target nor its working path exists; nothing is published"),
        WORK_SHA256_MISMATCH("a working file does not have the SHA-256 of the output; nothing is published"
                + " from it"),
        TARGET_SHA256_MISMATCH("the target does not have the SHA-256 of the output; it is kept and nothing is replaced"),
        WORK_ENTRY_UNNAMED("the working folder holds an entry whose name is no output file name"),
        WORK_ENTRY_UNLISTED("the working folder holds an entry that its manifest does not list; nothing is published"),
        CLAIM_MISSING("the final folder holds no claim; nothing is linked"),
        CLAIM_OTHER_OUTPUT("the final folder's claim names another output; nothing is linked"),
        FINAL_ENTRY_UNLISTED("the final folder holds %d entries that the manifest does not list; nothing is linked"),
        FINAL_NOT_EXACT("the final folder does not hold exactly its manifest's files (%d missing, %d other);"
                + " nothing is removed"),
        FINAL_MISSING("a listed name is missing in the final folder and its working file is gone;"
                + " nothing more is linked"),
        FINAL_SHA256_MISMATCH("a name in the final folder does not have the SHA-256 of the output; it is kept"
                + " and nothing is replaced"),
        MANIFEST_TOO_LARGE("a manifest is larger than a manifest may be"),
        MANIFEST_NO_LINE_FEED("the manifest does not end with a line feed"),
        MANIFEST_LINE_FORMAT("manifest line %d is not <sha256>  <file name>"),
        MANIFEST_LINE_ORDER("manifest line %d is not above the line before it");

        private final String text;
        private final int counts;

        Reason(String text) {
            this.text = text;
            this.counts = text.split("%d", -1).length - 1;
        }

        String text(long... counts) {
            if (counts.length != this.counts) {
                throw new IllegalArgumentException(name() + " takes " + this.counts + " counts");
            }
            return String.format(Locale.ROOT, text, Arrays.stream(counts).boxed().toArray());
        }
    }

    private final Reason reason;

    /** The library's form: the reason and its counts, nothing else. */
    public PublicationException(Reason reason, long... counts) {
        super(CODE + ": " + Objects.requireNonNull(reason, "reason").name() + ": " + reason.text(counts),
                null, false, false);
        this.reason = reason;
    }

    /**
     * For a job outside the library; {@code detail} may name the output kind or an ID, never an
     * entry, a file or a path.
     */
    public PublicationException(String detail) {
        super(CODE + ": " + detail, null, false, false);
        this.reason = null;
    }

    public String code() {
        return CODE;
    }

    /** The reason, or null for a message given as text. */
    public Reason reason() {
        return reason;
    }

    /** The message without its code. */
    public String detail() {
        return getMessage().substring(CODE.length() + 2);
    }
}
