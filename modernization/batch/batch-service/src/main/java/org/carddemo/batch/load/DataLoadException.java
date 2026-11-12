package org.carddemo.batch.load;

/**
 * A load step refused its input. Messages name files, lines, fields, columns and
 * error numbers, never a value from a record, keys included: account and customer
 * IDs and card numbers are personal data (D-NFR-04). Spring Batch writes the
 * message to the error log and to the job's and step's exit message. No cause is
 * attached, because a driver's or parser's message can quote the record.
 */
public class DataLoadException extends RuntimeException {

    public DataLoadException(String message) {
        super(message);
    }
}
