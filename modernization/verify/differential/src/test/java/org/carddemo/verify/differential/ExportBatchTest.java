package org.carddemo.verify.differential;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import org.carddemo.batch.cycle.CycleOutputs;
import org.junit.jupiter.api.Test;

/** The exporter reads batch-service's job repository and files where batch-service writes them. */
class ExportBatchTest {

    @Test
    void usesBatchServicesTablePrefix() throws Exception {
        String prefix = null;
        for (URL url : Collections.list(getClass().getClassLoader().getResources("application.properties"))) {
            Properties p = new Properties();
            try (InputStream in = url.openStream()) {
                p.load(in);
            }
            if (p.containsKey("spring.batch.jdbc.table-prefix")) {
                prefix = p.getProperty("spring.batch.jdbc.table-prefix");
            }
        }
        assertThat(prefix).isEqualTo(ExportBatch.TABLE_PREFIX);
    }

    @Test
    void opensTheRepositoryWithoutConnecting() {
        SQLServerDataSource ds = new SQLServerDataSource();
        ds.setURL("jdbc:sqlserver://127.0.0.1:1;databaseName=none;loginTimeout=1");
        assertThat(ExportBatch.repository(ds)).isNotNull();
    }

    @Test
    void theRejectFileIsTheJobsPath() {
        assertThat(CycleOutputs.rejectFile(3, 2)).isEqualTo(Path.of("rejects", "3-2", "DALYREJS.txt"));
    }
}
