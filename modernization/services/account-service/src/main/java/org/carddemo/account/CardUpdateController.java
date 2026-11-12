package org.carddemo.account;

import org.carddemo.account.CardUpdateService.Versioned;
import org.carddemo.account.api.CardLookupRequest;
import org.carddemo.account.api.CardUpdateApi;
import org.carddemo.account.api.CardUpdateForm;
import org.carddemo.account.api.CardUpdateRequest;
import org.carddemo.account.api.CardUpdateResponse;
import org.carddemo.account.api.CardValidateRequest;
import org.carddemo.account.api.CardValidateResponse;
import org.carddemo.platform.web.MessageCatalog;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

/** Unit CRDU (COCRDUPC): only a USER updates a card (D-09); an ADMIN reads it on card view. */
@RestController
@PreAuthorize("hasRole('USER')")
public class CardUpdateController implements CardUpdateApi {

    private final CardUpdateService service;

    public CardUpdateController(CardUpdateService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<CardUpdateForm> lookupCardForUpdate(CardLookupRequest request) {
        return withEtag(request == null ? service.lookup(null, null) : service.lookup(request.acctId(), request.cardNum()));
    }

    @Override
    public ResponseEntity<CardUpdateForm> fetchCardForUpdate(String cardId) {
        return withEtag(service.fetch(cardId));
    }

    @Override
    public CardValidateResponse validateCardUpdate(String cardId, CardValidateRequest request) {
        service.validate(cardId, request == null ? null : request.version(), request == null ? null : request.changes());
        return new CardValidateResponse(MessageCatalog.CHANGES_VALIDATED.render(),
                MessageCatalog.CHANGES_VALIDATED.severity());
    }

    @Override
    public ResponseEntity<CardUpdateResponse> updateCard(String cardId, String ifMatch, CardUpdateRequest request,
            Jwt jwt) {
        Versioned saved = service.update(cardId, ifMatch, request == null ? null : request.changes(), jwt.getSubject());
        return ResponseEntity.ok().header(HttpHeaders.ETAG, saved.etag()).body(new CardUpdateResponse(
                MessageCatalog.CHANGES_COMMITTED.render(), MessageCatalog.CHANGES_COMMITTED.severity(), saved.form()));
    }

    private static ResponseEntity<CardUpdateForm> withEtag(Versioned versioned) {
        return ResponseEntity.ok().header(HttpHeaders.ETAG, versioned.etag()).body(versioned.form());
    }
}
