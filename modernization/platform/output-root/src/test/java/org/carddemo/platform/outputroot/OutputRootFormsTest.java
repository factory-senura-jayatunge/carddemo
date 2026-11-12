package org.carddemo.platform.outputroot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.carddemo.platform.outputroot.OutputRoot.Instance;
import org.carddemo.platform.outputroot.OutputRootException.Code;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The canonical forms, the start check and the marker read: the vectors every caller relies on. */
class OutputRootFormsTest {

    static final String ID = "6776d692-7216-4e87-8451-02f9a6772e6f";

    @Test
    void theDatabaseComponentIsTheSha256OfTheUtf8Name() {
        assertThat(Instance.of("carddemo", ID).component())
                .isEqualTo("5840ec35206deb1526e4d5157357eb33967810eeaf78b62d08f273555c7cab0d");
        assertThat(Instance.of("carddemo_batch_e2e", ID).component())
                .isEqualTo("0f4d4c01b9ed045e253a23586296247326b6070d74139a653767701e7c8861dd");
    }

    @Test
    void theMarkerIsThreeAsciiLinesWithTheNameInBase64Url() {
        assertThat(new String(Instance.of("carddemo", ID).marker(), StandardCharsets.US_ASCII))
                .isEqualTo("carddemo-output-root 1\ndatabase=Y2FyZGRlbW8\ninstance=" + ID + "\n");
    }

    @Test
    void anUpperCaseDriverValueGivesTheLowerCaseId() {
        Instance instance = Instance.of("carddemo", ID.toUpperCase(java.util.Locale.ROOT));
        assertThat(instance.instanceText()).isEqualTo(ID);
        assertThat(instance).isEqualTo(Instance.of("carddemo", ID));
        assertThat(instance.marker()).isEqualTo(Instance.of("carddemo", ID).marker());
    }

