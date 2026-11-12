package org.carddemo.batch.execution;

import java.nio.file.Path;

import javax.sql.DataSource;

import org.carddemo.platform.outputroot.OutputRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The output root's settings and start checks (batch-execution-contract.md section 4.10).
 * {@code carddemo.var-dir} (CARDDEMO_VAR_DIR) has no default: without it, with a relative value or
 * with a removed output setting, the start fails.
 */
@Configuration(proxyBeanMethods = false)
public class OutputRootConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OutputRootConfiguration.class);

    /** The canonical CARDDEMO_VAR_DIR, resolved once at start. */
    record VarDir(Path path) {
    }

    /**
     * The configuration check. A post-processor, so that it fails the start before any bean is
     * made; as an ordinary bean it could come after the data source, whose own failure would hide it.
     */
    @Bean
    static BeanFactoryPostProcessor outputRootSettingsCheck() {
        return beanFactory -> beanFactory.registerSingleton("batchVarDir",
                new VarDir(OutputRoot.varDir(beanFactory.getBean(Environment.class)::getProperty)));
    }

    @Bean
    OutputRoots outputRoots(VarDir varDir, DataSource dataSource) {
        return new OutputRoots(varDir.path(), dataSource);
    }

    /**
     * The root and output folder check, once every bean exists and before the internal API or a
     * one-shot command runs: each start fails on a root it cannot create or that fails a check.
     * The log names no path below CARDDEMO_VAR_DIR (section 4.10, Messages outside the root).
     */
    @Bean
    SmartInitializingSingleton outputRootStartCheck(OutputRoots roots) {
        return () -> {
            roots.ensureAtStart();
            log.info("output root and its output folders are ready");
        };
    }
}
