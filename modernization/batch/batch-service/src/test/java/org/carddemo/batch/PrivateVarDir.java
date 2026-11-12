package org.carddemo.batch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.beans.factory.support.DefaultSingletonBeanRegistry;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.annotation.DirtiesContext.HierarchyMode;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Private test folders (batch-execution-contract.md section 4.10, O8): every Spring test class
 * runs with its own {@code carddemo.var-dir}, a new folder of mode {@code 0700} under the JVM's
 * temporary folder, so no test writes into a shared CARDDEMO_VAR_DIR or into one that another
 * class used. The test class is part of the context's cache key, so no two classes share a
 * context; the context is closed after its class, and the folder is removed after every other
 * bean of that context is destroyed. Both are registered in {@code META-INF/spring.factories}.
 */
public final class PrivateVarDir {

    /** The property source that holds the private folder; it comes before every other one. */
    public static final String SOURCE = "carddemo.privateVarDir";
    static final String PROPERTY = "carddemo.var-dir";
    /**
     * A word in every private folder's name, so that a test can show that no path below
     * CARDDEMO_VAR_DIR reaches a log, an exit message or an answer (section 4.10, Messages outside
     * the root).
     */
    public static final String SENTINEL = "varsentinel7q3z";

    private PrivateVarDir() {
    }

    /** Gives each test class's context its own folder. */
    public static final class Factory implements ContextCustomizerFactory {

        @Override
        public ContextCustomizer createContextCustomizer(Class<?> testClass,
                List<ContextConfigurationAttributes> configAttributes) {
            return new Customizer(testClass);
        }
    }

    record Customizer(Class<?> testClass) implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            Path dir = create();
            // Registered before any bean exists, so it is destroyed after all of them.
            ((DefaultSingletonBeanRegistry) context.getBeanFactory()).registerDisposableBean(SOURCE, () -> remove(dir));
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource(SOURCE, Map.of(PROPERTY, dir.toString())));
        }
    }

    /** Closes a test class's context once the class is done, which removes its folder. */
    public static final class Closer implements TestExecutionListener {

        @Override
        public void afterTestClass(TestContext testContext) {
            if (testContext.hasApplicationContext()) {
                testContext.markApplicationContextDirty(HierarchyMode.EXHAUSTIVE);
            }
        }
    }

    static Path create() {
        try {
            return Files.createTempDirectory("carddemo-var-" + SENTINEL + "-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Removes the folder and everything below it; links are removed, never followed. */
    static void remove(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
