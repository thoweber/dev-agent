package guru.thomasweber.devagent.cognitive;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import guru.thomasweber.devagent.model.GateVerdict;
import io.quarkiverse.langchain4j.RegisterAiService;

/**
 * Universal Invariant Gatekeeper AiService evaluating code diffs and plans
 * against architectural decisions.
 */
@RegisterAiService(chatLanguageModelSupplier = GatekeeperChatModelSupplier.class)
public interface UniversalArchitectGatekeeperAiService {

    @SystemMessage("""
        You are the Architectural Invariant Gatekeeper.
        Analyze the proposed target content against the provided architectural decisions and rules.
        Evaluate compliance strictly and return ONLY valid JSON matching the GateVerdict schema.
        """)
    @UserMessage("""
        ### PROPOSED TARGET CONTENT:
        {targetContent}

        ### ACTIVE ARCHITECTURAL DECISIONS & RULES (EVALUATION DATA):
        {retrievedAdrs}
        """)
    GateVerdict evaluate(@V("targetContent") String targetContent, @V("retrievedAdrs") String retrievedAdrs);
}
