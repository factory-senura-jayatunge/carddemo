package org.carddemo.batch.execution;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.batch.core.configuration.BatchConfigurationException;
import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.JdbcJobRepositoryFactoryBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;

/**
 * The job repository and transaction managers of batch-service (contract section 4.3).
 * There are two transaction managers: the fencing one is the only
 * {@link PlatformTransactionManager} bean, so every job step gets it; the plain one is no
 * bean and reaches only the job repository and {@link RequestTransactions}.
 */
@Configuration(proxyBeanMethods = false)
public class BatchInfrastructureConfiguration extends JdbcDefaultBatchConfiguration {

    private final DataSource dataSource;
    private final JdbcTransactionManager plain;
    private final String tablePrefix;
    private final boolean asyncLaunch;
    private final LeaseOwner owner = LeaseOwner.ofThisProcess();

    BatchInfrastructureConfiguration(DataSource dataSource,
            @Value("${spring.batch.jdbc.table-prefix}") String tablePrefix,
            @Value("${carddemo.batch.async-launch:false}") boolean asyncLaunch) {
        this.dataSource = dataSource;
        this.plain = new JdbcTransactionManager(dataSource);
        this.tablePrefix = tablePrefix;
        this.asyncLaunch = asyncLaunch;
    }

    @Bean
    ExecutionLeases executionLeases(JdbcClient db, LeaseSettings settings) {
        return new ExecutionLeases(db, owner, settings);
    }

    @Bean
    LeaseKeeper leaseKeeper(ExecutionLeases leases) {
        return new LeaseKeeper(leases);
    }

    @Bean
    FencingTransactionManager transactionManager(ExecutionLeases leases, LeaseKeeper keeper, OutputRoots roots) {
        return new FencingTransactionManager(plain, leases, keeper, roots);
    }

    @Bean
    OutputPublication outputPublication(JobRepository jobRepository, ExecutionLeases leases, LeaseKeeper keeper) {
        return new OutputPublication(jobRepository, leases, keeper);
    }

    @Bean
    RequestTransactions requestTransactions() {
        return new RequestTransactions(plain);
    }

    @Bean
    ExecutionRecovery executionRecovery(JdbcClient db, ExecutionLeases leases) {
        return new ExecutionRecovery(db, leases, new SafeTransactions(plain));
    }

    @Bean
    @Override
    public JobRepository jobRepository() {
        ExecutionLeases leases = applicationContext.getBean(ExecutionLeases.class);
        LeaseKeeper keeper = applicationContext.getBean(LeaseKeeper.class);
        SafeTransactions create = new SafeTransactions(plain);
        create.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return new LeaseGuardedJobRepository(delegateRepository(), create, leases, keeper,
                JdbcClient.create(dataSource), getJobKeyGenerator(), applicationContext.getBean(OutputRoots.class));
    }

    /**
     * JdbcDefaultBatchConfiguration's repository, except that every method joins the caller's
     * transaction. Spring Batch runs create* and getLastJobExecution* in a new transaction
     * (REQUIRES_NEW): the guarded repository's instance lock and the lease would then sit in
     * another transaction than the rows they protect, and each launch would hold two pooled
     * connections while it waits for the lock, which can exhaust the pool.
     */
    private JobRepository delegateRepository() {
        JdbcJobRepositoryFactoryBean factory = new JdbcJobRepositoryFactoryBean();
        try {
            factory.setDataSource(getDataSource());
            factory.setTransactionManager(getTransactionManager());
            factory.setDatabaseType(getDatabaseType());
            factory.setIncrementerFactory(getIncrementerFactory());
            factory.setJobKeyGenerator(getJobKeyGenerator());
            factory.setClobType(getClobType());
            factory.setTablePrefix(getTablePrefix());
            factory.setJobInstanceIncrementerName(getJobInstanceIncrementerName());
            factory.setJobExecutionIncrementerName(getJobExecutionIncrementerName());
            factory.setStepExecutionIncrementerName(getStepExecutionIncrementerName());
            factory.setSerializer(getExecutionContextSerializer());
            factory.setConversionService(getConversionService());
            factory.setJdbcOperations(getJdbcOperations());
            factory.setCharset(getCharset());
            factory.setMaxVarCharLength(getMaxVarCharLength());
            factory.setValidateTransactionState(false);
            NameMatchTransactionAttributeSource joined = new NameMatchTransactionAttributeSource();
            joined.addTransactionalMethod("*", new RuleBasedTransactionAttribute(
                    TransactionDefinition.PROPAGATION_REQUIRED, List.of()));
            factory.setTransactionAttributeSource(joined);
            factory.afterPropertiesSet();
            return factory.getObject();
        } catch (Exception e) {
            throw new BatchConfigurationException("Unable to configure the job repository", e);
        }
    }

    /**
     * Only for Spring Batch's own defaults; the delegate's methods all join the caller's
     * transaction, and the guarded repository opens its creates READ COMMITTED.
     */
    @Override
    protected Isolation getIsolationLevelForCreate() {
        return Isolation.READ_COMMITTED;
    }

    @Override
    protected DataSource getDataSource() {
        return dataSource;
    }

    @Override
    protected PlatformTransactionManager getTransactionManager() {
        return plain;
    }

    @Override
    protected String getTablePrefix() {
        return tablePrefix;
    }

    @Override
    protected TaskExecutor getTaskExecutor() {
        return asyncLaunch ? new SimpleAsyncTaskExecutor("batch-job-") : super.getTaskExecutor();
    }
}
