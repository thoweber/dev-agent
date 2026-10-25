package org.quarkus.agent.workspace;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Context-aware credential scrubber strictly quarantined for tool outputs and audit logs.
 */
@ApplicationScoped
public class SecretRedactionFilter {

    private static final String REDACTED_PLACEHOLDER = "***REDACTED_CREDENTIAL***";

    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
        "(?i)([\"']?(?:password|passwd|secret|auth[_-]?token|api[_-]?key|access[_-]?token|client[_-]?secret)[\"']?\\s*[:=]\\s*)(?:\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"|'([^'\\\\]*(?:\\\\.[^'\\\\]*)*)'|([^\\s\"',;]+))"
    );

    private static final List<Pattern> SECRET_PATTERNS = List.of(
        Pattern.compile("\\bgh[pousr]_[a-zA-Z0-9]{36,255}\\b"),
        Pattern.compile("\\bgithub_pat_[a-zA-Z0-9_]{82,120}\\b"),
        Pattern.compile("\\bAIzaSy[a-zA-Z0-9_-]{30,50}\\b"),
        Pattern.compile("\\bsk-ant-[a-zA-Z0-9_.-]{30,120}\\b"),
        Pattern.compile("\\bsk-[a-zA-Z0-9_-]{20,120}\\b"),
        Pattern.compile("\\bnpm_[a-zA-Z0-9]{36,64}\\b"),
        Pattern.compile("\\b(?:AKIA|ASIA|ABIA|ACCA)[0-9A-Z]{16}\\b"),
        Pattern.compile("(?i)\\bbearer\\s+[a-z0-9_\\-.=]{15,}"),
        Pattern.compile("(?i)\\bbasic\\s+[a-z0-9_\\-+/=]{20,}"),
        Pattern.compile("-----BEGIN [A-Z0-9 _-]+ PRIVATE KEY-----[\\s\\S]+?-----END [A-Z0-9 _-]+ PRIVATE KEY-----")
    );

    public SanitizedOutput sanitize(String input) {
        return SanitizedOutput.of(input, this);
    }

    public String scrub(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String result = KEY_VALUE_PATTERN.matcher(input).replaceAll(match -> {
            String prefix = match.group(1);
            if (match.group(2) != null) {
                return prefix + "\"" + REDACTED_PLACEHOLDER + "\"";
            } else if (match.group(3) != null) {
                return prefix + "'" + REDACTED_PLACEHOLDER + "'";
            } else {
                return prefix + REDACTED_PLACEHOLDER;
            }
        });
        for (Pattern pattern : SECRET_PATTERNS) {
            result = pattern.matcher(result).replaceAll(REDACTED_PLACEHOLDER);
        }
        return result;
    }
}
