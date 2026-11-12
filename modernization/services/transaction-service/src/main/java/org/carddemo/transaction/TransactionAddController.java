package org.carddemo.transaction;

import org.carddemo.transaction.api.AddResult;
import org.carddemo.transaction.api.LatestTransactionView;
import org.carddemo.transaction.api.TransactionAddApi;
import org.carddemo.transaction.api.TransactionCheck;
import org.carddemo.transaction.api.TransactionForm;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

/** Unit TRNA (COTRN02C): main-menu option 8 is a USER option, checked on the server (D-02, DEF-TRNA-012). */
@RestController
@PreAuthorize("hasRole('USER')")
public class TransactionAddController implements TransactionAddApi {

    private final TransactionAddService service;

    public TransactionAddController(TransactionAddService service) {
        this.service = service;
    }

    @Override
    public TransactionCheck validateTransaction(TransactionForm form) {
        return service.validate(form);
    }

    @Override
    public ResponseEntity<AddResult> addTransaction(String idempotencyKey, TransactionForm form, Jwt jwt) {
        TransactionAddService.Outcome outcome = service.add(form, idempotencyKey, jwt.getSubject());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (outcome.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(outcome.result());
    }

    @Override
    public LatestTransactionView latestTransaction() {
        return service.latest();
    }
}
