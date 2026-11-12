package org.carddemo.batch.pauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The pending-authorization settings of batch-service. There are no defaults in code: the
 * application properties hold them.
 *
 * @param sourceFile the IMS HD unload that {@code loadPendingAuthorizations} reads
 *        (ENT-AUTH-HD-UNLOAD), relative to the working directory or absolute. A string, not a
 *        {@code Path}: in the server profile the context's resource editor would read a relative
 *        path as a servlet resource and refuse one that starts with {@code ..}.
 * @param expiryDays days after which the purge deletes a detail (D-34). The launch passes it to
 *        {@code purgePendingAuthorizationsJob} as the job parameter {@code expiryDays}; the job
 *        itself reads only its parameters, so a restart uses the stored value.
 * @param commitInterval summaries per purge chunk transaction, and message rows per transaction
 *        of the message step; passed the same way as the parameter {@code commitInterval}
 */
@ConfigurationProperties("carddemo.batch.pauth")
public record PendingAuthorizationProperties(String sourceFile, Long expiryDays, Long commitInterval) {
}
