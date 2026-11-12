package org.carddemo.batch.cycle;

import java.time.Clock;

import javax.sql.DataSource;

import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.batch.execution.OutputRoots;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.ledger.BalancePosting;
import org.carddemo.platform.ledger.TransactionWriter;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The jobs of the daily cycle (batch.md, option F), in DAG order. Every tasklet runs inside a
 * {@link SafeTasklet}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({BatchFilesProperties.class, InterestProperties.class})
class CycleJobsConfiguration {

    private final CycleExitDescriptions exitDescriptions = new CycleExitDescriptions();

    @Bean
    DailyFiles dailyFiles(OutputRoots roots, JdbcClient db, OutputPublication publication) {
        return new DailyFiles(roots, db, publication);
    }

    @Bean
    CycleRestore cycleRestore(JdbcClient db, DailyFiles files) {
        return new CycleRestore(db, files);
    }

    @Bean
    Job archiveMasterJob(JobRepository jobRepository, PlatformTransactionManager tx, JdbcClient db,
            DataSource dataSource) {
        return new JobBuilder("archiveMasterJob", jobRepository).listener(exitDescriptions)
                .start(step("archive", new ArchiveMasterTasklet(db, dataSource), jobRepository, tx))
                .build();
    }

    @Bean
    Job ingestDailyFileJob(JobRepository jobRepository, PlatformTransactionManager tx, JdbcClient db,
            DataSource dataSource, DailyFiles files, BatchFilesProperties properties, FidelitySwitches switches) {
        StageDailyFileTasklet stage = new StageDailyFileTasklet(db, new NamedParameterJdbcTemplate(dataSource), files,
                properties.chunkSize());
        return new JobBuilder("ingestDailyFileJob", jobRepository).listener(exitDescriptions)
                .start(new StepBuilder("stage", jobRepository).tasklet(new SafeTasklet(stage), tx).listener(stage).build())
                .next(step("claimOnlineAdds", new ClaimOnlineAddsTasklet(db, switches), jobRepository, tx))
                .build();
    }

    @Bean
    Job postDailyTransactionsJob(JobRepository jobRepository, PlatformTransactionManager tx, JdbcClient db,
            TransactionWriter transactions, BalancePosting balances, BatchFilesProperties properties,
            OutputRoots roots, OutputPublication publication, ObjectProvider<Clock> clock) {
        PostDailyTransactionsTasklet post = new PostDailyTransactionsTasklet(db, transactions, balances, tx,
                clock.getIfAvailable(Clock::systemDefaultZone), properties.chunkSize());
        return new JobBuilder("postDailyTransactionsJob", jobRepository).listener(exitDescriptions)
                .start(step("snapshotBalances", new SnapshotBalancesTasklet(db), jobRepository, tx))
                .next(step("post", post, jobRepository, tx))
                .next(step("stageAuthorizationMatches", new StageAuthorizationMatchesTasklet(db), jobRepository, tx))
                .next(step("applyAuthorizationMatches",
                        new ApplyAuthorizationMatchesTasklet(db, tx, properties.chunkSize()), jobRepository, tx))
                .next(step("exportRejects", new ExportRejectsTasklet(db, roots, publication), jobRepository, tx))
                .build();
    }

    @Bean
    Job calculateInterestJob(JobRepository jobRepository, PlatformTransactionManager tx, JdbcClient db,
            TransactionWriter transactions, BalancePosting balances, FidelitySwitches switches,
            InterestProperties interest, BatchFilesProperties properties, ObjectProvider<Clock> clock) {
        CalculateInterestTasklet tasklet = new CalculateInterestTasklet(db, transactions, balances, switches, interest,
                clock.getIfAvailable(Clock::systemDefaultZone), properties.chunkSize());
        return new JobBuilder("calculateInterestJob", jobRepository).listener(exitDescriptions)
                .start(step("interest", tasklet, jobRepository, tx))
                .build();
    }

    private Step step(String name, Tasklet tasklet, JobRepository jobRepository, PlatformTransactionManager tx) {
        return new StepBuilder(name, jobRepository).tasklet(new SafeTasklet(tasklet), tx).build();
    }
}
