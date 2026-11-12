package org.carddemo.account.api;

/** Media types and the hand-written problem schemas the API annotations refer to. */
public final class ApiDocs {

    public static final String JSON = "application/json";
    public static final String PROBLEM_JSON = "application/problem+json";
    public static final String PROBLEM = "#/components/schemas/Problem";
    /** A field-rule failure: {@code invalidFields} lists every failing field in screen order. */
    public static final String VALIDATION_PROBLEM = "#/components/schemas/ValidationProblem";
    /** {@code 409 RECORD_CHANGED} with {@code current}, an {@code AccountUpdateForm}. */
    public static final String ACCOUNT_CONFLICT_PROBLEM = "#/components/schemas/AccountConflictProblem";
    /** {@code 409 RECORD_CHANGED} with {@code current}, a {@code CardUpdateForm}. */
    public static final String CARD_CONFLICT_PROBLEM = "#/components/schemas/CardConflictProblem";

    private ApiDocs() {
    }
}
