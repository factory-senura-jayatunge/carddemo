package org.carddemo.account;

import org.carddemo.account.api.AccountView;
import org.carddemo.account.api.AccountViewApi;
import org.carddemo.account.api.UnmaskRequest;
import org.carddemo.account.api.UnmaskResponse;
import org.carddemo.platform.web.Unmask;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

/** Unit ACVW (COACTVWC): staff of both roles view; only an ADMIN unmasks (D-09). */
@RestController
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
public class AccountViewController implements AccountViewApi {

    private final AccountViewService service;

    public AccountViewController(AccountViewService service) {
        this.service = service;
    }

    @Override
    public AccountView viewAccount(String acctId) {
        return service.view(acctId);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UnmaskResponse> unmaskAccount(String acctId, UnmaskRequest request, Jwt jwt) {
        return Unmask.noStore(new UnmaskResponse(
                service.unmask(acctId, request == null ? null : request.fields(), jwt.getSubject())));
    }
}
