package org.carddemo.identity.web;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.carddemo.identity.IdentityProperties;
import org.carddemo.identity.session.SessionService;
import org.carddemo.identity.session.StartedSession;
import org.carddemo.identity.signon.AuthFailure;
import org.carddemo.identity.signon.SignOnService;
import org.carddemo.identity.signon.SignOnService.SignedOn;
import org.carddemo.identity.token.AccessTokens;
import org.carddemo.identity.token.AuthCookies;
import org.carddemo.identity.token.SessionTokenResolver;
import org.carddemo.identity.token.SigningKeys;
import org.carddemo.identity.user.AppUser;
import org.carddemo.identity.user.UserRepository;
import org.carddemo.identity.web.Dtos.PasswordRequest;
import org.carddemo.identity.web.Dtos.SessionView;
import org.carddemo.identity.web.Dtos.SignOffResult;
import org.carddemo.identity.web.Dtos.SignOnRequest;
import org.carddemo.platform.web.MessageCatalog;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "auth")
@ApiResponse(responseCode = "4XX", description = "Refused, with the screen message",
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
class AuthController {

    private final SignOnService signOn;
    private final SessionService sessions;
    private final UserRepository users;
    private final AuthCookies cookies;
    private final CsrfTokenRepository csrf;
    private final JwtDecoder decoder;
    private final SigningKeys keys;
    private final IdentityProperties props;
    private final SessionTokenResolver tokens = new SessionTokenResolver();

    AuthController(SignOnService signOn, SessionService sessions, UserRepository users, AuthCookies cookies,
            CsrfTokenRepository csrf, JwtDecoder decoder, SigningKeys keys, IdentityProperties props) {
        this.signOn = signOn;
        this.sessions = sessions;
        this.users = users;
        this.cookies = cookies;
        this.csrf = csrf;
        this.decoder = decoder;
        this.keys = keys;
        this.props = props;
    }

    @PostMapping("/sign-on")
    @Operation(operationId = "signOn", summary = "Sign on (COSGN00C) and start a session")
    @ApiResponse(responseCode = "200", description = "Signed on; sets cd_at, cd_rt and XSRF-TOKEN")
    ResponseEntity<SessionView> signOn(@RequestBody SignOnRequest body, HttpServletRequest req,
            HttpServletResponse res) {
        StartedSession s = signOn.signOn(body.userId(), body.password(), req.getRemoteAddr());
        return started(s, req, res);
    }

    @PostMapping("/password")
    @Operation(operationId = "changePassword",
            summary = "Change the password with the current one, then start a session (D-SEC-07)")
    @ApiResponse(responseCode = "200", description = "Password changed and signed on; sets cd_at, cd_rt and XSRF-TOKEN")
    ResponseEntity<SessionView> changePassword(@RequestBody PasswordRequest body, HttpServletRequest req,
            HttpServletResponse res) {
        Optional<SignedOn> signedOn = token(req).flatMap(this::signedOn);
        StartedSession s = signOn.changePassword(body.userId(), body.currentPassword(), body.newPassword(),
                signedOn, req.getRemoteAddr());
        return started(s, req, res);
    }

    @PostMapping("/refresh")
    @Operation(operationId = "refresh", summary = "Use the refresh cookie once and get new cookies")
    @ApiResponse(responseCode = "200", description = "Session extended; new cd_at, cd_rt and XSRF-TOKEN")
    ResponseEntity<SessionView> refresh(
            @CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken,
            HttpServletRequest req, HttpServletResponse res) {
        StartedSession s = sessions.refresh(refreshToken, req.getRemoteAddr());
        return started(s, req, res);
    }

    @PostMapping("/sign-off")
    @Operation(operationId = "signOff", summary = "End the session (FLOW-SGN-003)")
    @ApiResponse(responseCode = "200", description = "Signed off; cookies cleared")
    ResponseEntity<SignOffResult> signOff(
            @CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken,
            HttpServletRequest req, HttpServletResponse res) {
        Optional<UUID> sessionId = token(req).map(jwt -> UUID.fromString(jwt.getClaimAsString(AccessTokens.CLAIM_SESSION)));
        sessions.signOff(sessionId, refreshToken, req.getRemoteAddr());
        addHeaders(res, cookies.cleared());
        return ResponseEntity.ok(new SignOffResult(MessageCatalog.SIGNED_OFF.text(), MessageCatalog.SIGNED_OFF.severity().key()));
    }

    @GetMapping("/me")
    @Operation(operationId = "me", summary = "The signed-on user")
    @ApiResponse(responseCode = "200", description = "The session's user")
    SessionView me(@AuthenticationPrincipal Jwt jwt) {
        UUID sessionId = UUID.fromString(jwt.getClaimAsString(AccessTokens.CLAIM_SESSION));
        if (sessions.active(sessionId).isEmpty()) {
            throw AuthFailure.of(HttpStatus.UNAUTHORIZED, MessageCatalog.UNAUTHENTICATED, null);
        }
        AppUser user = users.find(jwt.getSubject()).orElseThrow(AuthFailure::sessionExpired);
        return view(user, jwt.getClaimAsString(AccessTokens.CLAIM_ROLE));
    }

    @GetMapping("/.well-known/jwks.json")
    @Operation(operationId = "jwks", summary = "The public key that signs access tokens")
    @ApiResponse(responseCode = "200", description = "JWK set (RFC 7517)")
    Map<String, Object> jwks() {
        return keys.publicJwks();
    }

    private ResponseEntity<SessionView> started(StartedSession s, HttpServletRequest req, HttpServletResponse res) {
        csrf.saveToken(csrf.generateToken(req), req, res);
        addHeaders(res, cookies.session(s.accessToken(), s.accessMaxAge(), s.refreshToken(), s.refreshMaxAge()));
        return ResponseEntity.ok(view(s.user(), s.role()));
    }

    /**
     * Set-Cookie headers go straight to the servlet response: ResponseEntity headers replace any
     * Set-Cookie already written, which would drop the XSRF-TOKEN cookie.
     */
    private static void addHeaders(HttpServletResponse res, HttpHeaders headers) {
        headers.forEach((name, values) -> values.forEach(v -> res.addHeader(name, v)));
    }

    private SessionView view(AppUser user, String role) {
        Duration idle = props.idleTimeout();
        return new SessionView(user.userId(), String.valueOf(user.userType()), role, user.firstName(),
                user.lastName(), user.home(), idle.toSeconds());
    }

    /** A valid access token, when the request carries one; an expired or forged one counts as none. */
    private Optional<Jwt> token(HttpServletRequest req) {
        String value = tokens.fromRequest(req);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(decoder.decode(value));
        } catch (JwtException e) {
            return Optional.empty();
        }
    }

    private Optional<SignedOn> signedOn(Jwt jwt) {
        UUID sessionId = UUID.fromString(jwt.getClaimAsString(AccessTokens.CLAIM_SESSION));
        return sessions.active(sessionId).map(s -> new SignedOn(jwt.getSubject(), sessionId));
    }
}
