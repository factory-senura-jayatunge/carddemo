package org.carddemo.batch.execution;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;

/**
 * Transactions of request handlers (cycle registration, window open and close, reads):
 * the plain transaction manager, with no lease and no fence. Job steps never use it.
 */
public final class RequestTransactions {

    private final SafeTransactions template;

    RequestTransactions(PlatformTransactionManager plain) {
        this.template = new SafeTransactions(plain);
    }

    public <T> T execute(TransactionCallback<T> action) {
        return template.execute(action);
    }
}
