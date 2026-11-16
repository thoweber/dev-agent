package guru.thomasweber.devagent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathGuardTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given valid, symlinked, or oversized files - when PathGuard invoked - then enforces bounds and atomic writes")
    void givenFiles_whenPathGuardInvoked_thenEnforcesBoundsAndAtomicWrites() throws IOException {
        assertThat(PathGuard.toRealOrNormalized(null)).isNull();
        Path missing = tempDir.resolve("missing/file.txt");
        assertThat(PathGuard.toRealOrNormalized(missing)).isEqualTo(missing.toAbsolutePath().normalize());

        Path target = tempDir.resolve("nested/state.md");
        PathGuard.writeOwnerOnlyAtomically(target, "hello atomic");
        assertThat(PathGuard.readRegularFileBounded(target, 1024)).isEqualTo("hello atomic");

        Path symlink = Files.createSymbolicLink(tempDir.resolve("sym.txt"), target);
        assertThatThrownBy(() -> PathGuard.readRegularFileBounded(symlink, 1024))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> PathGuard.readRegularFileBounded(target, 5))
            .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> PathGuard.readRegularFileBounded(null, 10))
            .isInstanceOf(IllegalArgumentException.class);

        Path defaultTemp = PathGuard.createOwnerOnlyTempFile(null, "pathguard-test-", ".tmp");
        try {
            assertThat(defaultTemp).exists();
        } finally {
            Files.deleteIfExists(defaultTemp);
        }
    }
}
