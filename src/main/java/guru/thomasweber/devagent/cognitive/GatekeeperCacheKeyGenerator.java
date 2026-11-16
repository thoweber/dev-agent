package guru.thomasweber.devagent.cognitive;

import guru.thomasweber.devagent.model.ArchitecturalRecord;
import guru.thomasweber.devagent.workspace.DigestUtils;
import io.quarkus.cache.CacheKeyGenerator;
import jakarta.enterprise.context.ApplicationScoped;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Deterministic cache key generator for Architectural Gatekeeper evaluations (ADR-009).
 * Computes: SHA-256(MethodSignature + UnifiedDiff + DeduplicatedSortedRetrievedAdrs)
 */
@ApplicationScoped
public class GatekeeperCacheKeyGenerator implements CacheKeyGenerator {

    @Override
    public Object generate(Method method, Object... methodParams) {
        String methodSig = formatMethodSignature(method);
        String diff = extractDiff(methodParams);
        String adrs = extractAdrs(methodParams);
        String compositePayload = "METHOD:" + frame(methodSig) + "\nDIFF:" + frame(diff) + "\nADRS:" + frame(adrs);
        return DigestUtils.sha256Hex(compositePayload);
    }

    private static String formatMethodSignature(Method method) {
        if (isNull(method)) {
            return "";
        }
        String params = Arrays.stream(method.getParameterTypes())
            .map(Class::getName)
            .collect(Collectors.joining(","));
        return method.getName() + "(" + params + ")";
    }

    private static String extractDiff(Object[] methodParams) {
        if (nonNull(methodParams) && methodParams.length > 0 && nonNull(methodParams[0])) {
            return methodParams[0].toString().strip();
        }
        return "";
    }

    private static String extractAdrs(Object[] methodParams) {
        if (isNull(methodParams) || methodParams.length <= 1 || isNull(methodParams[1])) {
            return "";
        }
        Object second = methodParams[1];
        if (second instanceof List<?> list) {
            Set<String> uniqueItems = new LinkedHashSet<>();
            for (Object item : list) {
                if (nonNull(item)) {
                    uniqueItems.add(formatRecordItem(item));
                }
            }
            return uniqueItems.stream().sorted().collect(Collectors.joining("\n---\n"));
        }
        return second.toString().strip();
    }

    private static String formatRecordItem(Object item) {
        if (item instanceof ArchitecturalRecord rec) {
            String summary = Objects.toString(rec.summary(), "").strip();
            String content = Objects.toString(rec.content(), "").strip();
            return frame(Objects.toString(rec.id(), ""))
                + frame(Objects.toString(rec.title(), ""))
                + frame(Objects.toString(rec.status(), ""))
                + frame(summary)
                + frame(content);
        }
        return item.toString().strip();
    }

    private static String frame(String value) {
        return value.length() + ":" + value;
    }
}
