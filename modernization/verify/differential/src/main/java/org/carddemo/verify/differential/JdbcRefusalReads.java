package org.carddemo.verify.differential;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.carddemo.platform.outputroot.OutputRoot;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * {@link RefusalReads} on one connection, which the caller opened and closes: the job repository,
 * the SQL reads and the instance read all use it, so the checks and the instance come from the
 * same admin session.
 */
final class JdbcRefusalReads implements RefusalReads {

    static final String STATEMENT_JOB = "statementJob";

    static final String EXECUTION_SQL = "SELECT i.JOB_NAME, e.JOB_INSTANCE_ID, e.STATUS, e.END_TIME, e.EXIT_CODE,"
            + " e.EXIT_MESSAGE FROM " + ExportBatch.TABLE_PREFIX + "JOB_EXECUTION e JOIN " + ExportBatch.TABLE_PREFIX
            + "JOB_INSTANCE i ON i.JOB_INSTANCE_ID = e.JOB_INSTANCE_ID WHERE e.JOB_EXECUTION_ID = :id";
    static final String CYCLE_SQL = "SELECT business_date, attempt FROM batch.cycle WHERE cycle_id = :id";
    static final String EXECUTIONS_SQL = "SELECT JOB_EXECUTION_ID FROM " + ExportBatch.TABLE_PREFIX + "JOB_EXECUTION"
            + " WHERE JOB_INSTANCE_ID = :instance AND JOB_EXECUTION_ID > :load ORDER BY JOB_EXECUTION_ID";
    static final String RUN_SQL = "SELECT status FROM batch.statement_run WHERE cycle_id = :cycle AND attempt = :attempt";

    private final Connection connection;
    private final JdbcClient db;
    private final JobRepository repository;

    JdbcRefusalReads(Connection connection) {
        this.connection = connection;
        SingleConnectionDataSource ds = new SingleConnectionDataSource(connection, true);
        this.db = JdbcClient.create(ds);
        this.repository = ExportBatch.repository(ds);
    }

    @Override
    public Optional<Execution> execution(long executionId) {
        return db.sql(EXECUTION_SQL).param("id", executionId)
                .query((rs, n) -> new Execution(rs.getLong(2), STATEMENT_JOB.equals(rs.getString(1)), rs.getString(3),
                        rs.getObject(4, LocalDateTime.class), ExitTokens.exitStatus(rs.getString(5)),
                        ExitTokens.exitCode(rs.getString(6))))
                .optional();
    }

    @Override
    public Optional<Cycle> cycle(long cycleId) {
        return db.sql(CYCLE_SQL).param("id", cycleId)
                .query((rs, n) -> new Cycle(rs.getObject(1, LocalDate.class), rs.getInt(2)))
                .optional();
    }

    @Override
    public List<StatementExecution> statementExecutionsAbove(long cycleId, Cycle cycle, long loadExecutionId) {
        List<StatementExecution> found = new ArrayList<>();
        for (int a = 1; a <= cycle.attempt(); a++) {
            JobInstance instance = repository.getJobInstance(STATEMENT_JOB,
                    CycleJobs.identity(cycleId, cycle.businessDate(), a));
            if (instance == null || !STATEMENT_JOB.equals(instance.getJobName())) {
                continue;
            }
            for (Long id : db.sql(EXECUTIONS_SQL).param("instance", instance.getInstanceId())
                    .param("load", loadExecutionId).query(Long.class).list()) {
                found.add(new StatementExecution(a, id));
            }
        }
        return List.copyOf(found);
    }

    @Override
    public List<String> statementRunStatuses(long cycleId, int attempt) {
        return db.sql(RUN_SQL).param("cycle", cycleId).param("attempt", attempt).query(String.class).list();
    }

    @Override
    public OutputRoot.Instance instance() throws SQLException {
        return OutputRoot.read(connection);
    }
}
