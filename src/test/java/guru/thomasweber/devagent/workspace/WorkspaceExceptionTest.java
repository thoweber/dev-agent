package guru.thomasweber.devagent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceExceptionTest {

    @Test
    @DisplayName("Given message and cause - when WorkspaceException constructed - then exposes message and cause")
    void givenMessageAndCause_whenConstructed_thenExposesBoth() {
        WorkspaceException simple = new WorkspaceException("Worktree creation failed");
        IllegalStateException rootCause = new IllegalStateException("git failed");
        WorkspaceException wrapped = new WorkspaceException("Failed to hydrate worktree", rootCause);

        assertThat(simple.getMessage()).isEqualTo("Worktree creation failed");
        assertThat(simple.getCause()).isNull();
        assertThat(wrapped.getMessage()).isEqualTo("Failed to hydrate worktree");
        assertThat(wrapped.getCause()).isSameAs(rootCause);
    }
}
