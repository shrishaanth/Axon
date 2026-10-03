package io.github.shrishaanth.axon.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Hidden ground truth about what each client really does: what it sends, and which response fields its code
 * reads. Written by the traffic generator and by the demo clients; never shown to Axon.
 *
 * <p>The walker over request bodies below is written independently of Axon's shape extractor on purpose.
 */
public final class Truth {

    /** What one client does on one operation. */
    public static final class OpTruth {
        public long calls;
        /** Calls that carried a JSON body. */
        public long bodies;
        public Instant first;
        public Instant last;
        /** Request field path (body) or {@code ?name} (query) to the number of calls that carried it. */
        public final Map<String, Long> sent = new TreeMap<>();
        /** Body field path to (JSON type to count). */
        public final Map<String, Map<String, Long>> types = new TreeMap<>();
        /** Body field or query parameter to (string value to count). */
        public final Map<String, Map<String, Long>> values = new TreeMap<>();
        /** Response status to the field paths the client's code reads from that response. */
        public final Map<String, Set<String>> reads = new TreeMap<>();
        public final Map<String, Long> statuses = new TreeMap<>();
        /** For generated traffic: the calling rate per day the profile prescribes. */
        public double ratePerDay;
    }

    /** client name to (operation key to truth). */
    public final Map<String, Map<String, OpTruth>> clients = new TreeMap<>();

    public OpTruth op(String client, String operation) {
        return clients.computeIfAbsent(client, c -> new TreeMap<>()).computeIfAbsent(operation, o -> new OpTruth());
    }

    public void call(String client, String operation, Instant ts, int status, JsonNode requestBody,
                     Map<String, String> query) {
        OpTruth t = op(client, operation);
        t.calls++;
        if (t.first == null || ts.isBefore(t.first)) {
            t.first = ts;
        }
        if (t.last == null || ts.isAfter(t.last)) {
            t.last = ts;
        }
        t.statuses.merge(Integer.toString(status), 1L, Long::sum);
        for (Map.Entry<String, String> q : query.entrySet()) {
            t.sent.merge("?" + q.getKey(), 1L, Long::sum);
            t.values.computeIfAbsent("?" + q.getKey(), k -> new TreeMap<>()).merge(q.getValue(), 1L, Long::sum);
        }
        if (requestBody != null) {
            t.bodies++;
            Set<String> seen = new TreeSet<>();
            walk(requestBody, "$", t, seen);
            for (String path : seen) {
                t.sent.merge(path, 1L, Long::sum);
            }
        }
    }

    public void read(String client, String operation, int status, String path) {
        op(client, operation).reads.computeIfAbsent(Integer.toString(status), s -> new TreeSet<>()).add(path);
    }

    private static void walk(JsonNode node, String path, OpTruth t, Set<String> seen) {
        seen.add(path);
        String type;
        if (node.isNull()) {
            type = "null";
        } else if (node.isObject()) {
            type = "object";
        } else if (node.isArray()) {
            type = "array";
        } else if (node.isBoolean()) {
            type = "boolean";
        } else if (node.isNumber()) {
            type = node.asDouble() == Math.floor(node.asDouble()) ? "integer" : "number";
        } else {
            type = "string";
        }
        t.types.computeIfAbsent(path, p -> new TreeMap<>()).merge(type, 1L, Long::sum);
        if (node.isTextual()) {
            t.values.computeIfAbsent(path, p -> new TreeMap<>()).merge(node.asText(), 1L, Long::sum);
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                walk(e.getValue(), path + "." + e.getKey(), t, seen);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                walk(element, path + "[]", t, seen);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------

    private static final ObjectMapper JSON = new ObjectMapper();

    public void write(Path file) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode cs = root.putObject("clients");
        for (Map.Entry<String, Map<String, OpTruth>> c : clients.entrySet()) {
            ObjectNode ops = cs.putObject(c.getKey());
            for (Map.Entry<String, OpTruth> o : c.getValue().entrySet()) {
                OpTruth t = o.getValue();
                ObjectNode n = ops.putObject(o.getKey());
                n.put("calls", t.calls);
                n.put("bodies", t.bodies);
                n.put("first", t.first == null ? null : t.first.toString());
                n.put("last", t.last == null ? null : t.last.toString());
                n.put("rate_per_day", t.ratePerDay);
                n.set("sent", JSON.valueToTree(t.sent));
                n.set("types", JSON.valueToTree(t.types));
                n.set("values", JSON.valueToTree(t.values));
                n.set("reads", JSON.valueToTree(t.reads));
                n.set("statuses", JSON.valueToTree(t.statuses));
            }
        }
        if (file.toAbsolutePath().getParent() != null) {
            java.nio.file.Files.createDirectories(file.toAbsolutePath().getParent());
        }
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
    }

    public static Truth read(Path file) throws IOException {
        Truth truth = new Truth();
        JsonNode root = JSON.readTree(file.toFile());
        Iterator<Map.Entry<String, JsonNode>> cs = root.path("clients").fields();
        while (cs.hasNext()) {
            Map.Entry<String, JsonNode> c = cs.next();
            Iterator<Map.Entry<String, JsonNode>> ops = c.getValue().fields();
            while (ops.hasNext()) {
                Map.Entry<String, JsonNode> o = ops.next();
                JsonNode n = o.getValue();
                OpTruth t = truth.op(c.getKey(), o.getKey());
                t.calls = n.path("calls").asLong();
                t.bodies = n.path("bodies").asLong();
                t.first = n.hasNonNull("first") ? Instant.parse(n.get("first").asText()) : null;
                t.last = n.hasNonNull("last") ? Instant.parse(n.get("last").asText()) : null;
                t.ratePerDay = n.path("rate_per_day").asDouble();
                n.path("sent").fields().forEachRemaining(e -> t.sent.put(e.getKey(), e.getValue().asLong()));
                n.path("statuses").fields()
                        .forEachRemaining(e -> t.statuses.put(e.getKey(), e.getValue().asLong()));
                n.path("types").fields().forEachRemaining(e -> {
                    Map<String, Long> m = t.types.computeIfAbsent(e.getKey(), k -> new TreeMap<>());
                    e.getValue().fields().forEachRemaining(v -> m.put(v.getKey(), v.getValue().asLong()));
                });
                n.path("values").fields().forEachRemaining(e -> {
                    Map<String, Long> m = t.values.computeIfAbsent(e.getKey(), k -> new TreeMap<>());
                    e.getValue().fields().forEachRemaining(v -> m.put(v.getKey(), v.getValue().asLong()));
                });
                n.path("reads").fields().forEachRemaining(e -> {
                    Set<String> s = t.reads.computeIfAbsent(e.getKey(), k -> new TreeSet<>());
                    e.getValue().forEach(v -> s.add(v.asText()));
                });
            }
        }
        return truth;
    }
}
