package org.carddemo.verify.differential;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.carddemo.batch.BatchSwitches;
import org.carddemo.batch.cycle.CycleOutputs;
import org.carddemo.batch.data.CoreRows;
import org.carddemo.batch.data.ZipGroup;
import org.carddemo.batch.internal.ExecutionOutputs;
import org.carddemo.batch.internal.JobCatalog;
import org.carddemo.platform.fidelity.FidelityMode;
import org.carddemo.platform.legacyformat.entity.AccountRecord;
import org.carddemo.platform.legacyformat.entity.TcatBalRecord;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Exports what one cycle job left in the database to the files of the GnuCOBOL reference run
 * ({@code docs/modernization/spec/checks/harness.py} names), through the legacy-format encoders
 * the load and the jobs use. Everything is read and checked before the first file is written.
 *
 * <ul>
 * <li>POSTTRAN: {@code DALYREJS.txt}, {@code ACCTDATA.after.txt}, {@code TCATBALF.after.txt},
 * {@code TRANSACT.after.txt} (the master as CBTRN02C leaves it: the cycle's posted rows in key
 * order) and {@code counts.json}.
 * <li>INTCALC: {@code TRANSACT.txt} (the cycle's interest rows in the order CBACT04C writes them,
 * which is {@code tran_pk} order in either ID mode), {@code ACCTDATA.after.txt} and
 * {@code counts.json}.
 * </ul>
 *
 * The account and category balance files are the tables as they are now, so each export must run
 * right after its job; a POSTTRAN export after the cycle's interest run fails.
 *
 * <p>v1 puts each account's ZIP and group ID back in the given DEF-ACUP-002 mode, as the accounts
 * were loaded before the rows kept their load-time text. v2 ({@code --load-execution-id}) checks
 * that load (the {@link LoadReport} checks) and that every cycle job that ran came after it, writes
 * the accounts by the per-row rule of {@link CoreRows} and adds the load's and the rows' account
 * counts (schema 2).
 */
final class BatchExport {

    static final String COUNTS = "counts.json";

    private static final String MASTER_ORIGINS = "('POSTED', 'ONLINE_ADD')";

    private final JdbcClient db;
    private final JobRepository repository;

    BatchExport(JdbcClient db, JobRepository repository) {
        this.db = db;
        this.repository = repository;
    }

    record Cycle(long cycleId, LocalDate businessDate, int attempt, String interestParm, boolean snapshotTaken) {
    }

    /** The export's counts, as written to {@code counts.json}. */
    Map<String, Object> run(ExportOptions options) {
        Cycle cycle = cycle(options.cycleId());
        CycleJobs jobs = new CycleJobs(repository, cycle.cycleId(), cycle.businessDate(), cycle.attempt());
        CoreRows core = new CoreRows(db);
        Map<String, List<byte[]>> files = new LinkedHashMap<>();
        Map<String, Object> counts = new LinkedHashMap<>();
        LoadReport load = null;
        if (options.v2()) {
            load = LoadReport.read(repository, options.loadExecutionId().getAsLong());
            counts.put("schemaVersion", 2L);
        }
        counts.put("job", options.job().name());
        counts.put("cycleId", cycle.cycleId());
        counts.put("attempt", (long) cycle.attempt());
        if (load == null) {
            counts.put("groupIdMode", options.groupIdMode().key());
        } else {
            counts.put("loadExecutionId", load.loadExecutionId());
            counts.put("loadAccountsAsRead", load.accountsAsRead());
            counts.put("loadAccountsMigrated", load.accountsMigrated());
            counts.put("loadAccountLayoutSource", load.accountLayoutSource());
            counts.put("loadAcctdataSha256", load.acctdataSha256());
        }
        switch (options.job()) {
            case POSTTRAN -> posttran(cycle, jobs, core, options.outputDir(), files, counts);
            case INTCALC -> intcalc(cycle, jobs, files, counts);
        }
        if (load == null) {
            files.put("ACCTDATA.after.txt", v1Accounts(core, options.groupIdMode()));
        } else {
            jobs.requireAfterLoad(new JobCatalog(JobCatalog.contract()).cycleJobs(), load.loadExecutionId());
            List<CoreRows.AccountExport> accounts = core.accountExports();
            Map<String, Long> accountCounts = accountCounts(accounts);
            fail(accountProblems(accountCounts));
            files.put("ACCTDATA.after.txt", accounts.stream().map(a -> a.record().toBytes()).toList());
            counts.putAll(accountCounts);
        }
        if (options.job() == ExportOptions.Job.POSTTRAN) {
            // The reference run's file order: DALYREJS, ACCTDATA, TCATBALF, TRANSACT.
            reorder(files, "DALYREJS.txt", "ACCTDATA.after.txt", "TCATBALF.after.txt", "TRANSACT.after.txt");
        }
        ExportFiles out = ExportFiles.open(options.exportDir());
        try {
            files.forEach(out::records);
            out.bytes(COUNTS, CountsJson.of(counts));
        } catch (RuntimeException e) {
            out.discard();
            throw e;
        }
        return counts;
    }

