package org.carddemo.batch.load;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/**
 * dataLoadJob's loaders keep per-execution state in fields, so each step execution, including a
 * restart and an execution running at the same time, runs its own instance.
 */
class PerRunTaskletTest {

    @Test
    void everyExecutionRunsANewLoaderAndConcurrentOnesDoNotShareOne() throws Exception {
        List<Tasklet> created = Collections.synchronizedList(new ArrayList<>());
        List<Tasklet> ran = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch bothRunning = new CountDownLatch(2);
        Tasklet step = DataLoadJobConfiguration.perRun(() -> {
            Tasklet[] self = new Tasklet[1];
            self[0] = (contribution, chunkContext) -> {
                ran.add(self[0]);
                bothRunning.countDown();
                assertThat(bothRunning.await(10, TimeUnit.SECONDS)).isTrue();
                return RepeatStatus.FINISHED;
            };
            created.add(self[0]);
            return self[0];
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<RepeatStatus> first = pool.submit(() -> step.execute(null, null));
            Future<RepeatStatus> second = pool.submit(() -> step.execute(null, null));
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(RepeatStatus.FINISHED);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(RepeatStatus.FINISHED);
        } finally {
            pool.shutdownNow();
        }
        // A restart is another step execution.
        step.execute(null, null);

        assertThat(created).hasSize(3).doesNotHaveDuplicates();
        assertThat(ran).containsExactlyInAnyOrderElementsOf(created);
    }
}
