package org.quarkus.agent.cognitive;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HierarchicalMemoryStoreTest {

    @Test
    @DisplayName("Given 3-tier memory store - when messages added beyond capacity - then evicts scratchpad but preserves pinned tiers")
    void testHierarchicalEvictionStrategy() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.pinInvariants("Rules: Quarkus Arc CDI Scoping Invariants");
        memory.pinTaskSpecification("US-042: Add OAuth2 Endpoint", "{\"steps\":[\"step1\"]}");

        // Add 10 successive tool/scratchpad conversation turns
        for (int i = 1; i <= 10; i++) {
            memory.add(new UserMessage("Tool execution failure " + i));
            memory.add(new AiMessage("Fix attempt " + i));
        }

        List<ChatMessage> messages = memory.messages();

        // Max messages: 1 System (Invariants) + 1 User (Task Spec) + 4 Scratchpad turns = 6 messages
        assertThat(messages).hasSize(6);

        // Verify Tier 1 and Tier 2 remain strictly at indices 0 and 1
        assertThat(((dev.langchain4j.data.message.SystemMessage) messages.get(0)).text()).contains("Quarkus Arc CDI Scoping Invariants");
        assertThat(((dev.langchain4j.data.message.UserMessage) messages.get(1)).singleText()).contains("US-042: Add OAuth2 Endpoint");

        // Verify the newest scratchpad messages are preserved (Turn 10)
        assertThat(((dev.langchain4j.data.message.AiMessage) messages.get(5)).text()).contains("Fix attempt 10");
    }

    @Test
    @DisplayName("Given memory store - when id and clear called - behaves as expected")
    void testStoreIdAndClear() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        assertThat(memory.id()).isEqualTo("hierarchical-memory");

        memory.add(new UserMessage("Scratchpad message"));
        assertThat(memory.messages()).hasSize(1);

        memory.clear();
        assertThat(memory.messages()).isEmpty();
    }

    @Test
    @DisplayName("Given system message added directly - then updates pinned invariants")
    void testDirectSystemMessageUpdate() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.add(new dev.langchain4j.data.message.SystemMessage("Direct System Invariant"));

        assertThat(memory.messages()).hasSize(1);
        assertThat(((dev.langchain4j.data.message.SystemMessage) memory.messages().get(0)).text())
            .isEqualTo("Direct System Invariant");
    }

    @Test
    @DisplayName("Given blank or null inputs to pin methods - handles gracefully")
    void testPinNullAndBlankInputs() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.pinInvariants(null);
        memory.pinInvariants("   ");
        assertThat(memory.messages()).isEmpty();
    }

    @Test
    @DisplayName("Given pinned invariants - when SystemMessage added via add() - does not overwrite pinned invariants")
    void testAddSystemMessageDoesNotOverwritePinnedInvariants() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.pinInvariants("Pinned Invariants");
        memory.add(new dev.langchain4j.data.message.SystemMessage("Secondary System Instructions"));

        List<ChatMessage> messages = memory.messages();
        assertThat(messages).hasSize(2);
        assertThat(((dev.langchain4j.data.message.SystemMessage) messages.get(0)).text()).isEqualTo("Pinned Invariants");
        assertThat(((dev.langchain4j.data.message.SystemMessage) messages.get(1)).text()).isEqualTo("Secondary System Instructions");
    }

    @Test
    @DisplayName("Given tool request and execution result - when scratchpad trimmed - evicts tool result with request")
    void testAtomicToolCallAndResultEviction() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();

        dev.langchain4j.agent.tool.ToolExecutionRequest toolRequest = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
            .id("call-1")
            .name("run_test")
            .arguments("{}")
            .build();
        AiMessage aiWithTool = AiMessage.from(toolRequest);
        dev.langchain4j.data.message.ToolExecutionResultMessage toolResult = dev.langchain4j.data.message.ToolExecutionResultMessage.from(toolRequest, "Tests failed");

        memory.add(aiWithTool);
        memory.add(toolResult);
        memory.add(new UserMessage("Next step 1"));
        memory.add(new AiMessage("Response 1"));
        memory.add(new UserMessage("Next step 2"));
        memory.add(new AiMessage("Response 2")); // Triggers eviction of tool request and dependent tool result!

        List<ChatMessage> messages = memory.messages();
        // Tool result should not be orphaned
        assertThat(messages).noneMatch(m -> m instanceof dev.langchain4j.data.message.ToolExecutionResultMessage);
    }

    @Test
    @DisplayName("Given multiple tool execution results - does not self-evict during addition and drops orphaned results")
    void testMultipleToolResultsAndOrphanRejection() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();

        dev.langchain4j.agent.tool.ToolExecutionRequest req1 = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("c1").name("t1").arguments("{}").build();
        dev.langchain4j.agent.tool.ToolExecutionRequest req2 = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("c2").name("t2").arguments("{}").build();
        dev.langchain4j.agent.tool.ToolExecutionRequest req3 = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("c3").name("t3").arguments("{}").build();
        dev.langchain4j.agent.tool.ToolExecutionRequest req4 = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("c4").name("t4").arguments("{}").build();

        AiMessage aiWith4Tools = AiMessage.from(List.of(req1, req2, req3, req4));
        memory.add(aiWith4Tools);
        memory.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(req1, "r1"));
        memory.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(req2, "r2"));
        memory.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(req3, "r3"));
        memory.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(req4, "r4"));

        // All 4 results and the parent AiMessage should still be present (not wiped out by self-eviction)
        assertThat(memory.messages()).hasSize(5);
        assertThat(memory.messages().get(0)).isEqualTo(aiWith4Tools);

        // Orphaned result without matching request in scratchpad must be dropped
        dev.langchain4j.agent.tool.ToolExecutionRequest unknownReq = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("c99").name("t99").arguments("{}").build();
        memory.add(dev.langchain4j.data.message.ToolExecutionResultMessage.from(unknownReq, "orphan"));
        assertThat(memory.messages()).hasSize(5);
    }

    @Test
    @DisplayName("Given memory with all tiers - when clear called - removes all tiers; clearScratchpad only clears scratchpad")
    void testClearAllTiersVsScratchpad() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.pinInvariants("Invariants");
        memory.pinTaskSpecification("Story", "{}");
        memory.add(new UserMessage("Scratchpad"));

        assertThat(memory.messages()).hasSize(3);

        memory.clearScratchpad();
        assertThat(memory.messages()).hasSize(2);

        memory.clear();
        assertThat(memory.messages()).isEmpty();
    }
}
