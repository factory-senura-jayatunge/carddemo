package org.carddemo.batch.internal;

import org.carddemo.batch.execution.SafeFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The one-shot cycle-rerun (make -C modernization cycle-rerun CYCLE=id): {@link CycleReruns} for
 * {@code carddemo.batch.rerun.cycle-id}. The exit code is 0 only when the cycle was restored; a
 * refusal or a failure exits 1, and a missing or invalid cycle ID already fails the start
 * ({@link BatchCommandCheck}).
 */
@Component
@ConditionalOnBooleanProperty(name = "spring.batch.job.enabled", matchIfMissing = true)
@ConditionalOnProperty(name = "carddemo.batch.command", havingValue = "cycle-rerun")
class CycleRerunCommand implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CycleRerunCommand.class);

    private final CycleReruns reruns;
    private final String cycleId;
    private int exitCode = 1;

    CycleRerunCommand(CycleReruns reruns, @Value("${carddemo.batch.rerun.cycle-id:}") String cycleId) {
        this.reruns = reruns;
        this.cycleId = cycleId.strip();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!BatchCommandCheck.isCycleId(cycleId)) {
            log.error("cycle-rerun needs carddemo.batch.rerun.cycle-id, a cycle ID");
            return;
        }
        long id = Long.parseLong(cycleId);
        try {
            CycleReruns.Rerun rerun = reruns.rerun(id);
            var r = rerun.restored();
            log.info("cycle-rerun: cycle {} is OPEN at attempt {}; reverted {} authorization matches, restored {} accounts"
                    + " and replaced {} category balances, deleted {} transactions, {} rejects and {} staged rows,"
                    + " {} online adds wait again, recovered executions {}, daily file {}", id, rerun.cycle().attempt(),
                    r.matchesReverted(), r.accounts(), r.categoryBalances(), r.transactions(), r.rejects(), r.stagedRows(),
                    r.onlineAdds(), rerun.recovered(), rerun.dailyFileReturned() ? "returned to the inbound folder"
                            : "left as it is");
            log.info("cycle-rerun: the batch window of cycle {} stays open for the next DAG run", id);
            exitCode = 0;
        } catch (InternalApiException e) {
            log.error("cycle-rerun of cycle {} refused: {}", id, e.getMessage());
        } catch (RuntimeException e) {
            // Not left to Spring Boot, whose "Application run failed" log quotes the driver's message.
            log.error("cycle-rerun of cycle {} failed: {}", id, SafeFailures.describe(e), SafeFailures.sanitized(e));
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
