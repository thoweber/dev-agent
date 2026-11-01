package guru.thomasweber.devagent.cognitive;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatekeeperChatModelSupplierTest {

    @Test
    @DisplayName("Given GatekeeperChatModelSupplier - when calling get and doChat - then returns valid ChatResponse with JSON verdict")
    void givenSupplier_whenGettingAndChatting_thenReturnsValidVerdictJson() {
        GatekeeperChatModelSupplier supplier = new GatekeeperChatModelSupplier();
        ChatModel model = supplier.get();
        assertThat(model).isNotNull();

        ChatRequest request = ChatRequest.builder()
            .messages(UserMessage.from("Check diff"))
            .build();

        ChatResponse response = model.doChat(request);
        assertThat(response).isNotNull();
        assertThat(response.aiMessage().text()).contains("\"approved\":true");
    }
}
