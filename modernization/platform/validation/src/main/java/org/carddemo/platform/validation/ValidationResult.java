package org.carddemo.platform.validation;

import java.util.List;
import java.util.Optional;

/**
 * Every failed edit of a form in edit order, and the fields they mark. The screen shows only the
 * first message, and puts the cursor on the first wrong field in screen order, which is usually but
 * not always the field of that message (BR-ACUP-104).
 *
 * @param problems    the failed edits, in edit order
 * @param wrongFields the marked fields, in the form's field order, without repeats
 */
public record ValidationResult(List<FieldProblem> problems, List<String> wrongFields) {

    public ValidationResult {
        problems = List.copyOf(problems);
        wrongFields = List.copyOf(wrongFields);
    }

    public boolean valid() {
        return problems.isEmpty();
    }

    /** The message the screen shows. */
    public Optional<FieldProblem> first() {
        return problems.stream().findFirst();
    }

    /** Where the cursor goes. */
    public Optional<String> cursorField() {
        return wrongFields.stream().findFirst();
    }
}
