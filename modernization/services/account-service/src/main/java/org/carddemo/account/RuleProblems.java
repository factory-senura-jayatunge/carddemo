package org.carddemo.account;

import org.carddemo.platform.validation.FieldProblem;
import org.carddemo.platform.validation.ValidationResult;
import org.carddemo.platform.web.ApiException;
import org.carddemo.platform.web.Message;
import org.carddemo.platform.web.Severity;
import org.springframework.http.HttpStatus;

/**
 * A failed form of the rule table as a {@code 400} problem. The code and text come from
 * platform/validation, which the page's TypeScript mirror shares, so they are not catalogue
 * entries; {@code invalidFields} marks every failing field in screen order.
 */
final class RuleProblems {

    private RuleProblems() {
    }

    static ApiException rejected(ValidationResult result) {
        FieldProblem first = result.first().orElseThrow(() -> new IllegalArgumentException("the form passed"));
        Message message = new Message(first.code(), first.code(), first.message(), Severity.ERROR, "rules.json");
        if (!message.params().isEmpty()) {
            throw new IllegalStateException("rule text of " + first.code() + " has an unfilled placeholder");
        }
        return new ApiException(HttpStatus.BAD_REQUEST, message, first.field())
                .with("invalidFields", result.wrongFields());
    }
}
