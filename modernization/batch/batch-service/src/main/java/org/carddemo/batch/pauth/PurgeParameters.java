package org.carddemo.batch.pauth;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.batch.core.job.parameters.InvalidJobParametersException;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersValidator;

/**
 * The parameters of {@code purgePendingAuthorizationsJob} (batch-execution-contract.md, cycle
 * jobs): identifying {@code cycleId}, {@code businessDate} and {@code attempt}; non-identifying
 * {@code expiryDays} and {@code commitInterval}, fixed at the first launch so a restart uses the
 * stored values. Age is counted from the cycle's business date, never from the clock.
 */
record PurgeParameters(long cycleId, LocalDate businessDate, long attempt, long expiryDays, int commitInterval) {

    static final String CYCLE_ID = "cycleId";
    static final String BUSINESS_DATE = "businessDate";
    static final String ATTEMPT = "attempt";
    static final String EXPIRY_DAYS = "expiryDays";
    static final String COMMIT_INTERVAL = "commitInterval";

    /** The lowest {@code DATE} SQL Server stores. */
    static final LocalDate FIRST_SQL_DATE = LocalDate.of(1, 1, 1);

    static final JobParametersValidator VALIDATOR = PurgeParameters::of;

    static PurgeParameters of(JobParameters p) throws InvalidJobParametersException {
        long cycleId = value(p, CYCLE_ID, Long.class, true);
        LocalDate businessDate = value(p, BUSINESS_DATE, LocalDate.class, true);
        long attempt = value(p, ATTEMPT, Long.class, true);
        long expiryDays = value(p, EXPIRY_DAYS, Long.class, false);
        long commitInterval = value(p, COMMIT_INTERVAL, Long.class, false);
        if (expiryDays < 1) {
            throw new InvalidJobParametersException(EXPIRY_DAYS
                    + " must be at least 1: 0 would purge every detail dated up to the business date (DEF-AUPG-001)");
        }
        if (commitInterval < 1 || commitInterval > Integer.MAX_VALUE) {
            throw new InvalidJobParametersException(COMMIT_INTERVAL + " must be between 1 and " + Integer.MAX_VALUE);
        }
        if (cutoff(businessDate, expiryDays).isEmpty()) {
            throw new InvalidJobParametersException(BUSINESS_DATE + " - " + EXPIRY_DAYS
                    + " is before " + FIRST_SQL_DATE + ", the first date the database stores");
        }
        return new PurgeParameters(cycleId, businessDate, attempt, expiryDays, (int) commitInterval);
    }

    /** For the steps: the job's validator has already accepted the parameters. */
    static PurgeParameters from(JobParameters p) {
        try {
            return of(p);
        } catch (InvalidJobParametersException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * The last expired day: a detail is expired when {@code CAST(processed_at AS DATE)} is on or
     * before it, so a detail dated after the business date never is.
     */
    LocalDate detailCutoff() {
        return cutoff(businessDate, expiryDays).orElseThrow();
    }

    /** {@link #detailCutoff()} as the sargable bound {@code processed_at < detailsBefore()}, no time zone. */
    LocalDateTime detailsBefore() {
        return detailCutoff().plusDays(1).atStartOfDay();
    }

    /**
     * The bound for the UTC message timestamps: the earlier of the business-date cutoff and the
     * database's UTC date minus the expiry, so a business date ahead of the clock never deletes a
     * fresh idempotency row. Empty when that day is before the first stored date.
     */
    Optional<LocalDateTime> messagesBefore(LocalDate databaseUtcDate) {
        LocalDate business = detailCutoff();
        return cutoff(databaseUtcDate, expiryDays)
                .map(clock -> clock.isBefore(business) ? clock : business)
                .map(day -> day.plusDays(1).atStartOfDay());
    }

    private static Optional<LocalDate> cutoff(LocalDate day, long expiryDays) {
        try {
            LocalDate cutoff = day.minusDays(expiryDays);
            return cutoff.isBefore(FIRST_SQL_DATE) ? Optional.empty() : Optional.of(cutoff);
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    private static <T> T value(JobParameters p, String name, Class<T> type, boolean identifying)
            throws InvalidJobParametersException {
        JobParameter<?> parameter = p == null ? null : p.getParameter(name);
        if (parameter == null || parameter.value() == null) {
            throw new InvalidJobParametersException(name + " is missing");
        }
        if (!type.isInstance(parameter.value())) {
            throw new InvalidJobParametersException(name + " must be a " + type.getSimpleName());
        }
        if (parameter.identifying() != identifying) {
            throw new InvalidJobParametersException(name + " must be " + (identifying ? "" : "non-") + "identifying");
        }
        return type.cast(parameter.value());
    }
}
