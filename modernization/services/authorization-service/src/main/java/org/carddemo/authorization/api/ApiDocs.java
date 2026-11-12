package org.carddemo.authorization.api;

/** Media types and the hand-written problem schema the API annotations refer to. */
public final class ApiDocs {

    public static final String JSON = "application/json";
    public static final String PROBLEM_JSON = "application/problem+json";
    public static final String PROBLEM = "#/components/schemas/Problem";
    public static final String CONFLICT_PROBLEM = "#/components/schemas/AuthorizationConflictProblem";

    private ApiDocs() {
    }
}
