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
        Evaluate the proposed plan or diff against the active project architectural decisions:
        {retrievedAdrs}
        Return ONLY valid JSON matching the GateVerdict schema.
        """)
    GateVerdict evaluate(@UserMessage String targetContent, @V("retrievedAdrs") String retrievedAdrs);
}
