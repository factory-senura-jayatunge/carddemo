package org.carddemo.batch.load;

import java.util.function.Supplier;

import org.carddemo.platform.fidelity.FidelitySwitches;
import org.carddemo.platform.validation.RuleTable;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code dataLoadJob}: the sample files of {@code app/data/ASCII} into the core
 * and reference tables, and the lookup lists of the account edits into theirs
 * (data-model.md, sample-data migration). One step per file, each in one
 * transaction; the first failing step fails the job. Users are not loaded:
 * identity-service seeds them (D-06).
 */
@Configuration(proxyBeanMethods = false)
class DataLoadJobConfiguration {

    static final String JOB_NAME = "dataLoadJob";

    @Bean
    DataLoadStarts dataLoadStarts() {
        return new DataLoadStarts();
    }

    @Bean
    Job dataLoadJob(JobRepository jobRepository, PlatformTransactionManager tx, JdbcClient db,
            DataLoadProperties properties, FidelitySwitches switches, @Qualifier("pauthLoadStep") Step pauthLoadStep,
            DataLoadStarts starts) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .listener(starts)
                .start(step("clearCoreTables", new ClearCoreTablesTasklet(db, properties), jobRepository, tx))
                .next(step(() -> new CoreTableLoaders.Accounts(db, properties, switches, jobRepository), jobRepository, tx))
                .next(step(() -> new CoreTableLoaders.Customers(db, properties, switches), jobRepository, tx))
                .next(step(() -> new CoreTableLoaders.Cards(db, properties, switches), jobRepository, tx))
                .next(step(() -> new CoreTableLoaders.CardXrefs(db, properties, switches), jobRepository, tx))
                .next(step(() -> new CoreTableLoaders.CategoryBalances(db, properties, switches), jobRepository, tx))
                .next(step(() -> new RefTableLoaders.TransactionTypes(db, properties, switches), jobRepository, tx))
                .next(step(() -> new RefTableLoaders.Categories(db, properties, switches), jobRepository, tx))
                .next(step(() -> new RefTableLoaders.DisclosureGroups(db, properties, switches), jobRepository, tx))
                .next(step("loadLookups", new LoadLookupsTasklet(db, RuleTable.standard()), jobRepository, tx))
                .next(step("restartTranIdSequence", new RestartTranIdSequenceTasklet(db), jobRepository, tx))
                // The pending authorizations need their accounts loaded (D-33's pauth unload).
                .next(pauthLoadStep)
                .build();
    }

    private static Step step(Supplier<TableLoadTasklet> tasklets, JobRepository jobRepository,
            PlatformTransactionManager tx) {
        return step(tasklets.get().stepName(), perRun(tasklets), jobRepository, tx);
    }

    /**
     * A tasklet that runs a new loader for every step execution. The loaders keep what they read
     * and classified in fields between their hooks; one instance per execution means a restart or
     * a concurrent launch never sees another execution's file, manifest, hashes or counts.
     */
    static Tasklet perRun(Supplier<? extends Tasklet> tasklets) {
        return (contribution, chunkContext) -> tasklets.get().execute(contribution, chunkContext);
    }

    private static Step step(String name, Tasklet tasklet, JobRepository jobRepository, PlatformTransactionManager tx) {
        return new StepBuilder(name, jobRepository).tasklet(tasklet, tx).build();
    }
}
