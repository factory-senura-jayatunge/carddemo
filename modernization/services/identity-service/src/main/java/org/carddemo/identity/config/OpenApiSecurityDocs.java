package org.carddemo.identity.config;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.carddemo.identity.token.AuthCookies;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What SecurityConfig enforces, in the OpenAPI document: the security schemes, which operations
 * need a signed-on caller, the X-XSRF-TOKEN header that unsafe calls with the session cookie need
 * and that refresh and sign-off always need, and the 401 and 403 answers of user administration.
 */
@Configuration(proxyBeanMethods = false)
class OpenApiSecurityDocs {

    static final String SESSION = "sessionCookie";
    static final String REFRESH = "refreshCookie";
    static final String CSRF = "csrfHeader";
    static final String BEARER = "bearerToken";
    static final String CSRF_HEADER = "X-XSRF-TOKEN";

    /** GET operations outside /api/users that need a signed-on caller (SecurityConfig). */
    private static final List<String> AUTHENTICATED_GETS = List.of("/api/auth/me", "/api/features");

    @Bean
    OpenApiCustomizer identitySecurityDocs() {
        return OpenApiSecurityDocs::document;
    }

    static void document(OpenAPI api) {
        if (api.getComponents() == null) {
            api.setComponents(new Components());
        }
        api.getComponents()
                .addSecuritySchemes(SESSION, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE).name(AuthCookies.ACCESS)
                        .description("The access token cookie that sign-on, password change and refresh set."
                                + " With it, POST, PUT and DELETE also need " + CSRF_HEADER + "."))
                .addSecuritySchemes(REFRESH, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE).name(AuthCookies.REFRESH)
                        .description("The refresh token cookie (path " + AuthCookies.REFRESH_PATH + ") that sign-on,"
                                + " password change and refresh set. Refresh uses it once; sign-off ends its session."))
                .addSecuritySchemes(CSRF, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER).name(CSRF_HEADER)
                        .description("The value of the XSRF-TOKEN cookie (double submit), on every POST, PUT and"
                                + " DELETE sent with the session cookie, and on refresh and sign-off."))
                .addSecuritySchemes(BEARER, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                        .bearerFormat("JWT")
                        .description("The access token as Authorization: Bearer, for scripts. Without the session"
                                + " cookie no CSRF header is needed."));
        if (api.getPaths() == null) {
            return;
        }
        api.getPaths().forEach((path, item) -> {
            boolean users = path.equals("/api/users") || path.startsWith("/api/users/");
            item.readOperationsMap().forEach((method, op) -> {
                boolean safe = method == PathItem.HttpMethod.GET;
                if (users) {
                    op.setSecurity(requirements(safe));
                    addResponse(op, "401", "UNAUTHENTICATED");
                    addResponse(op, "403", safe ? "ACCESS_DENIED (not an administrator)"
                            : "ACCESS_DENIED (not an administrator) or CSRF_REJECTED (the session cookie without "
                                    + CSRF_HEADER + ")");
                    sortResponses(op);
                } else if (safe && AUTHENTICATED_GETS.contains(path)) {
                    op.setSecurity(requirements(true));
                } else if (path.equals("/api/auth/refresh")) {
                    op.setSecurity(List.of(new SecurityRequirement().addList(REFRESH).addList(CSRF)));
                } else if (path.equals("/api/auth/sign-off")) {
                    // Either cookie names the session to end; without one there is nothing to end, but
                    // the CSRF check still runs.
                    op.setSecurity(List.of(new SecurityRequirement().addList(REFRESH).addList(CSRF),
                            new SecurityRequirement().addList(SESSION).addList(CSRF)));
                }
            });
        });
    }

    /** The session cookie (with the CSRF header on unsafe calls), or a bearer token alone. */
    private static List<SecurityRequirement> requirements(boolean safe) {
        SecurityRequirement cookie = new SecurityRequirement().addList(SESSION);
        if (!safe) {
            cookie.addList(CSRF);
        }
        return List.of(cookie, new SecurityRequirement().addList(BEARER));
    }

    private static void addResponse(Operation op, String code, String description) {
        op.getResponses().addApiResponse(code, new ApiResponse().description(description)
                .content(new Content().addMediaType("application/problem+json",
                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiProblem")))));
    }

    private static void sortResponses(Operation op) {
        Map<String, ApiResponse> sorted = new TreeMap<>(op.getResponses());
        ApiResponses responses = new ApiResponses();
        sorted.forEach(responses::addApiResponse);
        op.setResponses(responses);
    }
}
