package org.carddemo.batch.execution;

import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.JobKeyGenerator;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.NoSuchStepException;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The Spring Batch job repository with execution ownership (contract sections 4.1 and 4.5):
 * creating an execution takes its lease in the same transaction, and every write for an
 * execution this process has lost is refused, so a resumed stale owner never overwrites the
 * recovered status or the restart's rows. Reads pass through.
 *
 * <p>Creating an instance or an execution is a critical section per job instance: an exclusive
 * application lock on the instance's name and key, taken first in a READ COMMITTED transaction,
 * then a check of what an earlier holder committed. Spring Batch's SERIALIZABLE default instead
 * deadlocks on SQL Server (error 1205) when launches of the same or of different instances meet:
 * each reads a key range shared, then inserts into it. Launches of different instances take
 * different locks and do not wait for each other.
 */
@SuppressWarnings({"deprecation", "removal"})
public final class LeaseGuardedJobRepository implements JobRepository {

    private static final Logger log = LoggerFactory.getLogger(LeaseGuardedJobRepository.class);

    private static final int CREATE_LOCK_TIMEOUT_MS = 10_000;

    private final JobRepository delegate;
    private final SafeTransactions createTransaction;
    private final ExecutionLeases leases;
    private final LeaseKeeper keeper;
    private final JdbcClient db;
    private final JobKeyGenerator keys;
    private final OutputRoots roots;

    /**
     * @param createTransaction the plain READ COMMITTED transaction in which an instance, or an
     *        execution and its lease, are created, and in which an execution's end is written
     * @param keys the job key generator of the delegate, which names the instance's lock
     * @param roots binds each first execution of an instance to the current output root
     */
    public LeaseGuardedJobRepository(JobRepository delegate, SafeTransactions createTransaction,
            ExecutionLeases leases, LeaseKeeper keeper, JdbcClient db, JobKeyGenerator keys, OutputRoots roots) {
        this.delegate = delegate;
        this.createTransaction = createTransaction;
        this.leases = leases;
        this.keeper = keeper;
        this.db = db;
        this.keys = keys;
        this.roots = roots;
    }
    private void guard(JobExecution execution) {
        if (keeper.isLost(execution.getId())) {
            throw new LeaseLostException(execution.getId());
        }
    }

    /**
     * A recovery raises the versions of the execution's rows, so a resumed stale owner usually
     * meets a version conflict in the step's own update before its fence runs at the commit.
     * When the lease is gone that conflict is the loss: without this, Spring Batch would go on
     * to write UNKNOWN over the recovered FAILED status.
     */
    private void write(JobExecution execution, Runnable write) {
        guard(execution);
        try {
            write.run();
        } catch (OptimisticLockingFailureException e) {
            if (!leases.held(execution.getId())) {
                keeper.lose(execution.getId());
                LeaseLostException lost = new LeaseLostException(execution.getId());
                lost.addSuppressed(e);
                throw lost;
            }
            throw e;
        }
    }

    /**
     * Another launch may have created the execution after the launcher read the instance's last
     * one. Only a first execution, or a restart after FAILED or STOPPED, is created; otherwise the
     * caller's decision is stale and it answers from the instance's state again.
     *
     * <p>The context gets its output root binding before the delegate inserts it, so the binding
     * commits with the execution and its lease, in this one transaction (contract section 4.10). A
     * restart's context is the previous execution's and keeps its binding; a restart whose
     * context has none is refused with {@code OUTPUT_ROOT_INVALID}.
     */
    @Override
    public JobExecution createJobExecution(JobInstance jobInstance, JobParameters jobParameters,
            ExecutionContext executionContext) {
        JobExecution execution = createTransaction.execute(status -> {
            lockInstance(jobInstance.getJobName(), jobParameters);
            JobExecution last = delegate.getLastJobExecution(jobInstance);
            if (last != null && last.getStatus() != BatchStatus.FAILED && last.getStatus() != BatchStatus.STOPPED) {
                throw new ConcurrentLaunchException(jobInstance, last);
            }
            ExecutionContext context = executionContext != null ? executionContext : new ExecutionContext();
            roots.bind(context, last);
            JobExecution created = delegate.createJobExecution(jobInstance, jobParameters, context);
            leases.acquire(created.getId());
            return created;
        });
        keeper.own(execution.getId());
        return execution;
    }

