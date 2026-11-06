package org.carddemo.batch.load;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code dataLoadJob}: the sample files of {@code app/data/ASCII} into the core
 * tables (data-model.md, sample-data migration). One step per file, each in one
 * transaction; the first failing step fails the job. Users are not loaded:
 * identity-service seeds them (D-06).
 */
@Configuration(proxyBeanMethods = false)
class DataLoadJobConfiguration {

    static final String JOB_NAME = "dataLoadJob";

    @Bean
    Job dataLoadJob(JobRepository jobRepository, PlatformTransactionManager tx, JdbcClient db,
            DataLoadProperties properties) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(step("clearCoreTables", new ClearCoreTablesTasklet(db, properties), jobRepository, tx))
                .next(step(new CoreTableLoaders.Accounts(db, properties), jobRepository, tx))
                .next(step(new CoreTableLoaders.Customers(db, properties), jobRepository, tx))
                .next(step(new CoreTableLoaders.Cards(db, properties), jobRepository, tx))
                .next(step(new CoreTableLoaders.CardXrefs(db, properties), jobRepository, tx))
                .next(step(new CoreTableLoaders.CategoryBalances(db, properties), jobRepository, tx))
                .next(step("restartTranIdSequence", new RestartTranIdSequenceTasklet(db), jobRepository, tx))
                .build();
    }

    private static Step step(TableLoadTasklet tasklet, JobRepository jobRepository, PlatformTransactionManager tx) {
        return step(tasklet.stepName(), tasklet, jobRepository, tx);
    }

    private static Step step(String name, Tasklet tasklet, JobRepository jobRepository, PlatformTransactionManager tx) {
        return new StepBuilder(name, jobRepository).tasklet(tasklet, tx).build();
    }
}
