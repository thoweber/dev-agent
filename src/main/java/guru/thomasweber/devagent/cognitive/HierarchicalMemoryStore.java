package guru.thomasweber.devagent.cognitive;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import guru.thomasweber.devagent.model.SessionId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 3-Tier Hierarchical Memory store implementing LangChain4j {@link ChatMemory}.
 *
 * <p>Each instance is explicitly tied to a {@link SessionId} at construction and operated sequentially
 * by its session thread. Multi-tenant isolation across tasks is governed by {@link HierarchicalChatMemoryProvider}.
 *
 * <ul>
 *   <li><b>Tier 1 (Pinned Invariants):</b> Preserved system rules and conventions.</li>
 *   <li><b>Tier 2 (Pinned Task Spec):</b> Preserved user story and approved plan.</li>
 *   <li><b>Tier 3 (Sliding Scratchpad):</b> Bounded conversational turns, atomically evicting tool calls with results.</li>
 * </ul>
 *
 * <p>Internal state tiers use lock-free concurrent primitives ({@link CopyOnWriteArrayList},
 * {@link ConcurrentLinkedDeque}, and {@link AtomicReference}) for memory visibility across threads
 * without monitor locking.
 */
public class HierarchicalMemoryStore implements ChatMemory {

    private static final int MAX_SCRATCHPAD_TURNS = 4;
    private static final int MAX_SCRATCHPAD_MESSAGES = 24;

    private final SessionId sessionId;
    private final List<SystemMessage> pinnedInvariants = new CopyOnWriteArrayList<>();
    private final AtomicReference<UserMessage> pinnedTaskSpec = new AtomicReference<>();
    private final Deque<ChatMessage> slidingScratchpad = new ConcurrentLinkedDeque<>();

    public HierarchicalMemoryStore(SessionId sessionId) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId cannot be null");
    }

    @Override
    public Object id() {
        return sessionId.value();
    }

    public SessionId sessionId() {
        return sessionId;
    }

    public void pinInvariants(String invariants) {
        if (invariants != null && !invariants.isBlank()) {
            this.pinnedInvariants.clear();
            this.pinnedInvariants.add(new SystemMessage(invariants));
        }
    }

    public void pinTaskSpecification(String story, String planJson) {
        String content = String.format("### TASK SPECIFICATION%n%s%n%n### APPROVED IMPLEMENTATION PLAN%n%s",
            story != null ? story : "No story specified",
            planJson != null ? planJson : "{}");
        this.pinnedTaskSpec.set(new UserMessage(content));
    }

    @Override
    public void add(ChatMessage message) {
        if (message instanceof SystemMessage systemMessage) {
            boolean alreadyPresent = pinnedInvariants.stream()
                .anyMatch(sm -> Objects.equals(sm.text(), systemMessage.text()));
            if (!alreadyPresent) {
                this.pinnedInvariants.add(systemMessage);
            }
            return;
        }

        // Drop orphaned tool execution results whose request is not present
        if (message instanceof ToolExecutionResultMessage toolResult && !hasMatchingRequest(toolResult)) {
            return;
        }

        slidingScratchpad.add(message);
        trimScratchpad();
    }

    private boolean hasMatchingRequest(ToolExecutionResultMessage result) {
        for (ChatMessage msg : slidingScratchpad) {
            if (msg instanceof AiMessage ai && ai.hasToolExecutionRequests() && hasMatchingId(ai, result.id())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMatchingId(AiMessage ai, String resultId) {
        for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
            if (Objects.equals(req.id(), resultId)) {
                return true;
            }
        }
        return false;
    }

    private void trimScratchpad() {
        while (countTurns() > MAX_SCRATCHPAD_TURNS) {
            evictOldestTurn();
        }
        while (countTurns() > 1 && slidingScratchpad.size() > MAX_SCRATCHPAD_MESSAGES) {
            evictOldestTurn();
        }
    }

    private int countTurns() {
        int count = 0;
        ChatMessage first = slidingScratchpad.peekFirst();
        if (first instanceof AiMessage) {
            count++;
        }
        for (ChatMessage msg : slidingScratchpad) {
            if (msg instanceof UserMessage) {
                count++;
            }
        }
        return count;
    }

    private void evictOldestTurn() {
        slidingScratchpad.pollFirst();
        evictUntilNextUserMessage();
    }

    private void evictUntilNextUserMessage() {
        while (!(slidingScratchpad.peekFirst() instanceof UserMessage)) {
            ChatMessage next = slidingScratchpad.pollFirst();
            if (next instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                purgeLeadingToolResults();
            }
        }
    }

    private void purgeLeadingToolResults() {
        while (slidingScratchpad.peekFirst() instanceof ToolExecutionResultMessage) {
            slidingScratchpad.pollFirst();
        }
    }

    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> assembled = new ArrayList<>(pinnedInvariants);
        UserMessage spec = this.pinnedTaskSpec.get();
        if (spec != null) {
            assembled.add(spec);
        }
        assembled.addAll(slidingScratchpad);
        return Collections.unmodifiableList(assembled);
    }

    @Override
    public void clear() {
        pinnedInvariants.clear();
        pinnedTaskSpec.set(null);
        slidingScratchpad.clear();
    }

    public void clearScratchpad() {
        slidingScratchpad.clear();
    }
}
