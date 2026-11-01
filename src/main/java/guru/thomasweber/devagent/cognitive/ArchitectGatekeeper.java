package guru.thomasweber.devagent.cognitive;

import guru.thomasweber.devagent.model.GateVerdict;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
}
