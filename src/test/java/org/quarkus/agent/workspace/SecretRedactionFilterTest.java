package org.quarkus.agent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactionFilterTest {

    private final SecretRedactionFilter filter = new SecretRedactionFilter();

    @Test
    @DisplayName("Given sensitive credentials - when scrub called - then masks with REDACTED placeholder")
    void testRedactionOfSecrets() {
        String logWithGithubPat = "Error pushing branch with ghp_123456789012345678901234567890123456 to origin";
        assertThat(filter.scrub(logWithGithubPat))
            .doesNotContain("ghp_123456789012345678901234567890123456")
            .contains("***REDACTED_CREDENTIAL***");

        String logWithGemini = "Connecting with API key AIzaSyA1B2C3D4E5F6G7H8I9J0K1L2M3N4O5P";
        assertThat(filter.scrub(logWithGemini))
            .doesNotContain("AIzaSyA1B2C3D4E5F6G7H8I9J0K1L2M3N4O5P")
            .contains("***REDACTED_CREDENTIAL***");

        String logWithBearer = "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.e30.t-IDc";
        assertThat(filter.scrub(logWithBearer))
            .doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.e30.t-IDc")
            .contains("***REDACTED_CREDENTIAL***");
    }

    @Test
    @DisplayName("Given regular Java and TypeScript source code - when scrub called - then leaves content untouched")
    void testSourceCodePreserved() {
        String javaSource = """
            public class UserService {
                public String getUsername() {
                    return "test-user";
                }
            }
            """;
        assertThat(filter.scrub(javaSource)).isEqualTo(javaSource);

        String tsSource = """
            export const calculateTotal = (a: number, b: number): number => {
                return a + b;
            };
            """;
        assertThat(filter.scrub(tsSource)).isEqualTo(tsSource);
    }
}
