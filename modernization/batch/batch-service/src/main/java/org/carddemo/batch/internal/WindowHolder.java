package org.carddemo.batch.internal;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who holds a batch window (contract section 6): a cycle, by cycleId, or any other run, by
 * windowRunKey. Exactly one is set, and holders match exactly.
 */
public record WindowHolder(Long cycleId, String windowRunKey) {

    public WindowHolder {
        if ((cycleId == null) == (windowRunKey == null)) {
            throw new IllegalArgumentException("a window holder is a cycleId or a windowRunKey");
        }
    }

    public static WindowHolder cycle(long cycleId) {
        return new WindowHolder(cycleId, null);
    }

    public static WindowHolder run(String windowRunKey) {
        return new WindowHolder(null, windowRunKey);
    }

    static WindowHolder of(Map<String, Object> request) {
        RequestValues values = RequestValues.forRequest();
        Object cycleId = request == null ? null : request.get("cycleId");
        Object runKey = request == null ? null : request.get("windowRunKey");
        if ((cycleId == null) == (runKey == null)) {
            throw values.error(cycleId == null ? "cycleId" : "windowRunKey", "Send exactly one of cycleId and windowRunKey.");
        }
        return cycleId != null ? cycle(values.id(cycleId, "cycleId")) : run(values.key(runKey, "windowRunKey"));
    }

    Map<String, Object> members() {
        Map<String, Object> members = new LinkedHashMap<>();
        members.put("cycleId", cycleId);
        members.put("windowRunKey", windowRunKey);
        return members;
    }
}
