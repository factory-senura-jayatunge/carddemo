package org.carddemo.account;

import org.carddemo.account.api.CardLookupRequest;
import org.carddemo.account.api.CardView;
import org.carddemo.account.api.CardViewApi;
import org.carddemo.account.api.UnmaskRequest;
import org.carddemo.account.api.UnmaskResponse;
import org.carddemo.platform.web.Unmask;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

/** Unit CRDV (COCRDSLC): staff of both roles view a card; only an ADMIN unmasks its number (D-09). */
@RestController
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
public class CardViewController implements CardViewApi {

    private final CardViewService service;

    public CardViewController(CardViewService service) {
        this.service = service;
    }

    @Override
    public CardView lookupCard(CardLookupRequest request) {
        return request == null ? service.lookup(null, null) : service.lookup(request.acctId(), request.cardNum());
    }

    @Override
    public CardView viewCard(String cardId) {
        return service.view(cardId);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UnmaskResponse> unmaskCard(String cardId, UnmaskRequest request, Jwt jwt) {
        return Unmask.noStore(new UnmaskResponse(
                service.unmask(cardId, request == null ? null : request.fields(), jwt.getSubject())));
    }
}
