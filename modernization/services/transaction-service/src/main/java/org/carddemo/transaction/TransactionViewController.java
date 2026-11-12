package org.carddemo.transaction;

import org.carddemo.platform.web.Unmask;
import org.carddemo.transaction.api.TransactionView;
import org.carddemo.transaction.api.TransactionViewApi;
import org.carddemo.transaction.api.UnmaskRequest;
import org.carddemo.transaction.api.UnmaskResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unit TRNV (COTRN01C): staff of both roles view any transaction (D-SEC-01); only an ADMIN unmasks
 * its card number (D-09).
 */
@RestController
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
public class TransactionViewController implements TransactionViewApi {

    private final TransactionViewService service;

    public TransactionViewController(TransactionViewService service) {
        this.service = service;
    }

    @Override
    public TransactionView viewTransaction(String tranId) {
        return service.view(tranId);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UnmaskResponse> unmaskTransaction(String tranId, UnmaskRequest request, Jwt jwt) {
        return Unmask.noStore(new UnmaskResponse(
                service.unmask(tranId, request == null ? null : request.fields(), jwt.getSubject())));
    }
}
