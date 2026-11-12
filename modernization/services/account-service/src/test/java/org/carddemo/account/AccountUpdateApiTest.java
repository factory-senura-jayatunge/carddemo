package org.carddemo.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.Cookie;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.carddemo.account.api.AccountUpdateForm;
import org.carddemo.platform.fidelity.SpecItem;
import org.carddemo.platform.testsupport.CoreFixtures;
import org.carddemo.platform.testsupport.TestClock;
import org.carddemo.platform.testsupport.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Section 6's ACUP scenarios against the API. Start data: the sample account 00000000001 with
 * customer 000000001 and card 9680294154603697, and account 00000000003 whose customer 000000003
 * has ZIP 19852-6716, both after the D-11 migration (group A000000000, {@code addr_zip} empty);
 * signed on as USER0001; today is 2026-11-07. Every test ends by checking that no account's
 * balances, {@code addr_zip}, filler or key and no customer's key or country changed; a test
 * without a save also checks that no account or customer row version moved.
 */
class AccountUpdateApiTest extends AccountTestBase {

    static final String ACCT = "00000000001";
    static final String CUST = "000000001";
    static final String CARD = "9680294154603697";
    static final String ACCT3 = "00000000003";
    static final String CUST3 = "000000003";
    static final String CARD3 = "4000000000000003";
    static final String USER = "USER0001";
    static final String ADMIN = "ADMIN001";
    static final String FILLER = "KEEP-THIS-FILLER";
    // Fixed rather than derived: a row version with no hex letters upper-cases to itself.
    static final String UPPER_CASE_TAG = "00000000ABCDEF01.00000000ABCDEF02";
    static final Instant TODAY = Instant.parse("2026-11-07T12:00:00Z");
    static final String CUSTOMER_UPDATE = "UPDATE ON OBJECT::core.customer (first_name, middle_name, last_name,"
            + " addr_line_1, addr_line_2, addr_line_3, state_cd, zip, phone_1, phone_2, ssn, govt_issued_id, dob,"
            + " eft_account_id, pri_card_holder_ind, fico_score)";

    /** Every clear PII value of the sample rows; none may leave the service. */
    static final List<String> CLEAR_VALUES = List.of("020973888", "020-97-3888", "1961-06-08",
            "00000000000049368437", "0053581756", CARD);

    static final List<String> FORM_MEMBERS = Arrays.stream(AccountUpdateForm.class.getRecordComponents())
            .map(RecordComponent::getName).sorted().toList();

    static final List<String> CUSTOMER_MEMBERS = List.of("custId", "ssn", "dob", "ficoScore", "firstName",
            "middleName", "lastName", "addrLine1", "addrLine2", "city", "stateCd", "zip", "zip5", "zipPlus4",
            "countryCd", "phone1", "phone1Area", "phone1Prefix", "phone1Line", "phone2", "phone2Area",
            "phone2Prefix", "phone2Line", "govtIssuedId", "eftAccountId", "priCardHolderInd");

    private final JdbcClient admin = DB.admin();
    private final ObjectMapper json = new ObjectMapper();
    private List<String> protectedBefore;

    @Autowired
    DataSource serviceDataSource;

    @BeforeEach
    void sampleAccounts() {
        TestClock.shared().set(TODAY);
        DB.fixtures().deleteAll()
                .account(CoreFixtures.Account.of(ACCT, "194.00"))
                .customer(AccountViewApiTest.SAMPLE_CUSTOMER)
                .card(CARD, ACCT, CUST)
                .account(CoreFixtures.Account.of(ACCT3, "50.00"))
                .customer(CoreFixtures.Customer.of(CUST3).withZip("19852-6716"))
                .card(CARD3, ACCT3, CUST3);
        // The D-11 migration moves the group into group_id and empties the ZIP slot.
        admin.sql("UPDATE core.account SET group_id = 'A000000000', addr_zip = '', legacy_filler = :f")
                .param("f", FILLER).update();
        protectedBefore = protectedColumns();
    }

    @AfterEach
    void protectedColumnsUnchanged() {
        TestClock.shared().reset();
        DB.fixtures().closeBatchWindows();
        assertThat(protectedColumns())
                .as("balances, addr_zip, legacy_filler, acct_id, cust_id, country_cd").isEqualTo(protectedBefore);
    }

