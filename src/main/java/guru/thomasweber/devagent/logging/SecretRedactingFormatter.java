package guru.thomasweber.devagent.logging;

import guru.thomasweber.devagent.workspace.SecretRedactionFilter;
import org.jboss.logmanager.ExtFormatter;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.formatters.PatternFormatter;

import java.util.Objects;

import static java.util.Objects.isNull;

/**
 * JBoss LogManager formatter applying {@link SecretRedactionFilter} at the log output boundary (ADR-008).
 */
public class SecretRedactingFormatter extends ExtFormatter {

    public static final String DEFAULT_PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5p [%c{2.}] (%t) %s%e%n";

    private final PatternFormatter delegate;
    private final SecretRedactionFilter secretFilter;

    public SecretRedactingFormatter(SecretRedactionFilter secretFilter, String pattern) {
        this.secretFilter = Objects.requireNonNullElseGet(secretFilter, SecretRedactionFilter::new);
        String effectivePattern = (isNull(pattern) || pattern.isBlank()) ? DEFAULT_PATTERN : pattern;
        this.delegate = new PatternFormatter(effectivePattern);
    }

    public SecretRedactingFormatter(SecretRedactionFilter secretFilter) {
        this(secretFilter, DEFAULT_PATTERN);
    }

    public SecretRedactingFormatter() {
        this(new SecretRedactionFilter(), DEFAULT_PATTERN);
    }

    @Override
    public String format(ExtLogRecord record) {
        if (isNull(record)) {
            return "";
        }
        String formatted = delegate.format(record);
        return secretFilter.scrub(formatted);
    }

    @Override
    public boolean isCallerCalculationRequired() {
        return delegate.isCallerCalculationRequired();
    }
}
