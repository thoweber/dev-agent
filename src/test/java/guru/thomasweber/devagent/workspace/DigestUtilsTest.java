package guru.thomasweber.devagent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DigestUtilsTest {

    @Test
    @DisplayName("Given input strings - when sha256Hex called - then returns deterministic 64-char hex digest")
    void givenInputs_whenSha256HexCalled_thenReturnsDeterministicDigest() {
        String emptyDigest = DigestUtils.sha256Hex(null);
        assertThat(emptyDigest)
            .isEqualTo(DigestUtils.sha256Hex(""))
            .hasSize(64);
        assertThat(DigestUtils.sha256Hex("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
