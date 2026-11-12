package org.carddemo.batch.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.Optional;

import org.carddemo.batch.execution.RequestTransactions;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Cycle registration (contract section 1) and the reads of batch.cycle. */
public class Cycles {

    public enum Interest { ALWAYS, MONTH_END, NEVER }

    public record Registered(Cycle cycle, boolean created) { }

    private static final String COLUMNS = "cycle_id, business_date, attempt, interest_parm, status, opened_at, closed_at";

    private final JdbcClient db;
    private final RequestTransactions transactions;

    public Cycles(JdbcClient db, RequestTransactions transactions) {
        this.db = db;
        this.transactions = transactions;
    }

    /** INTCALC's PARM for a cycle with interest: the business date as yyyyMMdd and 00. */
    static String interestParm(LocalDate businessDate, Interest policy) {
        boolean due = switch (policy) {
            case ALWAYS -> true;
            case NEVER -> false;
            case MONTH_END -> businessDate.equals(businessDate.with(TemporalAdjusters.lastDayOfMonth()));
        };
        return due ? businessDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "00" : null;
    }

    public Registered register(Map<String, Object> request) {
        RequestValues values = RequestValues.forRequest();
        if (request == null) {
            throw values.error("businessDate", "businessDate is required.");
        }
        LocalDate businessDate = values.date(request.get("businessDate"), "businessDate");
        Interest policy = switch (request.get("interest") instanceof String s ? s : "") {
            case "always" -> Interest.ALWAYS;
            case "month_end" -> Interest.MONTH_END;
            case "never" -> Interest.NEVER;
            default -> throw values.error("interest", "interest must be always, month_end or never.");
        };
        String parm = interestParm(businessDate, policy);
        return transactions.execute(status -> {
            Optional<Cycle> existing = db.sql("SELECT " + COLUMNS
                            + " FROM batch.cycle WITH (UPDLOCK, HOLDLOCK) WHERE business_date = :date")
                    .param("date", businessDate).query(Cycles::row).optional();
            if (existing.isEmpty()) {
                Cycle created = db.sql("INSERT INTO batch.cycle (business_date, interest_parm) OUTPUT "
                                + prefixed("inserted.") + " VALUES (:date, :parm)")
                        .param("date", businessDate).param("parm", parm).query(Cycles::row).single();
                return new Registered(created, true);
            }
            Cycle cycle = existing.get();
            if (cycle.completed()) {
                throw new InternalApiException(HttpStatus.CONFLICT, "CYCLE_COMPLETED",
                        "The cycle of this business date is completed; a rerun goes through cycle-rerun.", null)
                        .withAll(cycle.body());
            }
            if (cycle.interest() != (parm != null)) {
                throw new InternalApiException(HttpStatus.CONFLICT, "CYCLE_PARAMETERS_DIFFER",
                        "The cycle of this business date was registered with a different interest decision.", null)
                        .withAll(cycle.body());
            }
            return new Registered(cycle, false);
        });
    }

    public Optional<Cycle> find(long cycleId) {
        return db.sql("SELECT " + COLUMNS + " FROM batch.cycle WHERE cycle_id = :id").param("id", cycleId)
                .query(Cycles::row).optional();
    }

    /** Sets an open cycle COMPLETED; a completed one stays as it is. */
    Cycle complete(long cycleId) {
        db.sql("UPDATE batch.cycle SET status = 'COMPLETED', closed_at = SYSUTCDATETIME() "
                + "WHERE cycle_id = :id AND status = 'OPEN'").param("id", cycleId).update();
        return find(cycleId).orElseThrow();
    }

    private static String prefixed(String prefix) {
        return prefix + COLUMNS.replace(", ", ", " + prefix);
    }

    static Cycle row(ResultSet rs, int n) throws SQLException {
        return new Cycle(rs.getLong("cycle_id"), rs.getObject("business_date", LocalDate.class), rs.getInt("attempt"),
                rs.getString("interest_parm"), rs.getString("status"), rs.getObject("opened_at", LocalDateTime.class),
                rs.getObject("closed_at", LocalDateTime.class));
    }
}
