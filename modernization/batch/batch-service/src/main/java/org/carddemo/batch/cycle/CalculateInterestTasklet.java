package org.carddemo.batch.cycle;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.carddemo.batch.BatchSwitches;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.ledger.AccountNotFoundException;
import org.carddemo.platform.ledger.BalancePosting;
import org.carddemo.platform.ledger.DuplicateTranIdException;
import org.carddemo.platform.ledger.NewTransaction;
import org.carddemo.platform.ledger.Origin;
import org.carddemo.platform.ledger.TranIdExhaustedException;
import org.carddemo.platform.ledger.TransactionWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * calculateInterestJob's {@code interest}: CBACT04C over the category balances in key order
 * (BR-INTC-008), one account with all its balances per item, {@code chunkSize} accounts per
 * transaction. For each balance with a non-zero rate (BR-INTC-002, BR-INTC-003) it writes one
 * interest transaction (BR-INTC-004) through ledger-core; after the account's last balance it adds
 * the account's total interest to the balance and resets the cycle totals through
 * {@link BalancePosting#interest} (BR-INTC-006). The step's execution context holds the last
 * account done and the legacy ID counter and commits with the chunk, so an account's interest rows
 * and its balance update commit together and a restart resumes at the next account (DEF-INTC-009,
 * ERR-JINTC-001). A completed run is a completed job instance, so the cycle's interest cannot be
 * charged twice (DEF-INTC-005, D-BAT-10). Category balances are not changed. Nothing is printed per
 * record (DEF-INTC-011): failures name positions and codes, and the end line gives counts.
 */
final class CalculateInterestTasklet implements Tasklet {

    static final String LAST_ACCT = "interest.lastAcct";
    static final String LAST_SUFFIX = "interest.lastSuffix";
    static final String ACCOUNTS = "interest.accounts";
    static final String TRANSACTIONS = "interest.transactions";

    static final String DEFAULT_GROUP = "DEFAULT";
    static final String TYPE_CD = "01";
    static final String CAT_CD = "0005";
    static final String SOURCE = "System";
    static final String DESCRIPTION = "Int. for a/c ";
    static final String MERCHANT_ID = "000000000";
    /** WS-TRANID-SUFFIX PIC 9(06); the legacy program wraps after it (NUM-INTC-004), this one stops. */
    static final int MAX_SUFFIX = 999_999;
    /** SQL Server: the sequence object has reached its minimum or maximum value. */
    static final int SEQUENCE_EXHAUSTED = 11728;

    private static final Logger log = LoggerFactory.getLogger(CalculateInterestTasklet.class);

    private static final BigDecimal MONTHS_TIMES_PERCENT = BigDecimal.valueOf(1200);
    /** WS-MONTHLY-INT and WS-TOTAL-INT, PIC S9(09)V99: 11 digits at scale 2. */
    private static final BigInteger INTEREST_MODULUS = BigInteger.TEN.pow(11);

    private static final String NEXT_ACCOUNTS = """
            SELECT TOP (:n) acct_id FROM core.tcat_bal WHERE acct_id > :after GROUP BY acct_id ORDER BY acct_id""";

    private static final String BALANCES = """
            SELECT acct_id, type_cd, cat_cd, balance FROM core.tcat_bal
            WHERE acct_id > :after AND acct_id <= :last
            ORDER BY acct_id, type_cd, cat_cd""";

    private static final String GROUP = "SELECT group_id FROM core.account WHERE acct_id = :acct";

    /** The account's first card: the lowest card number, as the alternate index orders duplicates (BR-INTC-007). */
    private static final String CARD = "SELECT MIN(card_num) FROM core.card_xref WHERE acct_id = :acct";

    private static final String RATES = "SELECT group_id, type_cd, cat_cd, int_rate FROM ref.disclosure_group";

    record Balance(String acctId, String typeCd, String catCd, BigDecimal balance) {
    }

    private final JdbcClient db;
    private final TransactionWriter transactions;
    private final BalancePosting balances;
    private final FidelitySwitches switches;
    private final String legacyParm;
    private final Clock clock;
    private final int chunkSize;

    CalculateInterestTasklet(JdbcClient db, TransactionWriter transactions, BalancePosting balances,
            FidelitySwitches switches, InterestProperties properties, Clock clock, int chunkSize) {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("carddemo.batch.files.chunk-size must be at least 1");
        }
        this.db = db;
        this.transactions = transactions;
        this.balances = balances;
        this.switches = switches;
        this.legacyParm = properties.parmDate();
        this.clock = clock;
        this.chunkSize = chunkSize;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        CycleRun run = CycleRun.of(contribution.getStepExecution());
        ExecutionContext context = contribution.getStepExecution().getExecutionContext();
        String after = context.getString(LAST_ACCT, "");
        long accountsDone = context.getLong(ACCOUNTS, 0);
        long written = context.getLong(TRANSACTIONS, 0);
        int suffix = context.getInt(LAST_SUFFIX, 0);
        List<String> accounts = db.sql(NEXT_ACCOUNTS).param("n", chunkSize).param("after", after)
                .query(String.class).list();
        if (!accounts.isEmpty()) {
            Map<String, List<Balance>> byAccount = balancesOf(after, accounts.get(accounts.size() - 1));
            Map<String, BigDecimal> rates = rates();
            boolean sequenceIds = switches.isFixed(BatchSwitches.DEF_INTC_010);
            String prefix = sequenceIds ? null : parmDate(contribution);
            boolean positiveOnly = switches.isFixed(BatchSwitches.DEF_INTC_012);
            for (String acctId : accounts) {
                long position = ++accountsDone;
                String group = db.sql(GROUP).param("acct", acctId).query(String.class).optional()
                        .orElseThrow(() -> accountMissing(position));
                String card = db.sql(CARD).param("acct", acctId).query(String.class).optional().orElse(null);
                if (card == null) {
                    throw new CycleJobException("CARD_XREF_NOT_FOUND", "account " + position
                            + " of the interest run in category balance order has no card cross-reference");
                }
                BigDecimal total = BigDecimal.ZERO.setScale(2);
                for (Balance b : byAccount.getOrDefault(acctId, List.of())) {
                    contribution.incrementReadCount();
                    BigDecimal rate = rate(rates, group, b);
                    if (rate.signum() == 0 || (positiveOnly && b.balance().signum() < 0)) {
                        continue;
                    }
                    BigDecimal interest = monthlyInterest(b.balance(), rate);
                    total = cut(total.add(interest));
                    String tranId;
                    if (sequenceIds) {
                        tranId = sequenceId();
                    } else {
                        if (suffix >= MAX_SUFFIX) {
                            throw new CycleJobException(TranIdExhaustedException.CODE, "the interest run has used all "
                                    + MAX_SUFFIX + " transaction IDs of its prefix");
                        }
                        tranId = prefix + String.format("%06d", ++suffix);
                    }
                    write(run, acctId, card, tranId, interest, position);
                    written++;
                }
                try {
                    balances.interest(acctId, total);
                } catch (AccountNotFoundException e) {
                    throw accountMissing(position);
                }
            }
            contribution.incrementWriteCount(accounts.size());
            context.putString(LAST_ACCT, accounts.get(accounts.size() - 1));
            context.putLong(ACCOUNTS, accountsDone);
            context.putLong(TRANSACTIONS, written);
            context.putInt(LAST_SUFFIX, suffix);
        }
        if (accounts.size() < chunkSize) {
            log.info("interest: cycle {} attempt {} updated {} accounts and wrote {} interest transactions",
                    run.cycleId(), run.attempt(), accountsDone, written);
            return RepeatStatus.FINISHED;
        }
        return RepeatStatus.CONTINUABLE;
    }

    /**
     * BR-INTC-001: balance × annual rate / 1200, truncated toward zero to cents, then cut to
     * {@code S9(09)V99} as the COMPUTE into WS-MONTHLY-INT does (NUM-INTC-003).
     */
    static BigDecimal monthlyInterest(BigDecimal balance, BigDecimal rate) {
        return cut(balance.multiply(rate).divide(MONTHS_TIMES_PERCENT, 2, RoundingMode.DOWN));
    }

    /** A move into PIC S9(09)V99: decimals past 2 and integer digits past 9 dropped, the sign kept. */
    static BigDecimal cut(BigDecimal value) {
        BigInteger unscaled = value.setScale(2, RoundingMode.DOWN).unscaledValue();
        BigInteger kept = unscaled.abs().mod(INTEREST_MODULUS);
        return new BigDecimal(unscaled.signum() < 0 ? kept.negate() : kept, 2);
    }

    /**
     * DEF-JINTC-001: the 10-character prefix of the legacy IDs. Legacy takes the configured JCL
     * PARM as given; fixed takes the cycle's {@code interestParm}, derived from its business date.
     */
    private String parmDate(StepContribution contribution) {
        if (switches.isLegacy(BatchSwitches.DEF_JINTC_001)) {
            return legacyParm;
        }
        String parm = contribution.getStepExecution().getJobExecution().getJobParameters().getString("interestParm");
        if (parm == null || parm.length() != InterestProperties.PARM_DATE_LENGTH) {
            throw new IllegalStateException("calculateInterestJob needs the cycle's interestParm, 10 characters");
        }
        return parm;
    }

    /** DEF-INTC-010 fixed: a reserved ID from the shared sequence (D-59). */
    private String sequenceId() {
        long value;
        try {
            value = db.sql("SELECT NEXT VALUE FOR core.tran_id_seq").query(Long.class).single();
        } catch (DataAccessException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException sql && sql.getErrorCode() == SEQUENCE_EXHAUSTED) {
                    throw new CycleJobException(TranIdExhaustedException.CODE,
                            "core.tran_id_seq has no transaction ID left after 9999999999999999");
                }
            }
            throw e;
        }
        return String.format("%016d", value);
    }

    private void write(CycleRun run, String acctId, String card, String tranId, BigDecimal interest, long position) {
        var ts = ProcessingTimestamps.of(run.businessDate(), clock);
        NewTransaction t = new NewTransaction(tranId, TYPE_CD, CAT_CD, SOURCE, DESCRIPTION + acctId, interest, card,
                MERCHANT_ID, "", "", "", ts, ts, Origin.INTEREST, null, Math.toIntExact(run.cycleId()));
        try {
            transactions.insert(t);
        } catch (DuplicateTranIdException e) {
            throw new CycleJobException("DUPLICATE_TRAN_ID", "interest tran_id " + tranId + " of account " + position
                    + " of the interest run is already in the master");
        }
    }

    private Map<String, List<Balance>> balancesOf(String after, String last) {
        Map<String, List<Balance>> byAccount = new LinkedHashMap<>();
        db.sql(BALANCES).param("after", after).param("last", last)
                .query((rs, n) -> new Balance(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4)))
                .list().forEach(b -> byAccount.computeIfAbsent(b.acctId(), k -> new ArrayList<>()).add(b));
        return byAccount;
    }

    private Map<String, BigDecimal> rates() {
        Map<String, BigDecimal> rates = new HashMap<>();
        db.sql(RATES).query(rs -> {
            rates.put(key(rs.getString(1), rs.getString(2), rs.getString(3)), rs.getBigDecimal(4));
        });
        return rates;
    }

    /** BR-INTC-002: the account group's rate, else the DEFAULT group's, looked up afresh for each balance. */
    private static BigDecimal rate(Map<String, BigDecimal> rates, String group, Balance b) {
        BigDecimal rate = rates.get(key(group, b.typeCd(), b.catCd()));
        if (rate == null) {
            rate = rates.get(key(DEFAULT_GROUP, b.typeCd(), b.catCd()));
        }
        if (rate == null) {
            throw new CycleJobException("DISCLOSURE_GROUP_MISSING", "no rate for type " + b.typeCd() + " category "
                    + b.catCd() + " in the account's group or in " + DEFAULT_GROUP);
        }
        return rate;
    }

    /** CHAR columns compare without trailing spaces, as the VSAM key with its padding does. */
    private static String key(String group, String typeCd, String catCd) {
        return group.stripTrailing() + '|' + typeCd + '|' + catCd;
    }

    private static CycleJobException accountMissing(long position) {
        return new CycleJobException("ACCOUNT_NOT_FOUND", "account " + position
                + " of the interest run in category balance order is not in core.account");
    }
}
