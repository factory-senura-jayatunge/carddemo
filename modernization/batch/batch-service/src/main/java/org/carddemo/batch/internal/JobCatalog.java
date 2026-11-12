package org.carddemo.batch.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The jobs of the execution contract (section 2.1) with their kind, their window rule (section 6)
 * and the non-identifying parameters batch-service adds. A job is launchable once its Job bean of
 * the same name exists; until then a launch answers 404 JOB_NOT_FOUND.
 */
public final class JobCatalog {

    public enum Kind { CYCLE, REPORT, RUN_KEYED }

    /** Non-identifying parameters a job gets besides the kind's own. */
    public enum Extra {
        /** The cycle's interest_parm (calculateInterestJob). */
        INTEREST_PARM,
        /** carddemo.batch.pauth.expiry-days (the purge, and D-33 matching in posting). */
        EXPIRY_DAYS,
        /** carddemo.batch.pauth.commit-interval (the purge). */
        COMMIT_INTERVAL,
        /** The request's exportId, a COMPLETED batch.export_run (importMigrationJob). */
        EXPORT_ID
    }

    /**
     * @param pauthOnly the job reads and writes only pauth, so it may run whatever the cycle's status
     *        and attempt: its launch neither takes the launch lock for the cycle check nor re-reads the
     *        cycle (contract section 2.1, the cycle row under the launch lock)
     */
    public record JobSpec(String name, Kind kind, boolean needsWindow, Set<Extra> extras, boolean pauthOnly) {

        public JobSpec(String name, Kind kind, boolean needsWindow, Set<Extra> extras) {
            this(name, kind, needsWindow, extras, false);
        }

        /** Whether a launch re-reads the cycle row under the launch lock before it creates anything. */
        public boolean checksCycle() {
            return kind == Kind.CYCLE && !pauthOnly;
        }
    }

    private final Map<String, JobSpec> specs = new LinkedHashMap<>();

    public JobCatalog(Collection<JobSpec> specs) {
        specs.forEach(s -> this.specs.put(s.name(), s));
    }

    public static List<JobSpec> contract() {
        return List.of(
                new JobSpec("archiveMasterJob", Kind.CYCLE, true, Set.of()),
                new JobSpec("ingestDailyFileJob", Kind.CYCLE, true, Set.of()),
                new JobSpec("postDailyTransactionsJob", Kind.CYCLE, true, Set.of(Extra.EXPIRY_DAYS)),
                new JobSpec("calculateInterestJob", Kind.CYCLE, true, Set.of(Extra.INTEREST_PARM)),
                new JobSpec("statementJob", Kind.CYCLE, true, Set.of()),
                new JobSpec("applyRetentionJob", Kind.CYCLE, true, Set.of()),
                // It writes only pauth, which the window does not cover, and may run after the cycle completed.
                new JobSpec("purgePendingAuthorizationsJob", Kind.CYCLE, false,
                        Set.of(Extra.EXPIRY_DAYS, Extra.COMMIT_INTERVAL), true),
                new JobSpec("transactionReportJob", Kind.REPORT, false, Set.of()),
                new JobSpec("categoryBalanceListingJob", Kind.RUN_KEYED, false, Set.of()),
                new JobSpec("dataLoadJob", Kind.RUN_KEYED, true, Set.of()),
                new JobSpec("disclosureGroupReloadJob", Kind.RUN_KEYED, true, Set.of()),
                new JobSpec("transactionTypeMaintenanceJob", Kind.RUN_KEYED, true, Set.of()),
                new JobSpec("exportMastersJob", Kind.RUN_KEYED, false, Set.of()),
                new JobSpec("importMigrationJob", Kind.RUN_KEYED, true, Set.of(Extra.EXPORT_ID)));
    }

    public Optional<JobSpec> find(String name) {
        return Optional.ofNullable(specs.get(name));
    }

    public List<String> cycleJobs() {
        return specs.values().stream().filter(s -> s.kind() == Kind.CYCLE).map(JobSpec::name).toList();
    }

    public List<String> windowJobs() {
        return specs.values().stream().filter(JobSpec::needsWindow).map(JobSpec::name).toList();
    }
}
