package guru.thomasweber.devagent.testutil;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import guru.thomasweber.devagent.cognitive.HierarchicalMemoryStore;
import org.assertj.core.api.AbstractAssert;

import java.util.List;
import java.util.Objects;

/**
 * Fluent AssertJ custom assertion for black-box verification of HierarchicalMemoryStore invariants.
 */
public class HierarchicalMemoryAssert extends AbstractAssert<HierarchicalMemoryAssert, List<ChatMessage>> {

    protected HierarchicalMemoryAssert(List<ChatMessage> actual) {
        super(actual, HierarchicalMemoryAssert.class);
    }

    public static HierarchicalMemoryAssert assertThatMemory(HierarchicalMemoryStore store) {
        return new HierarchicalMemoryAssert(store != null ? store.messages() : null);
    }

    public static HierarchicalMemoryAssert assertThatMessages(List<ChatMessage> messages) {
        return new HierarchicalMemoryAssert(messages);
    }

    public HierarchicalMemoryAssert satisfiesTurnStructureInvariants() {
        isNotNull();

        // 1. All SystemMessages must be contiguous at the start (Tier 1 pinned invariants)
        int index = 0;
        while (index < actual.size() && actual.get(index) instanceof SystemMessage) {
            index++;
        }

        List<ChatMessage> remainder = actual.subList(index, actual.size());

        // 2. Remainder must not contain any SystemMessage
        for (int i = 0; i < remainder.size(); i++) {
            if (remainder.get(i) instanceof SystemMessage) {
                failWithMessage("Expected no SystemMessage in scratchpad, but found one at index <%s>.", index + i);
            }
        }

        // 3. Remainder must not start with ToolExecutionResultMessage
        if (!remainder.isEmpty() && remainder.getFirst() instanceof ToolExecutionResultMessage) {
            failWithMessage("Scratchpad must not begin with a ToolExecutionResultMessage.");
        }

        // 4. Every ToolExecutionResultMessage must have a preceding AiMessage with matching request id
        for (int i = 0; i < remainder.size(); i++) {
            if (remainder.get(i) instanceof ToolExecutionResultMessage toolResult
                && !hasPrecedingRequest(remainder, toolResult.id(), i)) {
                failWithMessage(
                    "ToolExecutionResultMessage with ID <%s> at index <%s> has no matching preceding AiMessage request.",
                    toolResult.id(), index + i);
            }
        }

        return this;
    }

    private static boolean hasPrecedingRequest(List<ChatMessage> list, String toolResultId, int currentIndex) {
        for (int j = 0; j < currentIndex; j++) {
            if (list.get(j) instanceof AiMessage ai
                && ai.hasToolExecutionRequests()
                && hasMatchingId(ai, toolResultId)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMatchingId(AiMessage ai, String resultId) {
        for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
            if (Objects.equals(req.id(), resultId)) {
                return true;
            }
        }
        return false;
    }
}
