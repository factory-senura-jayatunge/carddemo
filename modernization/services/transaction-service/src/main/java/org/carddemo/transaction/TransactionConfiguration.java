package org.carddemo.transaction;

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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.web.SafeTransactionTemplate;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReportProperties.class)
public class TransactionConfiguration {

    /**
     * Request bodies, whose members the client may leave out. Every other schema of this service is
     * a response, which always carries every member (null included), so all of them are required.
     */
    static final Set<String> OPTIONAL_MEMBERS = Set.of("UnmaskRequest", "TransactionForm", "ReportRequest");

    static {
        // Severity is shared by every response; one named schema keeps the generated client's type single.
        ModelResolver.enumsAsRef = true;
    }

    /** A write (the unmask's audit row, the add): one READ COMMITTED transaction. */
    @Bean
    public SafeTransactionTemplate transactionWriteTx(PlatformTransactionManager transactionManager) {
        SafeTransactionTemplate template = new SafeTransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    /** The list's and the view's reads: read-only READ COMMITTED, no lock hint (SE-TRNL-001, DEF-TRNV-001). */
    @Bean
    public SafeTransactionTemplate transactionReadTx(PlatformTransactionManager transactionManager) {
        SafeTransactionTemplate template = new SafeTransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setReadOnly(true);
        return template;
    }

    /**
     * The output root's start check: CARDDEMO_VAR_DIR set and absolute, and no removed output
     * setting; then the report staging setting, resolved once, and the clean-up of snapshot names
     * that a crash left there (transaction-service.md 16, item 4). A post-processor, so that it fails
     * the start before any bean is made; as an ordinary bean it could come after the data source,
     * whose own failure would hide it. The clean-up itself never fails the start.
     */
    @Bean
    static BeanFactoryPostProcessor reportFilesStartCheck() {
        return beanFactory -> {
            Environment environment = beanFactory.getBean(Environment.class);
            Path varDir = OutputRoot.varDir(environment::getProperty);
            ReportStaging staging = ReportStaging.resolve(varDir, environment::getProperty);
            beanFactory.registerSingleton("reportFiles", new ReportFiles(varDir));
            beanFactory.registerSingleton("reportStaging", staging);
            staging.cleanUp();
        };
    }

    @Bean
    public ReportTrigger reportTrigger(ReportProperties properties) {
        return new AirflowReportTrigger(properties.trigger());
    }

    @Bean
    public OpenAPI transactionOpenApi() {
        return new OpenAPI()
                .info(new Info().title("transaction-service").version("0.1.0").description(
                        "Snapshot of the transaction-service OpenAPI document. The service's OpenApiExportTest fails"
                                + " when its export differs from this file."))
                .servers(List.of(new Server().url("/")));
    }

    /**
     * The problem-details schemas are hand-written, because the error body is built by web-support's
     * {@code ApiProblem}, whose open member set the annotations cannot describe.
     */
    @Bean
    public OpenApiCustomizer transactionSchemas() {
        Map<String, Schema> problems = problemSchemas();
        return openApi -> {
            openApi.setTags(null);
            // springdoc tags each operation with its handler's class name, which would make the
            // snapshot change whenever a controller is renamed or split.
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
        try (InputStream in = TransactionConfiguration.class.getResourceAsStream("/openapi/problem-schemas.json")) {
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
