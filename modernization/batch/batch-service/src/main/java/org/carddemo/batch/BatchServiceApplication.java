package org.carddemo.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The batch jobs of CardDemo. In this slice it runs one job and exits
 * ({@code spring.batch.job.name}); the exit code is 0 only when the job
 * ends {@code COMPLETED}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BatchServiceApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(BatchServiceApplication.class, args)));
    }
}
