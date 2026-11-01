package guru.thomasweber.devagent.cognitive;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.function.Supplier;

/**
 * Default offline/fallback ChatModel supplier for UniversalArchitectGatekeeperAiService.
 */
@ApplicationScoped
public class GatekeeperChatModelSupplier implements Supplier<ChatModel> {

    @Override
    public ChatModel get() {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"approved\":true,\"summary\":\"Invariants verified\",\"violations\":[],\"recommendations\":[]}"))
                    .build();
            }
        };
    }
}
