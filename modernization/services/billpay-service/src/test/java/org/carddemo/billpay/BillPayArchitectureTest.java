package org.carddemo.billpay;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Only ledger-core writes the core tables (INV-004); Bill Pay goes through its interfaces. */
class BillPayArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.carddemo.billpay");

    private static final Pattern CORE_WRITE = Pattern.compile(
            "\\b(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|MERGE(\\s+INTO)?|TRUNCATE\\s+TABLE)\\s+core\\.",
            Pattern.CASE_INSENSITIVE);

    @Test
    void billPayUsesTheLedgerInterfacesOnly() {
        noClasses().that().resideInAPackage("org.carddemo.billpay..")
                .should().dependOnClassesThat().haveNameMatching("org\\.carddemo\\.platform\\.ledger\\.Jdbc.*")
                .because("ledger-core's JDBC implementations are reached through TransactionWriter and BalancePosting")
                .check(CLASSES);
    }

    @Test
    void theControllerGoesThroughTheService() {
        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .check(CLASSES);
    }

    @Test
    void noBillPaySqlWritesACoreTable() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            offenders = files.filter(Files::isRegularFile)
                    .filter(f -> CORE_WRITE.matcher(read(f)).find())
                    .map(Path::toString).toList();
        }
        assertThat(offenders).isEmpty();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
