package org.carddemo.batch.disclosure;

import org.carddemo.batch.catalogue.CatalogueFailure;
import org.carddemo.batch.catalogue.CatalogueFailures;
import org.carddemo.batch.data.RefRows;
import org.carddemo.batch.execution.OutputRoots;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** DISCGRP: validate, replace in one fenced transaction, then defend the DEFAULT invariant. */
@Configuration(proxyBeanMethods = false)
class DisclosureGroupReloadConfiguration {
    @Bean
    Job disclosureGroupReloadJob(JobRepository repository, PlatformTransactionManager tx, JdbcClient db,
            OutputRoots roots) {
        var reload = new StepBuilder("reload", repository).tasklet(CatalogueFailures.safe((contribution, context) -> {
            var parameters = contribution.getStepExecution().getJobParameters();
            String holder = parameters.getString("windowRunKey");
            if (holder == null || db.sql("""
                    SELECT COUNT(*) FROM ops.batch_window
                    WHERE closed_at IS NULL AND cycle_id IS NULL AND run_key = :holder
                      AND mode = 'WRITES_BLOCKED'
                    """).param("holder", holder).query(Integer.class).single() != 1) {
                throw new CatalogueFailure("BATCH_WINDOW_REQUIRED", "disclosureGroupReloadJob");
            }
            var date = parameters.getLocalDate("businessDate");
            if (date == null) {
                throw new CatalogueFailure("INVALID_PARAMETER", "businessDate");
            }
            var inbound = roots.inbound().resolve("discgrp").resolve(date + ".txt");
            var records = DisclosureInput.read(inbound);
            db.sql("DELETE FROM ref.disclosure_group").update();
            var rows = new RefRows(db);
            records.forEach(record -> {
                rows.insert(record);
                contribution.incrementReadCount();
            });
            contribution.incrementWriteCount(records.size());
            return org.springframework.batch.infrastructure.repeat.RepeatStatus.FINISHED;
        }), tx).build();
        var check = new StepBuilder("checkDefaultGroup", repository)
                .tasklet(CatalogueFailures.safe((contribution, context) -> {
                    if (db.sql("SELECT COUNT(*) FROM ref.disclosure_group WHERE group_id = 'DEFAULT'")
                            .query(Integer.class).single() == 0) {
                        throw new CatalogueFailure("DEFAULT_GROUP_MISSING", "checkDefaultGroup");
                    }
                    return org.springframework.batch.infrastructure.repeat.RepeatStatus.FINISHED;
                }), tx).build();
        return new JobBuilder("disclosureGroupReloadJob", repository).listener(new CatalogueFailures())
                .start(reload).next(check).build();
    }
}
