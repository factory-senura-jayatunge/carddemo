package org.carddemo.batch.load;

/**
 * A load step refused its input. Messages name files, lines, fields and keys
 * (card numbers masked to the last four), never other record content.
 */
public class DataLoadException extends RuntimeException {

    public DataLoadException(String message) {
        super(message);
    }
}
