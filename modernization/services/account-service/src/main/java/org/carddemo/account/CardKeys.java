package org.carddemo.account;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.carddemo.platform.validation.FieldProblem;
import org.carddemo.platform.validation.FormRules;
import org.carddemo.platform.validation.RuleTable;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.MessageCatalog;
import org.springframework.http.HttpStatus;

/**
 * The typed card keys of card view and card update (VAL-CRDV-001 to VAL-CRDV-006): the rule table's
 * {@code cardLookup} form, which the page's TypeScript mirror runs too. Only its first problem is
 * the answer.
 */
final class CardKeys {

    static final String FORM = "cardLookup";

    private static final Map<String, Message> MESSAGES = Map.of(
            "NO_INPUT", MessageCatalog.CARD_KEY_NO_INPUT,
            "ACCT_ID_REQUIRED", MessageCatalog.CARD_KEY_ACCT_ID_REQUIRED,
            "INVALID_ACCT_ID", MessageCatalog.CARD_KEY_INVALID_ACCT_ID,
            "CARD_NUM_REQUIRED", MessageCatalog.CARD_NUM_REQUIRED,
            "INVALID_CARD_NUM", MessageCatalog.INVALID_CARD_NUM);

    private CardKeys() {
    }

    /** Nothing when both keys are valid; otherwise the 400 of the first failed rule. */
    static void require(String acctId, String cardNum, LocalDate today) {
        Map<String, String> input = new HashMap<>();
        input.put("acctId", acctId);
        input.put("cardNum", cardNum);
        FormRules form = RuleTable.standard().form(FORM);
        form.validate(input, today).first().ifPresent(problem -> {
            throw new ApiException(HttpStatus.BAD_REQUEST, message(problem), problem.field());
        });
    }

    static Message message(FieldProblem problem) {
        Message message = MESSAGES.get(problem.code());
        if (message == null) {
            throw new IllegalStateException("no catalogue message for rule code " + problem.code());
        }
        return message;
    }
}
