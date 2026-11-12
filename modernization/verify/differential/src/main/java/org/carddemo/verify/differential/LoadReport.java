package org.carddemo.verify.differential;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameter;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

/**
 * What one {@code dataLoadJob} execution reports about its accounts (load report schema 1), and
 * the checks that it is a valid, completed and still current load. {@code --load-report} prints it;
 * a v2 export runs the same checks on its {@code --load-execution-id}. Failures name jobs,
 * executions, steps, keys and counts only, never an account or a file's content.
 *
 * <p>The key names are those the load writes; they are spelled out here rather than taken from
 * batch-service so that a renamed key fails this reader instead of following it silently.
 */
record LoadReport(long loadExecutionId, LocalDate businessDate, String groupIdMode, long accountRecords,
        long accountsAsRead, long accountsMigrated, String accountLayoutSource, long accountLayoutWarnings,
        String acctdataSha256, String accountLayoutManifestSha256) {

    static final int SCHEMA_VERSION = 1;
    static final String JOB = "dataLoadJob";
    static final String STEP = "loadAccounts";

    static final String AS_READ = "accountsAsRead";
    static final String MIGRATED = "accountsMigrated";
    static final String LAYOUT_SOURCE = "accountLayoutSource";
    static final String LAYOUT_WARNINGS = "accountLayoutWarnings";
    static final String ACCTDATA_SHA256 = "acctdataSha256";
    static final String MANIFEST_SHA256 = "accountLayoutManifestSha256";
    static final String GROUP_ID_MODE = "groupIdMode";
    static final String JOB_ACCTDATA_SHA256 = "dataLoad.acctdataSha256";
    static final String JOB_MANIFEST_SHA256 = "dataLoad.accountLayoutManifestSha256";

    private static final Set<String> SOURCES = Set.of("supplied", "builtin", "none", "ignored");
    /** The non-identifying parameters batch-service's launch gives dataLoadJob; any other is refused. */
    private static final Set<String> NON_IDENTIFYING = Set.of("businessDate", "windowRunKey");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** Reads execution {@code id} from the job repository and checks it (SELECT only). */
    static LoadReport read(JobRepository repository, long id) {
        JobExecution execution = repository.getJobExecution(id);
        if (execution == null) {
            throw new ExportException(JOB + " execution " + id + " does not exist");
        }
        long last = id;
        if (JOB.equals(execution.getJobInstance().getJobName())) {
            for (JobInstance instance : repository.findJobInstances(JOB)) {
                for (JobExecution e : repository.getJobExecutions(instance)) {
                    last = Math.max(last, e.getId());
                }
            }
        }
        return of(execution, last);
    }

    /**
     * The checks of the load report on an execution read with its steps.
     *
     * @param lastLoadId the highest {@code dataLoadJob} execution ID in the repository
     */
    static LoadReport of(JobExecution execution, long lastLoadId) {
        long id = execution.getId();
        String jobName = execution.getJobInstance().getJobName();
        if (!JOB.equals(jobName)) {
            throw new ExportException("execution " + id + " is " + jobName + ", not " + JOB);
        }
        String exit = execution.getExitStatus().getExitCode();
        if (execution.getStatus() != BatchStatus.COMPLETED || !"COMPLETED".equals(exit)) {
            throw new ExportException(JOB + " execution " + id + " is " + execution.getStatus() + " " + exit
                    + ", not completed");
        }
        LocalDate businessDate = launchParameters(execution);
        if (lastLoadId > id) {
            throw new ExportException(JOB + " execution " + id + " is not the last load (a later " + JOB
                    + " execution exists)");
        }
        StepExecution step = null;
        int steps = 0;
        for (StepExecution s : execution.getStepExecutions()) {
            if (s.getStepName().equals(STEP)) {
                step = s;
                steps++;
            }
        }
        if (steps != 1 || step.getStatus() != BatchStatus.COMPLETED) {
            throw new ExportException("step " + STEP + " of execution " + id + " did not complete");
        }
        ExecutionContext context = step.getExecutionContext();
        LoadReport report = new LoadReport(id, businessDate, text(context, GROUP_ID_MODE), step.getWriteCount(),
                number(context, AS_READ), number(context, MIGRATED), text(context, LAYOUT_SOURCE),
                number(context, LAYOUT_WARNINGS), text(context, ACCTDATA_SHA256), text(context, MANIFEST_SHA256));
        List<String> broken = report.inconsistencies(execution.getExecutionContext());
        if (!broken.isEmpty()) {
            throw new ExportException(JOB + " execution " + id + ": inconsistent load report ("
                    + String.join("; ", broken) + ")");
        }
        return report;
    }

    /**
     * The parameters batch-service's launch gives {@code dataLoadJob}: {@code runKey} the only
     * identifying one, a non-blank string; {@code businessDate} a non-identifying date; and at
     * most {@code windowRunKey} besides, a non-identifying, non-blank string.
     */
    private static LocalDate launchParameters(JobExecution execution) {
        List<String> identifying = new ArrayList<>();
        boolean other = false;
        for (JobParameter<?> p : execution.getJobParameters()) {
            if (p.identifying()) {
                identifying.add(p.name());
            } else {
                other |= !NON_IDENTIFYING.contains(p.name());
            }
        }
        JobParameter<?> runKey = execution.getJobParameters().getParameter("runKey");
        JobParameter<?> businessDate = execution.getJobParameters().getParameter("businessDate");
        JobParameter<?> windowRunKey = execution.getJobParameters().getParameter("windowRunKey");
        if (other || !identifying.equals(List.of("runKey")) || !(runKey.value() instanceof String key) || key.isBlank()
                || businessDate == null || !(businessDate.value() instanceof LocalDate date)
                || windowRunKey != null && !(windowRunKey.value() instanceof String window && !window.isBlank())) {
            throw new ExportException(JOB + " execution " + execution.getId() + " has unexpected parameters");
        }
        return date;
    }

    private static long number(ExecutionContext context, String key) {
        if (!(context.get(key) instanceof Number n) || n instanceof Double || n instanceof Float) {
            throw new ExportException("step " + STEP + " has no " + key);
        }
        return n.longValue();
    }

    private static String text(ExecutionContext context, String key) {
        if (!(context.get(key) instanceof String s)) {
            throw new ExportException("step " + STEP + " has no " + key);
        }
        return s;
    }

    /** The rules a load report holds to, by name; {@code job} is the execution's own context. */
    List<String> inconsistencies(ExecutionContext job) {
        List<String> broken = new ArrayList<>();
        if (accountsAsRead < 0 || accountsMigrated < 0 || accountLayoutWarnings < 0
                || accountsAsRead + accountsMigrated != accountRecords) {
            broken.add(AS_READ + " + " + MIGRATED + " is not the write count");
        }
        if (!SOURCES.contains(accountLayoutSource)) {
            broken.add(LAYOUT_SOURCE + " is not supplied, builtin, none or ignored");
        }
        boolean ignored = "ignored".equals(accountLayoutSource);
        if (!(ignored ? "legacy" : "fixed").equals(groupIdMode)
                || ignored && (accountsMigrated != 0 || accountLayoutWarnings != 0)) {
            broken.add(GROUP_ID_MODE + " legacy exactly when the layouts are ignored, then nothing migrated or warned");
        }
        if (!SHA256.matcher(acctdataSha256).matches()) {
            broken.add(ACCTDATA_SHA256 + " is not a SHA-256");
        }
        boolean supplied = "supplied".equals(accountLayoutSource);
        if (supplied ? !SHA256.matcher(accountLayoutManifestSha256).matches() : !accountLayoutManifestSha256.isEmpty()) {
            broken.add(MANIFEST_SHA256 + " set exactly when the manifest is supplied");
        }
        if (!acctdataSha256.equals(job.get(JOB_ACCTDATA_SHA256))) {
            broken.add(ACCTDATA_SHA256 + " differs from the job's " + JOB_ACCTDATA_SHA256);
        }
        Object jobManifest = job.get(JOB_MANIFEST_SHA256);
        if (!accountLayoutManifestSha256.equals(jobManifest == null ? "" : jobManifest)) {
            broken.add(MANIFEST_SHA256 + " differs from the job's " + JOB_MANIFEST_SHA256);
        }
        return broken;
    }

    /** The report's JSON object, keys in schema order. */
    byte[] json() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", (long) SCHEMA_VERSION);
        out.put("loadExecutionId", loadExecutionId);
        out.put("jobName", JOB);
        out.put("status", "COMPLETED");
        out.put("exitCode", "COMPLETED");
        out.put("businessDate", businessDate.toString());
        out.put("groupIdMode", groupIdMode);
        out.put("accountRecords", accountRecords);
        out.put("accountsAsRead", accountsAsRead);
        out.put("accountsMigrated", accountsMigrated);
        out.put("accountLayoutSource", accountLayoutSource);
        out.put("accountLayoutWarnings", accountLayoutWarnings);
        out.put("acctdataSha256", acctdataSha256);
        out.put("accountLayoutManifestSha256", accountLayoutManifestSha256);
        return CountsJson.of(out);
    }
}
