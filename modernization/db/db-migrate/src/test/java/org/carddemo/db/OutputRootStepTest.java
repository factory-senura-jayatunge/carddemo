package org.carddemo.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.outputroot.OutputRootException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** db-migrate's output-root step against a recorded stand-in for ops.database_instance. */
class OutputRootStepTest {

    static final String SENTINEL = "sentinel-5d93aa";

    @TempDir
    Path temp;
    Path var;
    FakeInstanceTable table;
    List<String> out;

    @BeforeEach
    void setUp() {
        var = OutputRoot.varDir(Map.of("CARDDEMO_VAR_DIR", temp.resolve("var").toString())::get);
        table = new FakeInstanceTable("carddemo", UUID.fromString("6776d692-7216-4e87-8451-02f9a6772e6f"));
        out = new ArrayList<>();
    }

    @Test
    void createsTheInstanceRootAndPrintsItWithNothingKept() throws Exception {
        Path root = OutputRootStep.run(table.connection(), var, false, out::add);

        OutputRoot.Instance instance = OutputRoot.Instance.of("carddemo", table.id.toString());
        assertThat(root).isEqualTo(OutputRoot.root(var, instance));
        assertThat(Files.readAllBytes(root.resolve(".instance"))).isEqualTo(instance.marker());
        assertThat(out).containsExactly("db-migrate: output root ready (instance " + table.id + ")");
        assertThat(out.getFirst()).doesNotContain(var.toString());
        assertThat(table.updates).isEmpty();
        assertThat(table.openStatements).isZero();
        assertThat(table.closed).isFalse();
    }

    @Test
    void aSecondRunKeepsTheSameRootAndChangesNothing() throws Exception {
        Path root = OutputRootStep.run(table.connection(), var, false, out::add);
        List<String> before = tree(var);
        out.clear();

        assertThat(OutputRootStep.run(table.connection(), var, false, out::add)).isEqualTo(root);
        assertThat(out).containsExactly("db-migrate: output root ready (instance " + table.id + ")");
        assertThat(tree(var)).isEqualTo(before);
    }

    @Test
    void newInstanceRotatesTheMarkerFirstAndKeepsTheOldRootListed() throws Exception {
        Path old = OutputRootStep.run(table.connection(), var, false, out::add);
        Files.createDirectories(var.resolve("reports"));
        Files.writeString(var.resolve("reports").resolve("TRANREPT.txt"), "x");
        Files.writeString(var.resolve("reports").resolve("TRANREPT.old"), "y");
        UUID previous = table.id;
        out.clear();

        Path root = OutputRootStep.run(table.connection(), var, true, out::add);

        assertThat(table.updates).containsExactly(OutputRootStep.ROTATE_SQL);
        assertThat(table.id).isNotEqualTo(previous);
        assertThat(root).isNotEqualTo(old).isEqualTo(OutputRoot.root(var, OutputRoot.Instance.of("carddemo", table.id.toString())));
        assertThat(old.resolve(".instance")).exists();
        assertThat(out).containsExactly(
                "db-migrate: output root ready (new instance " + table.id + ")",
                "db-migrate: kept, no longer read: ROOT " + old + " (1 file)",
                "db-migrate: kept, no longer read: PRE_INSTANCE " + var.resolve("reports") + " (2 files)");
    }

    @Test
    void aRotationThatFindsNoRowFailsBeforeAnyPathIsCreated() {
        table.rows = 0;
        assertThatThrownBy(() -> OutputRootStep.run(table.connection(), var, true, out::add))
                .isInstanceOfSatisfying(OutputRootException.class,
                        e -> assertThat(e.code()).isEqualTo(OutputRootException.Code.OUTPUT_ROOT_INVALID));
        assertThat(var).doesNotExist();
        assertThat(out).isEmpty();
    }

    @Test
    void aForeignRootIsReportedAndNotReplaced() throws Exception {
        Path root = OutputRoot.root(var, OutputRoot.Instance.of("carddemo", table.id.toString()));
        OutputRoot.ensure(var, OutputRoot.Instance.of("carddemo", table.id.toString()));
        byte[] foreign = OutputRoot.Instance.of("other", table.id.toString()).marker();
        Path marker = root.resolve(".instance");
        Files.write(marker, foreign);

        assertThatThrownBy(() -> OutputRootStep.run(table.connection(), var, false, out::add))
                .isInstanceOfSatisfying(OutputRootException.class,
                        e -> assertThat(e.code()).isEqualTo(OutputRootException.Code.OUTPUT_ROOT_MISMATCH));
        assertThat(Files.readAllBytes(marker)).isEqualTo(foreign);
        assertThat(out).isEmpty();
    }

    @Test
    void aSqlErrorIsReportedByClassNumberAndStateWithoutTheDriverMessage() {
        table.failure = new SQLException("Invalid object name 'ops.database_instance' secret-detail", "S0002", 208);

        assertThatThrownBy(() -> OutputRootStep.run(table.connection(), var, false, out::add))
                .isSameAs(table.failure);
        assertThat(var).doesNotExist();
        assertThat(out).isEmpty();
        assertThat(OutputRootStep.sqlFailure(table.failure))
                .isEqualTo("OUTPUT_ROOT_SQL_FAILED: SQLException, error 208, SQLState S0002")
                .doesNotContain("secret-detail").doesNotContain("database_instance");
        assertThat(OutputRootStep.sqlFailure(new SQLException("x"))).isEqualTo("OUTPUT_ROOT_SQL_FAILED: SQLException, error 0, SQLState none");
    }

    @Test
    void aRotationThatFailsInSqlCreatesNoPath() {
        table.failure = new SQLException("rotation refused", "S0005", 229);
        assertThatThrownBy(() -> OutputRootStep.run(table.connection(), var, true, out::add)).isSameAs(table.failure);
        assertThat(var).doesNotExist();
        assertThat(out).isEmpty();
    }

