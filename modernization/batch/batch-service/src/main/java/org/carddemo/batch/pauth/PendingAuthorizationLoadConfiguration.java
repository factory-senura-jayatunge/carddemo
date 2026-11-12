package org.carddemo.batch.pauth;

import org.carddemo.batch.load.DataLoadProperties;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The step bean {@code pauthLoadStep} ({@code loadPendingAuthorizations}), which dataLoadJob runs
 * after the core tables are loaded. It runs under the job's fencing transaction manager.
 */
@Configuration(proxyBeanMethods = false)
class PendingAuthorizationLoadConfiguration {

    @Bean
    Step pauthLoadStep(JobRepository jobRepository, PlatformTransactionManager tx, JdbcClient db,
            PendingAuthorizationProperties properties, DataLoadProperties dataLoad) {
        return new StepBuilder(LoadPendingAuthorizationsTasklet.STEP_NAME, jobRepository)
                .tasklet(new LoadPendingAuthorizationsTasklet(db, properties, dataLoad), tx)
                .build();
    }
}
