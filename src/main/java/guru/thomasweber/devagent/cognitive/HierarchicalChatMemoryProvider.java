package guru.thomasweber.devagent.cognitive;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Multi-tenant ChatMemoryProvider managing task-isolated HierarchicalMemoryStore instances.
 * Automatically discovered by Quarkus-LangChain4j via BeanChatMemoryProviderSupplier.
 */
@ApplicationScoped
public class HierarchicalChatMemoryProvider implements ChatMemoryProvider {

    private final ConcurrentMap<Object, HierarchicalMemoryStore> stores = new ConcurrentHashMap<>();

    @Override
    public HierarchicalMemoryStore get(Object memoryId) {
        Objects.requireNonNull(memoryId, "memoryId cannot be null");
        return stores.computeIfAbsent(memoryId, HierarchicalMemoryStore::new);
    }

    public boolean evict(Object memoryId) {
        if (memoryId == null) {
            return false;
        }
        return stores.remove(memoryId) != null;
    }

    public int activeSessionsCount() {
        return stores.size();
    }

    public void clearAll() {
        stores.clear();
    }
}