    private List<String> protectedColumns() {
        List<String> rows = new ArrayList<>(admin.sql("""
                SELECT acct_id, curr_bal, curr_cyc_credit, curr_cyc_debit, addr_zip, legacy_filler
                FROM core.account ORDER BY acct_id""")
                .query((rs, n) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) + "|"
                        + rs.getString(4) + "|" + rs.getString(5) + "|" + rs.getString(6))
                .list());
        rows.addAll(admin.sql("SELECT cust_id, country_cd FROM core.customer ORDER BY cust_id")
                .query((rs, n) -> rs.getString(1) + "|" + rs.getString(2)).list());
        return rows;
    }

    // ---- requests -------------------------------------------------------------------------------

    private ObjectNode changes(String... namesAndValues) {
        ObjectNode changes = json.createObjectNode();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            changes.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return changes;
    }

    private String validateBody(String version, ObjectNode changes) {
        ObjectNode body = json.createObjectNode();
        body.put("version", version);
        body.set("changes", changes);
        return body.toString();
    }

    private String putBody(ObjectNode changes) {
        ObjectNode body = json.createObjectNode();
        body.set("changes", changes);
        return body.toString();
    }

    private ResultActions fetch(String acctId) throws Exception {
        return fetch(acctId, TestTokens.user(USER));
    }

    private ResultActions fetch(String acctId, String token) throws Exception {
        return mvc.perform(get("/api/accounts/{a}/update-form", acctId).header("Authorization", bearer(token)));
    }

    private ResultActions validate(String acctId, String body) throws Exception {
        return validate(acctId, body, TestTokens.user(USER));
    }

    private ResultActions validate(String acctId, String body, String token) throws Exception {
        return mvc.perform(post("/api/accounts/{a}/validate", acctId).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Validate against the stored version of the account. */
    private ResultActions validate(String acctId, ObjectNode changes) throws Exception {
        return validate(acctId, validateBody(version(acctId), changes));
    }

    private ResultActions save(String acctId, String ifMatch, String body) throws Exception {
        return save(acctId, ifMatch, body, TestTokens.user(USER));
    }

    private ResultActions save(String acctId, String ifMatch, String body, String token) throws Exception {
        var request = put("/api/accounts/{a}", acctId).header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mvc.perform(request);
    }

    /** Save against the stored version of the account. */
    private ResultActions save(String acctId, ObjectNode changes) throws Exception {
        return save(acctId, etag(acctId), putBody(changes));
    }

    // ---- reading the results --------------------------------------------------------------------

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** The account's version as the API writes it: account row version, then its customer's. */
    private String version(String acctId) {
        HexFormat hex = HexFormat.of();
        String account = admin.sql("SELECT row_version FROM core.account WHERE acct_id = :a").param("a", acctId)
                .query((rs, n) -> hex.formatHex(rs.getBytes(1))).single();
        List<String> customer = admin.sql("""
                SELECT TOP 1 c.row_version FROM core.card_xref x JOIN core.customer c ON c.cust_id = x.cust_id
                WHERE x.acct_id = :a ORDER BY x.card_num""").param("a", acctId)
                .query((rs, n) -> hex.formatHex(rs.getBytes(1))).list();
        return customer.isEmpty() ? account : account + "." + customer.get(0);
    }

    private String etag(String acctId) {
        return '"' + version(acctId) + '"';
    }

    /** The row version of every account and customer row: a call that saves nothing changes none. */
    private List<String> rowVersions() {
        List<String> versions = new ArrayList<>();
        for (String table : List.of("core.account", "core.customer")) {
            versions.addAll(DB.fixtures().rowVersions(table));
        }
        return versions;
    }

    private Map<String, Object> account(String acctId) {
        return admin.sql("""
                SELECT active_status, CAST(credit_limit AS VARCHAR(20)) AS credit_limit,
                    CAST(cash_credit_limit AS VARCHAR(20)) AS cash_credit_limit,
                    CONVERT(VARCHAR(10), open_date, 23) AS open_date,
                    CONVERT(VARCHAR(10), expiration_date, 23) AS expiration_date,
                    CONVERT(VARCHAR(10), reissue_date, 23) AS reissue_date, group_id,
                    CHARINDEX(CHAR(0), group_id) AS group_nul
                FROM core.account WHERE acct_id = :a""").param("a", acctId).query().singleRow();
    }

    private Map<String, Object> customer(String custId) {
        return admin.sql("""
                SELECT first_name, middle_name, last_name, addr_line_1, addr_line_2, addr_line_3, state_cd, zip,
                    phone_1, phone_2, ssn, govt_issued_id, CONVERT(VARCHAR(10), dob, 23) AS dob, eft_account_id,
                    pri_card_holder_ind, CAST(fico_score AS INT) AS fico_score, DATALENGTH(middle_name) AS middle_length,
                    DATALENGTH(phone_1) AS phone_1_length
                FROM core.customer WHERE cust_id = :c""").param("c", custId).query().singleRow();
    }

    private List<String> auditFieldNames() {
        return admin.sql("SELECT field_names FROM ops.audit ORDER BY audit_id").query(String.class).list();
    }

    private int auditRows() {
        return admin.sql("SELECT COUNT(*) FROM ops.audit").query(Integer.class).single();
    }

    private String cardId(String cardNum) {
        return admin.sql("SELECT CAST(card_id AS VARCHAR(20)) FROM core.card WHERE card_num = :c").param("c", cardNum)
                .query(String.class).single();
    }

    private void deny(String permission, ThrowingRunnable call) throws Exception {
        admin.sql("DENY " + permission + " TO account_svc").update();
        try {
            call.run();
        } finally {
            admin.sql("GRANT " + permission + " TO account_svc").update();
        }
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private ResultActions expectProblem(ResultActions result, int status, String code, String message, String field)
            throws Exception {
        return result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.field").value(field))
                .andExpect(jsonPath("$.severity").value("error"));
    }

    private ResultActions expectRule(ResultActions result, String code, String message, String field,
            String... invalidFields) throws Exception {
        JsonNode body = body(expectProblem(result, 400, code, message, field));
        List<String> marked = new ArrayList<>();
        body.path("invalidFields").forEach(f -> marked.add(f.asText()));
        assertThat(marked).as("invalidFields").containsExactly(invalidFields);
        return result;
    }

    private ResultActions expectValidated(ResultActions result, String... changedFields) throws Exception {
        JsonNode body = body(result.andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Changes validated.Press F5 to save"))
                .andExpect(jsonPath("$.severity").value("info"))
                .andExpect(jsonPath("$.length()").value(3)));
        List<String> changed = new ArrayList<>();
        body.path("changedFields").forEach(f -> changed.add(f.asText()));
        assertThat(changed).as("changedFields").containsExactly(changedFields);
        return result;
    }

    private JsonNode expectSaved(ResultActions result, String acctId) throws Exception {
        String version = version(acctId);
        return body(result.andExpect(status().isOk())
                .andExpect(header().string("ETag", '"' + version + '"'))
                .andExpect(jsonPath("$.message").value("Changes committed to database"))
                .andExpect(jsonPath("$.severity").value("success"))
                .andExpect(jsonPath("$.form.acctId").value(acctId))
                .andExpect(jsonPath("$.form.version").value(version)));
    }

    private ResultActions expectNoChange(ResultActions result) throws Exception {
        return expectProblem(result, 400, "NO_CHANGE", "No change detected with respect to values fetched.",
                "activeStatus").andExpect(jsonPath("$.invalidFields").doesNotExist());
    }

    private static TreeSet<String> members(JsonNode body) {
        TreeSet<String> names = new TreeSet<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** The sample account's form as loaded (S4), with the stored version in body and ETag. */
    private JsonNode expectSampleForm(ResultActions result) throws Exception {
        String version = version(ACCT);
        result.andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("ETag", '"' + version + '"'))
                .andExpect(jsonPath("$.acctId").value(ACCT))
                .andExpect(jsonPath("$.cardId").value(cardId(CARD)))
                .andExpect(jsonPath("$.activeStatus").value("Y"))
                .andExpect(jsonPath("$.openYear").value("2014"))
                .andExpect(jsonPath("$.openMonth").value("11"))
                .andExpect(jsonPath("$.openDay").value("20"))
                .andExpect(jsonPath("$.expiryYear").value("2025"))
                .andExpect(jsonPath("$.expiryMonth").value("05"))
                .andExpect(jsonPath("$.expiryDay").value("20"))
                .andExpect(jsonPath("$.reissueYear").value("2025"))
                .andExpect(jsonPath("$.reissueMonth").value("05"))
                .andExpect(jsonPath("$.reissueDay").value("20"))
                .andExpect(jsonPath("$.creditLimit").value("2020.00"))
                .andExpect(jsonPath("$.creditLimitDisplay").value("+      2,020.00"))
                .andExpect(jsonPath("$.cashCreditLimit").value("1020.00"))
                .andExpect(jsonPath("$.cashCreditLimitDisplay").value("+      1,020.00"))
                .andExpect(jsonPath("$.currentBalance").value("194.00"))
                .andExpect(jsonPath("$.currentBalanceDisplay").value("+        194.00"))
                .andExpect(jsonPath("$.currCycCredit").value("0.00"))
                .andExpect(jsonPath("$.currCycCreditDisplay").value("+           .00"))
                .andExpect(jsonPath("$.currCycDebit").value("0.00"))
                .andExpect(jsonPath("$.currCycDebitDisplay").value("+           .00"))
                .andExpect(jsonPath("$.groupId").value("A000000000"))
                .andExpect(jsonPath("$.custId").value(CUST))
                .andExpect(jsonPath("$.ssn").value("***-**-3888"))
                .andExpect(jsonPath("$.dob").value("****-06-08"))
                .andExpect(jsonPath("$.ficoScore").value("274"))
                .andExpect(jsonPath("$.firstName").value("Immanuel"))
                .andExpect(jsonPath("$.middleName").value("Madeline"))
                .andExpect(jsonPath("$.lastName").value("Kessler"))
                .andExpect(jsonPath("$.addrLine1").value("618 Deshaun Route"))
                .andExpect(jsonPath("$.addrLine2").value("Apt. 802"))
                .andExpect(jsonPath("$.city").value("Altenwerthshire"))
                .andExpect(jsonPath("$.stateCd").value("NC"))
                .andExpect(jsonPath("$.zip").value("12546"))
                .andExpect(jsonPath("$.zip5").value("12546"))
                .andExpect(jsonPath("$.zipPlus4").value(""))
                .andExpect(jsonPath("$.countryCd").value("USA"))
                .andExpect(jsonPath("$.phone1").value("(908)119-8310"))
                .andExpect(jsonPath("$.phone1Area").value("908"))
                .andExpect(jsonPath("$.phone1Prefix").value("119"))
                .andExpect(jsonPath("$.phone1Line").value("8310"))
                .andExpect(jsonPath("$.phone2").value("(373)693-8684"))
                .andExpect(jsonPath("$.phone2Area").value("373"))
                .andExpect(jsonPath("$.phone2Prefix").value("693"))
                .andExpect(jsonPath("$.phone2Line").value("8684"))
                .andExpect(jsonPath("$.govtIssuedId").value("****************8437"))
                .andExpect(jsonPath("$.eftAccountId").value("******1756"))
                .andExpect(jsonPath("$.priCardHolderInd").value("Y"))
                .andExpect(jsonPath("$.version").value(version))
                .andExpect(jsonPath("$.message").isEmpty())
                .andExpect(jsonPath("$.severity").isEmpty());
        JsonNode body = body(result);
        assertThat(members(body)).containsExactlyElementsOf(FORM_MEMBERS);
        assertThat(body.toString()).doesNotContain(CLEAR_VALUES.toArray(String[]::new));
        return body;
    }

    // ---- S1, S29, S30: access and preconditions -------------------------------------------------

    @Test
    @DisplayName("ACUP-S1: every endpoint needs a session")
    @SpecItem("DEF-ACUP-001")
    void s1_noSession() throws Exception {
        List<String> before = rowVersions();
        String version = version(ACCT);
        for (ResultActions call : List.of(
                mvc.perform(get("/api/accounts/{a}/update-form", ACCT)),
                mvc.perform(post("/api/accounts/{a}/validate", ACCT).contentType(MediaType.APPLICATION_JSON)
                        .content(validateBody(version, changes("creditLimit", "2500.00")))),
                mvc.perform(put("/api/accounts/{a}", ACCT).header("If-Match", '"' + version + '"')
                        .contentType(MediaType.APPLICATION_JSON).content(putBody(changes("creditLimit", "2500.00")))))) {
            call.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("ACUP-S29: an ADMIN token is refused on fetch, validate and save")
    void s29_adminRefused() throws Exception {
        List<String> before = rowVersions();
        String token = TestTokens.admin(ADMIN);
        String version = version(ACCT);
        for (ResultActions call : List.of(fetch(ACCT, token),
                validate(ACCT, validateBody(version, changes("creditLimit", "2500.00")), token),
                save(ACCT, '"' + version + '"', putBody(changes("creditLimit", "2500.00")), token))) {
            expectProblem(call, 403, "ACCESS_DENIED", "You are not authorized to use this function ...", null);
        }
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("ACUP-S30: a save needs If-Match, a version of this account, and the CSRF token")
    void s30_preconditions() throws Exception {
        List<String> before = rowVersions();
        String version = version(ACCT);
        String body = putBody(changes("creditLimit", "2500.00"));
        expectProblem(save(ACCT, null, body), 428, "PRECONDITION_REQUIRED", "If-Match header is required", null);
        String account = version.substring(0, 16);
        for (String ifMatch : new String[] {"xyz", version, "W/\"" + version + "\"", "*",
            "\"" + version + "." + account + "\"", "\"" + UPPER_CASE_TAG + "\""}) {
            expectProblem(save(ACCT, ifMatch, body), 400, "INVALID_IF_MATCH",
                    "If-Match header is not a version of this record", null);
        }
        Cookie session = new Cookie("cd_at", TestTokens.user(USER));
        mvc.perform(put("/api/accounts/{a}", ACCT).cookie(session).header("If-Match", '"' + version + '"')
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        mvc.perform(post("/api/accounts/{a}/validate", ACCT).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content(validateBody(version, changes("creditLimit", "2500.00"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
        mvc.perform(put("/api/accounts/{a}", ACCT).cookie(session, new Cookie("XSRF-TOKEN", "t0k3n"))
                        .header("X-XSRF-TOKEN", "t0k3n").header("If-Match", '"' + version + '"')
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertThat(account(ACCT)).containsEntry("credit_limit", "2500.00");
    }

    @Test
    @DisplayName("ACUP-S30: a bearer header next to the cookie session does not lift the CSRF check")
    void s30_cookieAndBearerStillNeedTheCsrfToken() throws Exception {
        List<String> before = rowVersions();
        String version = version(ACCT);
        String body = putBody(changes("creditLimit", "2500.00"));
        Cookie session = new Cookie("cd_at", TestTokens.user(USER));
        String header = bearer(TestTokens.user(USER));
        mvc.perform(put("/api/accounts/{a}", ACCT).cookie(session).header("Authorization", header)
                        .header("If-Match", '"' + version + '"').contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        mvc.perform(post("/api/accounts/{a}/validate", ACCT).cookie(session).header("Authorization", header)
                        .contentType(MediaType.APPLICATION_JSON).content(validateBody(version, changes("creditLimit", "2500.00"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
        mvc.perform(put("/api/accounts/{a}", ACCT).cookie(session, new Cookie("XSRF-TOKEN", "t0k3n"))
                        .header("Authorization", header).header("X-XSRF-TOKEN", "t0k3n")
                        .header("If-Match", '"' + version + '"').contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertThat(account(ACCT)).containsEntry("credit_limit", "2500.00");
    }

    // ---- S3, S21, S5, S20, S42, S48: the reads --------------------------------------------------

    @Test
    @DisplayName("ACUP-S3, ACUP-S21: the account number is checked before any read, on every endpoint")
    @SpecItem({"VAL-ACUP-001", "VAL-ACUP-002", "BR-ACUP-101", "BR-ACUP-105", "DEF-ACUP-101", "FLOW-ACUP-101"})
    void s3_s21_accountNumber() throws Exception {
        List<String> before = rowVersions();
        String etag = etag(ACCT);
        deny(ACCOUNT_READ, () -> {
            for (String blank : new String[] {" ", "           ", "*", "*          "}) {
                expectProblem(fetch(blank), 400, "ACCT_ID_REQUIRED", "Account number not provided", "acctId");
                expectProblem(validate(blank, validateBody("x", changes("creditLimit", "2500.00"))), 400,
                        "ACCT_ID_REQUIRED", "Account number not provided", "acctId");
                expectProblem(save(blank, null, putBody(changes("creditLimit", "2500.00"))), 400,
                        "ACCT_ID_REQUIRED", "Account number not provided", "acctId");
            }
            for (String bad : new String[] {"123", "00000000000", "0000000000A", "000000000012", " 0000000001",
                "-0000000001"}) {
                String text = "Account Number if supplied must be a 11 digit Non-Zero Number";
                expectProblem(fetch(bad), 400, "INVALID_ACCT_ID", text, "acctId");
                expectProblem(validate(bad, validateBody("x", changes("creditLimit", "2500.00"))), 400,
                        "INVALID_ACCT_ID", text, "acctId");
                expectProblem(save(bad, etag, putBody(changes("creditLimit", "2500.00"))), 400, "INVALID_ACCT_ID",
                        text, "acctId");
            }
        });
        assertThat(rowVersions()).isEqualTo(before);
    }

    @Test
    @DisplayName("ACUP-S4: the sample account's form, PII masked, ETag the two row versions, nothing written")
    @SpecItem({"BR-ACUP-001", "BR-ACUP-002", "SE-ACUP-001", "SE-ACUP-002", "SE-ACUP-003", "FLOW-ACUP-010",
        "NUM-ACUP-001", "NUM-ACUP-002", "NUM-ACUP-004", "DEF-ACUP-016"})
    void s4_form() throws Exception {
        List<String> before = rowVersions();
        JsonNode body = expectSampleForm(fetch(ACCT));
        assertThat(body.path("version").asText()).matches("[0-9a-f]{16}\\.[0-9a-f]{16}");
        assertThat(body.has("addrZip")).isFalse();
        assertThat(body.toString()).doesNotContain(FILLER);
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("ACUP-S5: an unknown account gives only the not-found problem, on every endpoint")
    @SpecItem({"ERR-ACUP-002", "FLOW-ACUP-010", "NUM-ACUP-007", "NUM-ACUP-008", "DEF-ACUP-011"})
    void s5_unknownAccount() throws Exception {
        List<String> before = rowVersions();
        String unknown = "00000000077";
        String text = "Account 00000000077 not found.";
        JsonNode body = body(expectProblem(fetch(unknown), 404, "ACCOUNT_NOT_FOUND", text, "acctId"));
        assertThat(members(body)).doesNotContain("current", "creditLimit", "custId");
        assertThat(body.toString()).doesNotContain("ACCTDAT", "core.", "Kessler", "2020.00");
        expectProblem(validate(unknown, validateBody(version(ACCT), changes("creditLimit", "2500.00"))), 404,
                "ACCOUNT_NOT_FOUND", text, "acctId");
        expectProblem(save(unknown, etag(ACCT), putBody(changes("creditLimit", "2500.00"))), 404,
                "ACCOUNT_NOT_FOUND", text, "acctId");
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("ACUP-S20: a refused read of the account, cross-reference or customer is its read error; the"
            + " account is read first")
    @SpecItem({"SE-ACUP-001", "SE-ACUP-002", "SE-ACUP-003", "ERR-ACUP-001", "ERR-ACUP-002", "ERR-ACUP-003",
        "NUM-ACUP-007", "NUM-ACUP-008"})
    void s20_readFailures(CapturedOutput output) throws Exception {
        List<String> before = rowVersions();
        String version = version(ACCT);
        String[][] steps = {
            {ACCOUNT_READ, "ACCOUNT_LOOKUP_FAILED", "Error reading account data"},
            {"SELECT ON OBJECT::core.card_xref", "XREF_LOOKUP_FAILED", "Error reading card cross-reference data"},
            {"SELECT ON OBJECT::core.customer", "CUSTOMER_LOOKUP_FAILED", "Error reading customer data"},
        };
        for (int i = 0; i < steps.length; i++) {
            String[] step = steps[i];
            // Every later read is refused too, so the code shows that the reads stop at the first failure.
            ThrowingRunnable calls = () -> {
                for (ResultActions call : List.of(fetch(ACCT),
                        validate(ACCT, validateBody(version, changes("creditLimit", "2500.00"))),
                        save(ACCT, '"' + version + '"', putBody(changes("creditLimit", "2500.00"))))) {
                    JsonNode body = body(expectProblem(call, 500, step[1], step[2], "acctId"));
                    assertThat(body.toString()).doesNotContain("SELECT", "core.", "permission", "Kessler",
                            "CUSTDAT", "CXACAIX", "ACCTDAT");
                }
            };
            for (int j = steps.length - 1; j > i; j--) {
                ThrowingRunnable inner = calls;
                String permission = steps[j][0];
                calls = () -> deny(permission, inner);
            }
            deny(step[0], calls);
        }
        assertThat(output.getAll()).contains("account update read failed: account " + ACCT,
                "account update read failed: customer " + CUST).doesNotContain("Kessler", "Immanuel");
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("ACUP-S42: the lowest card number's customer; an account with no card; a cross-reference to a"
            + " missing customer")
    @SpecItem({"BR-ACUP-001", "FLOW-ACUP-010", "SE-ACUP-002", "SE-ACUP-003", "ERR-ACUP-001", "ERR-ACUP-003",
        "DEF-ACUP-007", "DEF-ACUP-012"})
    void s42_whichCustomer() throws Exception {
        DB.fixtures().account(CoreFixtures.Account.of("00000000042", "0.00"))
                .customer(CoreFixtures.Customer.of("000000042")).customer(CoreFixtures.Customer.of("000000043"))
                .card("4000000000000422", "00000000042", "000000042")
                .card("4000000000000421", "00000000042", "000000043")
                .account(CoreFixtures.Account.of("00000000044", "0.00"))
                .account(CoreFixtures.Account.of("00000000045", "0.00"));
        admin.sql("""
                INSERT INTO core.card (card_num, acct_id, cvv_cd, embossed_name, expiration_date, active_status)
                VALUES ('4000000000000450', '00000000045', '000', 'NO CUSTOMER', '2030-01-31', 'Y')""").update();
        protectedBefore = protectedColumns();
        List<String> before = rowVersions();

        fetch("00000000042").andExpect(status().isOk())
                .andExpect(jsonPath("$.custId").value("000000043"))
                .andExpect(jsonPath("$.cardId").value(cardId("4000000000000421")))
                .andExpect(header().string("ETag", etag("00000000042")));
        fetch("00000000044").andExpect(status().isOk())
                .andExpect(jsonPath("$.acctId").value("00000000044"))
                .andExpect(jsonPath("$.cardId").isEmpty())
                .andExpect(jsonPath("$.custId").isEmpty())
                .andExpect(jsonPath("$.version").value(version("00000000044")));

        admin.sql("ALTER TABLE core.card_xref NOCHECK CONSTRAINT FK_card_xref_customer").update();
        try {
            admin.sql("""
                    INSERT INTO core.card_xref (card_num, cust_id, acct_id)
                    VALUES ('4000000000000450', '000000099', '00000000045')""").update();
            String text = "Customer 000000099 not found.";
            JsonNode body = body(expectProblem(fetch("00000000045"), 404, "CUSTOMER_NOT_FOUND", text, "acctId"));
            assertThat(members(body)).doesNotContain("current", "creditLimit");
            assertThat(body.toString()).doesNotContain("2020.00", "NO CUSTOMER");
            String accountOnly = '"' + admin.sql("SELECT row_version FROM core.account WHERE acct_id = '00000000045'")
                    .query((rs, n) -> HexFormat.of().formatHex(rs.getBytes(1))).single() + '"';
            expectProblem(validate("00000000045", validateBody(accountOnly.replace("\"", ""),
                    changes("creditLimit", "2500.00"))), 404, "CUSTOMER_NOT_FOUND", text, "acctId");
            expectProblem(save("00000000045", accountOnly, putBody(changes("creditLimit", "2500.00"))), 404,
                    "CUSTOMER_NOT_FOUND", text, "acctId");
        } finally {
            admin.sql("DELETE FROM core.card_xref WHERE acct_id = '00000000045'").update();
            admin.sql("ALTER TABLE core.card_xref WITH CHECK CHECK CONSTRAINT FK_card_xref_customer").update();
        }
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("ACUP-S48: an account with no card: the account fields only, its version alone, saved without a"
            + " customer; a customer field is not editable")
    @SpecItem({"BR-ACUP-001", "ERR-ACUP-001"})
    void s48_noCard() throws Exception {
        String acct = "00000000048";
        DB.fixtures().account(CoreFixtures.Account.of(acct, "0.00"));
        protectedBefore = protectedColumns();
        String version = version(acct);
        assertThat(version).matches("[0-9a-f]{16}");
        JsonNode form = body(fetch(acct).andExpect(status().isOk())
                .andExpect(header().string("ETag", '"' + version + '"'))
                .andExpect(jsonPath("$.version").value(version))
                .andExpect(jsonPath("$.cardId").isEmpty())
                .andExpect(jsonPath("$.creditLimit").value("2020.00")));
        assertThat(members(form)).containsExactlyElementsOf(FORM_MEMBERS);
        for (String member : CUSTOMER_MEMBERS) {
            assertThat(form.path(member).isNull()).as(member).isTrue();
        }
        List<String> customers = customerVersions();

        expectValidated(validate(acct, validateBody(version, changes("creditLimit", "2500.00"))), "creditLimit");
        JsonNode saved = expectSaved(save(acct, '"' + version + '"', putBody(changes("creditLimit", "2500.00"))), acct);
        assertThat(saved.path("form").path("creditLimit").asText()).isEqualTo("2500.00");
        assertThat(saved.path("form").path("custId").isNull()).isTrue();
        assertThat(version(acct)).matches("[0-9a-f]{16}").isNotEqualTo(version);
        assertThat(account(acct)).containsEntry("credit_limit", "2500.00");
        assertThat(auditFieldNames()).containsExactly("creditLimit");
        assertThat(customerVersions()).as("no customer written").isEqualTo(customers);

        List<String> after = rowVersions();
        for (String[] field : new String[][] {{"ficoScore", "FICO Score"}, {"ssn1", "SSN: First 3 chars"},
            {"zip5", "Zip"}}) {
            expectProblem(validate(acct, validateBody(version(acct), changes("creditLimit", "2600.00", field[0], "700"))),
                    400, "FIELD_NOT_EDITABLE", field[1] + " cannot be changed on this screen.", field[0]);
            expectProblem(save(acct, etag(acct), putBody(changes("creditLimit", "2600.00", field[0], "700"))), 400,
                    "FIELD_NOT_EDITABLE", field[1] + " cannot be changed on this screen.", field[0]);
        }
        assertThat(rowVersions()).isEqualTo(after);
        assertThat(auditRows()).isOne();
    }

    private List<String> customerVersions() {
        return DB.fixtures().rowVersions("core.customer");
    }

    // ---- S6, S7, S22, S8, S10, S19, S24: change detection and save -------------------------------

    @Test
    @DisplayName("ACUP-S6: nothing changed, or every field typed again as stored, is NO_CHANGE and runs no rule")
    @SpecItem({"BR-ACUP-102", "BR-ACUP-103", "DEF-ACUP-122"})
    void s6_noChange() throws Exception {
        List<String> before = rowVersions();
        ObjectNode same = changes("activeStatus", "y", "openYear", "2014", "openMonth", "11", "openDay", "20",
                "creditLimit", "2,020.00", "expiryYear", "2025", "expiryMonth", "5", "expiryDay", "20",
                "cashCreditLimit", "1020", "reissueYear", "2025", "reissueMonth", "05", "reissueDay", "20",
                "groupId", "A000000000 ", "ssn1", "020", "ssn2", "97", "ssn3", "3888", "dobYear", "1961",
                "dobMonth", "06", "dobDay", "08", "ficoScore", "274", "firstName", " Immanuel", "middleName",
                "Madeline", "lastName", "Kessler", "addrLine1", "618 Deshaun Route", "stateCd", "nc",
                "addrLine2", "Apt. 802", "zip5", "12546", "zipPlus4", "", "city", "Altenwerthshire",
                "phone1Area", "908", "phone1Prefix", "119", "phone1Line", "8310", "govtIssuedId",
                "00000000000049368437", "phone2Area", "373", "phone2Prefix", "693", "phone2Line", "8684",
                "eftAccountId", "0053581756", "priCardHolderInd", "Y");
        for (ObjectNode edit : List.of(changes(), same)) {
            expectNoChange(validate(ACCT, edit));
            expectNoChange(save(ACCT, edit));
        }
        expectNoChange(validate(ACCT, validateBody(version(ACCT), null)));
        expectNoChange(save(ACCT, etag(ACCT), "{}"));
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("ACUP-S7, ACUP-S22: only the changed fields are checked; the stored phone 2 area 373 is not")
    @SpecItem({"BR-ACUP-003", "BR-ACUP-107", "BR-ACUP-110", "DEF-ACUP-112", "FLOW-ACUP-009"})
    void s7_s22_validated() throws Exception {
        List<String> before = rowVersions();
        expectValidated(validate(ACCT, changes("creditLimit", "2500.00")), "creditLimit");
        expectValidated(validate(ACCT, changes("ficoScore", "700", "creditLimit", "2500.00")), "creditLimit",
                "ficoScore");
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("ACUP-S8, ACUP-S10: FICO, phone 2 and ZIP validated, then saved: only the customer row is"
            + " written, one audit row")
    @SpecItem({"BR-ACUP-003", "BR-ACUP-006", "SE-ACUP-006", "SE-ACUP-007", "SE-ACUP-008", "FLOW-ACUP-009",
        "NUM-ACUP-003", "NUM-ACUP-010", "DEF-ACUP-001", "DEF-ACUP-002", "DEF-ACUP-020"})
    void s8_s10_save() throws Exception {
        ObjectNode edit = changes("ficoScore", "700", "phone2Area", "908", "phone2Prefix", "693", "phone2Line",
                "8684", "zip5", "27601");
        String before = version(ACCT);
        Map<String, Object> accountBefore = account(ACCT);
        expectValidated(validate(ACCT, validateBody(before, edit)), "ficoScore", "zip5", "phone2Area");
        assertThat(version(ACCT)).isEqualTo(before);

        ResultActions saved = save(ACCT, '"' + before + '"', putBody(edit));
        JsonNode form = expectSaved(saved, ACCT).path("form");
        assertThat(form.path("ficoScore").asText()).isEqualTo("700");
        assertThat(form.path("phone2").asText()).isEqualTo("(908)693-8684");
        assertThat(form.path("zip").asText()).isEqualTo("27601");
        assertThat(form.path("groupId").asText()).isEqualTo("A000000000");
        assertThat(form.path("ssn").asText()).isEqualTo("***-**-3888");
        assertThat(members(form)).containsExactlyElementsOf(FORM_MEMBERS);
        String after = version(ACCT);
        assertThat(after).isNotEqualTo(before);
        assertThat(after.substring(0, 16)).as("account row not updated").isEqualTo(before.substring(0, 16));
        assertThat(account(ACCT)).isEqualTo(accountBefore).containsEntry("group_id", "A000000000");
        assertThat(customer(CUST)).containsEntry("fico_score", 700).containsEntry("phone_2", "(908)693-8684")
                .containsEntry("zip", "27601").containsEntry("phone_1", "(908)119-8310")
                .containsEntry("ssn", "020973888").containsEntry("last_name", "Kessler");

        String correlationId = saved.andReturn().getResponse().getHeader("X-Correlation-Id");
        List<Map<String, Object>> rows = admin.sql("""
                SELECT service, actor_user_id, action, subject_type, subject_key, field_names, correlation_id,
                    CASE WHEN ABS(DATEDIFF(SECOND, occurred_at, SYSUTCDATETIME())) < 120 THEN 'now' END AS occurred
                FROM ops.audit""").query().listOfRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("service", "account-service").containsEntry("actor_user_id", USER)
                .containsEntry("action", "UPDATE").containsEntry("subject_type", "ACCOUNT")
                .containsEntry("subject_key", ACCT).containsEntry("field_names", "ficoScore,phone2,zip")
                .containsEntry("occurred", "now").containsEntry("correlation_id", correlationId);
    }

    @Test
    @DisplayName("ACUP-S10: every field changed at once is saved in its stored layout; groups audited by name")
    @SpecItem({"BR-ACUP-006", "SE-ACUP-006", "SE-ACUP-007", "NUM-ACUP-002", "NUM-ACUP-003", "NUM-ACUP-004"})
    void s10_saveEveryField() throws Exception {
        ObjectNode edit = changes("activeStatus", "n", "openYear", "2015", "openMonth", "1", "openDay", "2",
                "creditLimit", "3,000.50", "expiryYear", "2030", "expiryMonth", "12", "expiryDay", "31",
                "cashCreditLimit", "1500", "reissueYear", "2029", "reissueMonth", "06", "reissueDay", "15",
                "groupId", " GOLD ", "ssn1", "123", "ssn2", "45", "ssn3", "6789", "dobYear", "1970",
                "dobMonth", "2", "dobDay", "3", "ficoScore", "650", "firstName", "Ann", "middleName", "",
                "lastName", "O'Neil", "addrLine1", "1 Main St", "stateCd", "pa", "addrLine2", "Unit 2",
                "zip5", "19103", "zipPlus4", "1234", "city", "Philadelphia", "phone1Area", "215",
                "phone1Prefix", "555", "phone1Line", "0100", "govtIssuedId", "G123",
                "phone2Area", "", "phone2Prefix", "", "phone2Line", "", "eftAccountId", "1234567890",
                "priCardHolderInd", "n");
        JsonNode form = expectSaved(save(ACCT, edit), ACCT).path("form");
        assertThat(form.path("ssn").asText()).isEqualTo("***-**-6789");
        assertThat(form.path("zip5").asText()).isEqualTo("19103");
        assertThat(form.path("zipPlus4").asText()).isEqualTo("1234");
        assertThat(account(ACCT)).containsEntry("active_status", "N").containsEntry("credit_limit", "3000.50")
                .containsEntry("cash_credit_limit", "1500.00").containsEntry("open_date", "2015-01-02")
                .containsEntry("expiration_date", "2030-12-31").containsEntry("reissue_date", "2029-06-15")
                .containsEntry("group_id", "GOLD      ");
        assertThat(customer(CUST)).containsEntry("first_name", "Ann").containsEntry("middle_name", "")
                .containsEntry("last_name", "O'Neil").containsEntry("addr_line_1", "1 Main St")
                .containsEntry("addr_line_2", "Unit 2").containsEntry("addr_line_3", "Philadelphia")
                .containsEntry("state_cd", "PA").containsEntry("zip", "19103-1234")
                .containsEntry("phone_1", "(215)555-0100").containsEntry("phone_2", "")
                .containsEntry("ssn", "123456789").containsEntry("govt_issued_id", "G123")
                .containsEntry("dob", "1970-02-03").containsEntry("eft_account_id", "1234567890")
                .containsEntry("pri_card_holder_ind", "N").containsEntry("fico_score", 650);
        assertThat(auditFieldNames()).containsExactly("activeStatus,addrLine1,addrLine2,cashCreditLimit,city,"
                + "creditLimit,dob,eftAccountId,expirationDate,ficoScore,firstName,govtIssuedId,groupId,lastName,"
                + "middleName,openDate,phone1,phone2,priCardHolderInd,reissueDate,ssn,stateCd,zip");
        expectNoChange(validate(ACCT, edit));
    }

    @Test
    @DisplayName("ACUP-S19: a stored ZIP+4 is no change and survives a save of another field")
    @SpecItem({"NUM-ACUP-005", "DEF-ACUP-003", "BR-ACUP-102"})
    void s19_zipPlus4Kept() throws Exception {
        fetch(ACCT3).andExpect(status().isOk())
                .andExpect(jsonPath("$.zip").value("19852-6716"))
                .andExpect(jsonPath("$.zip5").value("19852"))
                .andExpect(jsonPath("$.zipPlus4").value("6716"));
        List<String> before = rowVersions();
        expectNoChange(validate(ACCT3, changes()));
        expectNoChange(validate(ACCT3, changes("zip5", "19852", "zipPlus4", "6716")));
        assertThat(rowVersions()).isEqualTo(before);
        expectSaved(save(ACCT3, changes("ficoScore", "750")), ACCT3);
        assertThat(customer(CUST3)).containsEntry("zip", "19852-6716").containsEntry("fico_score", 750);
        assertThat(auditFieldNames()).containsExactly("ficoScore");
    }

    @Test
    @DisplayName("ACUP-S24: status y is no change; n is validated and saved as N")
    @SpecItem({"VAL-ACUP-004", "BR-ACUP-102", "DEF-ACUP-109", "DEF-ACUP-110"})
    void s24_status() throws Exception {
        expectNoChange(validate(ACCT, changes("activeStatus", "y")));
        expectValidated(validate(ACCT, changes("activeStatus", "n")), "activeStatus");
        expectSaved(save(ACCT, changes("activeStatus", "n")), ACCT).path("form");
        assertThat(account(ACCT)).containsEntry("active_status", "N");
        expectNoChange(validate(ACCT, changes("activeStatus", "n")));
        expectRule(validate(ACCT, changes("activeStatus", "X")), "YES_NO", "Account Status must be Y or N.",
                "activeStatus", "activeStatus");
        expectRule(validate(ACCT, changes("activeStatus", "0")), "REQUIRED", "Account Status must be supplied.",
                "activeStatus", "activeStatus");
        assertThat(auditFieldNames()).containsExactly("activeStatus");
    }

    // ---- S13, S14, S15, S41, S43, S50: versions and locks ---------------------------------------

    @Test
    @DisplayName("ACUP-S13: a payment changed the balance since the fetch: RECORD_CHANGED with the current form;"
            + " validated and saved again with its version")
    @SpecItem({"BR-ACUP-005", "FLOW-ACUP-009"})
    void s13_balanceChanged() throws Exception {
        String old = etag(ACCT);
        validate(ACCT, validateBody(old.replace("\"", ""), changes("creditLimit", "2500.00"))).andExpect(status().isOk());
        admin.sql("UPDATE core.account SET curr_bal = curr_bal + 25.50 WHERE acct_id = :a").param("a", ACCT).update();
        protectedBefore = protectedColumns();
        String current = version(ACCT);
        JsonNode body = body(expectProblem(save(ACCT, old, putBody(changes("creditLimit", "2500.00"))), 409,
                "RECORD_CHANGED", "Record changed by some one else. Please review", "activeStatus")
                .andExpect(header().string("ETag", '"' + current + '"')));
        assertThat(body.path("current").path("currentBalance").asText()).isEqualTo("219.50");
        assertThat(body.path("current").path("creditLimit").asText()).isEqualTo("2020.00");
        assertThat(body.path("current").path("version").asText()).isEqualTo(current);
        assertThat(body.path("current").path("ssn").asText()).isEqualTo("***-**-3888");
        assertThat(members(body.path("current"))).containsExactlyElementsOf(FORM_MEMBERS);
        assertThat(version(ACCT)).isEqualTo(current);
        assertThat(auditRows()).isZero();

        expectValidated(validate(ACCT, validateBody(current, changes("creditLimit", "2500.00"))), "creditLimit");
        expectSaved(save(ACCT, '"' + current + '"', putBody(changes("creditLimit", "2500.00"))), ACCT);
        assertThat(account(ACCT)).containsEntry("credit_limit", "2500.00");
    }

    @Test
    @DisplayName("ACUP-S50: validate with a stale, missing or malformed version; nothing written")
    @SpecItem("BR-ACUP-005")
    void s50_validateVersion() throws Exception {
        String stale = version(ACCT);
        admin.sql("UPDATE core.customer SET fico_score = 275 WHERE cust_id = :c").param("c", CUST).update();
        String current = version(ACCT);
        List<String> before = rowVersions();
        JsonNode body = body(expectProblem(validate(ACCT, validateBody(stale, changes("creditLimit", "2500.00"))),
                409, "RECORD_CHANGED", "Record changed by some one else. Please review", "activeStatus")
                .andExpect(header().string("ETag", '"' + current + '"')));
        assertThat(body.path("current").path("ficoScore").asText()).isEqualTo("275");
        assertThat(body.path("current").path("version").asText()).isEqualTo(current);
        String account = current.substring(0, 16);
        ObjectNode edit = changes("creditLimit", "2500.00");
        for (String version : new String[] {null, "", "xyz", '"' + current + '"', UPPER_CASE_TAG,
            current + "." + account, "W/" + current}) {
            expectProblem(validate(ACCT, validateBody(version, edit)), 400, "INVALID_IF_MATCH",
                    "If-Match header is not a version of this record", null);
        }
        expectProblem(validate(ACCT, putBody(edit)), 400, "INVALID_IF_MATCH",
                "If-Match header is not a version of this record", null);
        // An account-only version for an account with a customer is well formed, but not this account's.
        expectProblem(validate(ACCT, validateBody(account, edit)), 409, "RECORD_CHANGED",
                "Record changed by some one else. Please review", "activeStatus");
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("ACUP-S43: the lowest card's cross-reference moved to another customer after the fetch:"
            + " RECORD_CHANGED showing that customer; keys in the body are ignored")
    @SpecItem({"BR-ACUP-005", "SE-ACUP-005", "DEF-ACUP-013"})
    void s43_linkMoved() throws Exception {
        String old = version(ACCT);
        admin.sql("UPDATE core.card_xref SET cust_id = :c WHERE card_num = :card").param("c", CUST3)
                .param("card", CARD).update();
        String current = version(ACCT);
        assertThat(current).isNotEqualTo(old);
        List<String> before = rowVersions();
        for (ResultActions call : List.of(validate(ACCT, validateBody(old, changes("creditLimit", "2500.00"))),
                save(ACCT, '"' + old + '"', putBody(changes("creditLimit", "2500.00"))))) {
            JsonNode body = body(expectProblem(call, 409, "RECORD_CHANGED",
                    "Record changed by some one else. Please review", "activeStatus")
                    .andExpect(header().string("ETag", '"' + current + '"')));
            assertThat(body.path("current").path("custId").asText()).isEqualTo(CUST3);
            assertThat(body.path("current").path("firstName").asText()).isEqualTo("TEST");
            assertThat(body.path("current").path("version").asText()).isEqualTo(current);
        }
        assertThat(rowVersions()).isEqualTo(before);

        ObjectNode request = json.createObjectNode();
        request.set("changes", changes("ficoScore", "720"));
        request.put("acctId", ACCT3).put("custId", CUST).put("cardId", "1").put("version", old);
        expectSaved(save(ACCT, '"' + current + '"', request.toString()), ACCT);
        assertThat(customer(CUST3)).containsEntry("fico_score", 720);
        assertThat(customer(CUST)).containsEntry("fico_score", 274);
        assertThat(admin.sql("SELECT subject_key FROM ops.audit").query(String.class).single()).isEqualTo(ACCT);
    }

    @Test
    @DisplayName("ACUP-S14: another connection holds the account row lock: ACCOUNT_LOCK_FAILED after 5 seconds,"
            + " then a save works")
    @SpecItem({"SE-ACUP-004", "ERR-ACUP-004"})
    @Timeout(60)
    void s14_accountLocked() throws Exception {
        String etag = etag(ACCT);
        List<String> before = rowVersions();
        try (Connection holder = DB.adminDataSource().getConnection()) {
            holder.setAutoCommit(false);
            hold(holder, "SELECT acct_id FROM core.account WITH (UPDLOCK, ROWLOCK) WHERE acct_id = '" + ACCT + "'");
            long start = System.nanoTime();
            expectProblem(save(ACCT, etag, putBody(changes("creditLimit", "2500.00"))), 409, "ACCOUNT_LOCK_FAILED",
                    "Could not lock account record for update", "acctId");
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertThat(millis).as("waited for the lock").isBetween(4_500L, 9_000L);
            holder.commit();
        }
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
        expectSaved(save(ACCT, etag, putBody(changes("creditLimit", "2500.00"))), ACCT);
        everyPooledConnectionWaitsForLocks();
    }

    @Test
    @DisplayName("ACUP-S15: another connection holds the customer row lock: CUSTOMER_LOCK_FAILED, the account"
            + " change is not saved either")
    @SpecItem({"SE-ACUP-005", "ERR-ACUP-004", "DEF-ACUP-004"})
    @Timeout(60)
    void s15_customerLocked() throws Exception {
        String etag = etag(ACCT);
        List<String> before = rowVersions();
        ObjectNode edit = changes("creditLimit", "2500.00", "ficoScore", "700");
        try (Connection holder = DB.adminDataSource().getConnection()) {
            holder.setAutoCommit(false);
            hold(holder, "SELECT cust_id FROM core.customer WITH (UPDLOCK, ROWLOCK) WHERE cust_id = '" + CUST + "'");
            expectProblem(save(ACCT, etag, putBody(edit)), 409, "CUSTOMER_LOCK_FAILED",
                    "Could not lock customer record for update", "acctId");
            holder.commit();
        }
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(account(ACCT)).containsEntry("credit_limit", "2020.00");
        assertThat(auditRows()).isZero();
        expectSaved(save(ACCT, etag, putBody(edit)), ACCT);
        everyPooledConnectionWaitsForLocks();
    }

    @Test
    @DisplayName("ACUP-S41: two saves with the same ETag at once: one 200, one RECORD_CHANGED, one audit row")
    @SpecItem({"SE-ACUP-004", "SE-ACUP-005", "BR-ACUP-005"})
    @Timeout(60)
    void s41_concurrentSaves() throws Exception {
        String etag = etag(ACCT);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = DB.adminDataSource().getConnection()) {
            holder.setAutoCommit(false);
            hold(holder, "SELECT acct_id FROM core.account WITH (UPDLOCK, ROWLOCK) WHERE acct_id = '" + ACCT + "'");
            Future<MvcResult> first = pool.submit(() ->
                    save(ACCT, etag, putBody(changes("creditLimit", "2500.00", "ficoScore", "700"))).andReturn());
            Future<MvcResult> second = pool.submit(() ->
                    save(ACCT, etag, putBody(changes("creditLimit", "2600.00", "ficoScore", "710"))).andReturn());
            awaitLockWaiters(2);
            holder.commit();
            List<MvcResult> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().map(r -> r.getResponse().getStatus()).sorted().toList()).containsExactly(200, 409);
            MvcResult conflict = results.stream().filter(r -> r.getResponse().getStatus() == 409).findFirst().orElseThrow();
            assertThat(json.readTree(conflict.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("code").asText())
                    .isEqualTo("RECORD_CHANGED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(auditRows()).isOne();
        Map<String, Object> account = account(ACCT);
        Object fico = customer(CUST).get("fico_score");
        assertThat(account.get("credit_limit") + "/" + fico).isIn("2500.00/700", "2600.00/710");
    }

    private static void hold(Connection holder, String lockingRead) throws Exception {
        try (Statement s = holder.createStatement(); ResultSet rs = s.executeQuery(lockingRead)) {
            assertThat(rs.next()).isTrue();
        }
    }

    /** Both saves are queued on the row lock before it is released, so they really race for it. */
    private void awaitLockWaiters(int count) throws InterruptedException {
        // Well inside the 5-second lock timeout, which the second save's wait also counts against.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        int waiting = 0;
        while (System.nanoTime() < deadline) {
            waiting = admin.sql("""
                    SELECT COUNT(*) FROM sys.dm_exec_requests
                    WHERE database_id = DB_ID() AND wait_type LIKE 'LCK[_]M[_]U%'""").query(Integer.class).single();
            if (waiting >= count) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(waiting).as("saves waiting for the row lock").isGreaterThanOrEqualTo(count);
    }

    /** SET LOCK_TIMEOUT belongs to the connection: no pooled connection keeps the save's limit. */
    private void everyPooledConnectionWaitsForLocks() throws Exception {
        int size = serviceDataSource instanceof HikariDataSource hikari ? hikari.getMaximumPoolSize() : 1;
        List<Connection> borrowed = new ArrayList<>();
        try {
            for (int i = 0; i < size; i++) {
                Connection c = serviceDataSource.getConnection();
                borrowed.add(c);
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT @@LOCK_TIMEOUT")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).as("@@LOCK_TIMEOUT of pooled connection %d", i).isEqualTo(-1);
                }
            }
        } finally {
            for (Connection c : borrowed) {
                c.close();
            }
        }
    }

    // ---- S16, S40, S32: failed saves ------------------------------------------------------------

    @Test
    @DisplayName("ACUP-S16: the customer update is refused after the account update: ACCOUNT_UPDATE_FAILED,"
            + " neither row changed")
    @SpecItem({"SE-ACUP-006", "SE-ACUP-007", "SE-ACUP-008", "ERR-ACUP-004", "DEF-ACUP-005", "ERR-JCSD-001"})
    void s16_customerUpdateFails() throws Exception {
        String etag = etag(ACCT);
        List<String> before = rowVersions();
        ObjectNode edit = changes("creditLimit", "2500.00", "ficoScore", "700", "phone2Area", "908", "phone2Prefix",
                "693", "phone2Line", "8684", "zip5", "27601");
        deny(CUSTOMER_UPDATE, () -> {
            JsonNode body = body(expectProblem(save(ACCT, etag, putBody(edit)), 500, "ACCOUNT_UPDATE_FAILED",
                    "Update of record failed", "acctId"));
            assertThat(body.toString()).doesNotContain("permission", "denied", "core.", "SET ", "fico");
        });
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(account(ACCT)).containsEntry("credit_limit", "2020.00");
        assertThat(customer(CUST)).containsEntry("fico_score", 274).containsEntry("zip", "12546");
        assertThat(auditRows()).isZero();
        expectSaved(save(ACCT, etag, putBody(edit)), ACCT);
    }

    @Test
    @DisplayName("ACUP-S40: the audit insert is refused after both updates: ACCOUNT_UPDATE_FAILED, both rows"
            + " unchanged")
    @SpecItem({"SE-ACUP-008", "ERR-ACUP-004", "DEF-ACUP-005"})
    void s40_auditFails() throws Exception {
        String etag = etag(ACCT);
        List<String> before = rowVersions();
        ObjectNode edit = changes("creditLimit", "2500.00", "ficoScore", "700");
        deny("INSERT ON OBJECT::ops.audit", () -> expectProblem(save(ACCT, etag, putBody(edit)), 500,
                "ACCOUNT_UPDATE_FAILED", "Update of record failed", "acctId"));
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(account(ACCT)).containsEntry("credit_limit", "2020.00");
        assertThat(customer(CUST)).containsEntry("fico_score", 274);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("ACUP-S32: an open batch window lets fetch and validate through and stops the save")
    void s32_batchWindow() throws Exception {
        DB.fixtures().openBatchWindow();
        List<String> before = rowVersions();
        String version = version(ACCT);
        expectSampleForm(fetch(ACCT));
        expectValidated(validate(ACCT, validateBody(version, changes("creditLimit", "2500.00"))), "creditLimit");
        String text = "Account update is not available during the batch cycle. Try again later...";
        assertThat(text).hasSize(74);
        expectProblem(save(ACCT, '"' + version + '"', putBody(changes("creditLimit", "2500.00"))), 503,
                "BATCH_WINDOW", text, "activeStatus")
                .andExpect(header().string("Retry-After", "300"));
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
    }

    @Test
    @DisplayName("ACUP DEF-ACUP-013: a save re-checks the changed fields without a validate first; nothing is"
            + " written")
    @SpecItem("DEF-ACUP-013")
    void saveWithoutValidateChecksTheRules() throws Exception {
        List<String> before = rowVersions();
        expectRule(save(ACCT, changes("ficoScore", "900")), "RANGE", "FICO Score: should be between 300 and 850",
                "ficoScore", "ficoScore");
        expectRule(save(ACCT, changes("creditLimit", "2500.00", "zip5", "12547")), "STATE_ZIP_MISMATCH",
                "Invalid zip code for state", "stateCd", "stateCd", "zip5");
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
    }

    // ---- S31: names the screen does not edit ----------------------------------------------------

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("ACUP-S31: a read-only name gets its label; any other name the generic text, and no part of it"
            + " is returned or logged")
    @SpecItem({"BR-ACUP-008", "VAL-ACUP-027", "DEF-ACUP-013", "DEF-ACUP-114"})
    void s31_notEditable(CapturedOutput output) throws Exception {
        List<String> before = rowVersions();
        String[][] readOnly = {{"currentBalance", "Current Balance"}, {"currCycDebit", "Current Cycle Debit"},
            {"currCycCredit", "Current Cycle Credit"}, {"acctId", "Account Number"}, {"custId", "Customer id"},
            {"countryCd", "Country"}};
        for (String[] field : readOnly) {
            ObjectNode edit = changes("creditLimit", "2500.00", field[0], "0.00");
            String text = field[1] + " cannot be changed on this screen.";
            expectProblem(validate(ACCT, edit), 400, "FIELD_NOT_EDITABLE", text, field[0]);
            expectProblem(save(ACCT, edit), 400, "FIELD_NOT_EDITABLE", text, field[0]);
        }
        String longName = "Zq9w".repeat(2_500);
        String markup = "<img src=x onerror=alert(1)>123-45-6789";
        for (String name : List.of("cvv", longName, markup)) {
            ObjectNode edit = changes("creditLimit", "2500.00", name, "999");
            for (ResultActions call : List.of(validate(ACCT, edit), save(ACCT, edit))) {
                String body = body(expectProblem(call, 400, "FIELD_NOT_EDITABLE",
                        "This field cannot be changed on this screen.", "changes")).toString();
                assertThat(body).doesNotContain("Zq9w", "onerror", "<img", "123-45-6789")
                        .doesNotContainIgnoringCase("cvv");
            }
        }
        assertThat(output.getAll()).doesNotContain("Zq9w", "onerror", "<img", "123-45-6789")
                .doesNotContainIgnoringCase("cvv")
                .contains("account update refused a change: code FIELD_NOT_EDITABLE, correlation ID");
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
    }

    // ---- S23, S25 to S28, S33 to S38, S46, S47, S49: rules --------------------------------------

    @Test
    @DisplayName("ACUP-S23: a changed ZIP that does not match the state marks state and ZIP")
    @SpecItem({"VAL-ACUP-034", "BR-ACUP-104", "FLOW-ACUP-102", "DEF-ACUP-117"})
    void s23_stateZip() throws Exception {
        List<String> before = rowVersions();
        expectRule(validate(ACCT, changes("zip5", "12547")), "STATE_ZIP_MISMATCH", "Invalid zip code for state",
                "stateCd", "stateCd", "zip5");
        expectRule(validate(ACCT, changes("ficoScore", "900", "zip5", "12547", "phone1Area", "800",
                "phone1Prefix", "119", "phone1Line", "8310")), "RANGE", "FICO Score: should be between 300 and 850",
                "ficoScore", "ficoScore", "stateCd", "zip5", "phone1Area", "phone1Prefix");
        assertThat(rowVersions()).isEqualTo(before);
    }

    @Test
    @DisplayName("ACUP-S25, ACUP-S26, ACUP-S49: month 13, 29 February of a common year, a short phone part and a"
            + " short year")
    @SpecItem({"VAL-ACUP-006", "VAL-ACUP-007", "VAL-ACUP-008", "DEF-ACUP-107"})
    void s25_s26_s49_parts() throws Exception {
        List<String> before = rowVersions();
        expectRule(validate(ACCT, changes("openYear", "2014", "openMonth", "13", "openDay", "20")),
                "DATE_MONTH_INVALID", "Open Date: Month must be a number between 1 and 12.", "openMonth", "openMonth");
        expectRule(validate(ACCT, changes("expiryYear", "2023", "expiryMonth", "02", "expiryDay", "29")),
                "DATE_LEAP_YEAR", "Expiry Date:Not a leap year.Cannot have 29 days in this month.", "expiryYear",
                "expiryYear", "expiryMonth", "expiryDay");
        // The stored prefix 119 fails the prefix rule (DEF-ACUP-118) once phone 1 changes.
        expectRule(validate(ACCT, changes("phone1Area", "90", "phone1Prefix", "119", "phone1Line", "8310")),
                "PHONE_AREA_NOT_3_DIGITS", "Phone Number 1: Area code must be A 3 digit number.", "phone1Area",
                "phone1Area", "phone1Prefix");
        expectRule(validate(ACCT, changes("openYear", "214", "openMonth", "11", "openDay", "20")),
                "DATE_YEAR_NOT_4_DIGITS", "Open Date must be 4 digit number.", "openYear", "openYear");
        assertThat(rowVersions()).isEqualTo(before);
    }

    @Test
    @DisplayName("ACUP-S27: a date of birth of today is accepted, tomorrow is in the future, by the service clock")
    @SpecItem({"VAL-ACUP-010", "NUM-ACUP-105", "DEF-ACUP-120"})
    void s27_dateOfBirth() throws Exception {
        List<String> before = rowVersions();
        expectValidated(validate(ACCT, changes("dobYear", "2026", "dobMonth", "11", "dobDay", "07")), "dobYear");
        expectRule(validate(ACCT, changes("dobYear", "2026", "dobMonth", "11", "dobDay", "08")), "DATE_FUTURE",
                "Date of Birth:cannot be in the future ", "dobYear", "dobYear", "dobMonth", "dobDay");
        TestClock.shared().set(Instant.parse("2026-11-08T00:00:00Z"));
        expectValidated(validate(ACCT, changes("dobYear", "2026", "dobMonth", "11", "dobDay", "08")), "dobYear");
        assertThat(rowVersions()).isEqualTo(before);
    }

    @Test
    @DisplayName("ACUP-S28, ACUP-S38: SSN 987 is refused; a partial SSN marks the missing parts; the stored SSN"
            + " typed again is no change")
    @SpecItem({"VAL-ACUP-013", "VAL-ACUP-014", "VAL-ACUP-015", "VAL-ACUP-016", "DEF-ACUP-103", "NUM-ACUP-004",
        "FLOW-ACUP-101"})
    void s28_s38_ssn() throws Exception {
        List<String> before = rowVersions();
        expectRule(validate(ACCT, changes("ssn1", "987", "ssn2", "65", "ssn3", "4321")), "NOT_ALLOWED",
                "SSN: First 3 chars: should not be 000, 666, or between 900 and 999", "ssn1", "ssn1");
        expectRule(validate(ACCT, changes("ssn1", "123")), "REQUIRED", "SSN 4th & 5th chars must be supplied.",
                "ssn2", "ssn2", "ssn3");
        expectRule(validate(ACCT, changes("ssn1", "000", "ssn2", "0", "ssn3", "0000")), "NON_ZERO",
                "SSN: First 3 chars must not be zero.", "ssn1", "ssn1", "ssn2", "ssn3");
        expectNoChange(validate(ACCT, changes("ssn1", "020", "ssn2", "97", "ssn3", "3888")));
        assertThat(rowVersions()).isEqualTo(before);
    }

    @Test
    @DisplayName("ACUP-S33: hyphen, apostrophe and full stop are letters of a name; a diaeresis is not; 26 letters"
            + " are too long; a case change is a change")
    @SpecItem({"VAL-ACUP-019", "VAL-ACUP-021", "VAL-ACUP-026", "DEF-ACUP-110", "DEF-ACUP-111"})
    void s33_names() throws Exception {
        List<String> before = rowVersions();
        expectValidated(validate(ACCT, changes("lastName", "O'Brien")), "lastName");
        expectValidated(validate(ACCT, changes("lastName", "Smith-Jones")), "lastName");
        expectValidated(validate(ACCT, changes("city", "St. Louis")), "city");
        expectValidated(validate(ACCT, changes("lastName", "kessler")), "lastName");
        expectRule(validate(ACCT, changes("firstName", "Zoë")), "ALPHABETIC", "First Name can have alphabets only.",
                "firstName", "firstName");
        expectRule(validate(ACCT, changes("lastName", "A".repeat(26))), "TOO_LONG",
                "Last Name must not be longer than 25 characters.", "lastName", "lastName");
        assertThat(rowVersions()).isEqualTo(before);
    }

    @Test
    @DisplayName("ACUP-S34: negative, overlong and malformed limits are refused, never cut; cash above credit is"
            + " refused whichever changed")
    @SpecItem({"VAL-ACUP-011", "VAL-ACUP-012", "NUM-ACUP-101", "DEF-ACUP-113"})
    void s34_amounts() throws Exception {
        List<String> before = rowVersions();
        expectRule(validate(ACCT, changes("creditLimit", "-1")), "NEGATIVE_AMOUNT", "Credit Limit must not be negative.",
                "creditLimit", "creditLimit");
        for (String bad : new String[] {"12345678901", "1.234", "1,00,0"}) {
            expectRule(validate(ACCT, changes("creditLimit", bad)), "AMOUNT_INVALID", "Credit Limit is not valid",
                    "creditLimit", "creditLimit");
        }
        expectRule(validate(ACCT, changes("cashCreditLimit", "3000.00")), "CASH_ABOVE_CREDIT",
                "Cash Credit Limit must not be more than the Credit Limit.", "creditLimit", "creditLimit",
                "cashCreditLimit");
        expectRule(validate(ACCT, changes("creditLimit", "1000.00")), "CASH_ABOVE_CREDIT",
                "Cash Credit Limit must not be more than the Credit Limit.", "creditLimit", "creditLimit",
                "cashCreditLimit");
        expectValidated(validate(ACCT, changes("creditLimit", "1020.00")), "creditLimit");
        assertThat(rowVersions()).isEqualTo(before);
    }

    @Test
    @DisplayName("ACUP-S35: a prefix starting with 0 and a non-general-purpose area are refused; a cleared phone"
            + " is saved as an empty string")
    @SpecItem({"VAL-ACUP-028", "VAL-ACUP-029", "VAL-ACUP-030", "NUM-ACUP-003", "DEF-ACUP-118"})
    void s35_phones() throws Exception {
        expectRule(validate(ACCT, changes("phone1Area", "908", "phone1Prefix", "011", "phone1Line", "8310")),
                "PHONE_PREFIX_INVALID", "Phone Number 1: Prefix code must not start with 0 or 1", "phone1Prefix",
                "phone1Prefix");
        expectRule(validate(ACCT, changes("phone1Area", "800", "phone1Prefix", "119", "phone1Line", "8310")),
                "PHONE_AREA_NOT_GENERAL_PURPOSE", "Phone Number 1: Not valid North America general purpose area code",
                "phone1Area", "phone1Area", "phone1Prefix");
        expectRule(validate(ACCT, changes("phone1Area", "800", "phone1Prefix", "555", "phone1Line", "8310")),
                "PHONE_AREA_NOT_GENERAL_PURPOSE", "Phone Number 1: Not valid North America general purpose area code",
                "phone1Area", "phone1Area");
        expectValidated(validate(ACCT, changes("phone1Area", "908", "phone1Prefix", "555", "phone1Line", "8310")),
                "phone1Area");
        assertThat(auditRows()).isZero();
        JsonNode form = expectSaved(save(ACCT, changes("phone1Area", "", "phone1Prefix", " ", "phone1Line", "*")),
                ACCT).path("form");
        assertThat(form.path("phone1").asText()).isEmpty();
        assertThat(form.path("phone1Area").asText()).isEmpty();
        assertThat(customer(CUST)).containsEntry("phone_1", "").containsEntry("phone_1_length", 0);
        expectNoChange(validate(ACCT, changes("phone1Area", "", "phone1Prefix", "", "phone1Line", "")));
        assertThat(auditFieldNames()).containsExactly("phone1");
    }

    @Test
    @DisplayName("ACUP-S36: a lower-case state is accepted and saved upper case")
    @SpecItem({"VAL-ACUP-023", "VAL-ACUP-024", "DEF-ACUP-109"})
    void s36_lowerCaseState() throws Exception {
        expectValidated(validate(ACCT, changes("stateCd", "nc", "zip5", "27601")), "zip5");
        expectSaved(save(ACCT, changes("stateCd", "nc", "zip5", "27601")), ACCT);
        assertThat(customer(CUST)).containsEntry("state_cd", "NC").containsEntry("zip", "27601");
        admin.sql("UPDATE core.customer SET state_cd = 'SC' WHERE cust_id = :c").param("c", CUST).update();
        expectSaved(save(ACCT, changes("stateCd", "nc")), ACCT);
        assertThat(customer(CUST)).containsEntry("state_cd", "NC");
        expectRule(validate(ACCT, changes("stateCd", "XX")), "NOT_IN_LIST", "State: is not a valid state code",
                "stateCd", "stateCd");
        assertThat(auditFieldNames()).containsExactly("zip", "stateCd");
    }

    @Test
    @DisplayName("ACUP-S37: a short ZIP+4 is refused; ZIP and ZIP+4 are saved as nnnnn-nnnn")
    @SpecItem({"VAL-ACUP-025", "NUM-ACUP-005", "DEF-ACUP-117"})
    void s37_zipPlus4() throws Exception {
        expectRule(validate(ACCT, changes("zip5", "27601", "zipPlus4", "12")), "NUMERIC", "Zip+4 must be all numeric.",
                "zipPlus4", "zipPlus4");
        JsonNode form = expectSaved(save(ACCT, changes("zip5", "27601", "zipPlus4", "1234")), ACCT).path("form");
        assertThat(form.path("zip").asText()).isEqualTo("27601-1234");
        assertThat(form.path("zip5").asText()).isEqualTo("27601");
        assertThat(form.path("zipPlus4").asText()).isEqualTo("1234");
        assertThat(customer(CUST)).containsEntry("zip", "27601-1234");
        assertThat(auditFieldNames()).containsExactly("zip");
    }

    @Test
    @DisplayName("ACUP-S39: a cleared middle name and group are saved empty, never binary zeros; typed again"
            + " empty they are no change")
    @SpecItem({"BR-ACUP-006", "BR-ACUP-105", "BR-ACUP-108", "DEF-ACUP-122"})
    void s39_cleared() throws Exception {
        expectSaved(save(ACCT, changes("middleName", "", "groupId", "*")), ACCT);
        assertThat(customer(CUST)).containsEntry("middle_name", "").containsEntry("middle_length", 0);
        assertThat(account(ACCT)).containsEntry("group_id", "          ").containsEntry("group_nul", 0);
        fetch(ACCT).andExpect(jsonPath("$.middleName").value("")).andExpect(jsonPath("$.groupId").value(""));
        List<String> before = rowVersions();
        expectNoChange(validate(ACCT, changes("middleName", "", "groupId", "")));
        expectNoChange(validate(ACCT, changes("middleName", "*  ", "groupId", "   ")));
        expectRule(validate(ACCT, changes("groupId", "ABCDEFGHIJK")), "TOO_LONG",
                "Account Group must not be longer than 10 characters.", "groupId", "groupId");
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditFieldNames()).containsExactly("groupId,middleName");
    }

    @Test
    @DisplayName("ACUP-S39: spaces before the * still make a field empty: saved as '', and no change for an empty"
            + " Zip+4")
    @SpecItem({"BR-ACUP-105", "DEF-ACUP-122"})
    void s39_spacedAsterisk() throws Exception {
        expectSaved(save(ACCT, changes("addrLine2", " *", "phone2Area", " *", "phone2Prefix", "  * ",
                "phone2Line", " *")), ACCT);
        assertThat(customer(CUST)).containsEntry("addr_line_2", "").containsEntry("phone_2", "");
        List<String> before = rowVersions();
        expectNoChange(validate(ACCT, changes("zip5", "12546", "zipPlus4", " *")));
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditFieldNames()).containsExactly("addrLine2,phone2");
    }

    @Test
    @DisplayName("ACUP-S44: one-digit month and day parts are accepted and saved padded")
    @SpecItem({"NUM-ACUP-002", "VAL-ACUP-006", "VAL-ACUP-007", "DEF-ACUP-107"})
    void s44_shortParts() throws Exception {
        expectSaved(save(ACCT, changes("expiryYear", "2026", "expiryMonth", "5", "expiryDay", "7")), ACCT);
        assertThat(account(ACCT)).containsEntry("expiration_date", "2026-05-07");
        expectSaved(save(ACCT, changes("reissueYear", "2026", "reissueMonth", "5", "reissueDay", "7")), ACCT);
        assertThat(account(ACCT)).containsEntry("reissue_date", "2026-05-07");
        // An open date of 2026-05-07 needs an expiry after it, so the stored expiry is moved first.
        admin.sql("UPDATE core.account SET expiration_date = '2027-01-01' WHERE acct_id = :a").param("a", ACCT).update();
        expectSaved(save(ACCT, changes("openYear", "2026", "openMonth", "5", "openDay", "7")), ACCT);
        assertThat(account(ACCT)).containsEntry("open_date", "2026-05-07");
        expectNoChange(validate(ACCT, changes("openYear", "2026", "openMonth", "05", "openDay", "07")));
        assertThat(auditFieldNames()).containsExactly("expirationDate", "reissueDate", "openDate");
    }

    @Test
    @DisplayName("ACUP-S46, ACUP-S47: open not after today, expiry after open, reissue between them, unchanged"
            + " dates taken as stored; no upper year limit; a year before 1900 runs no cross-date rule")
    @SpecItem({"VAL-ACUP-005", "DEF-ACUP-120", "Q-ACUP-104", "Q-ACUP-108"})
    void s46_s47_dateOrder() throws Exception {
        List<String> before = rowVersions();
        expectRule(validate(ACCT, changes("openYear", "2026", "openMonth", "11", "openDay", "08")), "DATE_FUTURE",
                "Open Date:cannot be in the future", "openYear", "openYear", "openMonth", "openDay");
        expectValidated(validate(ACCT, changes("openYear", "2026", "openMonth", "11", "openDay", "07",
                "expiryYear", "2027", "expiryMonth", "1", "expiryDay", "1", "reissueYear", "2026",
                "reissueMonth", "11", "reissueDay", "07")), "openYear", "expiryYear", "reissueYear");
        // The stored reissue date 2025-05-20 is then after the expiry too.
        expectRule(validate(ACCT, changes("expiryYear", "2014", "expiryMonth", "11", "expiryDay", "20")),
                "EXPIRY_NOT_AFTER_OPEN", "Expiry Date:must be after Open Date", "expiryYear", "expiryYear",
                "expiryMonth", "expiryDay", "reissueYear", "reissueMonth", "reissueDay");
        expectRule(validate(ACCT, changes("reissueYear", "2013", "reissueMonth", "01", "reissueDay", "01")),
                "REISSUE_NOT_BETWEEN", "Reissue Date:must be between Open Date and Expiry Date", "reissueYear",
                "reissueYear", "reissueMonth", "reissueDay");
        expectValidated(validate(ACCT, changes("expiryYear", "2100", "expiryMonth", "01", "expiryDay", "01")),
                "expiryYear");
        expectRule(validate(ACCT, changes("openYear", "1899", "openMonth", "11", "openDay", "20")), "DATE_CENTURY",
                "Open Date : Century is not valid.", "openYear", "openYear");
        expectRule(validate(ACCT, changes("reissueYear", "2025", "reissueMonth", "05", "reissueDay", "21")),
                "REISSUE_NOT_BETWEEN", "Reissue Date:must be between Open Date and Expiry Date", "reissueYear",
                "reissueYear", "reissueMonth", "reissueDay");
        expectRule(validate(ACCT, changes("openYear", "2025", "openMonth", "05", "openDay", "20")),
                "EXPIRY_NOT_AFTER_OPEN", "Expiry Date:must be after Open Date", "expiryYear", "expiryYear",
                "expiryMonth", "expiryDay");
        assertThat(rowVersions()).isEqualTo(before);

        // A stored NULL expiry is left out of the order checks.
        admin.sql("UPDATE core.account SET expiration_date = NULL WHERE acct_id = :a").param("a", ACCT).update();
        fetch(ACCT).andExpect(jsonPath("$.expiryYear").value("")).andExpect(jsonPath("$.expiryMonth").value(""))
                .andExpect(jsonPath("$.expiryDay").value(""));
        expectValidated(validate(ACCT, changes("openYear", "2025", "openMonth", "05", "openDay", "20")), "openYear");
        expectNoChange(validate(ACCT, changes("expiryYear", "", "expiryMonth", "", "expiryDay", "")));
    }

    // ---- S45: PII -------------------------------------------------------------------------------

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("ACUP-S45: no clear SSN, date of birth, government ID, EFT account or card number in any response"
            + " or log, no customer name in logs, audit rows hold field names only")
    @SpecItem({"DEF-ACUP-016"})
    void s45_noPiiLeaks(CapturedOutput output) throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        bodies.add(body(fetch(ACCT).andExpect(status().isOk())));
        bodies.add(body(validate(ACCT, changes("ssn1", "987", "ssn2", "65", "ssn3", "4321"))
                .andExpect(status().isBadRequest())));
        bodies.add(body(validate(ACCT, changes("ssn1", "123", "ssn2", "45", "ssn3", "6789", "dobYear", "1970",
                "dobMonth", "02", "dobDay", "03")).andExpect(status().isOk())));
        deny("SELECT ON OBJECT::core.customer", () -> bodies.add(body(fetch(ACCT)
                .andExpect(status().isInternalServerError()))));
        deny(CUSTOMER_UPDATE, () -> bodies.add(body(save(ACCT, changes("ssn1", "123", "ssn2", "45", "ssn3", "6789"))
                .andExpect(status().isInternalServerError()))));
        String stale = etag(ACCT);
        bodies.add(body(save(ACCT, stale, putBody(changes("ssn1", "123", "ssn2", "45", "ssn3", "6789",
                "govtIssuedId", "G99887766", "eftAccountId", "9988776655", "dobYear", "1970", "dobMonth", "02",
                "dobDay", "03"))).andExpect(status().isOk())));
        bodies.add(body(save(ACCT, stale, putBody(changes("ficoScore", "700"))).andExpect(status().isConflict())));
        bodies.add(body(fetch(ACCT).andExpect(status().isOk())));

        List<String> clear = new ArrayList<>(CLEAR_VALUES);
        clear.addAll(List.of("123456789", "123-45-6789", "1970-02-03", "G99887766", "9988776655"));
        for (JsonNode body : bodies) {
            assertThat(body.toString()).doesNotContain(clear.toArray(String[]::new));
        }
        assertThat(output.getAll()).doesNotContain(clear.toArray(String[]::new))
                .doesNotContain("Immanuel", "Madeline", "Kessler", "Deshaun", "Altenwerthshire")
                .contains("CUSTOMER_LOOKUP_FAILED", "ACCOUNT_UPDATE_FAILED");
        List<Map<String, Object>> audit = admin.sql("""
                SELECT field_names, CASE WHEN occurred_at IS NOT NULL THEN 'set' END AS occurred FROM ops.audit""")
                .query().listOfRows();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).containsEntry("field_names", "dob,eftAccountId,govtIssuedId,ssn")
                .containsEntry("occurred", "set");
        assertThat(customer(CUST)).containsEntry("ssn", "123456789").containsEntry("dob", "1970-02-03");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("ACUP-S45: a driver message that quotes a value of the save reaches neither a response nor the log")
    @SpecItem({"DEF-ACUP-016"})
    void s45_driverMessagesStayOutOfTheLog(CapturedOutput output) throws Exception {
        // Valid values with synthetic markers. The columns hold them, so a trigger copies each into a
        // three-character column: the save then fails with error 2628, whose driver message quotes the
        // start of the value.
        Map<String, String> values = Map.of(
                "govtIssuedId", "Zq7Ñ" + "É".repeat(16),
                "addrLine1", "Qx5Ö" + "É".repeat(46),
                "addrLine2", "Wv3Ü" + "É".repeat(46));
        Map<String, String> columns = Map.of("govtIssuedId", "govt_issued_id", "addrLine1", "addr_line_1",
                "addrLine2", "addr_line_2");
        admin.sql("DROP TRIGGER IF EXISTS core.TR_customer_s45_narrow").update();
        admin.sql("""
                CREATE TRIGGER core.TR_customer_s45_narrow ON core.customer AFTER UPDATE AS
                BEGIN
                    SET NOCOUNT ON;
                    DECLARE @narrow TABLE (v NVARCHAR(3) NOT NULL);
                    INSERT INTO @narrow (v)
                    SELECT govt_issued_id FROM inserted WHERE govt_issued_id LIKE N'Zq7%'
                    UNION ALL SELECT addr_line_1 FROM inserted WHERE addr_line_1 LIKE N'Qx5%'
                    UNION ALL SELECT addr_line_2 FROM inserted WHERE addr_line_2 LIKE N'Wv3%';
                END""").update();
        try {
            for (Map.Entry<String, String> value : values.entrySet()) {
                String field = value.getKey();
                String marker = value.getValue().substring(0, 3);
                Throwable driver = org.assertj.core.api.Assertions.catchThrowable(() -> admin.sql(
                        "UPDATE core.customer SET " + columns.get(field) + " = :v WHERE cust_id = :c")
                        .param("v", value.getValue()).param("c", CUST).update());
                assertThat(driver).as("the driver quotes the value it truncates: " + field).rootCause()
                        .hasMessageContaining(marker)
                        .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getErrorCode()).isEqualTo(2628));

                String response = expectProblem(save(ACCT, changes(field, value.getValue())), 500,
                        "ACCOUNT_UPDATE_FAILED", "Update of record failed", "acctId")
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                assertThat(response).as(field).doesNotContain("Zq7", "Qx5", "Wv3", "Truncated");
            }
        } finally {
            admin.sql("DROP TRIGGER IF EXISTS core.TR_customer_s45_narrow").update();
        }
        assertThat(output.getAll()).contains("SQL error 2628")
                .doesNotContain("Zq7", "Qx5", "Wv3", "Truncated value", "String or binary data");
        assertThat(customer(CUST)).containsEntry("govt_issued_id", "00000000000049368437")
                .containsEntry("addr_line_1", "618 Deshaun Route").containsEntry("addr_line_2", "Apt. 802");
    }

    // ---- S51: the characters of the free-text fields --------------------------------------------

    private static final String UNSUPPORTED = " contains a character that is not supported.";

    private String unmasked(String acctId, String field) throws Exception {
        return body(mvc.perform(post("/api/accounts/{a}/unmask", acctId)
                        .header("Authorization", bearer(TestTokens.admin(ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"" + field + "\"]}"))
                .andExpect(status().isOk())).path("fields").path(field).asText();
    }

    private List<String> updateAuditFieldNames() {
        return admin.sql("SELECT field_names FROM ops.audit WHERE action = 'UPDATE' ORDER BY audit_id")
                .query(String.class).list();
    }

    @Test
    @DisplayName("ACUP-S51: a government ID of 20 x É, or holding U+00A0, tab, U+0085, NUL and ÿ, is saved and"
            + " unmasked unchanged; 21 x É is too long")
    @SpecItem("BR-ACUP-108")
    void s51_governmentIdHoldsLatin1() throws Exception {
        String inner = "ID\u00A0\t\u0085\u0000\u00FF" + "9".repeat(13);
        assertThat(inner).hasSize(20);
        for (String value : List.of("É".repeat(20), inner)) {
            JsonNode form = expectSaved(save(ACCT, changes("govtIssuedId", value)), ACCT).path("form");
            assertThat(form.path("govtIssuedId").asText()).isEqualTo("*".repeat(16) + value.substring(16));
            assertThat(customer(CUST)).containsEntry("govt_issued_id", value);
            assertThat(unmasked(ACCT, "govtIssuedId")).isEqualTo(value);
            expectNoChange(validate(ACCT, changes("govtIssuedId", value)));
        }
        List<String> before = rowVersions();
        for (ResultActions result : List.of(validate(ACCT, changes("govtIssuedId", "É".repeat(21))),
                save(ACCT, changes("govtIssuedId", "É".repeat(21))))) {
            expectRule(result, "TOO_LONG", "Government Issued Id must not be longer than 20 characters.",
                    "govtIssuedId", "govtIssuedId");
        }
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(updateAuditFieldNames()).containsExactly("govtIssuedId", "govtIssuedId");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("ACUP-S51: a character above U+00FF in a free-text field is UNSUPPORTED_CHARACTER; nothing is"
            + " written, and neither the body nor the log holds the value")
    @SpecItem({"BR-ACUP-108", "VAL-ACUP-022"})
    void s51_unsupportedCharacter(CapturedOutput output) throws Exception {
        String[][] cases = {
            {"govtIssuedId", "Government Issued Id", "Qz1\u4E2D"},
            {"govtIssuedId", "Government Issued Id", "Qz2\u20AC"},
            {"govtIssuedId", "Government Issued Id", "Qz3\uD83D\uDE00"},
            {"addrLine1", "Address Line 1", "Qz4 \u4E2D St"},
            {"addrLine2", "Address Line 2", "Qz5 \u4E2D"},
            {"groupId", "Account Group", "Qz6\u4E2D"},
            {"govtIssuedId", "Government Issued Id", "Qz7\u4E2D" + "1".repeat(30)},
        };
        assertThat(("Government Issued Id" + UNSUPPORTED)).hasSize(64);
        List<String> before = rowVersions();
        List<String> bodies = new ArrayList<>();
        for (String[] c : cases) {
            for (ResultActions result : List.of(validate(ACCT, changes(c[0], c[2])), save(ACCT, changes(c[0], c[2])))) {
                bodies.add(body(expectRule(result, "UNSUPPORTED_CHARACTER", c[1] + UNSUPPORTED, c[0], c[0]))
                        .toString());
            }
        }
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditRows()).isZero();
        String[] values = {"Qz", "\u4E2D", "\u20AC", "\uD83D\uDE00"};
        assertThat(String.join("\n", bodies)).doesNotContain(values);
        assertThat(output.getAll()).doesNotContain(values);
    }

    @Test
    @DisplayName("ACUP-S51: address lines of 50 x ÿ are saved and shown unchanged; 51 are too long")
    @SpecItem({"BR-ACUP-108", "VAL-ACUP-022"})
    void s51_addressLinesHoldLatin1() throws Exception {
        String line = "ÿ".repeat(50);
        JsonNode form = expectSaved(save(ACCT, changes("addrLine1", line, "addrLine2", line)), ACCT).path("form");
        assertThat(form.path("addrLine1").asText()).isEqualTo(line);
        assertThat(form.path("addrLine2").asText()).isEqualTo(line);
        assertThat(customer(CUST)).containsEntry("addr_line_1", line).containsEntry("addr_line_2", line);
        fetch(ACCT).andExpect(jsonPath("$.addrLine1").value(line)).andExpect(jsonPath("$.addrLine2").value(line));
        List<String> before = rowVersions();
        expectNoChange(validate(ACCT, changes("addrLine1", line, "addrLine2", line)));
        expectRule(validate(ACCT, changes("addrLine1", "ÿ".repeat(51))), "TOO_LONG",
                "Address Line 1 must not be longer than 50 characters.", "addrLine1", "addrLine1");
        expectRule(save(ACCT, changes("addrLine2", "ÿ".repeat(51))), "TOO_LONG",
                "Address Line 2 must not be longer than 50 characters.", "addrLine2", "addrLine2");
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(auditFieldNames()).containsExactly("addrLine1,addrLine2");
    }

    private int matchingDisclosureGroups(String acctId) {
        return admin.sql("""
                SELECT COUNT(*) FROM core.account a JOIN ref.disclosure_group g ON g.group_id = a.group_id
                WHERE a.acct_id = :a""").param("a", acctId).query(Integer.class).single();
    }

    @Test
    @DisplayName("ACUP-S51: a group of 10 x É, or ÀÉ and U+0085, is saved and shown unchanged with no '?', matches"
            + " no disclosure group (so interest falls back to DEFAULT), and only its padding is removed on read")
    @SpecItem("BR-ACUP-108")
    void s51_groupIdHoldsLatin1() throws Exception {
        String ten = "É".repeat(10);
        assertThat(expectSaved(save(ACCT, changes("groupId", ten)), ACCT).path("form").path("groupId").asText())
                .isEqualTo(ten);
        assertThat(account(ACCT)).containsEntry("group_id", ten);

        String latin1 = "ÀÉ\u0085";
        assertThat(expectSaved(save(ACCT, changes("groupId", latin1)), ACCT).path("form").path("groupId").asText())
                .isEqualTo(latin1);
        assertThat(account(ACCT)).containsEntry("group_id", latin1 + " ".repeat(7)).containsEntry("group_nul", 0);
        assertThat((String) account(ACCT).get("group_id")).doesNotContain("?");
        fetch(ACCT).andExpect(jsonPath("$.groupId").value(latin1));
        mvc.perform(get("/api/accounts/{a}", ACCT).header("Authorization", bearer(TestTokens.user(USER))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.groupId").value(latin1));
        List<String> before = rowVersions();
        expectNoChange(validate(ACCT, changes("groupId", latin1)));
        expectNoChange(validate(ACCT, changes("groupId", latin1 + "   ")));
        assertThat(rowVersions()).isEqualTo(before);

        // The interest job is not built yet; this pins the join it is specified to use (group_id equal to
        // the disclosure group's, else DEFAULT), across the NCHAR and CHAR columns.
        admin.sql("INSERT INTO ref.disclosure_group (group_id, type_cd, cat_cd, int_rate)"
                + " VALUES ('S51GOLD', '01', '0001', 1.00)").update();
        try {
            assertThat(matchingDisclosureGroups(ACCT)).as("no group matches, so DEFAULT applies").isZero();
            expectSaved(save(ACCT, changes("groupId", "S51GOLD")), ACCT);
            assertThat(matchingDisclosureGroups(ACCT)).as("the padded NCHAR group equals the CHAR group").isOne();
        } finally {
            admin.sql("DELETE FROM ref.disclosure_group WHERE group_id = 'S51GOLD'").update();
        }

        // A tab or U+00A0 at the end of a loaded group is part of it; only the blanks of the padding go.
        admin.sql("UPDATE core.account SET group_id = :g WHERE acct_id = :a").param("g", "AB\t")
                .param("a", ACCT).update();
        fetch(ACCT).andExpect(jsonPath("$.groupId").value("AB\t"));
        admin.sql("UPDATE core.account SET group_id = :g WHERE acct_id = :a").param("g", "AB\u00A0")
                .param("a", ACCT).update();
        fetch(ACCT).andExpect(jsonPath("$.groupId").value("AB\u00A0"));
        assertThat(auditFieldNames()).containsExactly("groupId", "groupId", "groupId");
    }

    @Test
    @DisplayName("ACUP-S51: spaces and tabs at both ends are removed as today; a tab, NUL or U+0085 inside, and"
            + " U+0085 or U+00A0 at the end, are kept")
    @SpecItem({"BR-ACUP-102", "BR-ACUP-105", "BR-ACUP-108"})
    void s51_trimKeepsInnerAndLatin1Ends() throws Exception {
        ObjectNode edit = changes("govtIssuedId", " \tG1\u0000\t2\u0085 \t", "addrLine1", "\t 1 Main\tSt \t",
                "addrLine2", "Apt 2\u0085", "groupId", " GR\u00A0 ");
        JsonNode form = expectSaved(save(ACCT, edit), ACCT).path("form");
        assertThat(customer(CUST)).containsEntry("govt_issued_id", "G1\u0000\t2\u0085")
                .containsEntry("addr_line_1", "1 Main\tSt").containsEntry("addr_line_2", "Apt 2\u0085");
        assertThat(account(ACCT)).containsEntry("group_id", "GR\u00A0" + " ".repeat(7));
        assertThat(form.path("addrLine1").asText()).isEqualTo("1 Main\tSt");
        assertThat(form.path("addrLine2").asText()).isEqualTo("Apt 2\u0085");
        assertThat(form.path("groupId").asText()).isEqualTo("GR\u00A0");
        assertThat(unmasked(ACCT, "govtIssuedId")).isEqualTo("G1\u0000\t2\u0085");
        List<String> before = rowVersions();
        expectNoChange(validate(ACCT, edit));
        assertThat(rowVersions()).isEqualTo(before);
        assertThat(updateAuditFieldNames()).containsExactly("addrLine1,addrLine2,govtIssuedId,groupId");
    }

    @Test
    @DisplayName("ACUP: the stored dates of the form and of change detection are the DATE columns")
    @SpecItem("NUM-ACUP-002")
    void datesComeFromTheDateColumns() throws Exception {
        admin.sql("UPDATE core.account SET reissue_date = NULL WHERE acct_id = :a").param("a", ACCT).update();
        fetch(ACCT).andExpect(jsonPath("$.reissueYear").value("")).andExpect(jsonPath("$.openYear").value("2014"));
        expectSaved(save(ACCT, changes("reissueYear", "2020", "reissueMonth", "2", "reissueDay", "29")), ACCT);
        assertThat(account(ACCT)).containsEntry("reissue_date", LocalDate.of(2020, 2, 29).toString());
    }
}
