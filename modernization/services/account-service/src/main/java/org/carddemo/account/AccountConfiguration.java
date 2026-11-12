package org.carddemo.account;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json31;
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
import org.carddemo.platform.web.SafeTransactionTemplate;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

@Configuration(proxyBeanMethods = false)
public class AccountConfiguration {

    /**
     * Request bodies, whose members the client may leave out. Every other schema of this service is
     * a response, which always carries every member (null included), so all of them are required.
     */
    static final Set<String> OPTIONAL_MEMBERS = Set.of("CardSearchRequest", "CardLookupRequest", "CardChanges",
            "UnmaskRequest", "AccountValidateRequest", "AccountUpdateRequest", "CardValidateRequest", "CardUpdateRequest");

    static {
        // Severity is shared by every response; one named schema keeps the generated client's type single.
        ModelResolver.enumsAsRef = true;
    }

    /** Each save is one READ COMMITTED transaction (D-16, D-29). */
    @Bean
    public SafeTransactionTemplate accountTransaction(PlatformTransactionManager transactionManager) {
        SafeTransactionTemplate template = new SafeTransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    /** The views' reads: one read-only READ COMMITTED transaction, no locks (BR-ACVW-001). */
    @Bean
    public SafeTransactionTemplate accountReadTransaction(PlatformTransactionManager transactionManager) {
        SafeTransactionTemplate template = new SafeTransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(true);
        return template;
    }

    @Bean
    public OpenAPI accountOpenApi() {
        return new OpenAPI()
                .info(new Info().title("account-service").version("0.1.0").description(
                        "Snapshot of the account-service OpenAPI document. The service's OpenApiExportTest fails"
                                + " when its export differs from this file."))
                .servers(List.of(new Server().url("/")));
    }

    /**
     * The problem-details schemas are hand-written, because the error body is built by web-support's
     * {@code ApiProblem}, whose open member set the annotations cannot describe.
     */
    @Bean
    public OpenApiCustomizer accountSchemas() {
        Map<String, Schema> problems = problemSchemas();
        return openApi -> {
            openApi.setTags(null);
            // springdoc tags each operation with its handler's class name, which would make the
            // snapshot change whenever a unit's contract stand-in is replaced by its controller.
            if (openApi.getPaths() != null) {
                openApi.getPaths().values().forEach(item -> item.readOperations().forEach(op -> op.setTags(null)));
            }
            if (openApi.getComponents() == null) {
                openApi.setComponents(new io.swagger.v3.oas.models.Components());
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
                    schema.setRequired(new ArrayList<>(new java.util.TreeSet<String>(schema.getProperties().keySet())));
                }
                Map<String, Schema> properties = schema.getProperties();
                properties.replaceAll((member, property) -> nullableRef(property));
                Schema additional = properties.get("fields") instanceof Schema f && f.getAdditionalProperties() instanceof Schema a
                        && a.get$ref() != null && a.get$ref().endsWith("/NullableString") ? f : null;
                if (additional != null) {
                    additional.setAdditionalProperties(new JsonSchema().types(new LinkedHashSet<>(List.of("string", "null"))));
                }
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
        try (InputStream in = AccountConfiguration.class.getResourceAsStream("/openapi/problem-schemas.json")) {
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
