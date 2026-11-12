package org.carddemo.batch.data;

/** A value that a column cannot hold unchanged; the message names the column, never the value. */
public final class ColumnCapacityException extends RuntimeException {

    private final String column;

    ColumnCapacityException(String column, String problem) {
        super(column + " cannot hold the value: " + problem);
        this.column = column;
    }

    /** The table and column, for example {@code core.customer.first_name}. */
    public String column() {
        return column;
    }
}
