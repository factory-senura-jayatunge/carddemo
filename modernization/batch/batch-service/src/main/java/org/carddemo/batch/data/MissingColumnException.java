package org.carddemo.batch.data;

import java.util.List;

/**
 * A row names columns its table does not have: the database was migrated with an older set of
 * migrations than the build expects. The message names the table and the columns, never a value.
 */
public final class MissingColumnException extends RuntimeException {

    MissingColumnException(String table, List<String> columns) {
        super(table + " has no column " + String.join(", ", columns)
                + ": the database is not migrated to the schema this build expects");
    }
}
