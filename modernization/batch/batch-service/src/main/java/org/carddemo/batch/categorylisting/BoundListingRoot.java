package org.carddemo.batch.categorylisting;

import java.nio.file.Path;

import org.springframework.batch.core.job.JobExecution;

/**
 * Adapter to the data-owned execution-instance binding, never a fresh identity for the output.
 * It only reads and compares: no filesystem changes, directory creation or OutputRoot.ensure.
 * Each call runs in the current fenced transaction: re-read instance_id on its transactional
 * connection, compare it with the execution's bound instance and fail OUTPUT_ROOT_MISMATCH on
 * any difference. The binding must also check before each output-writing transaction commits
 * (contract section 4.10), including intent and publication completion.
 */
@FunctionalInterface
public interface BoundListingRoot {
    Path root(JobExecution execution);
}
