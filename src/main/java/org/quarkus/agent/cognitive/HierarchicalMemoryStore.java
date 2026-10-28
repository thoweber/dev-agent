package org.quarkus.agent.cognitive;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 3-Tier Hierarchical Memory store implementing LangChain4j ChatMemory.
 * Tier 1 (Pinned Invariants) and Tier 2 (Pinned Task Spec) are preserved across sliding iterations.
 * Tier 3 (Sliding Scratchpad) maintains a bounded window of recent compiler/test interactions,
 * evicting tool execution requests and their corresponding results atomically as a single unit.
 */
@ApplicationScoped
public class HierarchicalMemoryStore implements ChatMemory {

    private final List<SystemMessage> pinnedInvariants = new ArrayList<>();
    private UserMessage pinnedTaskSpec;
    private final List<ChatMessage> slidingScratchpad = new LinkedList<>();
    private static final int MAX_SCRATCHPAD_TURNS = 4;
    private static final int MAX_SCRATCHPAD_MESSAGES = 24;

    @Override
    public Object id() {
        return "hierarchical-memory";
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
        this.pinnedTaskSpec = new UserMessage(content);
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
        while (slidingScratchpad.size() > MAX_SCRATCHPAD_MESSAGES && canTrimOldestToolInteraction()) {
            trimOldestToolInteraction();
        }
    }

    int findOldestTrimmableToolInteractionIndex() {
        if (slidingScratchpad.isEmpty()) {
            return -1;
        }
        int startIndex = (slidingScratchpad.getFirst() instanceof UserMessage) ? 1 : 0;
        int toolAiIndex = -1;
        for (int i = startIndex; i < slidingScratchpad.size(); i++) {
            if (slidingScratchpad.get(i) instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                toolAiIndex = i;
                break;
            }
        }
        if (toolAiIndex == -1) {
            return -1;
        }
        for (int i = toolAiIndex + 1; i < slidingScratchpad.size(); i++) {
            if (slidingScratchpad.get(i) instanceof AiMessage) {
                return toolAiIndex;
            }
        }
        return -1;
    }

    boolean canTrimOldestToolInteraction() {
        return findOldestTrimmableToolInteractionIndex() != -1;
    }

    void trimOldestToolInteraction() {
        int targetIndex = findOldestTrimmableToolInteractionIndex();
        if (targetIndex == -1) {
            return;
        }
        AiMessage ai = (AiMessage) slidingScratchpad.get(targetIndex);
        Set<String> requestIds = ai.toolExecutionRequests().stream()
            .map(ToolExecutionRequest::id)
            .collect(Collectors.toSet());
        slidingScratchpad.remove(targetIndex);
        while (targetIndex < slidingScratchpad.size()) {
            ChatMessage next = slidingScratchpad.get(targetIndex);
            if (next instanceof ToolExecutionResultMessage toolResult && requestIds.contains(toolResult.id())) {
                slidingScratchpad.remove(targetIndex);
            } else {
                break;
            }
        }
    }

    private int countTurns() {
        if (slidingScratchpad.isEmpty()) {
            return 0;
        }
        int count = 0;
        if (slidingScratchpad.getFirst() instanceof AiMessage) {
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
        if (slidingScratchpad.isEmpty()) {
            return;
        }
        ChatMessage first = slidingScratchpad.removeFirst();
        if (first instanceof UserMessage) {
            evictUntilNextUserMessage();
        } else if (first instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
            purgeLeadingToolResults();
        }
    }

    private void evictUntilNextUserMessage() {
        while (!slidingScratchpad.isEmpty() && !(slidingScratchpad.getFirst() instanceof UserMessage)) {
            ChatMessage next = slidingScratchpad.removeFirst();
            if (next instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                purgeLeadingToolResults();
            }
        }
    }

    private void purgeLeadingToolResults() {
        while (!slidingScratchpad.isEmpty() && slidingScratchpad.getFirst() instanceof ToolExecutionResultMessage) {
            slidingScratchpad.removeFirst();
        }
    }

    public boolean assertTurnStructureInvariants() {
        if (containsSystemMessage() || startsWithToolExecutionResult()) {
            return false;
        }
        return allToolResultsHaveMatchingRequests();
    }

    private boolean containsSystemMessage() {
        for (ChatMessage msg : slidingScratchpad) {
            if (msg instanceof SystemMessage) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWithToolExecutionResult() {
        return !slidingScratchpad.isEmpty() && slidingScratchpad.getFirst() instanceof ToolExecutionResultMessage;
    }

    private boolean allToolResultsHaveMatchingRequests() {
        for (int i = 0; i < slidingScratchpad.size(); i++) {
            if (slidingScratchpad.get(i) instanceof ToolExecutionResultMessage toolResult
                && !hasPrecedingRequest(toolResult.id(), i)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasPrecedingRequest(String toolResultId, int currentIndex) {
        for (int j = 0; j < currentIndex; j++) {
            if (slidingScratchpad.get(j) instanceof AiMessage ai
                && ai.hasToolExecutionRequests()
                && hasMatchingId(ai, toolResultId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> assembled = new ArrayList<>(pinnedInvariants);
        if (pinnedTaskSpec != null) {
            assembled.add(pinnedTaskSpec);
        }
        assembled.addAll(slidingScratchpad);
        return Collections.unmodifiableList(assembled);
    }

    @Override
    public void clear() {
        pinnedInvariants.clear();
        pinnedTaskSpec = null;
        slidingScratchpad.clear();
    }

    public void clearScratchpad() {
        slidingScratchpad.clear();
    }
}