    private void posttran(Cycle cycle, CycleJobs jobs, CoreRows core, Optional<Path> outputDir,
            Map<String, List<byte[]>> files, Map<String, Object> counts) {
        jobs.completed(CycleOutputs.INGEST_JOB);
        JobExecution post = jobs.completed(CycleOutputs.POST_JOB);
        if (jobs.started(CycleOutputs.INTEREST_JOB)) {
            throw new ExportException("calculateInterestJob has already run for cycle " + cycle.cycleId()
                    + "; the POSTTRAN export must run before it");
        }
        if (!cycle.snapshotTaken()) {
            throw new ExportException("cycle " + cycle.cycleId() + " has no balance snapshot");
        }
        StepExecution step = CycleJobs.step(post, CycleOutputs.POST_STEP);
        CycleJobs.step(post, "exportRejects");
        long posted = CycleJobs.count(step, CycleOutputs.POSTED);
        long rejected = CycleJobs.count(step, CycleOutputs.REJECTED);
        Object jobRejects = post.getExecutionContext().get(ExecutionOutputs.REJECT_COUNT);

        List<byte[]> rejects = new ArrayList<>();
        db.sql("SELECT seq, raw_record, reason_cd, reason_desc FROM core.daily_reject WHERE cycle_id = :id ORDER BY seq")
                .param("id", cycle.cycleId())
                .query(rs -> {
                    rejects.add(CycleOutputs.rejectRecord(rs.getInt(1), rs.getString(2), rs.getString(3),
                            rs.getString(4)));
                });
        List<byte[]> master = transactions("origin IN " + MASTER_ORIGINS + " AND in_master = 1", "tran_id",
                cycle.cycleId());
        long dailyRows = count("SELECT COUNT_BIG(*) FROM core.daily_tran WHERE cycle_id = :id", cycle.cycleId());
        long interestRows = count("SELECT COUNT_BIG(*) FROM core.[transaction] WHERE cycle_id = :id"
                + " AND origin = 'INTEREST'", cycle.cycleId());
        long created = count("""
                SELECT COUNT_BIG(*) FROM core.tcat_bal AS t
                WHERE NOT EXISTS (SELECT 1 FROM batch.snapshot_tcat_bal AS s WHERE s.cycle_id = :id
                    AND s.acct_id = t.acct_id AND s.type_cd = t.type_cd AND s.cat_cd = t.cat_cd)""", cycle.cycleId());

        fail(CountRules.problems(new CountRules.Posting(dailyRows, rejects.size(), master.size(), interestRows,
                posted, rejected, jobRejects instanceof Number n ? n.longValue() : null,
                post.getExitStatus().getExitCode())));

        if (outputDir.isPresent()) {
            Object relative = post.getExecutionContext().get(CycleOutputs.REJECT_FILE);
            Path expected = CycleOutputs.rejectFile(cycle.cycleId(), cycle.attempt());
            if (relative == null || !Path.of(relative.toString()).equals(expected)) {
                throw new ExportException("postDailyTransactionsJob names no reject file for cycle "
                        + cycle.cycleId() + " attempt " + cycle.attempt());
            }
            sameBytes(outputDir.get().resolve(expected), rejects);
        }

        files.put("DALYREJS.txt", rejects);
        files.put("TCATBALF.after.txt", core.categoryBalances().stream().map(TcatBalRecord::toBytes).toList());
        files.put("TRANSACT.after.txt", master);
        counts.put("processed", dailyRows);
        counts.put("rejected", (long) rejects.size());
        counts.put("tcatbalCreated", created);
        counts.put("rc", (long) CountRules.postingReturnCode(rejects.size()));
    }

