package guru.thomasweber.devagent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BranchNameTest {

    @Test
    @DisplayName("Given valid templates and slugs - when BranchName rendered or serialized - then produces clean Git branch refs")
    void givenValidTemplates_whenRendered_thenProducesSafeBranchNames() throws Exception {
        TaskId taskId = TaskId.of("TASK-501");

        BranchName rendered = BranchName.render(null, taskId, "Add User Auth & Rate Limiting!!!");
        assertThat(rendered.value()).isEqualTo("feature/TASK-501-add-user-auth-rate-limiting");
        assertThat(rendered.toString()).isEqualTo("feature/TASK-501-add-user-auth-rate-limiting");

        BranchName defaultSlug = BranchName.render("agent/{task-id}/{story-slug}", taskId, "   ");
        assertThat(defaultSlug.value()).isEqualTo("agent/TASK-501/task");

        BranchName symbolSlug = BranchName.render("  ", taskId, "!!!");
        assertThat(symbolSlug.value()).isEqualTo("feature/TASK-501-task");

        BranchName longSlug = BranchName.render(BranchName.DEFAULT_TEMPLATE, taskId, "word-".repeat(20));
        assertThat(longSlug.value()).startsWith("feature/TASK-501-");
        assertThat(longSlug.value().length()).isLessThanOrEqualTo("feature/TASK-501-".length() + 48);

        assertThat(BranchName.forTask(taskId).value()).isEqualTo("feature/TASK-501");

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(rendered);
        assertThat(mapper.readValue(json, BranchName.class)).isEqualTo(rendered);
    }

    @Test
    @DisplayName("Given unsafe or malformed branch refs - when BranchName constructed - then throws SecurityException")
    void givenUnsafeBranchRefs_whenConstructed_thenThrowsSecurityException() {
        assertThatThrownBy(() -> BranchName.of(null)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> BranchName.of("  ")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> BranchName.of("-Dmain")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> BranchName.of("/feature/a")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> BranchName.of("feature/a/")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> BranchName.of("feature/a.")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> BranchName.of("feature/a.lock")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> BranchName.of("feature/a..b")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> BranchName.of("feature//a")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> BranchName.of("feature/@{0}")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> BranchName.of("feature/with space")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> BranchName.forTask(null)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> BranchName.render(BranchName.DEFAULT_TEMPLATE, null, "slug")).isInstanceOf(SecurityException.class);
    }
}
