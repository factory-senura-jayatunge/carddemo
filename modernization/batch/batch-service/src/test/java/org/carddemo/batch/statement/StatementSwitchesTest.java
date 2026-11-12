package org.carddemo.batch.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.carddemo.batch.BatchServiceApplication;
import org.carddemo.batch.BatchSwitches;
import org.carddemo.batch.internal.BatchCommandCheck;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.testsupport.TestDatabase;
import org.carddemo.platform.fidelity.FidelityAutoConfiguration;
import org.carddemo.platform.fidelity.FidelitySwitch;
import org.carddemo.platform.fidelity.FidelitySwitchCatalog;
import org.carddemo.platform.fidelity.FidelitySwitches;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * DEF-STMT-001, 002, 003, 004 and 007 are covered by DEF-STMT-005 and have no switch of their own
 * (batch.md, "Build rulings", Q1). A selector for any of them, fixed or legacy, as a property or an
 * environment variable, stops batch-service at startup instead of being ignored, and the
 * diagnostic names the key, never its value. {@link BatchSwitches} declares DEF-STMT-005 and none
 * of the five.
 */
@ExtendWith(OutputCaptureExtension.class)
class StatementSwitchesTest {

    static final TestDatabase DB = TestDatabase.shared();

    @TempDir
    static Path varDir;

    static final List<String> COVERED = List.of("DEF-STMT-001", "DEF-STMT-002", "DEF-STMT-003", "DEF-STMT-004",
            "DEF-STMT-007");

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FidelityAutoConfiguration.class))
                .withBean("batchSwitchCatalog", FidelitySwitchCatalog.class,
                        () -> FidelitySwitchCatalog.of(BatchSwitches.ALL.toArray(FidelitySwitch[]::new)));
    }

    static List<Arguments> selectors() {
        return COVERED.stream().flatMap(id -> List.of("fixed", "legacy").stream().map(v -> Arguments.of(id, v)))
                .toList();
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("selectors")
    void aSelectorOfACoveredItemStopsTheStartAndNamesOnlyTheKey(String id, String value) {
        runner().withPropertyValues("carddemo.fidelity." + id + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure().rootCause().isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no FidelitySwitchCatalog declares").hasMessageContaining(id)
                    .message().doesNotContainIgnoringCase(value);
        });
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("selectors")
    void theEnvironmentVariableFormIsRefusedToo(String id, String value) {
        String variable = "CARDDEMO_FIDELITY_" + id.replace('-', '_');
        runner().withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of(variable, value))))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause().hasMessageContaining("no FidelitySwitchCatalog declares")
                            .message().doesNotContainIgnoringCase(value);
                });
    }

    static List<String> covered() {
        return COVERED;
    }

    /** batch-service itself, started as its command line starts it, with one of the five set fixed. */
    @ParameterizedTest(name = "{0}=fixed")
    @MethodSource("covered")
    void batchServiceDoesNotStartWithASelectorOfACoveredItem(String id, CapturedOutput output) {
        assertThatThrownBy(() -> new SpringApplicationBuilder(BatchServiceApplication.class)
                .listeners(new BatchCommandCheck()).run(
                        "--carddemo.var-dir=" + varDir,
                        "--spring.datasource.url=" + DB.jdbcUrl(),
                        "--spring.datasource.username=" + DB.loginName(ServiceLogin.BATCH),
                        "--spring.datasource.password=" + DB.password(ServiceLogin.BATCH),
                        "--carddemo.fidelity." + id + "=fixed")
                .close())
                .satisfies(e -> assertThat(undeclared(e)).hasMessageContaining(id).message()
                        .doesNotContainIgnoringCase("fixed"));
        assertThat(output.getAll()).doesNotContain(id + "=fixed");
    }

    private static IllegalStateException undeclared(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof IllegalStateException e && String.valueOf(e.getMessage())
                    .contains("no FidelitySwitchCatalog declares")) {
                return e;
            }
        }
        throw new AssertionError("the start did not fail on the undeclared switch: " + failure, failure);
    }

    /** The ledger's entries for the five are not selectors: only declared switches read the ledger. */
    @Test
    void withoutASelectorTheProcessStartsAndDeclaresOnlyDefStmt005() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            FidelitySwitches switches = context.getBean(FidelitySwitches.class);
            assertThat(switches.effectiveModes()).containsKey("DEF-STMT-005");
            assertThat(switches.effectiveModes().keySet()).doesNotContainAnyElementsOf(COVERED);
        });
    }

    @ParameterizedTest(name = "DEF-STMT-005={0}")
    @MethodSource("modes")
    void defStmt005IsTheOneStatementSelector(String value) {
        runner().withPropertyValues("carddemo.fidelity.DEF-STMT-005=" + value).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(StatementSwitches.mode(context.getBean(FidelitySwitches.class)).name())
                    .isEqualToIgnoringCase(value);
        });
    }

    static List<String> modes() {
        return List.of("fixed", "legacy");
    }
}
