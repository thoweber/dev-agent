package guru.thomasweber.devagent.logging;

import guru.thomasweber.devagent.workspace.SecretRedactionFilter;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.ExtLogRecord.FormatStyle;
import org.jboss.logmanager.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactingFormatterTest {

    @Test
    @DisplayName("Given log record with credentials in message and thrown exception - when formatted - then scrubs secrets while retaining diagnostics")
    void givenSensitiveLogRecord_whenFormat_thenRedactsSecrets() {
        SecretRedactingFormatter formatter = new SecretRedactingFormatter(new SecretRedactionFilter());
        ExtLogRecord record = new ExtLogRecord(
            Level.DEBUG,
            "Calling API with Bearer abcdef1234567890abcdef and token=ghp_123456789012345678901234567890123456",
            FormatStyle.NO_FORMAT,
            "guru.thomasweber.devagent.Test"
        );
        record.setLoggerName("guru.thomasweber.devagent.Test");
        record.setThrown(new IllegalStateException("Failed connecting to https://user:supersecret@github.com/org/repo.git"));

        String output = formatter.format(record);

        assertThat(output)
            .contains("DEBUG", "Calling API with", "***REDACTED_CREDENTIAL***", "IllegalStateException")
            .doesNotContain("abcdef1234567890abcdef", "ghp_123456789012345678901234567890123456", "user:supersecret");
        assertThat(formatter.isCallerCalculationRequired()).isFalse();
    }

    @Test
    @DisplayName("Given default constructor and edge cases - when formatting records - then handles printf, null record, and blank patterns")
    void givenDefaultFormatter_whenFormatCleanRecord_thenFormatsCleanly() {
        SecretRedactingFormatter formatter = new SecretRedactingFormatter();
        ExtLogRecord record = new ExtLogRecord(Level.INFO, "Starting session %s", FormatStyle.PRINTF, "guru.thomasweber.devagent.Test");
        record.setLoggerName("guru.thomasweber.devagent.Test");
        record.setParameters(new Object[] { "sess-001" });

        String output = formatter.format(record);

        assertThat(output).contains("INFO", "Starting session sess-001");
        assertThat(formatter.format(null)).isEmpty();
        assertThat(new SecretRedactingFormatter(null, "  ").format(record)).contains("Starting session sess-001");
        assertThat(new SecretRedactingFormatter(null, null).format(record)).contains("Starting session sess-001");
        assertThat(new SecretRedactingFormatter(null, "%-5p %s%n").format(record)).isEqualTo("INFO  Starting session sess-001\n");
    }
}
