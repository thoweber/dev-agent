package guru.thomasweber.devagent.cognitive;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HierarchicalChatMemoryProviderTest {

    @Test
    @DisplayName("Given distinct task IDs - when get - then returns distinct store instances")
    void givenDistinctTaskIds_whenGet_thenReturnsDistinctStores() {
        HierarchicalChatMemoryProvider provider = new HierarchicalChatMemoryProvider();

        HierarchicalMemoryStore store1 = provider.get("task-1");
        HierarchicalMemoryStore store2 = provider.get("task-2");

        assertThat(store1).isNotNull();
        assertThat(store2).isNotNull();
        assertThat(store1).isNotSameAs(store2);
        assertThat(store1.id()).isEqualTo("task-1");
        assertThat(store2.id()).isEqualTo("task-2");
        assertThat(provider.activeSessionsCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Given same task ID - when get multiple times - then returns same store instance")
    void givenSameTaskId_whenGetMultipleTimes_thenReturnsSameStoreInstance() {
        HierarchicalChatMemoryProvider provider = new HierarchicalChatMemoryProvider();

        HierarchicalMemoryStore first = provider.get("task-persistent");
        first.pinInvariants("Rules: Panache isolation");
        first.add(new UserMessage("Generate entity"));

        HierarchicalMemoryStore second = provider.get("task-persistent");

        assertThat(second).isSameAs(first);
        assertThat(second.messages()).hasSize(2);
        assertThat(provider.activeSessionsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Given active store - when evicted - then unbinds store from registry")
    void givenActiveStore_whenEvicted_thenRemovesFromRegistry() {
        HierarchicalChatMemoryProvider provider = new HierarchicalChatMemoryProvider();

        HierarchicalMemoryStore original = provider.get("task-ephemeral");
        original.add(new UserMessage("Temp turn"));

        boolean evicted = provider.evict("task-ephemeral");
        assertThat(evicted).isTrue();
        assertThat(provider.activeSessionsCount()).isZero();

        // Evicting null or non-existent task returns false
        assertThat(provider.evict(null)).isFalse();
        assertThat(provider.evict("non-existent")).isFalse();

        // Subsequent get creates a fresh new instance
        HierarchicalMemoryStore fresh = provider.get("task-ephemeral");
        assertThat(fresh).isNotSameAs(original);
        assertThat(fresh.messages()).isEmpty();
    }

    @Test
    @DisplayName("Given null task ID - when get - then throws NullPointerException")
    void givenNullTaskId_whenGet_thenThrowsNullPointerException() {
        HierarchicalChatMemoryProvider provider = new HierarchicalChatMemoryProvider();

        assertThatThrownBy(() -> provider.get(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("memoryId cannot be null");
    }

    @Test
    @DisplayName("Given active sessions - when clearAll - then all sessions are purged")
    void givenActiveSessions_whenClearAll_thenAllSessionsPurged() {
        HierarchicalChatMemoryProvider provider = new HierarchicalChatMemoryProvider();
        provider.get("task-1");
        provider.get("task-2");
        provider.get("task-3");
        assertThat(provider.activeSessionsCount()).isEqualTo(3);

        provider.clearAll();
        assertThat(provider.activeSessionsCount()).isZero();
    }

    @Test
    @DisplayName("Given concurrent threads - when accessing registry concurrently - then operates thread-safely without race conditions")
    void givenConcurrentThreads_whenAccessingRegistry_thenOperatesThreadSafely() throws Exception {
        HierarchicalChatMemoryProvider provider = new HierarchicalChatMemoryProvider();
        int threadCount = 30;
        int operationsPerThread = 50;
        CountDownLatch startLatch = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                final String taskId = "concurrent-task-" + (t % 5);
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < operationsPerThread; i++) {
                            HierarchicalMemoryStore store = provider.get(taskId);
                            store.add(new UserMessage("Prompt " + i));
                            store.add(new AiMessage("Response " + i));
                            List<ChatMessage> snapshot = store.messages();
                            assertThat(snapshot).isNotEmpty();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }));
            }

            startLatch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(provider.activeSessionsCount()).isEqualTo(5);
        for (int i = 0; i < 5; i++) {
            HierarchicalMemoryStore store = provider.get("concurrent-task-" + i);
            assertThat(store.assertTurnStructureInvariants()).isTrue();
        }
    }
}
