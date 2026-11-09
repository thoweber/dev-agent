package guru.thomasweber.devagent.spi.build;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Robust Maven build/test output parser and log sanitizer.
 * Drops extraneous downloader and progress bar noise while capturing compilation diagnostics,
 * Surefire assertion failures, runtime exception stack traces, and bootstrap errors.
 */
@ApplicationScoped
@RegisterForReflection
public class MavenOutputParser {

    private static final Pattern DOWNLOAD_PROGRESS = Pattern.compile(
        "^(?:Progress \\(\\d+\\):|Downloading from|Downloaded from|\\[INFO\\] Download(?:ing|ed):).*",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern MAVEN_NOISE_ERROR = Pattern.compile(
        "^\\[ERROR\\]\\s*(?:-> \\[Help 1\\]|To see the full stack trace|Re-run Maven using|For more information|\\[Help 1\\]).*"
    );

    private static final Pattern SUREFIRE_SUMMARY_HEADER = Pattern.compile(
        "^\\[ERROR\\]\\s*(?:Failures|Errors):\\s*$"
    );

    private static final Pattern SUREFIRE_SUMMARY_FOOTER = Pattern.compile(
        "^\\[ERROR\\]\\s*Tests run:\\s*\\d+,\\s*Failures:\\s*\\d+.*"
    );

    private static final Pattern EXCEPTION_HEADER = Pattern.compile(
        "^(?:[a-zA-Z0-9_$.]+(?:Exception|Error|Failure)|[a-zA-Z0-9_$.]+Error):\\s*.*"
    );

    public SanitizedLog parse(String rawStdout, String rawStderr) {
        return parseInternal(null, rawStdout, rawStderr);
    }

    public SanitizedLog parse(int exitCode, String rawStdout, String rawStderr) {
        return parseInternal(exitCode, rawStdout, rawStderr);
    }

    private SanitizedLog parseInternal(Integer explicitExitCode, String rawStdout, String rawStderr) {
        List<String> errorLines = new ArrayList<>();
        StringBuilder cleanStdoutBuilder = new StringBuilder();
        StringBuilder cleanStderrBuilder = new StringBuilder();

        if (rawStdout != null && !rawStdout.isBlank()) {
            processLogStream(rawStdout, cleanStdoutBuilder, errorLines);
        }

        if (rawStderr != null && !rawStderr.isBlank()) {
            processLogStream(rawStderr, cleanStderrBuilder, errorLines);
        }

        String cleanStdout = cleanStdoutBuilder.toString().stripTrailing();
        String cleanStderr = cleanStderrBuilder.toString().stripTrailing();

        int effectiveExitCode = (explicitExitCode != null)
            ? explicitExitCode
            : (errorLines.isEmpty() ? 0 : 1);

        // Invariant: If exitCode != 0, critical diagnostics must NEVER be omitted
        if (effectiveExitCode != 0 && errorLines.isEmpty()) {
            recoverOmittedErrors(cleanStdout, cleanStderr, errorLines);
        }

        return SanitizedLog.builder()
            .exitCode(effectiveExitCode)
            .errorCount(errorLines.size())
            .errorLines(Collections.unmodifiableList(errorLines))
            .cleanStdout(cleanStdout)
            .cleanStderr(cleanStderr)
            .build();
    }

    private static void processLogStream(String rawStream, StringBuilder cleanBuilder, List<String> errorLines) {
        String[] lines = rawStream.split("\\r?\\n");
        boolean inErrorBlock = false;

        for (String line : lines) {
            if (isDownloadNoise(line)) {
                inErrorBlock = false;
                continue;
            }
            cleanBuilder.append(line).append(System.lineSeparator());

            if (isMeaningfulErrorLine(line)) {
                errorLines.add(line.trim());
                inErrorBlock = true;
            } else if (inErrorBlock && isErrorContinuation(line)) {
                int lastIdx = errorLines.size() - 1;
                errorLines.set(lastIdx, errorLines.get(lastIdx) + "\n" + line.trim());
            } else {
                inErrorBlock = false;
            }
        }
    }

    private static boolean isDownloadNoise(String line) {
        String trimmed = line.trim();
        return DOWNLOAD_PROGRESS.matcher(trimmed).matches();
    }

    private static boolean isMeaningfulErrorLine(String line) {
        String trimmed = line.trim();
        if (MAVEN_NOISE_ERROR.matcher(trimmed).matches() || SUREFIRE_SUMMARY_HEADER.matcher(trimmed).matches()) {
            return false;
        }
        if (SUREFIRE_SUMMARY_FOOTER.matcher(trimmed).matches() && !trimmed.contains("<<< FAILURE!")) {
            return false;
        }
        if (trimmed.startsWith("[ERROR]") && !trimmed.equals("[ERROR]") && !trimmed.equals("[ERROR] BUILD FAILURE")) {
            return true;
        }
        if (trimmed.contains("<<< FAILURE!") || trimmed.contains("<<< ERROR!")) {
            return true;
        }
        if (trimmed.contains("ERROR [") || trimmed.contains("FATAL [")) {
            return true;
        }
        if (trimmed.startsWith("Fatal error") || trimmed.startsWith("javac:")) {
            return true;
        }
        return EXCEPTION_HEADER.matcher(trimmed).matches();
    }

    private static boolean isErrorContinuation(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("[INFO]") || trimmed.startsWith("[WARNING]") || trimmed.startsWith("[DEBUG]")) {
            return false;
        }
        if (line.startsWith("  ") || line.startsWith("\t")) {
            return true;
        }
        if (trimmed.startsWith("at ") || trimmed.startsWith("Caused by:") || trimmed.startsWith("Suppressed:")
            || (trimmed.startsWith("...") && trimmed.endsWith("more"))) {
            return true;
        }
        return EXCEPTION_HEADER.matcher(trimmed).matches();
    }

    private static void recoverOmittedErrors(String cleanStdout, String cleanStderr, List<String> errorLines) {
        if (!cleanStderr.isBlank()) {
            for (String errLine : cleanStderr.split("\\r?\\n")) {
                String trimmed = errLine.trim();
                if (!trimmed.isBlank()) {
                    errorLines.add(trimmed);
                }
            }
            return;
        }

        if (!cleanStdout.isBlank()) {
            String[] stdoutLines = cleanStdout.split("\\r?\\n");
            for (String outLine : stdoutLines) {
                String trimmed = outLine.trim();
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (trimmed.contains("OutOfMemoryError")
                    || trimmed.contains("BUILD FAILURE")
                    || lower.contains("fatal")
                    || lower.contains("error")) {
                    errorLines.add(trimmed);
                }
            }
            if (errorLines.isEmpty() && stdoutLines.length > 0) {
                errorLines.add(stdoutLines[stdoutLines.length - 1].trim());
            }
        }
    }
}
