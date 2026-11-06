package org.carddemo.identity.config;

import org.carddemo.identity.ApiSecurityProperties;
import org.carddemo.identity.signon.Messages;
import org.carddemo.identity.token.AccessTokens;
import org.carddemo.identity.token.SessionTokenResolver;
import org.carddemo.identity.web.ProblemResponses;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Stateless resource-server security. CSRF is the SPA double-submit cookie, required on every
 * unsafe call except sign-on and password change, which have no session yet (SameSite=Strict
 * covers them).
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(ApiSecurityProperties security) {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookiePath("/");
        repo.setCookieCustomizer(c -> c.sameSite("Strict").secure(security.cookieSecure()));
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
                "UNAUTHENTICATED", Messages.SESSION_EXPIRED);
        AccessDeniedHandler denied = (req, res, e) -> {
            if (e instanceof CsrfException) {
                problems.write(res, HttpStatus.FORBIDDEN, "CSRF_INVALID", Messages.REQUEST_NOT_VERIFIED);
            } else {
                problems.write(res, HttpStatus.FORBIDDEN, "ACCESS_DENIED", Messages.ACCESS_DENIED);
            }
        };

        http
                .csrf(c -> c.csrfTokenRepository(csrf)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/api/auth/sign-on", "/api/auth/password")
                        // The default strategy clears XSRF-TOKEN whenever a request authenticates, which
                        // with a stateless bearer token is every call; tokens are issued at sign-on,
                        // password change and refresh instead.
                        .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(r -> r.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.POST, "/api/auth/sign-on", "/api/auth/password",
                                "/api/auth/refresh", "/api/auth/sign-off").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/.well-known/jwks.json", "/actuator/health",
                                "/actuator/health/**", "/v3/api-docs", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/me", "/api/features").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .bearerTokenResolver(new SessionTokenResolver())
                        .jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied));
        return http.build();
    }
}
