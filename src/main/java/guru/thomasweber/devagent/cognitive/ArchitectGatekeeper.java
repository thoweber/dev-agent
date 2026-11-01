package guru.thomasweber.devagent.cognitive;

import guru.thomasweber.devagent.model.ArchitecturalRecord;
import guru.thomasweber.devagent.model.GateVerdict;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Cached Gatekeeper service memoizing deterministic invariant evaluations backed by Caffeine (ADR-009).
 */
@ApplicationScoped
public class ArchitectGatekeeper {

    private final UniversalArchitectGatekeeperAiService gatekeeperAiService;

    @Inject
    public ArchitectGatekeeper(UniversalArchitectGatekeeperAiService gatekeeperAiService) {
        this.gatekeeperAiService = gatekeeperAiService;
    }

    @CacheResult(cacheName = "architect-verdicts", keyGenerator = GatekeeperCacheKeyGenerator.class)
    public GateVerdict evaluate(String targetContent, String retrievedAdrs) {
        return gatekeeperAiService.evaluate(targetContent, retrievedAdrs);
    }

    @CacheResult(cacheName = "architect-verdicts", keyGenerator = GatekeeperCacheKeyGenerator.class)
    public GateVerdict evaluate(String targetContent, List<ArchitecturalRecord> records) {
        String promptAdrs = formatRecords(records);
        return gatekeeperAiService.evaluate(targetContent, promptAdrs);
    }

    private static String formatRecords(List<ArchitecturalRecord> records) {
        if (records == null || records.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("### RETRIEVED ARCHITECTURAL DECISIONS & RULES:\n");
        for (ArchitecturalRecord record : records) {
            sb.append(String.format("- [%s] %s (Status: %s)%n", record.id(), record.title(), record.status()));
            if (record.summary() != null && !record.summary().isBlank()) {
                sb.append("  Summary: ").append(record.summary().strip()).append("\n");
            }
            if (record.content() != null && !record.content().isBlank()) {
                sb.append("  Content: ").append(record.content().strip()).append("\n");
            }
        }
        return sb.toString();
    }
}
