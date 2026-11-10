package guru.thomasweber.devagent.cognitive;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import guru.thomasweber.devagent.model.SessionId;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Multi-tenant {@link ChatMemoryProvider} managing session-tied {@link HierarchicalMemoryStore} instances.
 * Automatically discovered by Quarkus-LangChain4j via {@code BeanChatMemoryProviderSupplier}.
 */
@ApplicationScoped
public class HierarchicalChatMemoryProvider implements ChatMemoryProvider {

    private final ConcurrentMap<SessionId, HierarchicalMemoryStore> stores = new ConcurrentHashMap<>();

    @Override
    public HierarchicalMemoryStore get(Object memoryId) {
        Objects.requireNonNull(memoryId, "memoryId cannot be null");
        return get(SessionId.from(memoryId));
    }

    public HierarchicalMemoryStore get(SessionId sessionId) {
        Objects.requireNonNull(sessionId, "sessionId cannot be null");
        return stores.computeIfAbsent(sessionId, HierarchicalMemoryStore::new);
    }

    public boolean evict(Object memoryId) {
        if (memoryId == null) {
            return false;
        }
        return evict(SessionId.from(memoryId));
    }

    public boolean evict(SessionId sessionId) {
        if (sessionId == null) {
            return false;
        }
        return stores.remove(sessionId) != null;
    }

    public int activeSessionsCount() {
        return stores.size();
    }

    public void clearAll() {
        stores.clear();
    }
}
