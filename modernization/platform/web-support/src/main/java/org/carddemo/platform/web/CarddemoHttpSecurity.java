package org.carddemo.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The security every CardDemo service shares: stateless JWT resource server, the SPA's
 * double-submit CSRF cookie, and problem-details errors. Services add their own
 * {@code authorizeHttpRequests} rules; the default filter chain of {@link WebSupportAutoConfiguration}
 * requires authentication for everything but health and the API document.
 */
public class CarddemoHttpSecurity {

    private final JwtDecoder decoder;
    private final CsrfTokenRepository csrfTokens;
    private final CookieBearerTokenResolver tokenResolver = new CookieBearerTokenResolver();
    private final ProblemSecurityHandlers handlers = new ProblemSecurityHandlers();

    public CarddemoHttpSecurity(JwtDecoder decoder, CsrfTokenRepository csrfTokens) {
        this.decoder = decoder;
        this.csrfTokens = csrfTokens;
    }

    /**
     * @param csrfExemptPosts POST paths that run without a session, so a CSRF token cannot exist
     *     yet (identity's sign-on and password change); {@code SameSite=Strict} covers them
     */
    public HttpSecurity apply(HttpSecurity http, String... csrfExemptPosts) throws Exception {
        List<RequestMatcher> exempt = new ArrayList<>();
        for (String path : csrfExemptPosts) {
            exempt.add(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, path));
        }
        // Our own CsrfFilter instead of http.csrf(): the resource-server configurer exempts every
        // request its token resolver accepts from the configured CSRF check, and the resolver
        // accepts the cd_at cookie, which is exactly the case CSRF protection is for.
        CsrfFilter csrf = new CsrfFilter(csrfTokens);
        csrf.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        csrf.setRequireCsrfProtectionMatcher(request -> requiresCsrf(request, exempt));
        csrf.setAccessDeniedHandler(handlers);
        return http
                .csrf(c -> c.disable())
                .addFilterAt(csrf, CsrfFilter.class)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(tokenResolver)
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers)
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(CarddemoJwt.authenticationConverter())));
    }

    /**
     * Unsafe methods need the token, except exempt paths and requests that authenticate only by an
     * Authorization header, which a browser never adds on its own.
     */
    static boolean requiresCsrf(HttpServletRequest request, List<RequestMatcher> exempt) {
        if (!CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)) {
            return false;
        }
        if (CookieBearerTokenResolver.cookieToken(request) == null && CookieBearerTokenResolver.hasBearerHeader(request)) {
            return false;
        }
        return exempt.stream().noneMatch(m -> m.matches(request));
    }
}
