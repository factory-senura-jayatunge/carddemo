package org.carddemo.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.TestContextManager;

/** O8's private folder per test class, without a database. */
class PrivateVarDirTest {

    static final class First {
    }

    static final class Second {
    }

    private static ContextCustomizer customizer(Class<?> testClass) {
        return new PrivateVarDir.Factory().createContextCustomizer(testClass, List.of());
    }

    @Test
    void eachTestClassHasItsOwnContextKey() {
        assertThat(customizer(First.class)).isEqualTo(customizer(First.class));
        assertThat(customizer(First.class)).isNotEqualTo(customizer(Second.class));
    }

    @Test
    void aContextGetsAnOwnerOnlyFolderFirstAndItIsGoneOnceTheContextCloses() throws Exception {
        GenericApplicationContext first = new GenericApplicationContext();
        first.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("shared",
                java.util.Map.of(PrivateVarDir.PROPERTY, "/shared/var")));
        customizer(First.class).customizeContext(first, null);
        GenericApplicationContext second = new GenericApplicationContext();
        customizer(Second.class).customizeContext(second, null);
        first.refresh();
        second.refresh();

        Path dir = Path.of(first.getEnvironment().getProperty(PrivateVarDir.PROPERTY));
        Path other = Path.of(second.getEnvironment().getProperty(PrivateVarDir.PROPERTY));
        assertThat(dir).isAbsolute().isDirectory().isNotEqualTo(other);
        assertThat(Files.getPosixFilePermissions(dir)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        Path outside = Files.writeString(Files.createTempFile("carddemo-outside-", ".txt"), "kept");
        Files.createDirectories(dir.resolve("out/db/instance"));
        Files.writeString(dir.resolve("out/db/instance/FILE.txt"), "bytes");
        Files.createSymbolicLink(dir.resolve("out/link"), outside);

        first.close();

        assertThat(dir).doesNotExist();
        assertThat(outside).hasContent("kept");
        assertThat(other).isDirectory();
        second.close();
        assertThat(other).doesNotExist();
        Files.delete(outside);
    }

    @Test
    void bothAreRegisteredForEverySpringTest() {
        assertThat(BootstrapUtils.resolveTestContextBootstrapper(First.class).buildMergedContextConfiguration()
                .getContextCustomizers()).contains(customizer(First.class));
        assertThat(new TestContextManager(First.class).getTestExecutionListeners())
                .hasAtLeastOneElementOfType(PrivateVarDir.Closer.class);
    }
}