    /**
     * The update that ends an execution this process owns stops the renewals just before its
     * commit, so no reader sees the end while the lease is still renewed. When that update does
     * not commit, the database still shows the execution running and no thread is left to end
     * it: it becomes lost, so this process writes nothing more for it and a launch recovers it
     * once the lease is no longer renewed, instead of a lease renewed for as long as the
     * process lives. Releasing it without losing it would let a later write of this process
     * go through after a recovery.
     */
    @Override
    public void update(JobExecution jobExecution) {
        long id = jobExecution.getId();
        if (jobExecution.getStatus().isRunning() || !keeper.owns(id)) {
            write(jobExecution, () -> delegate.update(jobExecution));
            return;
        }
        createTransaction.executeWithoutResult(status -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    keeper.release(id);
                }

                @Override
                public void afterCompletion(int outcome) {
                    if (outcome != STATUS_COMMITTED) {
                        log.warn("job execution {} ended {} but the end was not committed; its lease is given up",
                                id, jobExecution.getStatus());
                        keeper.lose(id);
                    }
                }
            });
            write(jobExecution, () -> delegate.update(jobExecution));
        });
    }

    @Override
    public void updateExecutionContext(JobExecution jobExecution) {
        write(jobExecution, () -> delegate.updateExecutionContext(jobExecution));
    }

    @Override
    public StepExecution createStepExecution(String stepName, JobExecution jobExecution) {
        guard(jobExecution);
        return delegate.createStepExecution(stepName, jobExecution);
    }

    @Override
    public void update(StepExecution stepExecution) {
        write(stepExecution.getJobExecution(), () -> delegate.update(stepExecution));
    }

    @Override
    public void updateExecutionContext(StepExecution stepExecution) {
        write(stepExecution.getJobExecution(), () -> delegate.updateExecutionContext(stepExecution));
    }

    @Override
    public void deleteJobExecution(JobExecution jobExecution) {
        guard(jobExecution);
        delegate.deleteJobExecution(jobExecution);
    }

    @Override
    public void deleteStepExecution(StepExecution stepExecution) {
        guard(stepExecution.getJobExecution());
        delegate.deleteStepExecution(stepExecution);
    }

    /** An instance another launch created after the launcher looked for it is returned, as if it had been found. */
    @Override
    public JobInstance createJobInstance(String jobName, JobParameters jobParameters) {
        return createTransaction.execute(status -> {
            lockInstance(jobName, jobParameters);
            JobInstance existing = delegate.getJobInstance(jobName, jobParameters);
            return existing != null ? existing : delegate.createJobInstance(jobName, jobParameters);
        });
    }

    private void lockInstance(String jobName, JobParameters jobParameters) {
        String resource = "carddemo.batch_instance:" + jobName + ":" + keys.generateKey(jobParameters);
        if (!TransactionLocks.acquire(db, resource, "Exclusive", CREATE_LOCK_TIMEOUT_MS)) {
            throw new CannotAcquireLockException("job instance lock " + resource + " not granted");
        }
    }
    @Override
    public void deleteJobInstance(JobInstance jobInstance) {
        delegate.deleteJobInstance(jobInstance);
    }

    @Override
    public List<String> getJobNames() {
        return delegate.getJobNames();
    }

    @Override
    public List<JobInstance> getJobInstances(String jobName, int start, int count) {
        return delegate.getJobInstances(jobName, start, count);
    }

    @Override
    public List<JobInstance> findJobInstancesByJobName(String jobName, int start, int count) {
        return delegate.findJobInstancesByJobName(jobName, start, count);
    }

    @Override
    public List<JobInstance> findJobInstancesByName(String jobName, int start, int count) {
        return delegate.findJobInstancesByName(jobName, start, count);
    }

    @Override
    public List<JobInstance> findJobInstances(String jobName) {
        return delegate.findJobInstances(jobName);
    }

    @Override
    public boolean isJobInstanceExists(String jobName, JobParameters jobParameters) {
        return delegate.isJobInstanceExists(jobName, jobParameters);
    }

    @Override
    public JobInstance getJobInstance(long instanceId) {
        return delegate.getJobInstance(instanceId);
    }

    @Override
    public JobInstance getLastJobInstance(String jobName) {
        return delegate.getLastJobInstance(jobName);
    }

    /**
     * An instance without an execution is one another launch is creating (or one whose first
     * execution was never created); it reads as absent, so the launcher takes the fresh-start
     * path, whose creates wait for the instance's lock, instead of Spring Batch's "Cannot find
     * any job execution" failure.
     */
    @Override
    public JobInstance getJobInstance(String jobName, JobParameters jobParameters) {
        JobInstance instance = delegate.getJobInstance(jobName, jobParameters);
        return instance == null || delegate.getLastJobExecution(instance) == null ? null : instance;
    }

    @Override
    public long getJobInstanceCount(String jobName) throws NoSuchJobException {
        return delegate.getJobInstanceCount(jobName);
    }

    @Override
    public JobExecution getJobExecution(long executionId) {
        return delegate.getJobExecution(executionId);
    }

    @Override
    public List<JobExecution> getJobExecutions(JobInstance jobInstance) {
        return delegate.getJobExecutions(jobInstance);
    }

    @Override
    public List<JobExecution> findJobExecutions(JobInstance jobInstance) {
        return delegate.findJobExecutions(jobInstance);
    }

    @Override
    public JobExecution getLastJobExecution(JobInstance jobInstance) {
        return delegate.getLastJobExecution(jobInstance);
    }

    @Override
    public JobExecution getLastJobExecution(String jobName, JobParameters jobParameters) {
        return delegate.getLastJobExecution(jobName, jobParameters);
    }

    @Override
    public Set<JobExecution> findRunningJobExecutions(String jobName) {
        return delegate.findRunningJobExecutions(jobName);
    }

    @Override
    public StepExecution getStepExecution(long jobExecutionId, long stepExecutionId) {
        return delegate.getStepExecution(jobExecutionId, stepExecutionId);
    }

    @Override
    public StepExecution getStepExecution(long stepExecutionId) {
        return delegate.getStepExecution(stepExecutionId);
    }

    @Override
    public StepExecution getLastStepExecution(JobInstance jobInstance, String stepName) {
        return delegate.getLastStepExecution(jobInstance, stepName);
    }

    @Override
    public long getStepExecutionCount(JobInstance jobInstance, String stepName) throws NoSuchStepException {
        return delegate.getStepExecutionCount(jobInstance, stepName);
    }
}
