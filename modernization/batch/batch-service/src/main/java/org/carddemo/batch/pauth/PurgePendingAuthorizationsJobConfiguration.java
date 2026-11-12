package org.carddemo.batch.pauth;

import java.util.List;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code purgePendingAuthorizationsJob} (CBPAUP0J, D-34): the chunk step {@code purge} over
 * {@code pauth.auth_summary}, then {@code purgeMessages}. Both run under the fencing transaction
 * manager. The job has no incrementer: the launch passes the cycle's parameters
 * ({@link PurgeParameters}), and a restart resumes after the last committed chunk.
 *
 * <p>Two executions may run at once (other cycles or attempts are other job instances). They
 * take summary locks in the same ascending order and subtract only the rows their own
 * {@code DELETE} removed, so together they leave what one run would.
 */
@Configuration(proxyBeanMethods = false)
class PurgePendingAuthorizationsJobConfiguration {

    static final String JOB_NAME = "purgePendingAuthorizationsJob";
    static final String PURGE_STEP = "purge";
    static final String MESSAGES_STEP = "purgeMessages";

    @Bean
    Job purgePendingAuthorizationsJob(JobRepository jobRepository, PlatformTransactionManager tx, JdbcClient db,
            PurgeSummaryKeyReader purgeSummaryKeyReader, PurgeSummaryWriter purgeSummaryWriter) {
        Step purge = new StepBuilder(PURGE_STEP, jobRepository).<List<String>, List<String>>chunk(1)
                .reader(purgeSummaryKeyReader)
                .writer(purgeSummaryWriter)
                .transactionManager(tx)
                .build();
        Step messages = new StepBuilder(MESSAGES_STEP, jobRepository)
                .tasklet(new PurgeMessagesTasklet(db), tx)
                .build();
        return new JobBuilder(JOB_NAME, jobRepository)
                .validator(PurgeParameters.VALIDATOR)
                .start(purge)
                .next(messages)
                .build();
    }

    @Bean
    @StepScope
    PurgeSummaryKeyReader purgeSummaryKeyReader(JdbcClient db,
            @Value("#{stepExecution}") StepExecution stepExecution) {
        return new PurgeSummaryKeyReader(db, PurgeParameters.from(stepExecution.getJobParameters()).commitInterval());
    }

    @Bean
    @StepScope
    PurgeSummaryWriter purgeSummaryWriter(JdbcClient db, @Value("#{stepExecution}") StepExecution stepExecution) {
        return new PurgeSummaryWriter(db, PurgeParameters.from(stepExecution.getJobParameters()));
    }
}
