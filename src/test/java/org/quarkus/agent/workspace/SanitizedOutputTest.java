package org.quarkus.agent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quarkus.agent.entity.StepAuditEntity;

import static org.assertj.core.api.Assertions.assertThat;

class SanitizedOutputTest {

    private final SecretRedactionFilter filter = new SecretRedactionFilter();

    @Test
    @DisplayName("Given raw output with secrets - when sanitized - returns SanitizedOutput with masked credentials")
    void testSanitizedOutputMasksSecrets() {
        String raw = "Command failed with password=hunter2 and key sk-123456789012345678901234567890123456789012345678";
        SanitizedOutput output = filter.sanitize(raw);

        assertThat(output.value())
            .doesNotContain("hunter2")
            .doesNotContain("sk-123456789012345678901234567890123456789012345678")
            .contains("***REDACTED_CREDENTIAL***");
        assertThat(output.toString()).isEqualTo(output.value());
        assertThat(output.isBlank()).isFalse();
    }

    @Test
    @DisplayName("Given null input - when sanitized - returns empty SanitizedOutput")
    void testNullSanitizedOutput() {
        SanitizedOutput output = filter.sanitize(null);

        assertThat(output.value()).isEmpty();
        assertThat(output.isBlank()).isTrue();
    }

    @Test
    @DisplayName("Given SanitizedOutput - when applied to StepAuditEntity - populates sanitized field safely")
    void testStepAuditEntityConsumesSanitizedOutput() {
        StepAuditEntity entity = new StepAuditEntity();
        SanitizedOutput input = filter.sanitize("git push with ghp_123456789012345678901234567890123456");
        SanitizedOutput summary = filter.sanitize("Auth error: authToken=\"secretToken123\"");

        entity.setSanitizedInput(input);
        entity.setOutputSummary(summary);

        assertThat(entity.sanitizedInput).doesNotContain("ghp_123456789012345678901234567890123456");
        assertThat(entity.outputSummary).doesNotContain("secretToken123");
    }
}
