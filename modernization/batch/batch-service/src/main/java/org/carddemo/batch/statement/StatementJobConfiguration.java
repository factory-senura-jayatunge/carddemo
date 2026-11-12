package org.carddemo.batch.statement;

import javax.sql.DataSource;

import org.carddemo.batch.execution.OutputPublication;
import org.carddemo.batch.execution.OutputRoots;
import org.carddemo.batch.internal.InternalApiProperties;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code statementJob} (CREASTMT; batch.md, "Statements (statementJob)"): {@code capture},
 * {@code render}, then the publication's {@code intent}, {@code publish} and {@code complete}. The
 * render is the run's mode's: {@link LegacyRenderTasklet} or {@link FixedRenderTasklet}.
 * Every step runs at every start of the instance ({@code allowStartIfComplete}), because the run
 * row, not the step's earlier status, decides what a restart does; each step does nothing for a
 * run past its part. Every tasklet runs inside {@link StatementFailures#safe} under the fencing
 * transaction manager.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StatementProperties.class)
class StatementJobConfiguration {

    static final String JOB_NAME = "statementJob";

    @Bean
    StatementPublication statementPublication(JdbcClient db, OutputRoots roots) {
        return new StatementPublication(new StatementRuns(db), roots);
    }

    @Bean
    Job statementJob(JobRepository jobRepository, PlatformTransactionManager tx, JdbcClient db, DataSource dataSource,
            OutputRoots roots, OutputPublication publication, FidelitySwitches switches,
            StatementProperties properties, InternalApiProperties internal, StatementPublication publish) {
        StatementRuns runs = new StatementRuns(db);
        StatementCapture capture = new StatementCapture(db, dataSource, internal.launchLockTimeout());
        Tasklet render = byMode(runs, new LegacyRenderTasklet(db, runs, roots, publication),
                new FixedRenderTasklet(db, runs, roots, publication, properties));
        return new JobBuilder(JOB_NAME, jobRepository).listener(new StatementFailures())
                .start(step("capture", new CaptureTasklet(runs, capture, roots, switches), jobRepository, tx))
                .next(step("render", render, jobRepository, tx))
                .next(step("intent", publish.intent(), jobRepository, tx))
                .next(step("publish", publish.publish(), jobRepository, tx))
                .next(step("complete", publish.complete(), jobRepository, tx))
                .build();
    }

    /** The capture stored the run's mode, and nothing changes it afterwards. */
    private static Tasklet byMode(StatementRuns runs, Tasklet legacy, Tasklet fixed) {
        return (contribution, chunkContext) -> {
            StatementCycle cycle = StatementCycle.of(contribution.getStepExecution());
            StatementRuns.Run run = runs.owned(cycle, contribution.getStepExecution().getJobExecution().getId(), false);
            return (run.mode() == StatementRuns.Mode.FIXED ? fixed : legacy).execute(contribution, chunkContext);
        };
    }

    private static Step step(String name, Tasklet tasklet, JobRepository jobRepository,
            PlatformTransactionManager tx) {
        return new StepBuilder(name, jobRepository).tasklet(StatementFailures.safe(tasklet), tx)
                .allowStartIfComplete(true).build();
    }
}
