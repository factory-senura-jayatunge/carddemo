package org.carddemo.batch.internal;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** A row of batch.cycle as the internal API shows it (contract section 1). */
public record Cycle(long cycleId, LocalDate businessDate, int attempt, String interestParm, String status,
        LocalDateTime openedAt, LocalDateTime closedAt) {

    public boolean interest() {
        return interestParm != null;
    }

    public boolean completed() {
        return "COMPLETED".equals(status);
    }

    Map<String, Object> body() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cycleId", cycleId);
        body.put("businessDate", businessDate.toString());
        body.put("attempt", attempt);
        body.put("interest", interest());
        body.put("interestParm", interestParm);
        body.put("status", status);
        body.put("openedAt", Times.utc(openedAt));
        body.put("closedAt", Times.utc(closedAt));
        return body;
    }
}
