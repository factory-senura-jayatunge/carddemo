package org.carddemo.platform.web;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A {@link TransactionTemplate} whose callbacks never hand the framework an exception that holds a
 * value. When a rollback fails, Spring logs the callback's exception with every cause before any
 * handler of ours runs, and a driver exception quotes statement values (error 2628 prints the
 * truncated value). So the callback itself throws {@link SafeErrors#messageFree} of what it caught:
 * the same exception when its chain is already message-free (an {@link ApiException}), else a copy
 * of the same kind with the SQL error numbers and states and no message.
 */
public class SafeTransactionTemplate extends TransactionTemplate {

    public SafeTransactionTemplate(PlatformTransactionManager transactionManager) {
        super(transactionManager);
    }

    @Override
    public <T> T execute(TransactionCallback<T> action) {
        return super.execute(status -> {
            try {
                return action.doInTransaction(status);
            } catch (RuntimeException e) {
                throw SafeErrors.messageFree(e);
            } catch (Error e) {
                throw SafeErrors.isMessageFree(e) ? e : (Error) SafeErrors.sanitized(e);
            }
        });
    }
}
