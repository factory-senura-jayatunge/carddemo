package org.carddemo.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MessageCatalogTest {

    @Test
    void textsRenderTheirPlaceholdersInOrder() {
        assertThat(MessageCatalog.PAYMENT_SUCCESSFUL.params()).containsExactly("tranId");
        assertThat(MessageCatalog.PAYMENT_SUCCESSFUL.render("0000000000000301"))
                .isEqualTo("Payment successful.  Your Transaction ID is 0000000000000301.");
        assertThat(MessageCatalog.OPTION_COMING_SOON.render("Credit Card Update"))
                .isEqualTo("This option Credit Card Update is coming soon ...");
        assertThat(MessageCatalog.ACCT_ID_REQUIRED.render()).isEqualTo("Acct ID can NOT be empty...");
        assertThatThrownBy(() -> MessageCatalog.PAYMENT_SUCCESSFUL.render())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MessageCatalog.OPTION_COMING_SOON.render("$1 \\ odd")).isEqualTo("This option $1 \\ odd is coming soon ...");
    }

    @Test
    void variantsShareTheirApiCode() {
        assertThat(MessageCatalog.NEW_PASSWORD_LENGTH_LEGACY.code()).isEqualTo("NEW_PASSWORD_LENGTH");
        assertThat(MessageCatalog.NEW_PASSWORD_LENGTH_FIXED.code()).isEqualTo("NEW_PASSWORD_LENGTH");
        assertThat(MessageCatalog.INVALID_INPUT_USER_ID.code()).isEqualTo("INVALID_INPUT");
        assertThat(MessageCatalog.get("BATCH_WINDOW")).isSameAs(MessageCatalog.BATCH_WINDOW);
    }

    @Test
    void everyConstantIsInTheIndex() throws IllegalAccessException {
        int constants = 0;
        for (var field : MessageCatalog.class.getFields()) {
            if (field.getType() == Message.class) {
                constants++;
                Message m = (Message) field.get(null);
                assertThat(m.key()).isEqualTo(field.getName());
                assertThat(MessageCatalog.all()).containsEntry(m.key(), m);
            }
        }
        assertThat(MessageCatalog.all()).hasSize(constants);
    }

    /** Account-service variants keep Bill Pay's API codes and leave Bill Pay's own texts byte for byte. */
    @Test
    void accountVariantsReuseTheApiCodesAndKeepBillPayTexts() {
        assertThat(MessageCatalog.ACVW_ACCT_ID_REQUIRED.code()).isEqualTo(MessageCatalog.ACCT_ID_REQUIRED.code());
        assertThat(MessageCatalog.ACUP_ACCT_ID_REQUIRED.code()).isEqualTo("ACCT_ID_REQUIRED");
        assertThat(MessageCatalog.CARD_KEY_ACCT_ID_REQUIRED.code()).isEqualTo("ACCT_ID_REQUIRED");
        assertThat(MessageCatalog.ACCOUNT_ID_NOT_FOUND.code()).isEqualTo(MessageCatalog.ACCOUNT_NOT_FOUND.code());
        assertThat(MessageCatalog.ACCOUNT_DATA_READ_FAILED.code()).isEqualTo(MessageCatalog.ACCOUNT_LOOKUP_FAILED.code());
        assertThat(MessageCatalog.XREF_DATA_READ_FAILED.code()).isEqualTo(MessageCatalog.XREF_LOOKUP_FAILED.code());
        assertThat(MessageCatalog.ACCOUNT_SAVE_FAILED.code()).isEqualTo(MessageCatalog.ACCOUNT_UPDATE_FAILED.code());
        assertThat(MessageCatalog.ACCOUNT_UPDATE_BATCH_WINDOW.code()).isEqualTo(MessageCatalog.BATCH_WINDOW.code());
        assertThat(MessageCatalog.CARD_UPDATE_BATCH_WINDOW.code()).isEqualTo("BATCH_WINDOW");
        assertThat(MessageCatalog.CARD_LIST_INVALID_CURSOR.code()).isEqualTo(MessageCatalog.INVALID_CURSOR.code());

        assertThat(MessageCatalog.ACCT_ID_REQUIRED.text()).isEqualTo("Acct ID can NOT be empty...");
        assertThat(MessageCatalog.ACCOUNT_NOT_FOUND.text()).isEqualTo("Account ID NOT found...");
        assertThat(MessageCatalog.ACCOUNT_LOOKUP_FAILED.text()).isEqualTo("Unable to lookup Account...");
        assertThat(MessageCatalog.XREF_LOOKUP_FAILED.text()).isEqualTo("Unable to lookup XREF AIX file...");
        assertThat(MessageCatalog.ACCOUNT_UPDATE_FAILED.text()).isEqualTo("Unable to Update Account...");
        assertThat(MessageCatalog.TRAN_ID_EXHAUSTED.text()).isEqualTo(MessageCatalog.TRAN_ADD_FAILED.text());
        assertThat(MessageCatalog.BATCH_WINDOW.text())
                .isEqualTo("Bill payment is not available while the batch cycle runs. Try again later...");
        assertThat(MessageCatalog.INVALID_CURSOR.text()).isEqualTo("The page position is not valid");
    }

    @Test
    void accountTextsAreTheRulingsOnes() {
        assertThat(MessageCatalog.ACCOUNT_ID_NOT_FOUND.render("00000000099")).isEqualTo("Account 00000000099 not found.");
        assertThat(MessageCatalog.CUSTOMER_NOT_FOUND.render("000000001")).isEqualTo("Customer 000000001 not found.");
        assertThat(MessageCatalog.NO_CARD_LINKED.severity()).isEqualTo(Severity.INFO);
        // A save that went through is a success; the validated step still asks for F5, so it stays info.
        assertThat(MessageCatalog.CHANGES_COMMITTED.severity()).isEqualTo(Severity.SUCCESS);
        assertThat(MessageCatalog.CHANGES_VALIDATED.severity()).isEqualTo(Severity.INFO);
        assertThat(MessageCatalog.ACVW_INVALID_ACCT_ID.text()).contains("must  be");
        assertThat(MessageCatalog.CARD_DISPLAYED.text()).startsWith("   Displaying");
        assertThat(MessageCatalog.FIELD_NOT_EDITABLE.render("Country")).isEqualTo("Country cannot be changed on this screen.");
        assertThat(MessageCatalog.INVALID_PAGE_SIZE.render("7")).isEqualTo("Page size must be between 1 and 7");
    }

    /**
     * Each text is checked against the smallest message length of the units whose endpoints can
     * return it, rendered with the longest value any caller passes for each placeholder. Every
     * catalogue key needs an entry here, and nothing is skipped.
     */
    @Test
    void everyTextFitsTheMessageLineOfEveryUnitThatReturnsIt() {
        Map<String, Integer> unitLimit = Map.ofEntries(
                // NUM-ACVW-007, NUM-ACUP-103 and the 75-character work fields of COCRDLIC, COCRDSLC, COCRDUPC.
                Map.entry("ACVW", 75), Map.entry("ACUP", 75), Map.entry("CRDL", 75), Map.entry("CRDV", 75),
                Map.entry("CRDU", 75),
                // An 80-character work field of which ERRMSG shows 78: COBIL00C, COTRN02C, COSGN00C, COMEN01C,
                // COADM01C, and COUSR00C to COUSR03C.
                Map.entry("BILL", 78), Map.entry("TRNA", 78), Map.entry("SGN", 78), Map.entry("MENU", 78),
                Map.entry("ADMN", 78),
                // ERRMSG of COPAU00 (COPAUS0C) and COPAU01 (COPAUS1C): 78 characters.
                Map.entry("AUSM", 78), Map.entry("AUDT", 78),
                // ERRMSG of COTRN00 and COTRN01 is 78 long.
                Map.entry("TRNL", 78), Map.entry("TRNV", 78),
                // ERRMSG of CORPT00 is 78 long.
                Map.entry("RPTS", 78),
                // COUSR00C to COUSR03C: an 80-character work field of which ERRMSG shows 78.
                Map.entry("USR", 78));
        List<String> account = List.of("ACVW", "ACUP", "CRDL", "CRDV", "CRDU");
        List<String> every = List.copyOf(unitLimit.keySet());
        Map<String, List<String>> units = new LinkedHashMap<>();
        for (String key : List.of("UNAUTHENTICATED", "ACCESS_DENIED", "CSRF_REJECTED", "INVALID_REQUEST", "NOT_FOUND",
                "METHOD_NOT_ALLOWED", "INTERNAL_ERROR", "INVALID_KEY", "RECORD_CHANGED", "PRECONDITION_REQUIRED",
                "INVALID_IF_MATCH", "INVALID_PAGE_REQUEST", "INVALID_PAGE_SIZE", "INVALID_CURSOR", "INVALID_UNMASK_FIELD")) {
            units.put(key, every);
        }
        for (String key : List.of("ACCT_ID_REQUIRED", "ACCOUNT_NOT_FOUND", "ACCOUNT_LOOKUP_FAILED", "CONFIRM_PAYMENT",
                "NOTHING_TO_PAY", "INVALID_CONFIRM", "XREF_LOOKUP_FAILED", "TRAN_LOOKUP_FAILED", "TRAN_ID_EXISTS",
                "TRAN_ADD_FAILED", "TRAN_ID_EXHAUSTED", "ACCOUNT_UPDATE_FAILED", "PAYMENT_SUCCESSFUL",
                "IDEMPOTENCY_KEY_REQUIRED", "IDEMPOTENCY_KEY_REUSED", "BATCH_WINDOW")) {
            units.put(key, List.of("BILL"));
        }
        for (String key : List.of("UNMASK_AUDIT_FAILED", "ACVW_ACCT_ID_REQUIRED", "ACVW_INVALID_ACCT_ID",
                "ACUP_ACCT_ID_REQUIRED", "ACUP_INVALID_ACCT_ID", "CARD_KEY_NO_INPUT", "CARD_KEY_ACCT_ID_REQUIRED",
                "CARD_KEY_INVALID_ACCT_ID", "CARD_NUM_REQUIRED", "INVALID_CARD_NUM", "ACCOUNT_ID_NOT_FOUND",
                "CUSTOMER_NOT_FOUND", "NO_CARD_LINKED", "ACCOUNT_DATA_READ_FAILED", "CUSTOMER_LOOKUP_FAILED",
                "XREF_DATA_READ_FAILED", "ACCOUNT_VIEW_PROMPT", "ACCOUNT_UPDATE_PROMPT", "ACCOUNT_UPDATE_DETAILS",
                "FIELD_NOT_EDITABLE", "FIELD_NOT_EDITABLE_UNKNOWN", "NO_CHANGE", "CHANGES_VALIDATED", "CHANGES_COMMITTED",
                "CHANGES_UNSUCCESSFUL", "ACCOUNT_LOCK_FAILED", "CUSTOMER_LOCK_FAILED", "ACCOUNT_SAVE_FAILED",
                "ACCOUNT_UPDATE_BATCH_WINDOW", "INVALID_ACCT_FILTER", "INVALID_CARD_FILTER", "CARD_LIST_INVALID_CURSOR",
                "SELECT_ONE_RECORD", "INVALID_ACTION_CODE", "NO_RECORDS_FOUND", "NO_MORE_RECORDS", "NO_MORE_PAGES",
                "NO_PREVIOUS_PAGES", "CARD_LIST_PROMPT", "CARD_LIST_FAILED", "CARD_NOT_FOUND", "CARD_LOOKUP_FAILED",
                "CARD_KEYS_PROMPT", "CARD_DISPLAYED", "CARD_UPDATE_SHOWN", "CARD_UPDATE_DETAILS", "CARD_LOCK_FAILED",
                "CARD_UPDATE_FAILED", "CARD_UPDATE_BATCH_WINDOW")) {
            units.put(key, account);
        }
        for (String key : List.of("USER_ID_REQUIRED", "PASSWORD_REQUIRED", "INVALID_INPUT_USER_ID",
                "INVALID_INPUT_PASSWORD", "UNABLE_TO_VERIFY", "USER_NOT_FOUND", "WRONG_PASSWORD", "SIGN_ON_FAILED",
                "USER_TYPE_NOT_ALLOWED", "PASSWORD_CHANGE_REQUIRED", "NEW_PASSWORD_REQUIRED", "NEW_PASSWORD_LENGTH_LEGACY",
                "NEW_PASSWORD_LENGTH_FIXED", "NEW_PASSWORD_SAME", "NEW_PASSWORD_IS_USER_ID", "NEW_PASSWORD_MISMATCH",
                "USER_ID_MISMATCH", "TOO_MANY_ATTEMPTS", "SESSION_EXPIRED", "SIGNED_OFF")) {
            units.put(key, List.of("SGN"));
        }
        for (String key : List.of("ACCOUNT_ID_REQUIRED", "ACCOUNT_ID_NOT_NUMERIC", "AUSM_ACCOUNT_NOT_FOUND",
                "AUTH_XREF_NOT_FOUND", "AUTH_CUSTOMER_NOT_FOUND", "AUTH_XREF_READ_FAILED", "AUTH_ACCOUNT_READ_FAILED",
                "AUTH_CUSTOMER_READ_FAILED", "AUTH_SUMMARY_READ_FAILED", "AUTH_DETAIL_READ_FAILED",
                "AUSM_INVALID_PAGE_SIZE", "AUSM_INVALID_PAGE_REQUEST", "AUTH_LIST_TOP", "AUTH_LIST_BOTTOM")) {
            units.put(key, List.of("AUSM"));
        }
        for (String key : List.of("INVALID_AUTH_ID", "AUTHORIZATION_NOT_FOUND", "AUDT_DETAIL_READ_FAILED",
                "AUTH_NEXT_READ_FAILED", "INVALID_FRAUD_ACTION", "FRAUD_UPDATE_FAILED", "AUTH_MARKED_FRAUD",
                "AUTH_FRAUD_REMOVED", "AUTH_LAST")) {
            units.put(key, List.of("AUDT"));
        }
        for (String key : List.of("OPTION_COMING_SOON", "OPTION_NOT_INSTALLED", "INVALID_OPTION")) {
            units.put(key, List.of("MENU", "ADMN"));
        }
        for (String key : List.of("INVALID_SEARCH_TRAN_ID", "TRAN_LIST_FAILED", "TRAN_LIST_NO_ROWS", "TRAN_LIST_BOTTOM",
                "TRAN_LIST_TOP", "TRAN_LIST_ALREADY_TOP", "TRAN_LIST_ALREADY_BOTTOM", "TRAN_INVALID_SELECTION")) {
            units.put(key, List.of("TRNL"));
        }
        for (String key : List.of("TRAN_ID_REQUIRED", "TRAN_NOT_FOUND")) {
            units.put(key, List.of("TRNV"));
        }
        // Keys that transaction-service returns too.
        units.put("TRAN_LOOKUP_FAILED", List.of("BILL", "TRNV", "TRNA"));
        units.put("CARD_LIST_INVALID_CURSOR", List.of("CRDL", "TRNL"));
        units.put("UNMASK_AUDIT_FAILED", List.of("ACVW", "ACUP", "CRDL", "CRDV", "CRDU", "TRNV"));
        for (String key : List.of("ACCOUNT_NOT_FOUND", "INVALID_CONFIRM", "TRAN_ID_EXISTS", "IDEMPOTENCY_KEY_REQUIRED")) {
            units.put(key, List.of("BILL", "TRNA"));
        }
        for (String key : List.of("KEY_REQUIRED", "TRNA_INVALID_ACCT_ID", "TRNA_INVALID_CARD_NUM",
                "TRNA_XREF_LOOKUP_FAILED", "CARD_CHOICE_REQUIRED", "CARD_ACCOUNT_MISMATCH", "TRNA_CARD_NOT_FOUND",
                "TRNA_CARD_LOOKUP_FAILED", "FIELD_REQUIRED", "TRNA_UNSUPPORTED_CHARACTER", "TRNA_TOO_LONG",
                "TYPE_NOT_NUMERIC", "CATEGORY_NOT_NUMERIC", "TYPE_NOT_FOUND", "CATEGORY_NOT_FOUND",
                "REFERENCE_LOOKUP_FAILED", "INVALID_AMOUNT", "INVALID_ORIG_DATE_FORMAT", "INVALID_ORIG_DATE",
                "MERCHANT_ID_NOT_NUMERIC", "CONFIRM_ADD_TRANSACTION", "TRANSACTION_ADDED", "TRANSACTION_POSTING_HINT",
                "TRAN_IDEMPOTENCY_KEY_REUSED", "TRAN_ADD_BATCH_WINDOW", "NO_TRANSACTION", "ADD_FAILED")) {
            units.put(key, List.of("TRNA"));
        }
        for (String key : List.of("REPORT_TYPE_REQUIRED", "START_MONTH_REQUIRED", "START_DAY_REQUIRED",
                "START_YEAR_REQUIRED", "END_MONTH_REQUIRED", "END_DAY_REQUIRED", "END_YEAR_REQUIRED",
                "START_MONTH_INVALID", "START_DAY_INVALID", "START_YEAR_INVALID", "END_MONTH_INVALID", "END_DAY_INVALID",
                "END_YEAR_INVALID", "START_DATE_INVALID", "END_DATE_INVALID", "DATE_RANGE_INVALID", "CONFIRM_REQUIRED",
                "CONFIRM_INVALID", "REPORT_SUBMITTED", "REPORT_TRIGGER_FAILED", "INVALID_RUN_ID", "REPORT_RUN_NOT_FOUND",
                "REPORT_NOT_READY", "REPORT_FILE_MISSING", "REPORT_RUN_STALLED")) {
            units.put(key, List.of("RPTS"));
        }
        for (String key : USER_ADMIN_KEYS) {
            units.put(key, List.of("USR"));
        }
        // The longest value a caller passes, and where its bound comes from. The account unmask list
        // and the account update labels are rendered from their sources again in account-service.
        Map<String, String> longest = Map.of(
                "tranId", "9".repeat(16), // TransactionIds: 16 digits; core.transaction.tran_id CHAR(16)
                "acctId", "9".repeat(11), // passed after the 11-digit check; CK_account_acct_id
                "custId", "9".repeat(9), // read from core.card_xref.cust_id CHAR(9)
                "max", "10", // PageRequest.of screen size; two digits cover any size up to 99
                "fields", "ssn, dob, govtIssuedId, eftAccountId, cardNum", // AccountViewService.UNMASKABLE
                // The longest label of AccountUpdateLabels (20); the transaction add labels are shorter.
                "label", "Government Issued Id",
                "maxLength", "60", // the longest transaction add field, description
                "value", "X", // CONFIRM_INVALID echoes the first character only
                "userId", "N".repeat(8), // a canonical user ID, iam.app_user.user_id NCHAR(8)
                "name", "N".repeat(OPTION_NAME_WIDTH)); // menuOptionNamesComeFromTheBoundedTables; report names are shorter

        assertThat(units.keySet()).containsExactlyInAnyOrderElementsOf(MessageCatalog.all().keySet());
        for (Message m : MessageCatalog.all().values()) {
            int limit = units.get(m.key()).stream().mapToInt(unitLimit::get).min().orElseThrow();
            String[] args = m.params().stream()
                    .map(p -> {
                        assertThat(longest).as(m.key() + " placeholder " + p).containsKey(p);
                        return longest.get(p);
                    })
                    .toArray(String[]::new);
            String rendered = m.render(args);
            assertThat(rendered.length()).as(m.key() + ": " + rendered).isLessThanOrEqualTo(limit);
        }
    }


    private static final List<String> USER_ADMIN_KEYS = List.of("USER_FIRST_NAME_EMPTY", "USER_LAST_NAME_EMPTY", "USER_ID_EMPTY",
            "USER_PASSWORD_EMPTY", "USER_TYPE_EMPTY", "USER_ADDED", "USER_ID_EXISTS", "USER_ADD_FAILED", "USER_ID_NOT_FOUND",
            "USER_LOOKUP_FAILED", "USER_UPDATE_PROMPT", "USER_NO_CHANGE", "USER_UPDATED", "USER_UPDATE_FAILED",
            "USER_DELETE_FAILED", "USER_DELETE_PROMPT", "USER_DELETED", "USER_LIST_BOTTOM", "USER_LIST_TOP",
            "USER_LIST_NONE", "USER_LIST_ALREADY_TOP", "USER_LIST_ALREADY_BOTTOM", "USER_INVALID_SELECTION",
            "USER_ID_INVALID", "USER_TYPE_INVALID", "USER_CANNOT_DELETE_SELF", "USER_LAST_ADMIN", "USER_ADMIN_BATCH_WINDOW",
            "USER_UNSUPPORTED_CHARACTER", "USER_TOO_LONG");

    /**
     * The user administration texts as the identity-service contract lists them: every one fits
     * COUSR00C to COUSR03C's 78-character ERRMSG with an 8-character user ID and the longest user
     * admin label, the variants share their API codes, and the page-only texts carry their own keys.
     */
    @Test
    void userAdminTextsFitTheirScreensAndShareTheVariantCodes() {
        for (String key : USER_ADMIN_KEYS) {
            assertThat(key).as("user admin keys live in the USER_ namespace").startsWith("USER_");
            Message m = MessageCatalog.get(key);
            String rendered = m.render(m.params().stream()
                    .map(p -> switch (p) {
                        case "userId" -> "N".repeat(8);
                        case "maxLength" -> "20";
                        default -> "First Name";
                    }).toArray(String[]::new));
            assertThat(rendered.length()).as(key + ": " + rendered).isLessThanOrEqualTo(78);
        }
        assertThat(MessageCatalog.USER_NO_CHANGE.code()).isEqualTo("NO_CHANGE");
        assertThat(MessageCatalog.USER_ADMIN_BATCH_WINDOW.code()).isEqualTo(MessageCatalog.BATCH_WINDOW.code());
        assertThat(MessageCatalog.USER_UPDATE_FAILED.text()).isEqualTo(MessageCatalog.USER_DELETE_FAILED.text());
        assertThat(MessageCatalog.USER_ADDED.render("USER0006")).isEqualTo("User USER0006 has been added ...");
        assertThat(MessageCatalog.USER_ADMIN_BATCH_WINDOW.render()).hasSize(73);
        assertThat(MessageCatalog.USER_UNSUPPORTED_CHARACTER.code()).isEqualTo("UNSUPPORTED_CHARACTER");
        assertThat(MessageCatalog.USER_TOO_LONG.render("User ID", "8")).isEqualTo("User ID must not be longer than 8 characters.");
        for (String page : List.of("USER_UPDATE_PROMPT", "USER_DELETE_PROMPT", "USER_LIST_ALREADY_TOP",
                "USER_LIST_ALREADY_BOTTOM", "USER_INVALID_SELECTION")) {
            assertThat(MessageCatalog.get(page).code()).isEqualTo(page);
        }
        assertThat(MessageCatalog.USER_INVALID_SELECTION.severity()).isEqualTo(Severity.ERROR);
        assertThat(MessageCatalog.USER_LIST_NONE.severity()).isEqualTo(Severity.INFO);
    }

    /** COMEN02Y and COADM02Y hold each option name in {@code PIC X(35)}. */
    private static final int OPTION_NAME_WIDTH = 35;

    /**
     * A menu option renders OPTION_COMING_SOON when it has no route, and OPTION_NOT_INSTALLED when
     * it belongs to an optional module that is off, with its name from the page's fixed option
     * list, never from input. Every legacy name fits the copybooks' 35-character field, and every
     * name the page shows, target-only routed options included, fits it too; the page need not show
     * only legacy names. Which options can reach the texts is read from the page: once every option
     * has a route, only the module options can, and none at all is a valid page. The page read is
     * the one under {@code carddemo.menuPage} when that property is set, so another checkout can be
     * checked.
     */
    @Test
    void menuOptionNamesThatReachTheTextsAreBounded() throws IOException {
        Path root = Path.of("..", "..", "..");
        List<String> main = optionNames(root.resolve("app/cpy/COMEN02Y.cpy"));
        List<String> admin = optionNames(root.resolve("app/cpy/COADM02Y.cpy"));
        assertThat(main).hasSize(11).contains("Pending Authorization View");
        assertThat(admin).hasSize(6).contains("User List (Security)", "Transaction Type List/Update (Db2)");
        List<String> legacy = new java.util.ArrayList<>(main);
        legacy.addAll(admin);
        assertThat(legacy.stream().mapToInt(String::length).max().orElseThrow())
                .isEqualTo(34).isLessThanOrEqualTo(OPTION_NAME_WIDTH);

        Path pagePath = Path.of(System.getProperty("carddemo.menuPage",
                root.resolve("modernization/web/src/pages/MenuPage.tsx").toString()));
        MenuOptions options = MenuOptions.of(Files.readString(pagePath));
        assertThat(options.names()).as(pagePath + " options").hasSizeGreaterThanOrEqualTo(main.size());
        assertThat(options.producers()).as(pagePath + " options that render a text").isSubsetOf(options.names());
        assertBounded(options.names());
        assertBounded(options.producers());

        String bound = "N".repeat(OPTION_NAME_WIDTH);
        assertThat(MessageCatalog.OPTION_COMING_SOON.render(bound)).hasSize(66);
        assertThat(MessageCatalog.OPTION_NOT_INSTALLED.render(bound)).hasSize(67);
    }

    @Test
    void aPageWhoseOptionsAllHaveARouteAndNoModuleRendersNoOptionText() {
        MenuOptions options = MenuOptions.of("""
                export const MAIN_OPTIONS: MenuOption[] = [
                  { number: 1, name: 'Account View', to: '/accounts/view' },
                  { number: 3, name: 'User Update (Security)', to: '/admin/users', state: { intent: 'update' } satisfies S },
                ];
                """);
        assertThat(options.names()).containsExactly("Account View", "User Update (Security)");
        assertThat(options.producers()).isEmpty();
        assertBounded(options.producers());
    }

    @Test
    void anOptionWithoutARouteOrOfAModuleRendersAnOptionText() {
        MenuOptions options = MenuOptions.of("""
                  { number: 1, name: 'Account View', to: '/accounts/view' },
                  { number: 8, name: 'Transaction Add' },
                  { number: 9, name: 'Routed Before Module', to: '/x', state: { a: 'b' }, module: 'authorization' },
                  { number: 11, name: 'Pending Authorization View', to: '/authorizations', module: 'authorization' },
                """);
        assertThat(options.names()).hasSize(4);
        assertThat(options.producers())
                .containsExactly("Transaction Add", "Routed Before Module", "Pending Authorization View");
    }

    @Test
    void aNameThatReachesTheTextsMustFitTheCopybookField() {
        String tooLong = "N".repeat(OPTION_NAME_WIDTH + 1);
        MenuOptions options = MenuOptions.of("  { number: 8, name: '" + tooLong + "' },\n");
        assertThat(options.producers()).containsExactly(tooLong);
        assertThatThrownBy(() -> assertBounded(options.producers())).isInstanceOf(AssertionError.class)
                .hasMessageContaining(tooLong);
    }

    private static void assertBounded(List<String> names) {
        assertThat(names).allSatisfy(name -> assertThat(name.length()).as(name).isLessThanOrEqualTo(OPTION_NAME_WIDTH));
    }

    /**
     * A menu page's options, one per line: every name, and the names that render a text, those
     * without a route ({@code to:}) or of an optional module ({@code module:}).
     */
    private record MenuOptions(List<String> names, List<String> producers) {

        private static final java.util.regex.Pattern OPTION = java.util.regex.Pattern
                .compile("\\{ number: \\d+, name: '([^']+)'(.*)$", java.util.regex.Pattern.MULTILINE);

        static MenuOptions of(String page) {
            java.util.regex.Matcher m = OPTION.matcher(page);
            List<String> names = new java.util.ArrayList<>();
            List<String> producers = new java.util.ArrayList<>();
            while (m.find()) {
                names.add(m.group(1));
                if (!m.group(2).contains("to:") || m.group(2).contains("module:")) {
                    producers.add(m.group(1));
                }
            }
            return new MenuOptions(List.copyOf(names), List.copyOf(producers));
        }
    }

    /** The 35-character literals of a menu copybook, trimmed, skipping comment lines. */
    private static List<String> optionNames(Path copybook) throws IOException {
        List<String> names = new java.util.ArrayList<>();
        java.util.regex.Pattern literal = java.util.regex.Pattern.compile("'([^']{" + OPTION_NAME_WIDTH + "})'");
        for (String line : Files.readAllLines(copybook, StandardCharsets.UTF_8)) {
            if (line.length() > 6 && line.charAt(6) == '*') {
                continue;
            }
            java.util.regex.Matcher m = literal.matcher(line);
            if (m.find()) {
                names.add(m.group(1).stripTrailing());
            }
        }
        return names;
    }

    @Test
    void theBoundedTextsOfTheCatalogueRuling() {
        assertThat(MessageCatalog.ACCOUNT_UPDATE_BATCH_WINDOW.render())
                .isEqualTo("Account update is not available during the batch cycle. Try again later...")
                .hasSize(74);
        assertThat(MessageCatalog.CARD_UPDATE_BATCH_WINDOW.render()).hasSize(75);
        assertThat(MessageCatalog.BATCH_WINDOW.render()).hasSize(76);
        assertThat(MessageCatalog.FIELD_NOT_EDITABLE_UNKNOWN.code()).isEqualTo("FIELD_NOT_EDITABLE");
        assertThat(MessageCatalog.FIELD_NOT_EDITABLE_UNKNOWN.params()).isEmpty();
        assertThat(MessageCatalog.FIELD_NOT_EDITABLE_UNKNOWN.render())
                .isEqualTo("This field cannot be changed on this screen.")
                .hasSize(44);
    }

    /** The ruled COPAUS0C texts: the legacy wording without response codes, leading spaces kept (D-58). */
    @Test
    void pendingAuthorizationSummaryTexts() {
        assertThat(MessageCatalog.AUSM_ACCOUNT_NOT_FOUND.code()).isEqualTo(MessageCatalog.ACCOUNT_NOT_FOUND.code());
        assertThat(MessageCatalog.AUSM_ACCOUNT_NOT_FOUND.render("99999999999"))
                .isEqualTo("Account:99999999999 not found in ACCT file.");
        assertThat(MessageCatalog.AUTH_XREF_NOT_FOUND.render("00000000091"))
                .isEqualTo("Account:00000000091 not found in XREF file.");
        assertThat(MessageCatalog.AUTH_CUSTOMER_NOT_FOUND.render("000000091"))
                .isEqualTo("Customer:000000091 not found in CUST file.");
        assertThat(MessageCatalog.AUTH_SUMMARY_READ_FAILED.text()).isEqualTo(" System error while reading AUTH Summary");
        assertThat(MessageCatalog.AUTH_DETAIL_READ_FAILED.text()).isEqualTo(" System error while reading AUTH Details");
        assertThat(MessageCatalog.AUSM_INVALID_PAGE_SIZE.code()).isEqualTo(MessageCatalog.INVALID_PAGE_SIZE.code());
        assertThat(MessageCatalog.AUSM_INVALID_PAGE_REQUEST.code()).isEqualTo(MessageCatalog.INVALID_PAGE_REQUEST.code());
    }

    @Test
    void pendingAuthorizationDetailTexts() {
        assertThat(MessageCatalog.AUDT_DETAIL_READ_FAILED.code()).isEqualTo(MessageCatalog.AUTH_DETAIL_READ_FAILED.code());
        assertThat(MessageCatalog.AUDT_DETAIL_READ_FAILED.text()).isEqualTo(" System error while reading Auth Details");
        assertThat(MessageCatalog.AUTH_NEXT_READ_FAILED.text()).isEqualTo(" System error while reading next Auth");
        assertThat(MessageCatalog.FRAUD_UPDATE_FAILED.text()).isEqualTo(" System error while FRAUD Tagging, ROLLBACK");
        assertThat(MessageCatalog.AUTHORIZATION_NOT_FOUND.text()).isEqualTo("Authorization not found...");
        assertThat(MessageCatalog.AUTH_MARKED_FRAUD.text()).isEqualTo("AUTH MARKED FRAUD...");
        assertThat(MessageCatalog.AUTH_MARKED_FRAUD.severity()).isEqualTo(Severity.INFO);
        assertThat(MessageCatalog.AUTH_FRAUD_REMOVED.text()).isEqualTo("AUTH FRAUD REMOVED...");
        assertThat(MessageCatalog.AUTH_FRAUD_REMOVED.severity()).isEqualTo(Severity.INFO);
        assertThat(MessageCatalog.AUTH_LAST.text()).isEqualTo("Already at the last Authorization...");
    }

    /** COTRN00C and COTRN01C texts, copied from the COBOL with their dots and spaces. */
    @Test
    void transactionTextsAreTheCobolOnes() {
        assertThat(MessageCatalog.INVALID_SEARCH_TRAN_ID.text()).isEqualTo("Tran ID must be Numeric ...");
        assertThat(MessageCatalog.TRAN_LIST_FAILED.text()).isEqualTo("Unable to lookup transaction...");
        assertThat(MessageCatalog.TRAN_LIST_NO_ROWS.text()).isEqualTo("You are at the top of the page...");
        assertThat(MessageCatalog.TRAN_LIST_BOTTOM.text()).isEqualTo("You have reached the bottom of the page...");
        assertThat(MessageCatalog.TRAN_LIST_TOP.text()).isEqualTo("You have reached the top of the page...");
        assertThat(MessageCatalog.TRAN_LIST_ALREADY_TOP.text()).isEqualTo("You are already at the top of the page...");
        assertThat(MessageCatalog.TRAN_LIST_ALREADY_BOTTOM.text()).isEqualTo("You are already at the bottom of the page...");
        assertThat(MessageCatalog.TRAN_INVALID_SELECTION.text()).isEqualTo("Invalid selection. Valid value is S");
        assertThat(MessageCatalog.TRAN_ID_REQUIRED.text()).isEqualTo("Tran ID can NOT be empty...");
        assertThat(MessageCatalog.TRAN_NOT_FOUND.text()).isEqualTo("Transaction ID NOT found...");
        assertThat(MessageCatalog.TRAN_LOOKUP_FAILED.text()).isEqualTo("Unable to lookup Transaction...");
        for (Message notice : List.of(MessageCatalog.TRAN_LIST_NO_ROWS, MessageCatalog.TRAN_LIST_BOTTOM,
                MessageCatalog.TRAN_LIST_TOP, MessageCatalog.TRAN_LIST_ALREADY_TOP, MessageCatalog.TRAN_LIST_ALREADY_BOTTOM)) {
            assertThat(notice.severity()).as(notice.key()).isEqualTo(Severity.INFO);
        }
        assertThat(MessageCatalog.INVALID_PAGE_SIZE.render("10")).isEqualTo("Page size must be between 1 and 10");
    }

    /** COTRN02C and CORPT00C texts, and the transaction variants that leave Bill Pay's texts unchanged. */
    @Test
    void transactionAddAndReportTexts() {
        assertThat(MessageCatalog.FIELD_REQUIRED.render("Merchant Zip")).isEqualTo("Merchant Zip can NOT be empty...");
        assertThat(MessageCatalog.TRANSACTION_ADDED.render("9000000000000000"))
                .isEqualTo("Transaction added successfully.  Your Tran ID is 9000000000000000.");
        assertThat(MessageCatalog.TRANSACTION_POSTING_HINT.severity()).isEqualTo(Severity.INFO);
        assertThat(MessageCatalog.TRAN_ADD_BATCH_WINDOW.code()).isEqualTo("BATCH_WINDOW");
        assertThat(MessageCatalog.TRAN_ADD_BATCH_WINDOW.render()).hasSize(75);
        assertThat(MessageCatalog.TRAN_IDEMPOTENCY_KEY_REUSED.render()).hasSize(48);
        assertThat(MessageCatalog.IDEMPOTENCY_KEY_REUSED.text()).isEqualTo("Idempotency-Key was used for another payment");
        assertThat(MessageCatalog.TRNA_TOO_LONG.render("Description", "60"))
                .isEqualTo("Description must not be longer than 60 characters.");
        assertThat(MessageCatalog.TRNA_INVALID_CARD_NUM.code()).isEqualTo(MessageCatalog.INVALID_CARD_NUM.code());
        assertThat(MessageCatalog.INVALID_CARD_NUM.text()).isEqualTo("Card number must be a non zero 16 digit number");
        assertThat(MessageCatalog.CONFIRM_REQUIRED.render("Monthly")).isEqualTo("Please confirm to print the Monthly report...");
        assertThat(MessageCatalog.CONFIRM_INVALID.render("X")).isEqualTo("\"X\" is not a valid value to confirm...");
        assertThat(MessageCatalog.REPORT_SUBMITTED.render("Custom")).isEqualTo("Custom report submitted for printing ...");
        assertThat(MessageCatalog.REPORT_SUBMITTED.severity()).isEqualTo(Severity.SUCCESS);
    }

    /** The web client's generated message module is built from this file. */
    @Test
    void exportsTheCatalogueAsJson() throws IOException {
        String json = MessageCatalog.toJson();
        JsonNode tree = JsonMapper.builder().build().readTree(json);
        assertThat(tree.size()).isEqualTo(MessageCatalog.all().size());
        for (Map.Entry<String, Message> e : MessageCatalog.all().entrySet()) {
            JsonNode node = tree.get(e.getKey());
            assertThat(node.get("code").asString()).isEqualTo(e.getValue().code());
            assertThat(node.get("text").asString()).isEqualTo(e.getValue().text());
            assertThat(node.get("severity").asString()).isEqualTo(e.getValue().severity().key());
        }
        Path out = Path.of("target", "messages.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json, StandardCharsets.UTF_8);
    }
}
