package org.carddemo.platform.validation;

/**
 * One failed edit: the field it marks, a stable code and the message text the legacy screen shows.
 *
 * @param field   the input field the edit marks as wrong (for a composite, the first part it marks)
 * @param code    stable code, for example {@code REQUIRED} or {@code DATE_MONTH_INVALID}
 * @param message the message with the field label filled in
 */
public record FieldProblem(String field, String code, String message) {
}
