package org.carddemo.transaction.api;

/** Media types and the hand-written problem schemas the API annotations refer to. */
public final class ApiDocs {

    public static final String JSON = "application/json";
    public static final String PROBLEM_JSON = "application/problem+json";
    public static final String PROBLEM = "#/components/schemas/Problem";
    /** A field-rule failure: {@code invalidFields} lists every failing field in screen order. */
    public static final String VALIDATION_PROBLEM = "#/components/schemas/ValidationProblem";
    /** A transaction add check: the values of the checks that passed, and the account's cards when one must be chosen. */
    public static final String TRANSACTION_PROBLEM = "#/components/schemas/TransactionProblem";
    /** CONFIRM_REQUIRED of a report request: the resolved range the user is asked to confirm. */
    public static final String REPORT_PROBLEM = "#/components/schemas/ReportProblem";

    private ApiDocs() {
    }
}
