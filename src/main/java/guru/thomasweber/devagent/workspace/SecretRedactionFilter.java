package guru.thomasweber.devagent.workspace;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.regex.Pattern;

import static java.util.Objects.isNull;

/**
 * Context-aware credential scrubber strictly quarantined for tool outputs and audit logs.
 */
@ApplicationScoped
public class SecretRedactionFilter {

    private static final String REDACTED_PLACEHOLDER = "***REDACTED_CREDENTIAL***";

    private static final String KEY_PREFIX_CREDENTIALS =
        "(?i)(\\b[\"']?[\\w-]{0,64}(?:password|passwd|pass|pwd|secret|token|jwt|dsn)[\"']?\\s*[:=]\\s*)";
    private static final String KEY_PREFIX_AUTH =
        "(?i)(\\b[\"']?[\\w-]{0,64}(?:authorization|auth|credential|connection_string|key)[\"']?\\s*[:=]\\s*)";

    private static final List<Pattern> KEY_DOUBLE_QUOTED_PATTERNS = List.of(
        Pattern.compile(KEY_PREFIX_CREDENTIALS + "\"([^\"]*)\""),
        Pattern.compile(KEY_PREFIX_AUTH + "\"([^\"]*)\"")
    );
    private static final List<Pattern> KEY_SINGLE_QUOTED_PATTERNS = List.of(
        Pattern.compile(KEY_PREFIX_CREDENTIALS + "'([^']*)'"),
        Pattern.compile(KEY_PREFIX_AUTH + "'([^']*)'")
    );
    private static final List<Pattern> KEY_UNQUOTED_PATTERNS = List.of(
        Pattern.compile(KEY_PREFIX_CREDENTIALS + "([^\\s\"',;]+)"),
        Pattern.compile(KEY_PREFIX_AUTH + "([^\\s\"',;]+)")
    );
    private static final Pattern URL_CREDENTIALS = Pattern.compile(
        "([a-zA-Z][a-zA-Z0-9+.-]*://)([^\\s/@:]+:[^\\s/@]+)(@)"
    );

    private static final List<Pattern> SECRET_PATTERNS = List.of(
        Pattern.compile("\\bgh[pousr]_[a-zA-Z0-9]{36,255}\\b"),
        Pattern.compile("\\bgithub_pat_\\w{82,120}\\b"),
        Pattern.compile("\\bAIzaSy[a-zA-Z0-9_-]{30,50}\\b"),
        Pattern.compile("\\bsk-ant-[a-zA-Z0-9_.-]{30,120}\\b"),
        Pattern.compile("\\bsk-[a-zA-Z0-9_-]{20,120}\\b"),
        Pattern.compile("\\bnpm_[a-zA-Z0-9]{36,64}\\b"),
        Pattern.compile("\\b(?:AKIA|ASIA|ABIA|ACCA)[0-9A-Z]{16}\\b"),
        Pattern.compile("(?i)\\bbearer\\s+[a-z0-9_\\-.=+/]{15,2048}"),
        Pattern.compile("(?i)\\bbasic\\s+[a-z0-9_\\-+/=]{20,2048}"),
        Pattern.compile("-----BEGIN [A-Z0-9 ]+ PRIVATE KEY-----[\\s\\S]{1,16384}?-----END [A-Z0-9 ]+ PRIVATE KEY-----")
    );

    public SanitizedOutput sanitize(String input) {
        return SanitizedOutput.of(input, this);
    }

    public String scrub(String input) {
        if (isNull(input) || input.isBlank()) {
            return input;
        }
        String result = URL_CREDENTIALS.matcher(input).replaceAll("$1" + REDACTED_PLACEHOLDER + "$3");
        for (Pattern pattern : SECRET_PATTERNS) {
            result = pattern.matcher(result).replaceAll(REDACTED_PLACEHOLDER);
        }
        for (Pattern pattern : KEY_DOUBLE_QUOTED_PATTERNS) {
            result = pattern.matcher(result).replaceAll("$1\"" + REDACTED_PLACEHOLDER + "\"");
        }
        for (Pattern pattern : KEY_SINGLE_QUOTED_PATTERNS) {
            result = pattern.matcher(result).replaceAll("$1'" + REDACTED_PLACEHOLDER + "'");
        }
        for (Pattern pattern : KEY_UNQUOTED_PATTERNS) {
            result = pattern.matcher(result).replaceAll("$1" + REDACTED_PLACEHOLDER);
        }
        return result;
    }
}
