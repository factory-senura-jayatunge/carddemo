package org.carddemo.account;

import org.carddemo.account.AccountUpdateService.Versioned;
import org.carddemo.account.api.AccountUpdateApi;
import org.carddemo.account.api.AccountUpdateForm;
import org.carddemo.account.api.AccountUpdateRequest;
import org.carddemo.account.api.AccountUpdateResponse;
import org.carddemo.account.api.AccountValidateRequest;
import org.carddemo.account.api.AccountValidateResponse;
import org.carddemo.platform.web.MessageCatalog;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

/** Unit ACUP (COACTUPC): only a USER updates an account (D-09); an ADMIN reads it on account view. */
@RestController
@PreAuthorize("hasRole('USER')")
public class AccountUpdateController implements AccountUpdateApi {

    private final AccountUpdateService service;

    public AccountUpdateController(AccountUpdateService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<AccountUpdateForm> fetchAccountForUpdate(String acctId) {
        Versioned versioned = service.fetch(acctId);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, versioned.etag()).body(versioned.form());
    }

    @Override
    public AccountValidateResponse validateAccountUpdate(String acctId, AccountValidateRequest request) {
        var changed = service.validate(acctId, request == null ? null : request.version(),
                request == null ? null : request.changes());
        return new AccountValidateResponse(MessageCatalog.CHANGES_VALIDATED.render(),
                MessageCatalog.CHANGES_VALIDATED.severity(), changed);
    }

    @Override
    public ResponseEntity<AccountUpdateResponse> updateAccount(String acctId, String ifMatch,
            AccountUpdateRequest request, Jwt jwt) {
        Versioned saved = service.update(acctId, ifMatch, request == null ? null : request.changes(),
                jwt.getSubject());
        return ResponseEntity.ok().header(HttpHeaders.ETAG, saved.etag()).body(new AccountUpdateResponse(
                MessageCatalog.CHANGES_COMMITTED.render(), MessageCatalog.CHANGES_COMMITTED.severity(), saved.form()));
    }
}
