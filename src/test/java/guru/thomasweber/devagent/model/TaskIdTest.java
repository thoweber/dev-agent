package guru.thomasweber.devagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskIdTest {

    @Test
    @DisplayName("Given valid task identifier strings - when TaskId constructed - then preserves value and serializes as string")
    void givenValidTaskIds_whenConstructed_thenPreservesValueAndSerializes() throws Exception {
        TaskId id = TaskId.of("TASK-401.rc_1");
        assertThat(id.value()).isEqualTo("TASK-401.rc_1");
        assertThat(id.idString()).isEqualTo("TASK-401.rc_1");
        assertThat(id.toString()).isEqualTo("TASK-401.rc_1");

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(id);
        assertThat(json).isEqualTo("\"TASK-401.rc_1\"");
        assertThat(mapper.readValue(json, TaskId.class)).isEqualTo(id);
    }

    @Test
    @DisplayName("Given unsafe or malformed task identifiers - when TaskId constructed - then throws SecurityException")
    void givenUnsafeTaskIds_whenConstructed_thenThrowsSecurityException() {
        assertThatThrownBy(() -> TaskId.of(null)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TaskId.of("   ")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TaskId.of(".")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TaskId.of("..")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TaskId.of("../escape")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TaskId.of("bad/id")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TaskId.of("bad\\id")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TaskId.of("TASK..1")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TaskId.of("-leading-dash")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TaskId.of("T".repeat(65))).isInstanceOf(SecurityException.class);
    }
}
