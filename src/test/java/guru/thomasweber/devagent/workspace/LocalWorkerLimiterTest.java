package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.testutil.WorktreeStateMother;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalWorkerLimiterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given 2 active tasks and max-concurrent-workers=2 in dev-agent.yaml - when 3rd task enters gate - then marks 3rd as QUEUED until permit opens")
    void givenTwoActiveTasksAndLimitTwo_whenThirdTaskEnters_thenQueuesUntilPermitOpens() throws Exception {
        Files.createDirectories(tempDir.resolve(".agents"));
        Files.writeString(tempDir.resolve(".agents/dev-agent.yaml"), """
            agent:
              max-concurrent-workers: 2
            """);

        StateSocketService socketService = new StateSocketService();
        LocalWorkerLimiter limiter = new LocalWorkerLimiter(socketService, new DevAgentConfigLoader());
        LocalWorkerLimiter.PermitGate gate = limiter.openGateForWorkspace(tempDir);

        assertThat(gate.maxPermits()).isEqualTo(2);
        assertThat(gate.availablePermits()).isEqualTo(2);

        CountDownLatch twoRunning = new CountDownLatch(2);
        CountDownLatch releaseTwo = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);

        Thread t1 = Thread.ofVirtual().start(() -> gate.executeWithPermit(tempDir, WorktreeStateMother.builder("TASK-1").build(), () -> {
            maxObserved.accumulateAndGet(active.incrementAndGet(), Math::max);
            twoRunning.countDown();
            releaseTwo.await(5, TimeUnit.SECONDS);
            active.decrementAndGet();
            return "done-1";
        }));
        Thread t2 = Thread.ofVirtual().start(() -> gate.executeWithPermit(tempDir, WorktreeStateMother.builder("TASK-2").build(), () -> {
            maxObserved.accumulateAndGet(active.incrementAndGet(), Math::max);
            twoRunning.countDown();
            releaseTwo.await(5, TimeUnit.SECONDS);
            active.decrementAndGet();
            return "done-2";
        }));

        assertThat(twoRunning.await(5, TimeUnit.SECONDS)).isTrue();

        Thread t3 = Thread.ofVirtual().start(() -> gate.executeWithPermit(tempDir, WorktreeStateMother.builder("TASK-3").build(), () -> {
            maxObserved.accumulateAndGet(active.incrementAndGet(), Math::max);
            active.decrementAndGet();
            return "done-3";
        }));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(gate.queuedCount()).isEqualTo(1);
            assertThat(socketService.readState(tempDir, "TASK-3"))
                .isPresent()
                .get()
                .extracting(WorktreeState::status)
                .isEqualTo(TaskStatus.QUEUED);
        });

        releaseTwo.countDown();
        t1.join(5000);
        t2.join(5000);
        t3.join(5000);

        assertThat(maxObserved.get()).isEqualTo(2);
        assertThat(gate.availablePermits()).isEqualTo(2);
        assertThat(gate.queuedCount()).isZero();
    }

    @Test
    @DisplayName("Given batch of tasks - when executeBatch runs - then returns ordered results respecting concurrency limit")
    void givenBatchOfTasks_whenExecuteBatch_thenReturnsOrderedResults() {
        LocalWorkerLimiter limiter = new LocalWorkerLimiter();
        LocalWorkerLimiter.PermitGate gate = limiter.openGateForWorkspace(tempDir);
        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);
        CountDownLatch overlapLatch = new CountDownLatch(2);

        List<LocalWorkerLimiter.QueuedTask<String>> tasks = List.of(
            new LocalWorkerLimiter.QueuedTask<>(WorktreeStateMother.builder("B-1").build(), () -> {
                maxObserved.accumulateAndGet(active.incrementAndGet(), Math::max);
                overlapLatch.countDown();
                overlapLatch.await(200, TimeUnit.MILLISECONDS);
                active.decrementAndGet();
                return "res-1";
            }),
            new LocalWorkerLimiter.QueuedTask<>(WorktreeStateMother.builder("B-2").build(), () -> {
                maxObserved.accumulateAndGet(active.incrementAndGet(), Math::max);
                overlapLatch.countDown();
                overlapLatch.await(200, TimeUnit.MILLISECONDS);
                active.decrementAndGet();
                return "res-2";
            }),
            new LocalWorkerLimiter.QueuedTask<>(WorktreeStateMother.builder("B-3").build(), () -> {
                maxObserved.accumulateAndGet(active.incrementAndGet(), Math::max);
                overlapLatch.countDown();
                active.decrementAndGet();
                return "res-3";
            })
        );

        List<String> results = gate.executeBatch(tempDir, tasks);
        assertThat(results).containsExactly("res-1", "res-2", "res-3");
        assertThat(maxObserved.get()).isLessThanOrEqualTo(2);
        assertThat(gate.executeBatch(tempDir, List.of())).isEmpty();
    }

    @Test
    @DisplayName("Given failing task inside executeWithPermit - when exception thrown - then releases permit cleanly")
    void givenFailingTask_whenExecuteWithPermit_thenReleasesPermit() {
        LocalWorkerLimiter limiter = new LocalWorkerLimiter();
        LocalWorkerLimiter.PermitGate gate = limiter.openGate(0); // Clamped to minimum 1
        assertThat(gate.maxPermits()).isEqualTo(1);

        WorktreeState errState = WorktreeStateMother.builder("TASK-ERR").build();
        assertThatThrownBy(() -> gate.executeWithPermit(tempDir, errState, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(WorkspaceException.class);

        assertThat(gate.availablePermits()).isEqualTo(1);
        assertThat(limiter.openGateForWorkspace(null).maxPermits()).isEqualTo(2);
        assertThat(gate.executeBatch(tempDir, null)).isEmpty();

        List<LocalWorkerLimiter.QueuedTask<String>> failingBatch = List.of(
            new LocalWorkerLimiter.QueuedTask<>(WorktreeStateMother.builder("B-FAIL").build(), () -> {
                throw new IllegalArgumentException("batch task boom");
            })
        );
        assertThatThrownBy(() -> gate.executeBatch(tempDir, failingBatch))
            .isInstanceOf(WorkspaceException.class);

        // Null state heartbeat skip branch + interrupted awaitQueuedPermit branch
        assertThat(gate.executeWithPermit(null, null, () -> "ok-no-socket")).isEqualTo("ok-no-socket");

        LocalWorkerLimiter.PermitGate singleGate = limiter.openGate(1);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> singleGate.executeWithPermit(null, null, () -> {
            acquired.countDown();
            release.await(3, TimeUnit.SECONDS);
            return "held";
        }));
        try {
            assertThat(acquired.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> singleGate.executeWithPermit(null, null, () -> "never"))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("Interrupted");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            Thread.interrupted();
            release.countDown();
            try {
                holder.join(3000L);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }

        // Checked non-WorkspaceException wrapped cleanly in both executeWithPermit and executeBatch
        WorktreeState errWrapState = WorktreeStateMother.builder("TASK-WRAP").build();
        assertThatThrownBy(() -> gate.executeWithPermit(null, errWrapState, () -> {
            throw new IOException("checked permit failure");
        })).isInstanceOf(WorkspaceException.class);

        List<LocalWorkerLimiter.QueuedTask<String>> checkedBatch = List.of(
            new LocalWorkerLimiter.QueuedTask<>(errWrapState, () -> {
                throw new IOException("checked batch failure");
            })
        );
        assertThatThrownBy(() -> gate.executeBatch(null, checkedBatch))
            .isInstanceOf(WorkspaceException.class);
    }
}
