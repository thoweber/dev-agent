package guru.thomasweber.devagent.testutil;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import guru.thomasweber.devagent.cognitive.HierarchicalMemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static guru.thomasweber.devagent.testutil.HierarchicalMemoryAssert.assertThatMemory;
import static guru.thomasweber.devagent.testutil.HierarchicalMemoryAssert.assertThatMessages;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HierarchicalMemoryAssertTest {

    @Test
    @DisplayName("Given null store or message list - when satisfiesTurnStructureInvariants - then fails with AssertionError")
    void givenNullMessages_whenSatisfiesTurnStructureInvariants_thenFails() {
        HierarchicalMemoryAssert nullMessagesAssertion = assertThatMessages(null);
        assertThatThrownBy(nullMessagesAssertion::satisfiesTurnStructureInvariants)
            .isInstanceOf(AssertionError.class);

        HierarchicalMemoryAssert nullMemoryAssertion = assertThatMemory(null);
        assertThatThrownBy(nullMemoryAssertion::satisfiesTurnStructureInvariants)
            .isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("Given empty messages - when satisfiesTurnStructureInvariants - then passes cleanly")
    void givenEmptyMessages_whenSatisfiesTurnStructureInvariants_thenPassesCleanly() {
        assertThatMessages(Collections.emptyList()).satisfiesTurnStructureInvariants();

        HierarchicalMemoryStore store = new HierarchicalMemoryStore();
        assertThatMemory(store).satisfiesTurnStructureInvariants();
    }

    @Test
    @DisplayName("Given valid pinned invariants and conversation turns - when satisfiesTurnStructureInvariants - then passes cleanly")
    void givenValidPinnedInvariantsAndTurns_whenSatisfiesTurnStructureInvariants_thenPassesCleanly() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
            .id("call-1")
            .name("compile")
            .arguments("{}")
            .build();

        List<ChatMessage> messages = List.of(
            new SystemMessage("Pinned rule 1"),
            new SystemMessage("Pinned rule 2"),
            new UserMessage("Task story"),
            new AiMessage(List.of(req)),
            new ToolExecutionResultMessage("call-1", "compile", "SUCCESS"));

        assertThatMessages(messages).satisfiesTurnStructureInvariants();
    }

    @Test
    @DisplayName("Given scratchpad containing misplaced SystemMessage - when satisfiesTurnStructureInvariants - then throws AssertionError")
    void givenScratchpadContainingMisplacedSystemMessage_whenSatisfiesTurnStructureInvariants_thenFails() {
        List<ChatMessage> messages = List.of(
            new UserMessage("Task story"),
            new SystemMessage("Misplaced system invariant inside scratchpad"));
        HierarchicalMemoryAssert assertion = assertThatMessages(messages);

        assertThatThrownBy(assertion::satisfiesTurnStructureInvariants)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Expected no SystemMessage in scratchpad");
    }

    @Test
    @DisplayName("Given scratchpad starting with ToolExecutionResultMessage - when satisfiesTurnStructureInvariants - then throws AssertionError")
    void givenScratchpadStartingWithToolExecutionResult_whenSatisfiesTurnStructureInvariants_thenFails() {
        List<ChatMessage> messages = List.of(
            new ToolExecutionResultMessage("call-orphan", "tool", "output"));
        HierarchicalMemoryAssert assertion = assertThatMessages(messages);

        assertThatThrownBy(assertion::satisfiesTurnStructureInvariants)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Scratchpad must not begin with a ToolExecutionResultMessage");
    }

    @Test
    @DisplayName("Given orphaned ToolExecutionResultMessage without preceding AiMessage - when satisfiesTurnStructureInvariants - then throws AssertionError")
    void givenOrphanedToolExecutionResultMessage_whenSatisfiesTurnStructureInvariants_thenFails() {
        List<ChatMessage> messages = List.of(
            new UserMessage("Generate code"),
            new ToolExecutionResultMessage("unmatched-call", "tool", "error"));
        HierarchicalMemoryAssert assertion = assertThatMessages(messages);

        assertThatThrownBy(assertion::satisfiesTurnStructureInvariants)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("has no matching preceding AiMessage request");
    }

    @Test
    @DisplayName("Given ToolExecutionResultMessage with mismatched request ID - when satisfiesTurnStructureInvariants - then throws AssertionError")
    void givenToolExecutionResultMessageWithMismatchedId_whenSatisfiesTurnStructureInvariants_thenFails() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
            .id("call-alpha")
            .name("test")
            .arguments("{}")
            .build();

        List<ChatMessage> messages = List.of(
            new UserMessage("Run test"),
            new AiMessage(List.of(req)),
            new ToolExecutionResultMessage("call-beta", "test", "result"));
        HierarchicalMemoryAssert assertion = assertThatMessages(messages);

        assertThatThrownBy(assertion::satisfiesTurnStructureInvariants)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("has no matching preceding AiMessage request");
    }
}
