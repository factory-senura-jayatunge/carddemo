package org.carddemo.batch.internal;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.carddemo.batch.cycle.CycleRestore;
import org.carddemo.batch.execution.ExecutionLeases;
import org.carddemo.batch.execution.ExecutionRecovery;
import org.carddemo.batch.execution.RequestTransactions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The internal API's services. They exist in both modes: the server profile serves them over
 * HTTP, and the one-shot load uses the same window and launch rules.
 */
@Configuration(proxyBeanMethods = false)
class InternalApiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InternalApiConfiguration.class);

    @Bean
    JobCatalog jobCatalog() {
        return new JobCatalog(JobCatalog.contract());
    }

    @Bean
    Cycles cycles(JdbcClient db, RequestTransactions transactions) {
        return new Cycles(db, transactions);
    }

    @Bean
    BatchWindows batchWindows(JdbcClient db, RequestTransactions transactions, Cycles cycles, JobCatalog catalog,
            InternalApiProperties properties, ExecutionLeases leases) {
        return new BatchWindows(db, transactions, cycles, catalog, properties, leases.owner());
    }

    @Bean
    JobLaunches jobLaunches(JobCatalog catalog, ObjectProvider<Job> jobs, JobOperator operator,
            JobRepository repository, ExecutionLeases leases, ExecutionRecovery recovery, BatchWindows windows,
            Cycles cycles, JdbcClient db, DataSource dataSource, Environment environment,
            InternalApiProperties properties) {
        Map<String, Job> byName = jobs.orderedStream().collect(Collectors.toMap(Job::getName, Function.identity()));
        return new JobLaunches(catalog, byName, operator, repository, leases, recovery, windows, cycles, db, dataSource,
                environment, properties);
    }

    @Bean
    CycleReruns cycleReruns(JdbcClient db, RequestTransactions transactions, Cycles cycles, BatchWindows windows,
            JobCatalog catalog, ExecutionRecovery recovery, CycleRestore restore, InternalApiProperties properties) {
        return new CycleReruns(db, transactions, cycles, windows, catalog, recovery, restore, properties);
    }

    @Bean
    ExecutionViews executionViews(JobRepository repository, ExecutionLeases leases) {
        return new ExecutionViews(repository, leases);
    }

    @Bean
    FilterRegistrationBean<InternalTokenFilter> internalTokenFilter(InternalApiProperties properties) {
        if (!properties.hasToken()) {
            log.warn("CARDDEMO_BATCH_INTERNAL_TOKEN is not set: /internal/* refuses every request");
        }
        FilterRegistrationBean<InternalTokenFilter> registration =
                new FilterRegistrationBean<>(new InternalTokenFilter(properties));
        registration.addUrlPatterns("/internal/*");
        return registration;
    }
}
