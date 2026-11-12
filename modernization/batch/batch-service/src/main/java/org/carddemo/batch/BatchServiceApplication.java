package org.carddemo.batch;

import java.util.TimeZone;

import org.carddemo.batch.internal.BatchCommandCheck;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The batch jobs of CardDemo. By default it runs the one-shot load and exits, with exit code 0
 * only when dataLoadJob completed; with the profile {@code server} it serves the internal API
 * (batch-execution-contract.md) on loopback and starts jobs only when Airflow launches them.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BatchServiceApplication {

    public static void main(String[] args) {
        // Spring Batch writes its timestamps in the JVM's zone; the lease-less recovery compares
        // LAST_UPDATED with SYSUTCDATETIME(), and the poll reports database (UTC) times.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication application = new SpringApplication(BatchServiceApplication.class);
        application.addListeners(new BatchCommandCheck());
        ConfigurableApplicationContext context = application.run(args);
        if (context instanceof WebServerApplicationContext) {
            return;
        }
        System.exit(SpringApplication.exit(context));
    }
}
