package org.carddemo.batch.execution;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;

/**
 * The leases this process owns, renewed on a scheduler thread, and the executions it has
 * lost. A lost execution stays lost: nothing more is written for it from this process
 * (contract section 4.2).
 */
public class LeaseKeeper implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LeaseKeeper.class);

    private final ExecutionLeases leases;
    private final Set<Long> owned = ConcurrentHashMap.newKeySet();
    private final Set<Long> lost = ConcurrentHashMap.newKeySet();
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean suspended;

    public LeaseKeeper(ExecutionLeases leases) {
        this.leases = leases;
    }

    void own(long executionId) {
        lost.remove(executionId);
        owned.add(executionId);
    }

    /** The execution ended; its lease needs no more renewals. */
    void release(long executionId) {
        owned.remove(executionId);
    }

    public void lose(long executionId) {
        if (owned.remove(executionId) | lost.add(executionId)) {
            log.warn("{}: job execution {} lost its lease; this process writes nothing more for it",
                    LeaseLostException.CODE, executionId);
        }
    }

    public boolean isLost(long executionId) {
        return lost.contains(executionId);
    }

    public boolean owns(long executionId) {
        return owned.contains(executionId);
    }

    /** One renewal round. A database error is not a loss: the predicate decides at the next round or fence. */
    void renewAll() {
        if (suspended) {
            return;
        }
        for (long id : owned) {
            try {
                if (!leases.renew(id)) {
                    lose(id);
                }
            } catch (DataAccessException e) {
                log.warn("lease renewal of job execution {} failed: {}", id, e.getClass().getSimpleName());
            }
        }
    }

    /** Stands in for a paused process in tests: no renewals until resumed. */
    public void suspendRenewals(boolean suspend) {
        this.suspended = suspend;
    }

    @Override
    public void start() {
        long interval = leases.settings().renewalInterval().toMillis();
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lease-renewal");
            t.setDaemon(true);
            return t;
        });
        s.scheduleWithFixedDelay(this::renewAll, interval, interval, TimeUnit.MILLISECONDS);
        scheduler = s;
    }

    @Override
    public void stop() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}
