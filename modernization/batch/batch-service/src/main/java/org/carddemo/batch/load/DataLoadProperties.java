package org.carddemo.batch.load;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * @param sourceDir directory of the sample files ({@code app/data/ASCII}), relative to the
 *        working directory or absolute. A string, not a {@code Path}: in the server profile the
 *        context's resource editor would read a relative path as a servlet resource and refuse
 *        one that starts with {@code ..}.
 * @param reloadAllowed whether the load may empty tables that already hold rows.
 *        Only local and demo environments set it (D-41): elsewhere the load is a
 *        seed load for an empty database.
 * @param accountLayoutManifest the DEF-ACUP-002 layout manifest of {@code acctdata.txt}, relative
 *        to the working directory or absolute; unset or blank means the built-in lookup by the
 *        file's SHA-256. Read in fixed mode only. A property like {@code sourceDir}, fixed when the
 *        JVM starts.
 */
@ConfigurationProperties("carddemo.data-load")
public record DataLoadProperties(String sourceDir, boolean reloadAllowed, String accountLayoutManifest) {

    @ConstructorBinding
    public DataLoadProperties {
    }

    /** No supplied layout manifest. */
    public DataLoadProperties(String sourceDir, boolean reloadAllowed) {
        this(sourceDir, reloadAllowed, null);
    }
}
