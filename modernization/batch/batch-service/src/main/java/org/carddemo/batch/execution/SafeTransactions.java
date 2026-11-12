package org.carddemo.batch.execution;

import java.lang.reflect.UndeclaredThrowableException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.CallbackPreferringPlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The TransactionTemplate of batch-service. When a rollback fails, Spring's template logs the
 * callback's exception with every cause, and a driver exception quotes statement values. This one
 * begins, commits and rolls back through the same manager itself (so the definition, the
 * synchronizations and the fence behave exactly as with Spring's), and differs only there: it
 * logs {@link SafeFailures#describe} of the callback's exception and throws the rollback failure
 * with sanitized copies. When the rollback succeeds, the callback's exception is rethrown as it
 * is, because callers act on its type (a launch that lost a race, a recovery that found the
 * lease taken, an internal API answer).
 */
public class SafeTransactions extends TransactionTemplate {

    private static final Logger log = LoggerFactory.getLogger(SafeTransactions.class);

    public SafeTransactions(PlatformTransactionManager transactionManager) {
        super(transactionManager);
        if (transactionManager instanceof CallbackPreferringPlatformTransactionManager) {
            throw new IllegalArgumentException("a callback-preferring transaction manager runs its own rollback");
        }
    }

    @Override
    public <T> T execute(TransactionCallback<T> action) throws TransactionException {
        PlatformTransactionManager manager = getTransactionManager();
        TransactionStatus status = manager.getTransaction(this);
        T result;
        try {
            result = action.doInTransaction(status);
        } catch (RuntimeException | Error e) {
            rollbackOnException(manager, status, e);
            throw e;
        } catch (Throwable e) {
            rollbackOnException(manager, status, e);
            throw new UndeclaredThrowableException(e, "TransactionCallback threw undeclared checked exception");
        }
        manager.commit(status);
        return result;
    }

    private static void rollbackOnException(PlatformTransactionManager manager, TransactionStatus status,
            Throwable application) {
        try {
            manager.rollback(status);
        } catch (RuntimeException | Error rollback) {
            log.error("Application exception overridden by rollback exception: {}; rollback: {}",
                    SafeFailures.describe(application), SafeFailures.describe(rollback));
            TransactionSystemException failure = new TransactionSystemException(
                    "Rollback failed: " + SafeFailures.describe(rollback), SafeFailures.sanitized(rollback));
            failure.initApplicationException(SafeFailures.sanitized(application));
            throw failure;
        }
    }
}
