package org.carddemo.authorization.summary;

import org.carddemo.authorization.api.AuthorizationPage;
import org.carddemo.authorization.api.AuthorizationSummary;
import org.carddemo.authorization.api.AuthorizationSummaryApi;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Unit AUSM (COPAUS0C): any USER, on any account (D-03); an ADMIN token is refused (D-02). */
@RestController
@PreAuthorize("hasRole('USER')")
public class AuthorizationSummaryController implements AuthorizationSummaryApi {

    private final AuthorizationSummaryService service;

    public AuthorizationSummaryController(AuthorizationSummaryService service) {
        this.service = service;
    }

    @Override
    public AuthorizationSummary getAuthorizationSummary(String acctId) {
        return service.summary(acctId);
    }

    @Override
    public AuthorizationPage listAuthorizations(String acctId, String after, String before, String size) {
        return service.list(acctId, after, before, size);
    }
}
