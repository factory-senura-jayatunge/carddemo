package org.carddemo.billpay;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.carddemo.platform.fidelity.FidelitySwitchCatalog;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class BillPayConfiguration {

    static {
        // Severity is shared by every response; one named schema keeps the generated client's type single.
        ModelResolver.enumsAsRef = true;
    }

    @Bean
    public FidelitySwitchCatalog billPayFidelitySwitches() {
        return FidelitySwitchCatalog.of(BillPaySwitches.DEF_BILL_010);
    }

    /** Step 5 of the payment: one READ COMMITTED transaction for every write (D-29). */
    @Bean
    public TransactionTemplate billPayTransaction(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    @Bean
    public OpenAPI billPayOpenApi() {
        return new OpenAPI()
                .info(new Info().title("billpay-service").version("0.1.0").description(
                        "Snapshot of the billpay-service OpenAPI document. The service's OpenApiExportTest fails"
                                + " when its export differs from this file."))
                .servers(List.of(new Server().url("/")));
    }

    /**
     * The problem-details schemas: hand-written, because the error body is built by web-support's
     * {@code ApiProblem}, whose open member set the annotations cannot describe.
     */
    @Bean
    public OpenApiCustomizer billPayProblemSchemas() {
        Map<String, Schema> problems = problemSchemas();
        return openApi -> {
            openApi.setTags(null);
            problems.forEach(openApi.getComponents()::addSchemas);
        };
    }

    @SuppressWarnings("unchecked")
    static Map<String, Schema> problemSchemas() {
        try (InputStream in = BillPayConfiguration.class.getResourceAsStream("/openapi/problem-schemas.json")) {
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
