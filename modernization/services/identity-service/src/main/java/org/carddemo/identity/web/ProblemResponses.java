package org.carddemo.identity.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;
import org.carddemo.identity.signon.AuthFailure;
import org.carddemo.identity.token.AuthCookies;
import org.carddemo.platform.web.Message;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Problem responses, for controllers and for the security filters that answer before them. */
@Component
public class ProblemResponses {

    private final JsonMapper json;
    private final AuthCookies cookies;

    public ProblemResponses(JsonMapper json, AuthCookies cookies) {
        this.json = json;
        this.cookies = cookies;
    }

    public ResponseEntity<ApiProblem> entity(AuthFailure f) {
        HttpHeaders headers = new HttpHeaders();
        if (f.clearCookies()) {
            headers.addAll(cookies.cleared());
        }
        if (f.retryAfter() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, f.retryAfter().toSeconds())));
        }
        return ResponseEntity.status(f.status()).headers(headers).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblem.of(f.status().value(), f.status().getReasonPhrase(), f.code(), f.getMessage(),
                        f.field(), f.severity()));
    }

    public void write(HttpServletResponse res, HttpStatus status, Message message) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(res.getOutputStream(), ApiProblem.of(status.value(), status.getReasonPhrase(), message.code(),
                message.text(), null, message.severity().key()));
    }
}
