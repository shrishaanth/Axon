package io.github.shrishaanth.axon.spec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed OpenAPI document.
 *
 * @param basePaths path prefixes taken from {@code servers[].url} (for example "/v1"), used by the matcher
 */
public record ApiSpec(
        String title,
        String version,
        String openapi,
        List<String> basePaths,
        List<Operation> operations,
        List<Unsupported> unsupported,
        int componentSchemas) {

    public Operation operation(String method, String path) {
        for (Operation op : operations) {
            if (op.method().equals(method) && op.path().equals(path)) {
                return op;
            }
        }
        return null;
    }

    public Map<String, Integer> unsupportedCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Unsupported u : unsupported) {
            counts.merge(u.construct(), 1, Integer::sum);
        }
        return counts;
    }
}
