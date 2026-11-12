package org.carddemo.batch.transactionreport;

import java.nio.file.Path;

import org.springframework.batch.core.job.JobExecution;

/**
 * The execution's bound instance root ({@code OutputRoots.root}), read and compared in the
 * current fenced transaction (contract section 4.10). It never creates a path.
 */
@FunctionalInterface
interface BoundReportRoot {
    Path root(JobExecution execution);
}