    @Test
    void onlyTheHyphenatedUuidFormIsAnId() {
        for (String text : new String[] {null, "", "1-1-1-1-1", "{" + ID + "}", " " + ID, ID + " ", ID.replace("-", "")}) {
            assertThatThrownBy(() -> Instance.of("carddemo", text)).as(String.valueOf(text))
                    .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_ROOT_INVALID));
        }
    }

    @Test
    void everySysnameGivesASafeDistinctComponentAndARoundTrippingMarker(@TempDir Path dir) {
        List<String> names = List.of("a/b", "..", "x y", "carddemo", "CARDDEMO", "\u00e9".repeat(128),
                "\ud83d\ude00".repeat(64), "a\\b", ".", "%2e%2e");
        Set<String> components = new HashSet<>();
        for (String name : names) {
            Instance instance = Instance.of(name, ID);
            assertThat(instance.component()).as(name).matches("[0-9a-f]{64}");
            components.add(instance.component());
            Path root = OutputRoot.root(dir, instance);
            assertThat(root).isEqualTo(dir.resolve("out").resolve(instance.component()).resolve(ID));
            String[] lines = new String(instance.marker(), StandardCharsets.US_ASCII).split("\n", -1);
            assertThat(lines).hasSize(4);
            assertThat(lines[3]).isEmpty();
            assertThat(lines[1]).startsWith("database=").matches("database=[A-Za-z0-9_-]+");
            assertThat(new String(Base64.getUrlDecoder().decode(lines[1].substring(9)), StandardCharsets.UTF_8))
                    .isEqualTo(name);
        }
        assertThat(components).hasSize(names.size());
    }

    @Test
    void aNameThatIsNoSysnameIsRefused() {
        for (String name : new String[] {null, "", "x".repeat(129), "a\0b", "\ud800x", "x\udc00"}) {
            assertThatThrownBy(() -> Instance.of(name, ID))
                    .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_ROOT_INVALID));
        }
    }

    @Test
    void eachRemovedSettingIsRefusedByNameWithoutItsValue(@TempDir Path dir) {
        for (String name : OutputRoot.LEGACY_SETTINGS) {
            Map<String, String> settings = Map.of("CARDDEMO_VAR_DIR", dir.toString(), name, "/secret/place");
            assertThatThrownBy(() -> OutputRoot.varDir(settings::get))
                    .isInstanceOfSatisfying(OutputRootException.class, e -> {
                        assertThat(e.code()).isEqualTo(Code.OUTPUT_ROOT_LEGACY_SETTING);
                        assertThat(e.getMessage()).startsWith("OUTPUT_ROOT_LEGACY_SETTING: " + name + " is no longer read")
                                .doesNotContain("/secret/place");
                    });
        }
        assertThat(OutputRoot.LEGACY_SETTINGS).hasSize(7);
        Map<String, String> blank = Map.of("CARDDEMO_VAR_DIR", dir.toString(), "CARDDEMO_TRANREPT_DIR", " ");
        assertThat(OutputRoot.varDir(blank::get)).isNotNull();
    }

    @Test
    void theVarDirMustBeSetAndAbsolute() {
        for (Map<String, String> settings : List.of(Map.<String, String>of(), Map.of("CARDDEMO_VAR_DIR", "  "),
                Map.of("CARDDEMO_VAR_DIR", "var"), Map.of("carddemo.var-dir", "./var"))) {
            assertThatThrownBy(() -> OutputRoot.varDir(settings::get)).as(settings.toString())
                    .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_ROOT_INVALID));
        }
    }

    @Test
    void thePropertyWinsAndLinksInTheBaseAreFollowedOnce(@TempDir Path dir) throws Exception {
        Path real = Files.createDirectory(dir.resolve("real"));
        Path link = Files.createSymbolicLink(dir.resolve("link"), real);
        Map<String, String> settings = new HashMap<>();
        settings.put("CARDDEMO_VAR_DIR", "/elsewhere");
        settings.put("carddemo.var-dir", link.toString());
        assertThat(OutputRoot.varDir(settings::get)).isEqualTo(real.toRealPath());

        settings.put("carddemo.var-dir", link.resolve("missing/var").toString());
        assertThat(OutputRoot.varDir(settings::get)).isEqualTo(real.toRealPath().resolve("missing/var"));
        assertThat(real.resolve("missing")).doesNotExist();

        settings.remove("carddemo.var-dir");
        settings.put("CARDDEMO_VAR_DIR", link + "/sub/../x");
        assertThat(OutputRoot.varDir(settings::get)).isEqualTo(real.toRealPath().resolve("x"));
    }

    @Test
    void aVarDirThatIsAFileIsRefused(@TempDir Path dir) throws Exception {
        Path file = Files.createFile(dir.resolve("file"));
        assertThatThrownBy(() -> OutputRoot.varDir(Map.of("CARDDEMO_VAR_DIR", file.toString())::get))
                .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_ROOT_INVALID));
    }

    @Test
    void theMarkerReadUsesTheCallersConnectionAndLeavesItOpen() throws Exception {
        List<String> calls = new ArrayList<>();
        Instance instance = OutputRoot.read(connection(calls, List.<String[]>of(new String[] {"carddemo", ID.toUpperCase(java.util.Locale.ROOT)})));
        assertThat(instance).isEqualTo(Instance.of("carddemo", ID));
        assertThat(calls).containsExactly("prepare " + OutputRoot.READ_SQL, "close ResultSet", "close PreparedStatement");
    }

    @Test
    void noMarkerRowOrTwoAreInvalid() {
        for (List<String[]> rows : List.of(List.<String[]>of(),
                List.of(new String[] {"carddemo", ID}, new String[] {"carddemo", ID}))) {
            List<String> calls = new ArrayList<>();
            assertThatThrownBy(() -> OutputRoot.read(connection(calls, rows)))
                    .isInstanceOfSatisfying(OutputRootException.class, e -> assertThat(e.code()).isEqualTo(Code.OUTPUT_ROOT_INVALID));
            assertThat(calls).doesNotContain("close Connection").contains("close ResultSet", "close PreparedStatement");
        }
    }

    private static Connection connection(List<String> calls, List<String[]> rows) {
        int[] row = {-1};
        ResultSet resultSet = proxy(ResultSet.class, (method, args) -> switch (method) {
            case "next" -> ++row[0] < rows.size();
            case "getString" -> rows.get(row[0])[(Integer) args[0] - 1];
            case "close" -> {
                calls.add("close ResultSet");
                yield null;
            }
            default -> throw new UnsupportedOperationException(method);
        });
        PreparedStatement statement = proxy(PreparedStatement.class, (method, args) -> switch (method) {
            case "executeQuery" -> resultSet;
            case "close" -> {
                calls.add("close PreparedStatement");
                yield null;
            }
            default -> throw new UnsupportedOperationException(method);
        });
        return proxy(Connection.class, (method, args) -> switch (method) {
            case "prepareStatement" -> {
                calls.add("prepare " + args[0]);
                yield statement;
            }
            case "close" -> {
                calls.add("close Connection");
                yield null;
            }
            default -> throw new UnsupportedOperationException(method);
        });
    }

    interface Handler {
        Object call(String method, Object[] args);
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (p, method, args) -> handler.call(method.getName(), args)));
    }
}
