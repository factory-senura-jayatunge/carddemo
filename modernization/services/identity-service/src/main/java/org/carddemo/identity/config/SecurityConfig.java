package org.carddemo.identity.config;

import java.util.Set;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.carddemo.identity.token.AccessTokens;
import org.carddemo.identity.token.AuthCookies;
import org.carddemo.identity.token.SessionTokenResolver;
import org.carddemo.identity.user.Roles;
import org.carddemo.identity.web.ProblemResponses;
import org.carddemo.platform.web.CarddemoSecurityProperties;
import org.carddemo.platform.web.MessageCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Stateless resource-server security. CSRF is the SPA double-submit cookie, required on every
 * unsafe call except sign-on and password change, which have no session yet (SameSite=Strict
 * covers them).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    /** No session exists yet, so no CSRF token can either; SameSite=Strict covers them. */
    static final Set<String> CSRF_EXEMPT_POSTS = Set.of("/api/auth/sign-on", "/api/auth/password");

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(CarddemoSecurityProperties security) {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookiePath("/");
        repo.setCookieCustomizer(c -> c.sameSite("Strict").secure(security.isCookieSecure()));
        return repo;
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtDecoder decoder, CookieCsrfTokenRepository csrf,
            ProblemResponses problems) throws Exception {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName(AccessTokens.CLAIM_ROLE);
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);

        AuthenticationEntryPoint unauthenticated = (req, res, e) -> problems.write(res, HttpStatus.UNAUTHORIZED,
                MessageCatalog.UNAUTHENTICATED);
        AccessDeniedHandler denied = (req, res, e) -> {
            if (e instanceof CsrfException) {
                problems.write(res, HttpStatus.FORBIDDEN, MessageCatalog.CSRF_REJECTED);
            } else {
                problems.write(res, HttpStatus.FORBIDDEN, MessageCatalog.ACCESS_DENIED);
            }
        };

        // Our own CsrfFilter instead of http.csrf(): the resource-server configurer exempts from the
        // configured CSRF check every request its token resolver accepts, and the resolver accepts
        // the cd_at cookie on /api/users, which is exactly the case CSRF protection is for. Without
        // http.csrf() there is also no strategy that clears XSRF-TOKEN on every authenticated call;
        // tokens are issued at sign-on, password change and refresh.
        CsrfFilter csrfFilter = new CsrfFilter(csrf);
        csrfFilter.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        csrfFilter.setRequireCsrfProtectionMatcher(SecurityConfig::requiresCsrf);
        csrfFilter.setAccessDeniedHandler(denied);

        http
                .csrf(c -> c.disable())
                .addFilterAt(csrfFilter, CsrfFilter.class)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .authorizeHttpRequests(a -> a
                        // The error dispatch after a refused request (the firewall's 400) keeps its
                        // status instead of becoming a 401 for want of a session.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/sign-on", "/api/auth/password",
                                "/api/auth/refresh", "/api/auth/sign-off").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/.well-known/jwks.json", "/actuator/health",
                                "/actuator/health/**", "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me", "/api/features").authenticated()
                        .requestMatchers("/api/users", "/api/users/**").hasRole(Roles.ADMIN)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(new SessionTokenResolver())
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied));
        return http.build();
    }

    /**
     * Unsafe methods need the token, except sign-on and password change, and requests that
     * authenticate only by an Authorization header, which a browser never adds on its own.
     */
    static boolean requiresCsrf(HttpServletRequest request) {
        if (!CsrfFilter.DEFAULT_CSRF_MATCHER.matches(request)) {
            return false;
        }
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (SessionTokenResolver.cookie(request, AuthCookies.ACCESS) == null && auth != null
                && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return false;
        }
        return !(HttpMethod.POST.matches(request.getMethod()) && CSRF_EXEMPT_POSTS.contains(path(request)));
    }

    private static String path(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }
}
