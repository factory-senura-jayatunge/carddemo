package org.carddemo.batch.cycle;

/**
 * A cycle job failure with a stable code; the message, {@code CODE: detail}, becomes the exit
 * description of the step and the job. The detail names IDs and record numbers, never a card number.
 */
final class CycleJobException extends RuntimeException {

    private final String code;

    CycleJobException(String code, String detail) {
        super(code + ": " + detail, null, false, false);
        this.code = code;
    }

    String code() {
        return code;
    }
}
