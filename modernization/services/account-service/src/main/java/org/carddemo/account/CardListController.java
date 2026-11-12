package org.carddemo.account;

import org.carddemo.account.api.CardListApi;
import org.carddemo.account.api.CardPage;
import org.carddemo.account.api.CardSearchRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Unit CRDL (COCRDLIC): every signed-on USER or ADMIN lists all cards (BR-CRDL-002, D-03). */
@RestController
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
public class CardListController implements CardListApi {

    private final CardListService service;

    public CardListController(CardListService service) {
        this.service = service;
    }

    @Override
    public CardPage listCards(CardSearchRequest request) {
        return service.list(request);
    }
}