    private void intcalc(Cycle cycle, CycleJobs jobs, Map<String, List<byte[]>> files,
            Map<String, Object> counts) {
        if (cycle.interestParm() == null) {
            throw new ExportException("cycle " + cycle.cycleId() + " has no interest run");
        }
        JobExecution interest = jobs.completed(CycleOutputs.INTEREST_JOB);
        StepExecution step = CycleJobs.step(interest, CycleOutputs.INTEREST_STEP);
        long accounts = CycleJobs.count(step, CycleOutputs.INTEREST_ACCOUNTS);
        long written = CycleJobs.count(step, CycleOutputs.INTEREST_TRANSACTIONS);
        List<byte[]> transact = transactions("origin = 'INTEREST'", "tran_pk", cycle.cycleId());
        long balanceAccounts = db.sql("SELECT COUNT_BIG(DISTINCT acct_id) FROM core.tcat_bal").query(Long.class)
                .single();
        fail(CountRules.problems(new CountRules.Interest(transact.size(), balanceAccounts, accounts, written)));
        files.put("TRANSACT.txt", transact);
        counts.put("accounts", accounts);
        counts.put("transactions", (long) transact.size());
        counts.put("rc", 0L);
    }

    /**
     * The accounts as v1 wrote them: the current ZIP and group ID at 103 and 113 in legacy mode,
     * swapped back in fixed mode, whatever the row's load-time text. Fixed mode swaps every row,
     * which is right only when every account was migrated; a row the loader stored as read
     * (origin R) would come out with ZIP and group exchanged, so v1 refuses the export instead.
     */
    private List<byte[]> v1Accounts(CoreRows core, FidelityMode mode) {
        if (mode == FidelityMode.FIXED) {
            long asRead = db.sql("SELECT COUNT_BIG(*) FROM core.account WHERE zip_group_origin = :r")
                    .param("r", ZipGroup.AS_READ).query(Long.class).single();
            refuseFixedV1(asRead);
        }
        Map<String, String[]> zipGroup = new java.util.HashMap<>();
        db.sql("SELECT acct_id, addr_zip, group_id FROM core.account")
                .query(rs -> {
                    zipGroup.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3)});
                });
        return core.accounts().stream().map(a -> {
            String[] stored = zipGroup.get(a.acctId());
            return v1Account(a, stored[0], stored[1], mode);
        }).toList();
    }

    /** v1 fixed mode swaps every account, so it refuses a database holding any account stored as read. */
    static void refuseFixedV1(long accountsAsRead) {
        if (accountsAsRead > 0) {
            throw new ExportException("v1 --fidelity " + BatchSwitches.DEF_ACUP_002.itemId() + "=fixed cannot export "
                    + accountsAsRead + " accounts stored as read (zip_group_origin " + ZipGroup.AS_READ
                    + "); use --load-execution-id (counts schemaVersion 2)");
        }
    }

    /** One v1 account record: {@code addrZip} at 103 and {@code groupId} at 113 in legacy mode, swapped in fixed mode. */
    static byte[] v1Account(AccountRecord a, String addrZip, String groupId, FidelityMode mode) {
        boolean moved = mode == FidelityMode.FIXED;
        return new AccountRecord(a.acctId(), a.activeStatus(), a.currBal(), a.creditLimit(), a.cashCreditLimit(),
                a.openDate(), a.expirationDate(), a.reissueDate(), a.currCycCredit(), a.currCycDebit(),
                moved ? groupId : addrZip, moved ? addrZip : groupId, a.legacyFiller()).toBytes();
    }

    /** The schema 2 account counts, in key order. */
    static Map<String, Long> accountCounts(List<CoreRows.AccountExport> accounts) {
        long r = 0;
        long m = 0;
        long none = 0;
        long restored = 0;
        for (CoreRows.AccountExport a : accounts) {
            if (ZipGroup.AS_READ.equals(a.origin())) {
                r++;
            } else if (ZipGroup.MIGRATED.equals(a.origin())) {
                m++;
            } else if (a.origin() == null) {
                none++;
            }
            if (a.restored()) {
                restored++;
            }
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("accountRecords", (long) accounts.size());
        counts.put("accountOriginR", r);
        counts.put("accountOriginM", m);
        counts.put("accountOriginNone", none);
        counts.put("accountZipGroupRestored", restored);
        counts.put("accountZipGroupRebuilt", accounts.size() - restored);
        return counts;
    }

    /** The schema 2 invariants of the account counts. */
    static List<String> accountProblems(Map<String, Long> c) {
        List<String> problems = new ArrayList<>();
        long records = c.get("accountRecords");
        if (c.get("accountOriginR") + c.get("accountOriginM") + c.get("accountOriginNone") != records) {
            problems.add("account origins R + M + none do not add up to the account records");
        }
        if (c.get("accountZipGroupRestored") + c.get("accountZipGroupRebuilt") != records) {
            problems.add("restored + rebuilt accounts do not add up to the account records");
        }
        if (c.get("accountOriginNone") > c.get("accountZipGroupRebuilt")) {
            problems.add("an account with no provenance was restored");
        }
        return problems;
    }

    private static void reorder(Map<String, List<byte[]>> files, String... names) {
        Map<String, List<byte[]>> ordered = new LinkedHashMap<>();
        for (String n : names) {
            ordered.put(n, files.remove(n));
        }
        ordered.putAll(files);
        files.clear();
        files.putAll(ordered);
    }

    private Cycle cycle(long cycleId) {
        return db.sql("SELECT business_date, attempt, interest_parm, snapshot_at FROM batch.cycle WHERE cycle_id = :id")
                .param("id", cycleId)
                .query((rs, n) -> new Cycle(cycleId, rs.getObject(1, LocalDate.class), rs.getInt(2), rs.getString(3),
                        rs.getObject(4) != null))
                .optional()
                .orElseThrow(() -> new ExportException("cycle " + cycleId + " does not exist"));
    }

    private List<byte[]> transactions(String where, String order, long cycleId) {
        return db.sql("SELECT " + TransactionRow.COLUMNS + " FROM core.[transaction] WHERE cycle_id = :id AND "
                + where + " ORDER BY " + order)
                .param("id", cycleId)
                .query((rs, n) -> TransactionRow.of(rs).toRecord().toBytes())
                .list();
    }

    private long count(String sql, long cycleId) {
        return db.sql(sql).param("id", cycleId).query(Long.class).single();
    }

    private static void fail(List<String> problems) {
        if (!problems.isEmpty()) {
            throw new ExportException(String.join("; ", problems));
        }
    }

    private static void sameBytes(Path jobFile, List<byte[]> records) {
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        for (byte[] r : records) {
            expected.writeBytes(r);
            expected.write('\n');
        }
        byte[] actual;
        try {
            actual = Files.readAllBytes(jobFile);
        } catch (IOException e) {
            throw new ExportException("cannot read the job's reject file", e);
        }
        if (!Arrays.equals(actual, expected.toByteArray())) {
            throw new ExportException("the job's reject file (" + actual.length + " bytes) differs from the export of"
                    + " core.daily_reject (" + expected.size() + " bytes)");
        }
    }
}
