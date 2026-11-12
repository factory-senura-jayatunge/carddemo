package org.carddemo.verify.differential;

import com.microsoft.sqlserver.jdbc.SQLServerDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Started in its own JVM by {@link ExportBatchStdoutTest}: logs at every level, opens the job
 * repository as the exporter does, then prints a load report through the exporter's own path.
 */
final class StdoutProbe {

    private StdoutProbe() {
    }

    public static void main(String[] args) {
        Logger log = LoggerFactory.getLogger(StdoutProbe.class);
        log.error("probe-error");
        log.warn("probe-warn");
        log.info("probe-info");
        log.debug("probe-debug");
        java.util.logging.Logger.getLogger("probe.jul").info("probe-jul-info");
        SQLServerDataSource ds = new SQLServerDataSource();
        ds.setURL("jdbc:sqlserver://127.0.0.1:9;databaseName=none;loginTimeout=1");
        ExportBatch.repository(ds);
        System.exit(ExportBatch.print(LoadReport.of(LoadReportTest.load(1), 1).json()));
    }
}
