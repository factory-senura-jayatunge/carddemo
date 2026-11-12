package org.carddemo.batch.pauth;

import java.util.List;

import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The summary keys of the purge in ascending {@code acct_id}, {@code commitInterval} keys per
 * item. The step's chunk is one item, so one chunk transaction covers {@code commitInterval}
 * summaries and the chunk size follows the job parameter. The read is a keyset read under
 * {@code READ_COMMITTED_SNAPSHOT} and takes no lock; the writer locks each summary in the order
 * read, so every purge takes its locks in ascending order. The last key of the committed chunk is
 * kept in the step's execution context, so a restart resumes after it.
 */
class PurgeSummaryKeyReader implements ItemStreamReader<List<String>> {

    static final String LAST_KEY = "lastAcctId";

    private final JdbcClient db;
    private final int keysPerChunk;
    private String last = "";

    PurgeSummaryKeyReader(JdbcClient db, int keysPerChunk) {
        this.db = db;
        this.keysPerChunk = keysPerChunk;
    }

    @Override
    public void open(ExecutionContext context) {
        last = context.containsKey(LAST_KEY) ? context.getString(LAST_KEY) : "";
    }

    @Override
    public void update(ExecutionContext context) {
        context.putString(LAST_KEY, last);
    }

    @Override
    public List<String> read() {
        List<String> keys = db.sql("""
                SELECT TOP (:n) acct_id FROM pauth.auth_summary
                WHERE acct_id > CAST(:last AS CHAR(11))
                ORDER BY acct_id""")
                .param("n", keysPerChunk).param("last", last)
                .query(String.class).list();
        if (keys.isEmpty()) {
            return null;
        }
        last = keys.getLast();
        return keys;
    }
}
