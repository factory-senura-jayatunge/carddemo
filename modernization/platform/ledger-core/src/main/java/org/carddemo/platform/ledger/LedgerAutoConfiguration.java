package org.carddemo.platform.ledger;

import javax.sql.DataSource;
import org.carddemo.platform.fidelity.FidelitySwitchCatalog;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The JDBC implementations of the ledger interfaces, on the application's DataSource. */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnSingleCandidate(DataSource.class)
public class LedgerAutoConfiguration {

    @Bean
    public FidelitySwitchCatalog ledgerFidelitySwitches() {
        return FidelitySwitchCatalog.of(LedgerSwitches.BR_TRNA_003);
    }

    @Bean
    @ConditionalOnMissingBean
    public TransactionIds transactionIds(DataSource dataSource, FidelitySwitches switches) {
        return new JdbcTransactionIds(JdbcClient.create(dataSource), switches);
    }

    @Bean
    @ConditionalOnMissingBean
    public TransactionWriter transactionWriter(DataSource dataSource) {
        return new JdbcTransactionWriter(JdbcClient.create(dataSource));
    }

    @Bean
    @ConditionalOnMissingBean
    public BalancePosting balancePosting(DataSource dataSource) {
        return new JdbcBalancePosting(JdbcClient.create(dataSource));
    }

    @Bean
    @ConditionalOnMissingBean
    public BatchWindowGuard batchWindowGuard(DataSource dataSource) {
        return new JdbcBatchWindowGuard(JdbcClient.create(dataSource));
    }
}
