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

    private static final String KEY_PREFIX =
        "(?i)([\"']?(?:password|passwd|secret|auth[_-]?token|api[_-]?key|access[_-]?token|client[_-]?secret)[\"']?\\s*[:=]\\s*)";

    private static final Pattern KEY_DOUBLE_QUOTED = Pattern.compile(KEY_PREFIX + "\"([^\"]*)\"");
    private static final Pattern KEY_SINGLE_QUOTED = Pattern.compile(KEY_PREFIX + "'([^']*)'");
    private static final Pattern KEY_UNQUOTED = Pattern.compile(KEY_PREFIX + "([^\\s\"',;]+)");

    private static final List<Pattern> SECRET_PATTERNS = List.of(
        Pattern.compile("\\bgh[pousr]_[a-zA-Z0-9]{36,255}\\b"),
        Pattern.compile("\\bgithub_pat_\\w{82,120}\\b"),
        Pattern.compile("\\bAIzaSy[a-zA-Z0-9_-]{30,50}\\b"),
        Pattern.compile("\\bsk-ant-[a-zA-Z0-9_.-]{30,120}\\b"),
        Pattern.compile("\\bsk-[a-zA-Z0-9_-]{20,120}\\b"),
        Pattern.compile("\\bnpm_[a-zA-Z0-9]{36,64}\\b"),
        Pattern.compile("\\b(?:AKIA|ASIA|ABIA|ACCA)[0-9A-Z]{16}\\b"),
        Pattern.compile("(?i)\\bbearer\\s+[a-z0-9_\\-.=]{15,}"),
        Pattern.compile("(?i)\\bbasic\\s+[a-z0-9_\\-+/=]{20,}"),
        Pattern.compile("-----BEGIN [A-Z0-9 _]+ PRIVATE KEY-----\\s*[a-zA-Z0-9+/=\\s]+?\\s*-----END [A-Z0-9 _]+ PRIVATE KEY-----")
    );

    public SanitizedOutput sanitize(String input) {
        return SanitizedOutput.of(input, this);
    }

    public String scrub(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String result = KEY_DOUBLE_QUOTED.matcher(input).replaceAll("$1\"" + REDACTED_PLACEHOLDER + "\"");
        result = KEY_SINGLE_QUOTED.matcher(result).replaceAll("$1'" + REDACTED_PLACEHOLDER + "'");
        result = KEY_UNQUOTED.matcher(result).replaceAll("$1" + REDACTED_PLACEHOLDER);
        for (Pattern pattern : SECRET_PATTERNS) {
            result = pattern.matcher(result).replaceAll(REDACTED_PLACEHOLDER);
        }
        return result;
    }
}
