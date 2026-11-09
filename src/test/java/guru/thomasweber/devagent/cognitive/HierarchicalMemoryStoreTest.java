package guru.thomasweber.devagent.cognitive;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
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

import static guru.thomasweber.devagent.testutil.HierarchicalMemoryAssert.assertThatMemory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        // Max messages: 1 System (Invariants) + 1 User (Task Spec) + 4 Scratchpad turns (4 * 2 = 8) = 10 messages
        assertThat(messages).hasSize(10);

        // Verify Tier 1 and Tier 2 remain strictly at indices 0 and 1
        assertThat(((SystemMessage) messages.get(0)).text()).contains("Quarkus Arc CDI Scoping Invariants");
        assertThat(((UserMessage) messages.get(1)).singleText()).contains("US-042: Add OAuth2 Endpoint");

        // Oldest scratchpad message should be Turn 7
        assertThat(((UserMessage) messages.get(2)).singleText()).contains("Tool execution failure 7");
        // Verify the newest scratchpad messages are preserved (Turn 10)
        assertThat(((AiMessage) messages.get(9)).text()).contains("Fix attempt 10");
    }

    @Test
    @DisplayName("Given memory store - when id and clear called - then behaves as expected")
    void testStoreIdAndClear() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        assertThat(memory.id()).isEqualTo("hierarchical-memory");

        memory.add(new UserMessage("Scratchpad message"));
        assertThat(memory.messages()).hasSize(1);

        memory.clear();
        assertThat(memory.messages()).isEmpty();
    }

    @Test
    @DisplayName("Given system message - when added directly - then updates pinned invariants")
    void testDirectSystemMessageUpdate() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.add(new SystemMessage("Direct System Invariant"));

        assertThat(memory.messages()).hasSize(1);
        assertThat(((SystemMessage) memory.messages().get(0)).text())
            .isEqualTo("Direct System Invariant");
    }

    @Test
    @DisplayName("Given blank or null inputs to pin methods - when passed - then handles gracefully")
    void testPinNullAndBlankInputs() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.pinInvariants(null);
        memory.pinInvariants("   ");
        assertThat(memory.messages()).isEmpty();
    }

    @Test
    @DisplayName("Given pinned invariants - when SystemMessage added via add() - then does not overwrite pinned invariants")
    void testAddSystemMessageDoesNotOverwritePinnedInvariants() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.pinInvariants("Pinned Invariants");
        memory.add(new SystemMessage("Secondary System Instructions"));

        List<ChatMessage> messages = memory.messages();
        assertThat(messages).hasSize(2);
        assertThat(((SystemMessage) messages.get(0)).text()).isEqualTo("Pinned Invariants");
        assertThat(((SystemMessage) messages.get(1)).text()).isEqualTo("Secondary System Instructions");
    }

    @Test
    @DisplayName("Given tool request and execution result - when scratchpad trimmed - then evicts tool result with request")
    void testAtomicToolCallAndResultEviction() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();

        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
            .id("call-1")
            .name("run_test")
            .arguments("{}")
            .build();
        AiMessage aiWithTool = AiMessage.from(toolRequest);
        ToolExecutionResultMessage toolResult = ToolExecutionResultMessage.from(toolRequest, "Tests failed");

        memory.add(aiWithTool);
        memory.add(toolResult);
        memory.add(new UserMessage("Next step 1"));
        memory.add(new AiMessage("Response 1"));
        memory.add(new UserMessage("Next step 2"));
        memory.add(new AiMessage("Response 2"));
        memory.add(new UserMessage("Next step 3"));
        memory.add(new AiMessage("Response 3"));
        memory.add(new UserMessage("Next step 4"));
        memory.add(new AiMessage("Response 4")); // Exceeds 4 turns; triggers eviction of initial tool request and result!

        List<ChatMessage> messages = memory.messages();
        // Tool result should not be orphaned
        assertThat(messages).noneMatch(ToolExecutionResultMessage.class::isInstance);
    }

    @Test
    @DisplayName("Given multiple tool execution results - when added - then does not self-evict during addition and drops orphaned results")
    void testMultipleToolResultsAndOrphanRejection() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();

        ToolExecutionRequest req1 = ToolExecutionRequest.builder().id("c1").name("t1").arguments("{}").build();
        ToolExecutionRequest req2 = ToolExecutionRequest.builder().id("c2").name("t2").arguments("{}").build();
        ToolExecutionRequest req3 = ToolExecutionRequest.builder().id("c3").name("t3").arguments("{}").build();
        ToolExecutionRequest req4 = ToolExecutionRequest.builder().id("c4").name("t4").arguments("{}").build();

        AiMessage aiWith4Tools = AiMessage.from(List.of(req1, req2, req3, req4));
        memory.add(aiWith4Tools);
        memory.add(ToolExecutionResultMessage.from(req1, "r1"));
        memory.add(ToolExecutionResultMessage.from(req2, "r2"));
        memory.add(ToolExecutionResultMessage.from(req3, "r3"));
        memory.add(ToolExecutionResultMessage.from(req4, "r4"));

        // All 4 results and the parent AiMessage should still be present (not wiped out by self-eviction)
        assertThat(memory.messages()).hasSize(5);
        assertThat(memory.messages().get(0)).isEqualTo(aiWith4Tools);

        // Orphaned result without matching request in scratchpad must be dropped
        ToolExecutionRequest unknownReq = ToolExecutionRequest.builder().id("c99").name("t99").arguments("{}").build();
        memory.add(ToolExecutionResultMessage.from(unknownReq, "orphan"));
        assertThat(memory.messages()).hasSize(5);
    }

    @Test
    @DisplayName("Given memory with all tiers - when clear called - then removes all tiers")
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

    @Test
    @DisplayName("Given conversation turns - when scratchpad trimmed - then evicts user message and ai response together atomically")
    void testAtomicUserAndAiTurnEviction() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();

        memory.add(new UserMessage("Question 1"));
        memory.add(new AiMessage("Answer 1"));
        memory.add(new UserMessage("Question 2"));
        memory.add(new AiMessage("Answer 2"));
        memory.add(new UserMessage("Question 3"));
        memory.add(new AiMessage("Answer 3"));
        memory.add(new UserMessage("Question 4"));
        memory.add(new AiMessage("Answer 4"));

        // When 5th turn is added, turn 1 (Question 1 + Answer 1) must be evicted atomically together
        memory.add(new UserMessage("Question 5"));

        List<ChatMessage> messages = memory.messages();
        // Scratchpad must NOT begin with Answer 1 (AiMessage); it begins with Question 2
        assertThat(messages.get(0)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) messages.get(0)).singleText()).isEqualTo("Question 2");
    }

    @Test
    @DisplayName("Given duplicate SystemMessage via add() - when added - then ignores duplicates and does not grow Tier 1")
    void testDuplicateSystemMessageIgnored() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.pinInvariants("Base Invariant");
        memory.add(new SystemMessage("Base Invariant"));
        memory.add(new SystemMessage("Base Invariant"));

        assertThat(memory.messages()).hasSize(1);
        assertThat(((SystemMessage) memory.messages().get(0)).text()).isEqualTo("Base Invariant");

        // Adding a different system message is accepted
        memory.add(new SystemMessage("Another Invariant"));
        assertThat(memory.messages()).hasSize(2);
    }

    @Test
    @DisplayName("Given secondary SystemMessage via add() - when added - then preserves in Tier 1 and keeps scratchpad free of SystemMessages")
    void testSystemMessageQuarantinedToTier1() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.pinInvariants("Primary Invariants");
        memory.pinTaskSpecification("Task Story", "{}");
        memory.add(new SystemMessage("Secondary Invariants"));
        memory.add(new UserMessage("Working turn"));

        List<ChatMessage> messages = memory.messages();
        // Tier 1 contains Primary & Secondary invariants, Tier 2 contains Task Story, Scratchpad contains UserMessage
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(1)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(2)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(3)).isInstanceOf(UserMessage.class);

        // When scratchpad is cleared, Tier 1 system messages must remain intact
        memory.clearScratchpad();
        assertThat(memory.messages()).hasSize(3);
        assertThat(memory.messages().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(memory.messages().get(1)).isInstanceOf(SystemMessage.class);
        assertThat(memory.messages().get(2)).isInstanceOf(UserMessage.class);
    }

    @Test
    @DisplayName("Given single active turn with many tool executions - when exceeding message budget - then preserves full turn in-flight without mid-turn severance")
    void testSingleTurnToolBudgetTrimming() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.add(new UserMessage("Root prompt for single long turn"));

        for (int i = 1; i <= 15; i++) {
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call-" + i)
                .name("tool_" + i)
                .arguments("{}")
                .build();
            memory.add(AiMessage.from(req));
            memory.add(ToolExecutionResultMessage.from(req, "Result " + i));
        }

        // 1 user message + 15 * 2 = 31 messages.
        // In a single turn, mid-turn trimming is prohibited by ADR-011; full in-flight turn is preserved
        assertThat(memory.messages()).hasSize(31);
        assertThat(memory.messages().getFirst()).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) memory.messages().getFirst()).singleText()).isEqualTo("Root prompt for single long turn");
        assertThatMemory(memory).satisfiesTurnStructureInvariants();

        // When a subsequent turn arrives exceeding budget, older turn is evicted atomically
        memory.add(new UserMessage("Turn 2"));
        assertThat(memory.messages()).hasSize(1);
        assertThat(((UserMessage) memory.messages().getFirst()).singleText()).isEqualTo("Turn 2");
        assertThatMemory(memory).satisfiesTurnStructureInvariants();

        // Edge case: when initial message is AiMessage with tool requests (no UserMessage at index 0)
        HierarchicalMemoryStore aiFirstMemory = new HierarchicalMemoryStore();
        for (int i = 1; i <= 15; i++) {
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call-ai-" + i)
                .name("tool_" + i)
                .arguments("{}")
                .build();
            aiFirstMemory.add(AiMessage.from(req));
            aiFirstMemory.add(ToolExecutionResultMessage.from(req, "Result " + i));
        }
        assertThat(aiFirstMemory.messages()).hasSize(30);
        assertThatMemory(aiFirstMemory).satisfiesTurnStructureInvariants();

        aiFirstMemory.add(new UserMessage("Subsequent turn"));
        assertThat(aiFirstMemory.messages()).hasSize(1);
        assertThat(aiFirstMemory.messages().getLast()).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) aiFirstMemory.messages().getLast()).singleText()).isEqualTo("Subsequent turn");
        assertThatMemory(aiFirstMemory).satisfiesTurnStructureInvariants();
    }

    @Test
    @DisplayName("Given single active turn with plain AiMessage before tool executions - when exceeding message budget - then preserves user prompt and thoughts in-flight")
    void testSingleTurnWithPlainAiMessageBeforeToolInteractionsTrimming() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.add(new UserMessage("Root prompt for single long turn"));
        memory.add(new AiMessage("Thinking about how to solve the problem..."));

        for (int i = 1; i <= 15; i++) {
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call-step-" + i)
                .name("tool_" + i)
                .arguments("{}")
                .build();
            memory.add(AiMessage.from(req));
            memory.add(ToolExecutionResultMessage.from(req, "Result " + i));
        }

        // 1 user message + 1 plain AI message + 15 * 2 = 32 messages preserved in single turn
        assertThat(memory.messages()).hasSize(32);
        assertThat(memory.messages().getFirst()).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) memory.messages().getFirst()).singleText()).isEqualTo("Root prompt for single long turn");
        assertThat(memory.messages().get(1)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) memory.messages().get(1)).text()).isEqualTo("Thinking about how to solve the problem...");
        assertThatMemory(memory).satisfiesTurnStructureInvariants();

        memory.add(new UserMessage("Next Turn"));
        assertThat(memory.messages()).hasSize(1);
        assertThat(((UserMessage) memory.messages().getFirst()).singleText()).isEqualTo("Next Turn");
        assertThatMemory(memory).satisfiesTurnStructureInvariants();
    }

    @Test
    @DisplayName("Given single turn with active in-flight tool call - when exceeding budget without later AiMessage - then preserves in-flight tool results")
    void testScratchpadExceedsBudgetWithInFlightToolCallPreserved() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        ToolExecutionRequest req = ToolExecutionRequest.builder()
            .id("call-solo")
            .name("tool")
            .arguments("{}")
            .build();

        memory.add(new UserMessage("Prompt"));
        memory.add(AiMessage.from(req));
        for (int i = 0; i < 26; i++) {
            memory.add(ToolExecutionResultMessage.from(req, "res-" + i));
        }

        // Total messages: 1 user + 1 AiMessage + 26 results = 28 messages (> 24).
        // Since no subsequent AiMessage has arrived, the tool interaction is active/in-flight and cannot be trimmed.
        assertThat(memory.messages()).hasSize(28);
        assertThatMemory(memory).satisfiesTurnStructureInvariants();
    }

    @Test
    @DisplayName("Given single turn with plain AI thoughts - when exceeding message budget without tool calls - then terminates trimming cleanly")
    void testSingleTurnPlainAiThoughtsExceedingBudget() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.add(new UserMessage("Analyze complex architecture"));
        for (int i = 1; i <= 26; i++) {
            memory.add(new AiMessage("Analysis step " + i));
        }

        // All 27 messages preserved in single turn because there are no tool interactions to trim
        assertThat(memory.messages()).hasSize(27);
        assertThat(memory.messages().getFirst()).isInstanceOf(UserMessage.class);
        assertThatMemory(memory).satisfiesTurnStructureInvariants();
    }

    @Test
    @DisplayName("Given multi-tool execution in older turn - when message budget exceeded - then evicts multi-tool request and all results atomically")
    void testMultiToolExecutionAtomicallyTrimmed() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.add(new UserMessage("Execute batch tasks"));

        // Step 1: AI requests 3 tools concurrently
        ToolExecutionRequest reqA = ToolExecutionRequest.builder().id("call-a").name("tA").arguments("{}").build();
        ToolExecutionRequest reqB = ToolExecutionRequest.builder().id("call-b").name("tB").arguments("{}").build();
        ToolExecutionRequest reqC = ToolExecutionRequest.builder().id("call-c").name("tC").arguments("{}").build();
        memory.add(AiMessage.from(List.of(reqA, reqB, reqC)));
        memory.add(ToolExecutionResultMessage.from(reqA, "resA"));
        memory.add(ToolExecutionResultMessage.from(reqB, "resB"));
        memory.add(ToolExecutionResultMessage.from(reqC, "resC"));

        // Turn 2: Sequential single tool executions exceeding budget
        memory.add(new UserMessage("Followup batch tasks"));
        for (int i = 2; i <= 12; i++) {
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("step-" + i)
                .name("tool")
                .arguments("{}")
                .build();
            memory.add(AiMessage.from(req));
            memory.add(ToolExecutionResultMessage.from(req, "res-" + i));
        }
        memory.add(new AiMessage("All batch steps completed successfully."));

        // Verifies Turn 1 was evicted atomically to respect budget
        assertThat(memory.messages().getFirst()).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) memory.messages().getFirst()).singleText()).isEqualTo("Followup batch tasks");
        // Confirms that multi-tool request and its 3 results (step 1) were atomically removed together
        assertThat(memory.messages()).noneMatch(m ->
            m instanceof ToolExecutionResultMessage tr &&
            (tr.id().equals("call-a") || tr.id().equals("call-b") || tr.id().equals("call-c"))
        );
        // And last AI completion message is preserved
        assertThat(memory.messages().getLast()).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) memory.messages().getLast()).text()).isEqualTo("All batch steps completed successfully.");
        assertThatMemory(memory).satisfiesTurnStructureInvariants();
    }

    @Test
    @DisplayName("Given multi-tool execution with intervening reasoning message in older turn - when message budget exceeded - then evicts all tool results and thoughts atomically")
    void testMultiToolExecutionWithInterveningMessageTrimmed() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.add(new UserMessage("Batch processing"));

        // AI requests two tools: call-inter-1 and call-inter-2
        ToolExecutionRequest req1 = ToolExecutionRequest.builder()
            .id("call-inter-1").name("tool1").arguments("{}").build();
        ToolExecutionRequest req2 = ToolExecutionRequest.builder()
            .id("call-inter-2").name("tool2").arguments("{}").build();
        memory.add(AiMessage.from(List.of(req1, req2)));
        memory.add(ToolExecutionResultMessage.from(req1, "res1"));
        // Intervening AI message between tool results
        memory.add(new AiMessage("Processing intermediate step..."));
        memory.add(ToolExecutionResultMessage.from(req2, "res2"));

        // Turn 2 exceeding budget
        memory.add(new UserMessage("Next batch"));
        for (int i = 3; i <= 14; i++) {
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("step-inter-" + i)
                .name("tool")
                .arguments("{}")
                .build();
            memory.add(AiMessage.from(req));
            memory.add(ToolExecutionResultMessage.from(req, "res-" + i));
        }
        memory.add(new AiMessage("All steps done."));

        assertThat(memory.messages().getFirst()).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) memory.messages().getFirst()).singleText()).isEqualTo("Next batch");
        assertThat(memory.messages()).noneMatch(m ->
            m instanceof ToolExecutionResultMessage tr &&
            (tr.id().equals("call-inter-1") || tr.id().equals("call-inter-2"))
        );
        assertThatMemory(memory).satisfiesTurnStructureInvariants();
    }

    @Test
    @DisplayName("Given conversation starting with AiMessage followed by tool interactions - when new turn exceeds budget - then evicts cleanly")
    void testAiMessageStartingConversationWithToolTrimming() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        for (int i = 1; i <= 15; i++) {
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("ai-init-" + i)
                .name("tool")
                .arguments("{}")
                .build();
            memory.add(AiMessage.from(req));
            memory.add(ToolExecutionResultMessage.from(req, "result-" + i));
        }
        memory.add(new AiMessage("Final resolution"));

        // New turn exceeding budget
        memory.add(new UserMessage("Next user turn"));
        assertThat(memory.messages()).hasSize(1);
        assertThat(memory.messages().getLast()).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) memory.messages().getLast()).singleText()).isEqualTo("Next user turn");
        assertThatMemory(memory).satisfiesTurnStructureInvariants();
    }

    @Test
    @DisplayName("Given turn structure validator - when verifying memory - then validates alternating turns and absence of dangling tool calls")
    void testTurnStructureInvariants() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.pinInvariants("Rules");
        memory.pinTaskSpecification("Spec", "{}");

        memory.add(new UserMessage("Do X"));
        ToolExecutionRequest req = ToolExecutionRequest.builder()
            .id("call-x").name("toolX").arguments("{}").build();
        memory.add(AiMessage.from(req));
        memory.add(ToolExecutionResultMessage.from(req, "Done"));
        memory.add(new AiMessage("X is finished"));

        assertThatMemory(memory).satisfiesTurnStructureInvariants();

        // Verify observable handling of invalid messages through public API
        // Case 1: SystemMessage is routed to pinned invariants, not scratchpad
        HierarchicalMemoryStore m1 = new HierarchicalMemoryStore();
        m1.add(new UserMessage("Do X"));
        m1.add(new SystemMessage("Invariant"));
        assertThatMemory(m1).satisfiesTurnStructureInvariants();
        assertThat(m1.messages().getFirst()).isInstanceOf(SystemMessage.class);

        // Case 2: Orphaned ToolExecutionResultMessage is dropped by add()
        HierarchicalMemoryStore m2 = new HierarchicalMemoryStore();
        m2.add(ToolExecutionResultMessage.from(req, "Orphan"));
        assertThatMemory(m2).satisfiesTurnStructureInvariants();
        assertThat(m2.messages()).isEmpty();

        // Case 3: ToolExecutionResultMessage without preceding matching request is dropped
        HierarchicalMemoryStore m3 = new HierarchicalMemoryStore();
        m3.add(new UserMessage("Start"));
        m3.add(ToolExecutionResultMessage.from(req, "Dangling"));
        assertThatMemory(m3).satisfiesTurnStructureInvariants();
        assertThat(m3.messages()).hasSize(1);
    }

    @Test
    @DisplayName("Given null story and plan in pinTaskSpecification - when pinned - then uses fallback values")
    void testPinTaskSpecificationNullDefaults() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.pinTaskSpecification(null, null);

        List<ChatMessage> messages = memory.messages();
        assertThat(messages).hasSize(1);
        assertThat(((UserMessage) messages.get(0)).singleText())
            .contains("No story specified")
            .contains("{}");
    }

    @Test
    @DisplayName("Given AiMessage without tool requests - when turn count exceeds capacity - then evicts cleanly")
    void testEvictScratchpadStartingWithAiMessage() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.add(new AiMessage("Leading Ai turn"));
        memory.add(new UserMessage("Turn 1"));
        memory.add(new AiMessage("Ans 1"));
        memory.add(new UserMessage("Turn 2"));
        memory.add(new AiMessage("Ans 2"));
        memory.add(new UserMessage("Turn 3"));
        memory.add(new AiMessage("Ans 3"));
        memory.add(new UserMessage("Turn 4"));
        memory.add(new AiMessage("Ans 4"));
        memory.add(new UserMessage("Turn 5")); // Triggers eviction of Leading Ai turn

        List<ChatMessage> messages = memory.messages();
        assertThat(messages).noneMatch(m -> m instanceof AiMessage ai && "Leading Ai turn".equals(ai.text()));
    }

    @Test
    @DisplayName("Given ToolExecutionResultMessage with non-matching request ID - when added - then dropped by store")
    void testNonMatchingToolExecutionRequestIdDropped() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        ToolExecutionRequest req1 = ToolExecutionRequest.builder()
            .id("req-1").name("tool1").arguments("{}").build();
        ToolExecutionRequest req2 = ToolExecutionRequest.builder()
            .id("req-2").name("tool2").arguments("{}").build();

        memory.add(AiMessage.from(req1));

        // Attempt to add tool result for req2 (does not match req1)
        ToolExecutionResultMessage mismatchResult =
            ToolExecutionResultMessage.from(req2, "out");
        memory.add(mismatchResult);

        // Result was dropped
        assertThat(memory.messages()).hasSize(1);
    }

    @Test
    @DisplayName("Given multiple turns with many messages - when message budget exceeded - then evicts complete older turns")
    void testMessageBudgetEvictsOlderTurns() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.add(new UserMessage("Turn 1"));
        for (int i = 1; i <= 6; i++) {
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call-t1-" + i).name("tool" + i).arguments("{}").build();
            memory.add(AiMessage.from(req));
            memory.add(ToolExecutionResultMessage.from(req, "res-" + i));
        }

        memory.add(new UserMessage("Turn 2"));
        for (int i = 1; i <= 7; i++) {
            ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("call-t2-" + i).name("tool" + i).arguments("{}").build();
            memory.add(AiMessage.from(req));
            memory.add(ToolExecutionResultMessage.from(req, "res-" + i));
        }

        List<ChatMessage> messages = memory.messages();
        assertThat(messages.getFirst()).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) messages.getFirst()).singleText()).isEqualTo("Turn 2");
        assertThatMemory(memory).satisfiesTurnStructureInvariants();
    }

    @Test
    @DisplayName("Given multiple turns below message budget - when added - then preserves all turns")
    void testMultipleTurnsBelowMessageBudget() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.add(new UserMessage("Turn 1"));
        memory.add(new AiMessage("Ans 1"));
        memory.add(new UserMessage("Turn 2"));
        memory.add(new AiMessage("Ans 2"));

        assertThat(memory.messages()).hasSize(4);
    }

    @Test
    @DisplayName("Given duplicate SystemMessage and null task spec - when updating - then deduplicates and handles nulls")
    void testDuplicateSystemMessageAndNullTaskSpec() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.add(new SystemMessage("Invariant A"));
        memory.add(new SystemMessage("Invariant A")); // Duplicate
        assertThat(memory.messages()).hasSize(1);

        memory.pinTaskSpecification(null, null);
        assertThat(memory.messages()).hasSize(2);

        memory.add(new UserMessage("Scratch"));
        assertThat(memory.messages()).hasSize(3);

        memory.clearScratchpad();
        assertThat(memory.messages()).hasSize(2);
    }

    @Test
    @DisplayName("Given AiMessage without tool requests - when tool result added - then dropped as unmatched")
    void testAiMessageWithoutToolRequestsDropsToolResult() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore();
        memory.add(new AiMessage("Plain AI answer"));
        ToolExecutionRequest req = ToolExecutionRequest.builder().id("call-x").name("tool").arguments("{}").build();
        memory.add(ToolExecutionResultMessage.from(req, "res"));
        assertThat(memory.messages()).hasSize(1);
    }

    @Test
    @DisplayName("Given custom memoryId - when instantiated - then id returns assigned memoryId")
    void testStoreCustomMemoryId() {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore("task-42-custom");
        assertThat(memory.id()).isEqualTo("task-42-custom");

        assertThatThrownBy(() -> new HierarchicalMemoryStore(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("memoryId cannot be null");
    }

    @Test
    @DisplayName("Given concurrent threads - when adding and reading messages - then no race conditions or exceptions occur")
    void testConcurrentAccessUnderHighContention() throws Exception {
        HierarchicalMemoryStore memory = new HierarchicalMemoryStore("task-concurrency-test");
        memory.pinInvariants("Rules: Invariants");
        memory.pinTaskSpecification("Story 1", "{}");

        int threadCount = 20;
        int operationsPerThread = 50;
        CountDownLatch latch = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < operationsPerThread; i++) {
                            memory.add(new UserMessage("Prompt from thread " + threadId + " op " + i));
                            memory.add(new AiMessage("Response from thread " + threadId + " op " + i));
                            List<ChatMessage> snapshot = memory.messages();
                            assertThat(snapshot).isNotEmpty();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                }));
            }

            latch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();

            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertThat(memory.messages()).isNotEmpty();
        assertThatMemory(memory).satisfiesTurnStructureInvariants();
    }
}
