package guru.thomasweber.devagent.cognitive;

import guru.thomasweber.devagent.model.ArchitecturalRecord;
import guru.thomasweber.devagent.model.GateVerdict;
import guru.thomasweber.devagent.workspace.SecretRedactionFilter;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Cached Gatekeeper service memoizing deterministic invariant evaluations backed by Caffeine (ADR-009).
 */
@ApplicationScoped
public class ArchitectGatekeeper {

    private static final Logger LOG = Logger.getLogger(ArchitectGatekeeper.class);
    private static final Pattern DELIMITER_TAG_PATTERN = Pattern.compile("(?i)</?(target_content|evaluation_data)>");

    private final UniversalArchitectGatekeeperAiService gatekeeperAiService;
    private final SecretRedactionFilter secretFilter;

    @Inject
    public ArchitectGatekeeper(
        UniversalArchitectGatekeeperAiService gatekeeperAiService,
        SecretRedactionFilter secretFilter
    ) {
        this.gatekeeperAiService = gatekeeperAiService;
        this.secretFilter = Objects.requireNonNullElseGet(secretFilter, SecretRedactionFilter::new);
    }

    public ArchitectGatekeeper(UniversalArchitectGatekeeperAiService gatekeeperAiService) {
        this(gatekeeperAiService, new SecretRedactionFilter());
    }

    @CacheResult(cacheName = "architect-verdicts", keyGenerator = GatekeeperCacheKeyGenerator.class)
    public GateVerdict evaluate(String targetContent, String retrievedAdrs) {
        String safeTarget = sanitizePromptData(targetContent);
        String safeAdrs = sanitizePromptData(retrievedAdrs);
        LOG.debugf("Evaluating gatekeeper invariants (targetChars=%d, adrChars=%d)", safeTarget.length(), safeAdrs.length());
        return gatekeeperAiService.evaluate(safeTarget, safeAdrs);
    }

    @CacheResult(cacheName = "architect-verdicts", keyGenerator = GatekeeperCacheKeyGenerator.class)
    public GateVerdict evaluate(String targetContent, List<ArchitecturalRecord> records) {
        String safeTarget = sanitizePromptData(targetContent);
        String safeAdrs = sanitizePromptData(formatRecords(records));
        LOG.debugf("Evaluating gatekeeper invariants (targetChars=%d, recordCount=%d)", safeTarget.length(), isNull(records) ? 0 : records.size());
        return gatekeeperAiService.evaluate(safeTarget, safeAdrs);
    }

    private String sanitizePromptData(String raw) {
        if (isNull(raw) || raw.isEmpty()) {
            return Objects.toString(raw, "");
        }
        String scrubbed = secretFilter.sanitize(raw).value();
        return DELIMITER_TAG_PATTERN.matcher(scrubbed).replaceAll(match -> "&lt;" + match.group().substring(1));
    }

    private static String formatRecords(List<ArchitecturalRecord> records) {
        if (isNull(records) || records.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("### RETRIEVED ARCHITECTURAL DECISIONS & RULES:%n"));
        for (ArchitecturalRecord rec : records) {
            sb.append(String.format("- [%s] %s (Status: %s)%n", rec.id(), rec.title(), rec.status()));
            if (nonNull(rec.summary()) && !rec.summary().isBlank()) {
                sb.append(String.format("  Summary: %s%n", rec.summary().strip()));
            }
            if (nonNull(rec.content()) && !rec.content().isBlank()) {
                sb.append(String.format("  Content: %s%n", rec.content().strip()));
            }
        }
        return sb.toString();
    }
}
