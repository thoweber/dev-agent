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
        "(?i)([\"']?(?:password|secret|auth[_-]?token|api[_-]?key)[\"']?\\s*[:=]\\s*[\"']?)([^\\s\"',;]+)([\"']?)"
    );

    private static final List<Pattern> SECRET_PATTERNS = List.of(
        Pattern.compile("ghp_[a-zA-Z0-9]{36}"),
        Pattern.compile("github_pat_\\w{82}"),
        Pattern.compile("AIzaSy[a-zA-Z0-9_-]{30,40}"),
        Pattern.compile("sk-[a-zA-Z0-9_-]{20,}"),
        Pattern.compile("sk-ant-[a-zA-Z0-9_-]{95}"),
        Pattern.compile("npm_[a-zA-Z0-9]{36}"),
        Pattern.compile("AKIA[0-9A-Z]{16}"),
        Pattern.compile("(?i)bearer\\s+[a-z0-9_\\-.=]{15,}"),
        Pattern.compile("(?i)basic\\s+[a-z0-9_\\-+/=]{20,}"),
        Pattern.compile("-----BEGIN [A-Z0-9 _-]+ PRIVATE KEY-----[\\s\\S]+?-----END [A-Z0-9 _-]+ PRIVATE KEY-----")
    );

    public String scrub(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String result = KEY_VALUE_PATTERN.matcher(input).replaceAll("$1" + REDACTED_PLACEHOLDER + "$3");
        for (Pattern pattern : SECRET_PATTERNS) {
            result = pattern.matcher(result).replaceAll(REDACTED_PLACEHOLDER);
        }
        return result;
    }
}
