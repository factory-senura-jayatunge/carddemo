package org.carddemo.batch.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.UncheckedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.outputroot.OutputRootException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The binding and its checks against a marker row that the test sets, without a database. */
class OutputRootsTest {

    static final String DATABASE = "carddemo_test";
    static final UUID FIRST = UUID.fromString("0b0e4f7c-1d2a-4c3b-9e8f-7a6b5c4d3e2f");
    static final UUID SECOND = UUID.fromString("9f8e7d6c-5b4a-4321-8fed-cba987654321");

    @TempDir
    Path temp;

    Path varDir;
    String[] marker;
    final AtomicInteger reads = new AtomicInteger();
    OutputRoots roots;

    @BeforeEach
    void roots() {
        varDir = OutputRoot.varDir(Map.of(OutputRoot.VAR_DIR_PROPERTY, temp.resolve("var").toString())::get);
        marker = new String[] {DATABASE, FIRST.toString().toUpperCase()};
        roots = new OutputRoots(varDir, markerSource());
    }

    /** A data source whose only query is the marker read, answered with {@link #marker}. */
    private DataSource markerSource() {
        Object statement = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("executeQuery")) {
                        reads.incrementAndGet();
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class},
                                oneRow());
                    }
                    return null;
                });
        Object connection = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        assertThat((String) args[0]).contains("ops.database_instance");
                        yield statement;
                    }
                    case "isClosed" -> false;
                    default -> null;
                });
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> method.getName().equals("getConnection") ? connection : null);
    }

    private InvocationHandler oneRow() {
        int[] row = {0};
        return (proxy, method, args) -> switch (method.getName()) {
            case "next" -> row[0]++ == 0;
            case "getString" -> marker[(Integer) args[0] - 1];
            default -> null;
        };
    }

    private static JobExecution execution(ExecutionContext context) {
        JobExecution execution = new JobExecution(7L, new JobInstance(3L, "postDailyTransactionsJob"),
                new JobParameters());
        execution.setExecutionContext(context);
        return execution;
    }

    @Test
    void aFirstExecutionIsBoundToTheCurrentInstanceAndItsRootIsMade() {
        ExecutionContext context = new ExecutionContext();

        OutputRoot.Instance bound = roots.bind(context, null);

        assertThat(bound).isEqualTo(new OutputRoot.Instance(DATABASE, FIRST));
        assertThat(context.getString(OutputRoots.DATABASE_KEY)).isEqualTo(DATABASE);
        assertThat(context.getString(OutputRoots.INSTANCE_KEY)).isEqualTo(FIRST.toString());
        assertThat(OutputRoot.resolve(varDir, bound)).contains(OutputRoot.root(varDir, bound));
    }

    @Test
    void aRestartKeepsItsBindingWithoutReadingTheMarker() {
        ExecutionContext context = new ExecutionContext();
        context.putString(OutputRoots.DATABASE_KEY, DATABASE);
        context.putString(OutputRoots.INSTANCE_KEY, SECOND.toString());

        assertThat(roots.bind(context, execution(context))).isEqualTo(new OutputRoot.Instance(DATABASE, SECOND));
        assertThat(reads).hasValue(0);
        assertThat(context.getString(OutputRoots.INSTANCE_KEY)).isEqualTo(SECOND.toString());
    }

    @Test
    void aRestartOfAnUnboundExecutionIsRefusedNotBoundLate() {
        JobExecution previous = execution(new ExecutionContext());
        ExecutionContext context = new ExecutionContext();

        assertThatThrownBy(() -> roots.bind(context, previous)).isInstanceOf(OutputRootException.class)
                .hasMessage("OUTPUT_ROOT_INVALID: job execution 7 is bound to no output root, so its restart is refused;"
                        + " rerun the cycle or start a new run");
        assertThat(context.containsKey(OutputRoots.INSTANCE_KEY)).isFalse();
        assertThat(context.containsKey(OutputRoots.DATABASE_KEY)).isFalse();
        assertThat(reads).hasValue(0);
        assertThat(varDir.resolve("out")).doesNotExist();
    }

    @Test
    void anIncompleteBindingIsRefused() {
        ExecutionContext context = new ExecutionContext();
        context.putString(OutputRoots.DATABASE_KEY, DATABASE);

        assertThatThrownBy(() -> roots.bind(context, null)).isInstanceOf(OutputRootException.class)
                .hasMessage("OUTPUT_ROOT_INVALID: the job execution context holds an incomplete output root binding");
    }

    @Test
    void anUnboundExecutionIsRefusedNotBoundLate() {
        JobExecution execution = execution(new ExecutionContext());

        assertThatThrownBy(() -> roots.root(execution)).isInstanceOf(OutputRootException.class)
                .hasMessage("OUTPUT_ROOT_INVALID: job execution 7 is bound to no output root");
        assertThatThrownBy(() -> OutputRoots.requireBound(execution)).isInstanceOf(OutputRootException.class);
        assertThat(execution.getExecutionContext().containsKey(OutputRoots.INSTANCE_KEY)).isFalse();
        assertThat(reads).hasValue(0);
    }

    @Test
    void theBoundRootIsReturnedWhileTheDatabaseIsTheBoundInstance() {
        ExecutionContext context = new ExecutionContext();
        OutputRoot.Instance bound = roots.bind(context, null);

        assertThat(roots.root(execution(context))).isEqualTo(OutputRoot.root(varDir, bound));
    }

    @Test
    void anotherInstanceUnderARunningExecutionIsAMismatch() {
        ExecutionContext context = new ExecutionContext();
        roots.bind(context, null);
        marker[1] = SECOND.toString();

        assertThatThrownBy(() -> roots.root(execution(context))).isInstanceOf(OutputRootException.class)
                .hasMessage("OUTPUT_ROOT_MISMATCH: job execution 7 is bound to another database instance;"
                        + " it writes nothing more")
                .extracting(e -> ((OutputRootException) e).code())
                .isEqualTo(OutputRootException.Code.OUTPUT_ROOT_MISMATCH);
        assertThat(OutputRoot.resolve(varDir, new OutputRoot.Instance(DATABASE, SECOND))).isEmpty();
    }

    @Test
    void aBoundRootThatWasRemovedIsNotMadeAgain() throws Exception {
        ExecutionContext context = new ExecutionContext();
        OutputRoot.Instance bound = roots.bind(context, null);
        Path root = OutputRoot.root(varDir, bound);
        Files.delete(root.resolve(".instance"));
        Files.delete(root);

        assertThatThrownBy(() -> roots.root(execution(context))).isInstanceOf(OutputRootException.class)
                .hasMessage("OUTPUT_ROOT_INVALID: the output root of job execution 7 is missing");
        assertThat(root).doesNotExist();
    }

    @Test
    void theStartMakesTheRootWithEveryOutputFolderAndItsWorkFolder() {
        assertThat(roots.currentRoot()).isEmpty();

        Path root = roots.ensureAtStart();

        assertThat(roots.currentRoot()).contains(root);
        for (String folder : new String[] {"statements", "reports/tranrept", "reports/prtcatbl", "exports", "imports",
                "rejects", "dailytran/archive"}) {
            assertThat(root.resolve(folder)).isDirectory();
            assertThat(root.resolve(folder).resolve(".work")).isDirectory();
        }
        assertThat(roots.inbound()).isEqualTo(varDir.resolve("inbound")).doesNotExist();
    }

    /** Contract section 4.10, Messages outside the root: a refusal names no path below CARDDEMO_VAR_DIR. */
    @Test
    void aStartRefusalCarriesItsCodeAndAFixedReasonOnly() throws Exception {
        Path root = roots.ensureAtStart();
        Files.setPosixFilePermissions(root.resolve("rejects"), PosixFilePermissions.fromString("rwxr-xr-x"));

        assertThatThrownBy(roots::ensureAtStart).isInstanceOf(OutputRootException.class).hasNoCause()
                .hasMessage("OUTPUT_PERMISSIONS: ENSURE_DIRECTORY: an output folder could not be made or failed its check")
                .extracting(e -> ((OutputRootException) e).code())
                .isEqualTo(OutputRootException.Code.OUTPUT_PERMISSIONS);
    }

    @Test
    void aBindingRefusalCarriesItsCodeAndAFixedReasonOnly() throws Exception {
        Files.createDirectories(varDir.resolve("out"));
        Files.setPosixFilePermissions(varDir.resolve("out"), PosixFilePermissions.fromString("rwxr-xr-x"));
        ExecutionContext context = new ExecutionContext();

        assertThatThrownBy(() -> roots.bind(context, null)).isInstanceOf(OutputRootException.class).hasNoCause()
                .hasMessage("OUTPUT_PERMISSIONS: ENSURE: the instance root could not be made or failed its check");
        assertThat(context.containsKey(OutputRoots.INSTANCE_KEY)).isFalse();
    }

    @Test
    void aBoundRootThatFailsItsCheckCarriesItsCodeAndAFixedReasonOnly() throws Exception {
        ExecutionContext context = new ExecutionContext();
        OutputRoot.Instance bound = roots.bind(context, null);
        Files.setPosixFilePermissions(OutputRoot.root(varDir, bound), PosixFilePermissions.fromString("rwxr-x---"));

        assertThatThrownBy(() -> roots.root(execution(context))).isInstanceOf(OutputRootException.class).hasNoCause()
                .hasMessage("OUTPUT_PERMISSIONS: RESOLVE: the output root failed its check");
        assertThatThrownBy(roots::currentRoot).isInstanceOf(OutputRootException.class).hasNoCause()
                .hasMessage("OUTPUT_PERMISSIONS: RESOLVE: the output root failed its check");
    }

    @Test
    void aFileSystemFailureAtTheStartKeepsTheTypeOfItsCauseOnly() throws Exception {
        Path file = Files.createFile(temp.resolve("not-a-folder"));
        OutputRoots under = new OutputRoots(file.resolve("var"), markerSource());

        Throwable thrown = catchThrowable(under::ensureAtStart);

        assertThat(thrown).isInstanceOf(UncheckedIOException.class).hasMessageStartingWith(PublicationFailures.IO)
                .hasMessageNotContaining(temp.getFileName().toString());
        assertThat(thrown.getCause().getMessage()).isNull();
        assertThat(thrown.getCause()).hasNoCause();
        assertThat(thrown.getSuppressed()).isEmpty();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theStartCheckLogsNoPath(CapturedOutput output) {
        new OutputRootConfiguration().outputRootStartCheck(roots).afterSingletonsInstantiated();

        assertThat(roots.currentRoot()).isPresent();
        assertThat(output.getAll()).contains("output root and its output folders are ready")
                .doesNotContain(temp.getFileName().toString());
    }
}
