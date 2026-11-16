package guru.thomasweber.devagent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetDirTest {

    @Test
    @DisplayName("Given blank or valid target directory values - when TargetDir resolved - then normalizes defaults and trailing slashes")
    void givenValidOrBlankValues_whenResolved_thenNormalizesSafely() {
        assertThat(TargetDir.of(null)).isEqualTo(TargetDir.DEFAULT);
        assertThat(TargetDir.of("   ").value()).isEqualTo("target-agent");
        assertThat(new TargetDir(null).value()).isEqualTo("target-agent");

        TargetDir custom = TargetDir.of("  target-custom/  ");
        assertThat(custom.value()).isEqualTo("target-custom");
        assertThat(custom.excludeEntry()).isEqualTo("target-custom/");
        assertThat(custom.toString()).isEqualTo("target-custom");

        TargetDir winStyle = TargetDir.of("build\\agent\\");
        assertThat(winStyle.value()).isEqualTo("build/agent");
        assertThat(winStyle.excludeEntry()).isEqualTo("build/agent/");
    }

    @Test
    @DisplayName("Given unsafe target directory paths - when TargetDir constructed - then throws SecurityException")
    void givenUnsafePaths_whenConstructed_thenThrowsSecurityException() {
        assertThatThrownBy(() -> TargetDir.of("../target")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TargetDir.of("foo/..")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TargetDir.of("/tmp/target")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TargetDir.of("/")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TargetDir.of("C:\\target")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TargetDir.of("~/target")).isInstanceOf(SecurityException.class);
    }
}
