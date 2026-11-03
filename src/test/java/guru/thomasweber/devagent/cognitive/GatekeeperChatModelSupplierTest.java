package guru.thomasweber.devagent.cognitive;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GatekeeperChatModelSupplierTest {

    @Test
    @DisplayName("Given GatekeeperChatModelSupplier without bean in production - when calling get and doChat - then fails closed with rejection")
    void givenSupplierWithoutBean_whenGettingAndChatting_thenFailsClosedWithRejection() {
        GatekeeperChatModelSupplier supplier = new GatekeeperChatModelSupplier();
        ChatModel model = supplier.get();
        assertThat(model).isNotNull();

        ChatRequest request = ChatRequest.builder()
            .messages(UserMessage.from("Check diff"))
            .build();

        ChatResponse response = model.doChat(request);
        assertThat(response).isNotNull();
        assertThat(response.aiMessage().text())
            .contains("\"approved\":false")
            .contains("No ChatModel bean registered");
    }

    @Test
    @DisplayName("Given fallback model in test mode - when calling doChat - then returns offline approval stub")
    void givenFallbackModelInTestMode_whenCallingDoChat_thenReturnsApprovalStub() {
        ChatModel testFallback = GatekeeperChatModelSupplier.createFallbackChatModel(LaunchMode.TEST);
        ChatRequest request = ChatRequest.builder()
            .messages(UserMessage.from("Check diff"))
            .build();

        ChatResponse response = testFallback.doChat(request);
        assertThat(response).isNotNull();
        assertThat(response.aiMessage().text()).contains("\"approved\":true");
    }

    @Test
    @DisplayName("Given GatekeeperChatModelSupplier with resolvable bean - when calling get - then delegates to resolvable bean")
    @SuppressWarnings("unchecked")
    void givenSupplierWithResolvableBean_whenGetting_thenDelegatesToBean() {
        Instance<ChatModel> mockInstance = mock(Instance.class);
        ChatModel mockModel = mock(ChatModel.class);
        when(mockInstance.isResolvable()).thenReturn(true);
        when(mockInstance.get()).thenReturn(mockModel);

        GatekeeperChatModelSupplier supplier = new GatekeeperChatModelSupplier(mockInstance);
        ChatModel resolved = supplier.get();

        assertThat(resolved).isSameAs(mockModel);
    }

    @Test
    @DisplayName("Given GatekeeperChatModelSupplier with unresolvable bean - when calling get - then falls back to fallback model")
    @SuppressWarnings("unchecked")
    void givenSupplierWithUnresolvableBean_whenGetting_thenFallsBack() {
        Instance<ChatModel> mockInstance = mock(Instance.class);
        when(mockInstance.isResolvable()).thenReturn(false);

        GatekeeperChatModelSupplier supplier = new GatekeeperChatModelSupplier(mockInstance);
        ChatModel resolved = supplier.get();

        assertThat(resolved).isNotNull();
    }
}
