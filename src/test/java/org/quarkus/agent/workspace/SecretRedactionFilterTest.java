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

        String logWithBasic = "Authorization: Basic dXNlcjpwYXNzd29yZDEyMzQ1Njc=";
        assertThat(filter.scrub(logWithBasic))
            .doesNotContain("dXNlcjpwYXNzd29yZDEyMzQ1Njc=")
            .contains("***REDACTED_CREDENTIAL***");

        String logWithOpenAi = "Key: sk-123456789012345678901234567890123456789012345678";
        assertThat(filter.scrub(logWithOpenAi))
            .contains("***REDACTED_CREDENTIAL***");

        String logWithNpm = "Token: npm_123456789012345678901234567890123456";
        assertThat(filter.scrub(logWithNpm))
            .contains("***REDACTED_CREDENTIAL***");

        String logWithAws = "AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE";
        assertThat(filter.scrub(logWithAws))
            .doesNotContain("AKIAIOSFODNN7EXAMPLE")
            .contains("***REDACTED_CREDENTIAL***");

        String logWithOpenAiProj = "sk-proj-abc1234567890_def-GHIJKLMN-1234567890";
        assertThat(filter.scrub(logWithOpenAiProj))
            .doesNotContain("sk-proj-abc1234567890_def-GHIJKLMN-1234567890")
            .contains("***REDACTED_CREDENTIAL***");

        String pemKey = """
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEA0Y1+
            -----END RSA PRIVATE KEY-----
            """;
        assertThat(filter.scrub(pemKey))
            .doesNotContain("MIIEowIBAAKCAQEA0Y1+")
            .contains("***REDACTED_CREDENTIAL***");

        String jsonConfig = "{\"password\": \"superSecretPassword123\", \"authToken\": \"mySecretAuthToken456\"}";
        assertThat(filter.scrub(jsonConfig))
            .doesNotContain("superSecretPassword123")
            .doesNotContain("mySecretAuthToken456")
            .contains("\"password\": \"***REDACTED_CREDENTIAL***\"")
            .contains("\"authToken\": \"***REDACTED_CREDENTIAL***\"");
    }

    @Test
    @DisplayName("Given null or blank input - when scrub called - returns input unchanged")
    void testNullAndBlankInput() {
        assertThat(filter.scrub(null)).isNull();
        assertThat(filter.scrub("")).isEmpty();
        assertThat(filter.scrub("   ")).isEqualTo("   ");
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
