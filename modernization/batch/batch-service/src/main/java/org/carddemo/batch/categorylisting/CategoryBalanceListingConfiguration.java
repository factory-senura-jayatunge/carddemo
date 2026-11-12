package org.carddemo.batch.categorylisting;

import org.carddemo.batch.catalogue.CatalogueFailures;
import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.batch.execution.OutputRoots;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** PRTCATBL has no window and never changes category balances. */
@Configuration(proxyBeanMethods = false)
class CategoryBalanceListingConfiguration {
    @Bean
    BoundListingRoot boundListingRoot(OutputRoots roots) {
        return roots::root;
    }

    @Bean
    Job categoryBalanceListingJob(JobRepository repository, PlatformTransactionManager tx, JdbcClient db,
            OutputPublication outputs, BoundListingRoot roots, FidelitySwitches switches) {
        var list = new StepBuilder("list", repository).tasklet(CatalogueFailures.safe(
                new CategoryListingTasklet(db, repository, outputs, roots, switches)), tx).build();
        return new JobBuilder("categoryBalanceListingJob", repository).listener(new CatalogueFailures())
                .start(list).build();
    }
}
