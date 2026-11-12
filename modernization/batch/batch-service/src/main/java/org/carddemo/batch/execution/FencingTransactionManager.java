package org.carddemo.batch.execution;

import org.carddemo.platform.outputroot.OutputRoot;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.JobContext;
import org.springframework.batch.core.scope.context.JobSynchronizationManager;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.core.Ordered;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The only transaction manager job steps get (contract section 4.3). Each new transaction
 * belongs to the execution bound to the thread, and its last statement before COMMIT
 * is the lease renewal; when that updates no row the transaction rolls back with
 * {@link LeaseLostException}. After the renewal, which holds the lease row's lock until the
 * commit, the database instance marker is read again and compared with the execution's output
 * root binding (section 4.10); another instance rolls the transaction back with
 * {@code OUTPUT_ROOT_MISMATCH}. A transaction with no bound execution, or for an execution with no
 * binding, is refused.
 */
public final class FencingTransactionManager implements PlatformTransactionManager {

    private final PlatformTransactionManager delegate;
    private final ExecutionLeases leases;
    private final LeaseKeeper keeper;
    private final OutputRoots roots;

    public FencingTransactionManager(PlatformTransactionManager delegate, ExecutionLeases leases, LeaseKeeper keeper,
            OutputRoots roots) {
        this.delegate = delegate;
        this.leases = leases;
        this.keeper = keeper;
        this.roots = roots;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
        JobExecution execution = boundExecution();
        long executionId = execution.getId();
        if (keeper.isLost(executionId)) {
            throw new LeaseLostException(executionId);
        }
        // Before the delegate opens anything, so a refusal leaves no transaction behind.
        OutputRoot.Instance instance = OutputRoots.requireBound(execution);
        TransactionStatus status = delegate.getTransaction(definition);
        if (status.isNewTransaction()) {
            TransactionSynchronizationManager.registerSynchronization(new Fence(executionId, instance));
        }
        return status;
    }

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
        delegate.commit(status);
    }

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
        delegate.rollback(status);
    }

    /** The step's execution, or, in a job listener, the job's. */
    private static JobExecution boundExecution() {
        StepContext step = StepSynchronizationManager.getContext();
        if (step != null) {
            return step.getStepExecution().getJobExecution();
        }
        JobContext job = JobSynchronizationManager.getContext();
        if (job != null) {
            return job.getJobExecution();
        }
        throw new IllegalStateException("a fenced transaction needs a step or job execution bound to the thread");
    }

    private final class Fence implements TransactionSynchronization {

        private final long executionId;
        private final OutputRoot.Instance instance;

        Fence(long executionId, OutputRoot.Instance instance) {
            this.executionId = executionId;
            this.instance = instance;
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            if (!leases.renew(executionId)) {
                keeper.lose(executionId);
                throw new LeaseLostException(executionId);
            }
            roots.check(executionId, instance);
        }
    }
}
