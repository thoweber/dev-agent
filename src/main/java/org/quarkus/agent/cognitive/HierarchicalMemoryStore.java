package org.quarkus.agent.cognitive;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * 3-Tier Hierarchical Memory store implementing LangChain4j ChatMemory.
 * Tier 1 (Pinned Invariants) and Tier 2 (Pinned Task Spec) are never evicted during self-healing loops.
 * Tier 3 (Sliding Scratchpad) maintains a bounded window of recent compiler/test interactions.
 */
@ApplicationScoped
public class HierarchicalMemoryStore implements ChatMemory {

    private SystemMessage pinnedInvariants;
    private UserMessage pinnedTaskSpec;
    private final List<ChatMessage> slidingScratchpad = new LinkedList<>();
    private final int maxScratchpadMessages = 4;

    @Override
    public Object id() {
        return "hierarchical-memory";
    }

    public void pinInvariants(String invariants) {
        if (invariants != null && !invariants.isBlank()) {
            this.pinnedInvariants = new SystemMessage(invariants);
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
            this.pinnedInvariants = systemMessage;
            return;
        }
        slidingScratchpad.add(message);
        while (slidingScratchpad.size() > maxScratchpadMessages) {
            slidingScratchpad.remove(0);
        }
    }

    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> assembled = new ArrayList<>();
        if (pinnedInvariants != null) {
            assembled.add(pinnedInvariants);
        }
        if (pinnedTaskSpec != null) {
            assembled.add(pinnedTaskSpec);
        }
        assembled.addAll(slidingScratchpad);
        return Collections.unmodifiableList(assembled);
    }

    @Override
    public void clear() {
        slidingScratchpad.clear();
    }
}
