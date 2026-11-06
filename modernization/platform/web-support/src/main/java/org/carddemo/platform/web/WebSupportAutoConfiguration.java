package org.carddemo.platform.web;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/**
 * The application clock ({@code carddemo.zone}, default the system zone) and, in a servlet
 * application, the shared resource-server security and error handling. Every bean backs off when
 * the service declares its own.
 */
@AutoConfiguration(beforeName = {
        "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration",
        "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration"})
@EnableConfigurationProperties(CarddemoSecurityProperties.class)
public class WebSupportAutoConfiguration {

    public static final String ZONE_PROPERTY = "carddemo.zone";

    @Bean
    @ConditionalOnMissingBean
    public Clock clock(Environment environment) {
        String zone = environment.getProperty(ZONE_PROPERTY, "");
        return Clock.system(zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @EnableWebSecurity
    @EnableMethodSecurity
    static class ServletSecurity {

        @Bean
        @ConditionalOnMissingBean
        public JwtDecoder jwtDecoder(CarddemoSecurityProperties properties, Clock clock) {
            return CarddemoJwt.decoder(properties, clock);
        }

        @Bean
        @ConditionalOnMissingBean
        public CsrfTokenRepository csrfTokenRepository(CarddemoSecurityProperties properties) {
            CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
            repository.setCookieName(CarddemoSecurityProperties.CSRF_COOKIE);
            repository.setHeaderName(CarddemoSecurityProperties.CSRF_HEADER);
            repository.setCookiePath("/");
            repository.setCookieCustomizer(c -> c.secure(properties.isCookieSecure()).sameSite("Strict"));
            return repository;
        }

        @Bean
        @ConditionalOnMissingBean
        public CarddemoHttpSecurity carddemoHttpSecurity(JwtDecoder decoder, CsrfTokenRepository csrfTokens) {
            return new CarddemoHttpSecurity(decoder, csrfTokens);
        }

        @Bean
        @ConditionalOnMissingBean(SecurityFilterChain.class)
        public SecurityFilterChain carddemoSecurityFilterChain(HttpSecurity http, CarddemoHttpSecurity security)
                throws Exception {
            security.apply(http).authorizeHttpRequests(a -> a
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                            "/v3/api-docs", "/v3/api-docs/**", "/error").permitAll()
                    .anyRequest().authenticated());
            return http.build();
        }

        @Bean
        @ConditionalOnMissingBean
        public ProblemExceptionHandler problemExceptionHandler() {
            return new ProblemExceptionHandler();
        }

        @Bean
        @ConditionalOnMissingBean(name = "correlationIdFilter")
        public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
            FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
