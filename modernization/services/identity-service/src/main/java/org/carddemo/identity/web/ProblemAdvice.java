package org.carddemo.identity.web;

import org.carddemo.identity.signon.AuthFailure;
import org.carddemo.identity.signon.Messages;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ProblemAdvice {

    private final ProblemResponses problems;

    ProblemAdvice(ProblemResponses problems) {
        this.problems = problems;
    }

    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<ApiProblem> authFailure(AuthFailure f) {
        return problems.entity(f);
    }

    /** A body that is not the expected JSON: only scripts send that, the web form cannot. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiProblem> unreadable(HttpMessageNotReadableException e) {
        return problems.entity(HttpStatus.BAD_REQUEST, "INVALID_INPUT", Messages.USER_ID_REQUIRED, "userId");
    }
}
