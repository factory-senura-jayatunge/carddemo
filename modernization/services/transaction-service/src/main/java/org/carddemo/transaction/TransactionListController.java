package org.carddemo.transaction;

import org.carddemo.transaction.api.TransactionListApi;
import org.carddemo.transaction.api.TransactionPage;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unit TRNL (COTRN00C): every signed-on USER lists every transaction (BR-TRNL-006, D-03), and an
 * ADMIN reads the same list from admin option 7.
 */
@RestController
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
public class TransactionListController implements TransactionListApi {

    private final TransactionListService service;

    public TransactionListController(TransactionListService service) {
        this.service = service;
    }

    @Override
    public TransactionPage listTransactions(String start, String after, String before, String size) {
        return service.list(start, after, before, size);
    }
}
