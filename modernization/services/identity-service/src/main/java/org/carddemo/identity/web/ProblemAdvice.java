package org.carddemo.identity.web;

import org.carddemo.identity.signon.AuthFailure;
import org.carddemo.platform.web.MessageCatalog;
import org.carddemo.platform.web.ProblemExceptionHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The platform's error handling (catalogue texts only, INTERNAL_ERROR with the correlation ID)
 * plus the auth refusals. Being a ProblemExceptionHandler, it replaces the platform's bean.
 */
@RestControllerAdvice
class ProblemAdvice extends ProblemExceptionHandler {

    private final ProblemResponses problems;

    ProblemAdvice(ProblemResponses problems) {
        this.problems = problems;
    }

    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<ApiProblem> authFailure(AuthFailure f) {
        return problems.entity(f);
    }

    /**
     * A body that is not the expected JSON: only scripts send that, the web form cannot. The auth
     * endpoints answer with the sign-on screen's text; the others with INVALID_REQUEST.
     */
    @Override
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<org.carddemo.platform.web.ApiProblem> unreadable(HttpMessageNotReadableException e) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)
                || !attrs.getRequest().getRequestURI().startsWith("/api/auth/")) {
            return super.unreadable(e);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(org.carddemo.platform.web.ApiProblem.MEDIA_TYPE)
                .body(org.carddemo.platform.web.ApiProblem.of(HttpStatus.BAD_REQUEST,
                        MessageCatalog.INVALID_INPUT_USER_ID, "userId"));
    }
}