    /**
     * Contract 4.10, Messages outside the root: with a var dir whose name holds a sentinel, every
     * module message names it, and no failure line does.
     */
    @Test
    void aFailureLineNamesNoPathBelowTheVarDir() throws Exception {
        Path sentinelVar = OutputRoot.varDir(Map.of("CARDDEMO_VAR_DIR", temp.resolve("var-" + SENTINEL).toString())::get);
        OutputRoot.Instance instance = OutputRoot.Instance.of("carddemo", table.id.toString());
        Path root = OutputRoot.ensure(sentinelVar, instance);
        Files.write(root.resolve(".instance"), OutputRoot.Instance.of("other", table.id.toString()).marker());

        OutputRootException mismatch = refusal(sentinelVar);
        assertThat(mismatch.code()).isEqualTo(OutputRootException.Code.OUTPUT_ROOT_MISMATCH);
        assertThat(OutputRootStep.failure(OutputRootStep.ROOT_STEP, mismatch))
                .isEqualTo("OUTPUT_ROOT_MISMATCH: output root step");

        Files.write(root.resolve(".instance"), instance.marker());
        Files.setPosixFilePermissions(root.getParent(), PosixFilePermissions.fromString("rwxr-xr-x"));
        OutputRootException permissions = refusal(sentinelVar);
        assertThat(permissions.code()).isEqualTo(OutputRootException.Code.OUTPUT_PERMISSIONS);
        assertThat(OutputRootStep.failure(OutputRootStep.ROOT_STEP, permissions))
                .isEqualTo("OUTPUT_PERMISSIONS: output root step");
        assertThat(out).isEmpty();
    }

    @Test
    void anIoFailureLineNamesTheExceptionClassOnly() {
        UncheckedIOException io = new UncheckedIOException(
                new java.nio.file.AccessDeniedException("/var-" + SENTINEL + "/out/x"));
        assertThat(OutputRootStep.failure(OutputRootStep.ROOT_STEP, io))
                .isEqualTo("OUTPUT_ROOT_IO_FAILED: output root step, AccessDeniedException");
        assertThat(OutputRootStep.failure(OutputRootStep.VAR_DIR_CHECK, io))
                .isEqualTo("OUTPUT_ROOT_IO_FAILED: CARDDEMO_VAR_DIR check, AccessDeniedException");
    }

    @Test
    void aLegacySettingKeepsItsNameOnlyMessage() {
        OutputRootException legacy = new OutputRootException(OutputRootException.Code.OUTPUT_ROOT_LEGACY_SETTING,
                "CARDDEMO_REPORTS_DIR is no longer read; remove it (outputs are under CARDDEMO_VAR_DIR/out/)");
        assertThat(OutputRootStep.failure(OutputRootStep.VAR_DIR_CHECK, legacy)).isEqualTo(legacy.getMessage());
    }

    private OutputRootException refusal(Path varDir) {
        try {
            OutputRootStep.run(table.connection(), varDir, false, out::add);
        } catch (OutputRootException e) {
            assertThat(e.getMessage()).as("the module's own message names the path").contains(SENTINEL);
            assertThat(OutputRootStep.failure(OutputRootStep.ROOT_STEP, e)).doesNotContain(SENTINEL);
            return e;
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
        throw new AssertionError("no refusal");
    }

    private static List<String> tree(Path start) throws Exception {
        try (Stream<Path> paths = Files.walk(start)) {
            return paths.map(p -> p + (Files.isRegularFile(p) ? " " + size(p) : "")).sorted().toList();
        }
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** ops.database_instance row 1 as SQL Server returns it: DB_NAME() and the ID in upper case. */
    static final class FakeInstanceTable {
        final String database;
        UUID id;
        int rows = 1;
        SQLException failure;
        final List<String> updates = new ArrayList<>();
        int openStatements;
        boolean closed;

        FakeInstanceTable(String database, UUID id) {
            this.database = database;
            this.id = id;
        }

        Connection connection() {
            return proxy(Connection.class, (method, args) -> switch (method) {
                case "createStatement" -> statement();
                case "prepareStatement" -> prepared((String) args[0]);
                case "close" -> {
                    closed = true;
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method);
            });
        }

        private Statement statement() {
            openStatements++;
            return proxy(Statement.class, (method, args) -> switch (method) {
                case "executeUpdate" -> {
                    if (failure != null) {
                        throw failure;
                    }
                    updates.add((String) args[0]);
                    if (rows == 1) {
                        id = UUID.randomUUID();
                    }
                    yield rows;
                }
                case "close" -> {
                    openStatements--;
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method);
            });
        }

        private PreparedStatement prepared(String sql) {
            assertThat(sql).contains("DB_NAME()").contains("ops.database_instance").contains("id = 1");
            openStatements++;
            return proxy(PreparedStatement.class, (method, args) -> switch (method) {
                case "executeQuery" -> {
                    if (failure != null) {
                        throw failure;
                    }
                    yield result();
                }
                case "close" -> {
                    openStatements--;
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method);
            });
        }

        private ResultSet result() {
            int[] position = {0};
            return proxy(ResultSet.class, (method, args) -> switch (method) {
                case "next" -> ++position[0] <= rows;
                case "getString" -> (int) args[0] == 1 ? database : id.toString().toUpperCase(Locale.ROOT);
                case "close" -> null;
                default -> throw new UnsupportedOperationException(method);
            });
        }
    }

    interface Handler {
        Object call(String method, Object[] args) throws Exception;
    }

    static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (p, method, args) -> handler.call(method.getName(), args)));
    }
}
