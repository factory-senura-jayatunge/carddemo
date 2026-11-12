package org.carddemo.authorization;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.JsonSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.carddemo.platform.web.CarddemoHttpSecurity;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class AuthorizationConfiguration {

    /**
     * Request bodies, whose members the client may leave out. Every other schema of this service is
     * a response, which always carries every member (null included), so all of them are required.
     */
    static final Set<String> OPTIONAL_MEMBERS = Set.of();

    static {
        // Severity is shared by every response; one named schema keeps the generated client's type single.
        ModelResolver.enumsAsRef = true;
    }

    /**
     * Every {@code /api} route needs a {@code USER} token, so an {@code ADMIN} token gets 403 on the
     * pending-authorization screens (D-02, D-03) and a route added later is closed by default.
     */
    @Bean
    public SecurityFilterChain authorizationSecurity(HttpSecurity http, CarddemoHttpSecurity security) throws Exception {
        security.apply(http).authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                        "/v3/api-docs", "/v3/api-docs/**", "/error").permitAll()
                .requestMatchers("/api/**").hasRole("USER")
                .anyRequest().authenticated());
        return http.build();
    }

    /** Steps 3 to 9 of a request: one READ COMMITTED transaction (contract §6.2). */
    @Bean
    public TransactionTemplate authorizationTransaction(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    /** The screens' reads: one read-only READ COMMITTED transaction per request, no lock hints. */
    @Bean
    public TransactionTemplate authorizationReadTransaction(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(true);
        return template;
    }

    @Bean
    public OpenAPI authorizationOpenApi() {
        return new OpenAPI()
                .info(new Info().title("authorization-service").version("0.1.0").description(
                        "Snapshot of the authorization-service OpenAPI document. The service's OpenApiExportTest"
                                + " fails when its export differs from this file."))
                .servers(List.of(new Server().url("/")));
    }

    /**
     * The problem-details schemas are hand-written, because the error body is built by web-support's
     * {@code ApiProblem}, whose open member set the annotations cannot describe.
     */
    @Bean
    public OpenApiCustomizer authorizationSchemas() {
        Map<String, Schema> problems = problemSchemas();
        return openApi -> {
            openApi.setTags(null);
            // springdoc tags each operation with its handler's class name, which would make the
            // snapshot change whenever a handler class is renamed.
            if (openApi.getPaths() != null) {
                openApi.getPaths().values().forEach(item -> item.readOperations().forEach(op -> op.setTags(null)));
            }
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            if (openApi.getComponents().getSchemas() == null) {
                openApi.getComponents().setSchemas(new LinkedHashMap<>());
            }
            Map<String, Schema> schemas = openApi.getComponents().getSchemas();
            schemas.remove("NullableString");
            schemas.forEach((name, schema) -> {
                if (schema.getProperties() == null) {
                    return;
                }
                if (!OPTIONAL_MEMBERS.contains(name)) {
                    schema.setRequired(new ArrayList<>(new TreeSet<String>(schema.getProperties().keySet())));
                }
                Map<String, Schema> properties = schema.getProperties();
                properties.replaceAll((member, property) -> nullableRef(property));
            });
            problems.forEach(openApi.getComponents()::addSchemas);
        };
    }

    /**
     * A nullable member that refers to another schema: springdoc writes the {@code null} type next
     * to the {@code $ref}, which client generators drop, so it becomes {@code anyOf [ref, null]}.
     */
    static Schema nullableRef(Schema property) {
        if (property.get$ref() == null || property.getTypes() == null || !property.getTypes().contains("null")) {
            return property;
        }
        Schema result = new JsonSchema().anyOf(List.of(new JsonSchema().$ref(property.get$ref()),
                new JsonSchema().types(new LinkedHashSet<>(List.of("null")))));
        result.setDescription(property.getDescription());
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Schema> problemSchemas() {
        try (InputStream in = AuthorizationConfiguration.class.getResourceAsStream("/openapi/problem-schemas.json")) {
            Map<String, Object> raw = Json31.mapper().readValue(in, Map.class);
            Map<String, Schema> schemas = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                schemas.put(e.getKey(), Json31.mapper().convertValue(e.getValue(), Schema.class));
            }
            return schemas;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
