package org.carddemo.batch.load;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param sourceDir directory of the sample files ({@code app/data/ASCII}); the job
 *        parameter {@code sourceDir} overrides it for one run
 * @param reloadAllowed whether the load may empty tables that already hold rows.
 *        Only local and demo environments set it (D-41): elsewhere the load is a
 *        seed load for an empty database.
 */
@ConfigurationProperties("carddemo.data-load")
public record DataLoadProperties(Path sourceDir, boolean reloadAllowed) {
}
