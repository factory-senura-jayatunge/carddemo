package org.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.carddemo.db.ServiceLogin;
import org.carddemo.platform.outputroot.OutputRoot;
import org.carddemo.platform.testsupport.TestClockConfiguration;
import org.carddemo.platform.testsupport.TestDatabase;
import org.carddemo.platform.testsupport.TestTokensConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The whole service on MockMvc, as the transaction database login, with test tokens and the test clock. */
@SpringBootTest(classes = TransactionApplication.class, properties = "carddemo.ledger.dir=target/no-ledger")
@AutoConfigureMockMvc
@Import({TestClockConfiguration.class, TestTokensConfiguration.class})
abstract class TransactionTestBase {

    static final TestDatabase DB = TestDatabase.shared();
    /** CARDDEMO_VAR_DIR of every test context: a scratch folder, never the developer's var/. */
    static final Path VAR_DIR = scratchVarDir();
    static final String USER = "USER0001";
    static final String ADMIN = "ADMIN001";
    /** Granted to transaction_svc and read by every list and view query, so denying it fails each of them. */
    static final String TRAN_ID_COLUMN = "SELECT ON OBJECT::core.[transaction] (tran_id)";
    static final String AUDIT_INSERT = "INSERT ON OBJECT::ops.audit";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        DB.register(registry, ServiceLogin.TRANSACTION);
        registry.add(OutputRoot.VAR_DIR_PROPERTY, VAR_DIR::toString);
    }

    static Path scratchVarDir() {
        try {
            return Files.createTempDirectory("transaction-var");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    MockMvc mvc;

    final JdbcClient admin = DB.admin();
    final ObjectMapper json = new ObjectMapper();

    static String bearer(String token) {
        return "Bearer " + token;
    }

    /** A checksum of every master, daily and reject row, so a test can show that nothing changed. */
    List<String> coreRows() {
        List<String> rows = new ArrayList<>();
        for (String table : List.of("core.[transaction]", "core.daily_tran", "core.daily_reject", "core.card",
                "core.account")) {
            rows.add(table + " " + admin.sql("SELECT CONCAT(COUNT(*), ' ', CHECKSUM_AGG(BINARY_CHECKSUM(*))) FROM "
                    + table).query(String.class).single());
        }
        return rows;
    }

    int auditRows() {
        return admin.sql("SELECT COUNT(*) FROM ops.audit").query(Integer.class).single();
    }

    /** Runs the call with the permission denied to transaction_svc, then grants it back. */
    void deny(String permission, ThrowingRunnable call) throws Exception {
        admin.sql("DENY " + permission + " TO transaction_svc").update();
        try {
            call.run();
        } finally {
            admin.sql("GRANT " + permission + " TO transaction_svc").update();
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    ResultActions expectProblem(ResultActions result, int status, String code, String message, String field)
            throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.field").value(field))
                .andExpect(jsonPath("$.severity").value("error"));
    }

    static TreeSet<String> members(JsonNode body) {
        TreeSet<String> names = new TreeSet<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** Every text value of a JSON tree, so a test can look for a value whatever member holds it. */
    static List<String> textValues(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isValueNode()) {
            values.add(node.asText());
        }
        node.forEach(child -> values.addAll(textValues(child)));
        return values;
    }
}
