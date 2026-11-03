package guru.thomasweber.devagent.cognitive;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.function.Supplier;

/**
 * Dynamic ChatModel supplier for UniversalArchitectGatekeeperAiService.
 * Resolves active ChatModel CDI bean when configured, or provides an offline
 * fallback stub when running without an external LLM provider.
 */
@ApplicationScoped
public class GatekeeperChatModelSupplier implements Supplier<ChatModel> {

    private final Instance<ChatModel> chatModelInstance;

    @Inject
    public GatekeeperChatModelSupplier(Instance<ChatModel> chatModelInstance) {
        this.chatModelInstance = chatModelInstance;
    }

    public GatekeeperChatModelSupplier() {
        this(null);
    }

    @Override
    public ChatModel get() {
        if (chatModelInstance != null && chatModelInstance.isResolvable()) {
            return chatModelInstance.get();
        }
        return createFallbackChatModel(LaunchMode.current());
    }

    static ChatModel createFallbackChatModel(LaunchMode launchMode) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                if (launchMode == LaunchMode.NORMAL) {
                    return ChatResponse.builder()
                        .aiMessage(AiMessage.from("{\"approved\":false,\"summary\":\"Architectural evaluation failed: No ChatModel bean registered in production runtime.\",\"violations\":[\"No ChatModel bean registered\"],\"recommendations\":[\"Configure an LLM provider such as Ollama, OpenAI, or Anthropic\"]}"))
                        .build();
                }
                return ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"approved\":true,\"summary\":\"Invariants verified (offline test stub)\",\"violations\":[],\"recommendations\":[]}"))
                    .build();
            }
        };
    }
}
