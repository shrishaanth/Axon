package io.github.shrishaanth.axon.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shrishaanth.axon.diff.Change;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides, from hidden ground truth, which clients a change really affects. It reads {@link Truth} only and
 * shares no code with Axon's impact engine.
 */
public final class TruthOracle {

    private static final ObjectMapper JSON = new ObjectMapper();

    private TruthOracle() {
    }

    /**
     * Names of the truly affected clients, or null when the truth does not define an answer for this kind of
     * change (status-code removals, media types, header parameters, a query parameter's type).
     */
    public static Set<String> affected(Change c, Truth truth) {
        String kind = c.kind().wire();
        boolean query = "request.query".equals(c.part());
        boolean body = "request.body".equals(c.part());
        if (kind.startsWith("request_field") || kind.startsWith("request_enum")) {
            if (!query && !body) {
                return null;
            }
            if (query && kind.equals("request_field_type_changed")) {
                return null;
            }
        }
        if (kind.startsWith("request_body") || kind.startsWith("media_type") || kind.startsWith("response_status")) {
            return null;
        }
        String key = c.field() == null ? null : query ? "?" + c.field().substring(2) : c.field();
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, Map<String, Truth.OpTruth>> client : truth.clients.entrySet()) {
            Truth.OpTruth t = client.getValue().get(c.operationKey());
            if (t == null || t.calls == 0) {
                continue;
            }
            boolean hit;
            switch (kind) {
                case "operation_removed" -> hit = true;
                case "request_field_removed" -> hit = t.sent.getOrDefault(key, 0L) > 0;
                case "request_field_made_required", "request_field_added_required" -> {
                    long parent;
                    if (query) {
                        parent = t.calls;
                    } else {
                        String parentPath = parentOf(c.field());
                        parent = parentPath == null ? 0 : t.sent.getOrDefault(parentPath, 0L);
                    }
                    hit = parent - t.sent.getOrDefault(key, 0L) > 0;
                }
                case "request_field_type_changed" -> {
                    Set<String> accepted = new HashSet<>();
                    if (c.to() == null || c.to().equals("any")) {
                        hit = false;
                        break;
                    }
                    for (String name : c.to().split("\\|")) {
                        accepted.add(name);
                    }
                    if (accepted.contains("number")) {
                        accepted.add("integer");
                    }
                    hit = t.types.getOrDefault(key, Map.of()).keySet().stream().anyMatch(x -> !accepted.contains(x));
                }
                case "request_enum_narrowed" -> {
                    Set<String> before = values(c.from());
                    Set<String> after = values(c.to());
                    if (after == null) {
                        hit = false;
                    } else if (before == null) {
                        // the field became an enum: any value outside the new list is rejected
                        hit = t.values.getOrDefault(key, Map.of()).keySet().stream().anyMatch(v -> !after.contains(v));
                    } else {
                        before.removeAll(after);
                        hit = t.values.getOrDefault(key, Map.of()).keySet().stream().anyMatch(before::contains);
                    }
                }
                default -> {
                    if (!kind.startsWith("response_field") && !kind.startsWith("response_enum")) {
                        return null;
                    }
                    Set<String> reads = t.reads.get(c.status());
                    hit = reads != null && reads.contains(c.field());
                }
            }
            if (hit) {
                out.add(client.getKey());
            }
        }
        return out;
    }

    private static String parentOf(String path) {
        if (path.endsWith("[]")) {
            return path.substring(0, path.length() - 2);
        }
        int dot = path.lastIndexOf('.');
        return dot <= 0 ? null : path.substring(0, dot);
    }

    private static Set<String> values(String json) {
        if (json == null) {
            return null;
        }
        try {
            Set<String> out = new HashSet<>();
            for (JsonNode v : JSON.readTree(json)) {
                out.add(v.asText());
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
