package org.carddemo.platform.ledger;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link BalancePosting} on core.account and core.tcat_bal. Rows are read with an update lock and
 * the sums computed here, because COBOL keeps the low-order digits of an overflowing sum where
 * SQL Server would raise an arithmetic overflow. The account is always locked first, so writers
 * that also touch category balances cannot deadlock on the order.
 */
public class JdbcBalancePosting implements BalancePosting {

    private final JdbcClient jdbc;

    public JdbcBalancePosting(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void balanceOnly(String acctId, BigDecimal delta) {
        Map<String, Object> account = lockAccount(acctId);
        BigDecimal balance = Amounts.truncate(((BigDecimal) account.get("curr_bal")).add(delta), Amounts.ACCOUNT_DIGITS);
        update(jdbc.sql("UPDATE core.account SET curr_bal = :bal WHERE acct_id = :id")
                .param("bal", balance).param("id", acctId).update(), acctId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void post(String acctId, String typeCd, String catCd, BigDecimal amount) {
        Map<String, Object> account = lockAccount(acctId);
        BigDecimal category = jdbc.sql("""
                        SELECT balance FROM core.tcat_bal WITH (UPDLOCK, ROWLOCK)
                        WHERE acct_id = :id AND type_cd = :type AND cat_cd = :cat""")
                .param("id", acctId).param("type", typeCd).param("cat", catCd)
                .query(BigDecimal.class).optional().orElse(null);
        if (category == null) {
            jdbc.sql("INSERT INTO core.tcat_bal (acct_id, type_cd, cat_cd, balance) VALUES (:id, :type, :cat, :bal)")
                    .param("id", acctId).param("type", typeCd).param("cat", catCd)
                    .param("bal", Amounts.truncate(amount, Amounts.CATEGORY_DIGITS)).update();
        } else {
            updateCategory(acctId, typeCd, catCd, category.add(amount));
        }
        postAccount(account, acctId, amount);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedCategory post(String acctId, String typeCd, String catCd, BigDecimal amount, String fillerForNewRow) {
        String filler = PostedCategory.requireFiller(fillerForNewRow);
        Map<String, Object> account = lockAccount(acctId);
        Map<String, Object> row = jdbc.sql("""
                        SELECT balance, legacy_filler FROM core.tcat_bal WITH (UPDLOCK, ROWLOCK)
                        WHERE acct_id = :id AND type_cd = :type AND cat_cd = :cat""")
                .param("id", acctId).param("type", typeCd).param("cat", catCd)
                .query().listOfRows().stream().findFirst().orElse(null);
        PostedCategory posted;
        if (row == null) {
            jdbc.sql("""
                            INSERT INTO core.tcat_bal (acct_id, type_cd, cat_cd, balance, legacy_filler)
                            VALUES (:id, :type, :cat, :bal, :filler)""")
                    .param("id", acctId).param("type", typeCd).param("cat", catCd)
                    .param("bal", Amounts.truncate(amount, Amounts.CATEGORY_DIGITS)).param("filler", filler).update();
            posted = new PostedCategory(true, filler);
        } else {
            updateCategory(acctId, typeCd, catCd, ((BigDecimal) row.get("balance")).add(amount));
            posted = new PostedCategory(false, (String) row.get("legacy_filler"));
        }
        postAccount(account, acctId, amount);
        return posted;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void interest(String acctId, BigDecimal totalInterest) {
        if (totalInterest.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("total interest has more than 2 decimals");
        }
        Map<String, Object> account = lockAccount(acctId);
        BigDecimal balance = Amounts.truncate(((BigDecimal) account.get("curr_bal")).add(totalInterest),
                Amounts.ACCOUNT_DIGITS);
        update(jdbc.sql("UPDATE core.account SET curr_bal = :bal, curr_cyc_credit = 0, curr_cyc_debit = 0 WHERE acct_id = :id")
                .param("bal", balance).param("id", acctId).update(), acctId);
    }

    private void updateCategory(String acctId, String typeCd, String catCd, BigDecimal sum) {
        jdbc.sql("UPDATE core.tcat_bal SET balance = :bal WHERE acct_id = :id AND type_cd = :type AND cat_cd = :cat")
                .param("bal", Amounts.truncate(sum, Amounts.CATEGORY_DIGITS))
                .param("id", acctId).param("type", typeCd).param("cat", catCd).update();
    }

    private void postAccount(Map<String, Object> account, String acctId, BigDecimal amount) {
        BigDecimal balance = Amounts.truncate(((BigDecimal) account.get("curr_bal")).add(amount), Amounts.ACCOUNT_DIGITS);
        boolean credit = amount.signum() >= 0;
        String column = credit ? "curr_cyc_credit" : "curr_cyc_debit";
        BigDecimal cycle = Amounts.truncate(((BigDecimal) account.get(column)).add(amount), Amounts.ACCOUNT_DIGITS);
        update(jdbc.sql("UPDATE core.account SET curr_bal = :bal, " + column + " = :cycle WHERE acct_id = :id")
                .param("bal", balance).param("cycle", cycle).param("id", acctId).update(), acctId);
    }

    private Map<String, Object> lockAccount(String acctId) {
        return jdbc.sql("""
                        SELECT curr_bal, curr_cyc_credit, curr_cyc_debit
                        FROM core.account WITH (UPDLOCK, ROWLOCK) WHERE acct_id = :id""")
                .param("id", acctId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> new AccountNotFoundException(acctId));
    }

    private static void update(int rows, String acctId) {
        if (rows == 0) {
            throw new AccountNotFoundException(acctId);
        }
    }
}
