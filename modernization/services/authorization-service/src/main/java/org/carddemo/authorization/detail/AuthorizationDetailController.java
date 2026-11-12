package org.carddemo.authorization.detail;

import org.carddemo.authorization.api.AuthorizationDetail;
import org.carddemo.authorization.api.AuthorizationDetailApi;
import org.carddemo.authorization.api.FraudRequest;
import org.carddemo.authorization.api.FraudResponse;
import org.carddemo.authorization.detail.AuthorizationDetailService.Marked;
import org.carddemo.authorization.detail.AuthorizationDetailService.Versioned;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

/** Units AUDT and AUFR (COPAUS1C, COPAUS2C): any USER, on any account (D-03); an ADMIN token is refused (D-02). */
@RestController
@PreAuthorize("hasRole('USER')")
public class AuthorizationDetailController implements AuthorizationDetailApi {

    private final AuthorizationDetailService service;

    public AuthorizationDetailController(AuthorizationDetailService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<AuthorizationDetail> getAuthorization(String authId) {
        Versioned versioned = service.detail(authId);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, versioned.etag()).body(versioned.detail());
    }

    @Override
    public ResponseEntity<FraudResponse> changeAuthorizationFraud(String authId, String ifMatch, FraudRequest request,
            Jwt jwt) {
        Marked marked = service.changeFraud(authId, ifMatch, request == null ? null : request.action(),
                jwt.getSubject());
        return ResponseEntity.ok().header(HttpHeaders.ETAG, marked.saved().etag()).body(new FraudResponse(
                marked.message().render(), marked.message().severity(), marked.saved().detail()));
    }
}
