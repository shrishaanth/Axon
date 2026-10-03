package io.github.shrishaanth.axon.traffic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.spec.JsonType;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * One sanitised request/response pair: shapes, never values (see schemas/traffic-event.schema.json).
 *
 * @param operation    "METHOD /template" when the sanitiser matched the request against a spec, else null
 * @param path         redacted path, used when {@code operation} is null
 * @param client       pseudonymous client id, or null when no identity is configured
 * @param query        query parameters seen, as {@code $.name} fields; never null
 * @param requestBody  fields of the JSON request body, or null when there was no JSON body
 * @param responseBody fields of the JSON response body, or null when there was no JSON body
 */
public record TrafficEvent(
        Instant ts,
        String method,
        String operation,
        String path,
        int status,
        Double latencyMs,
        String client,
        List<Field> query,
        List<Field> requestBody,
        List<Field> responseBody,
        boolean truncated) {

    /**
     * @param values kept only for fields the spec declares as an enum, at most {@link #MAX_VALUES}; else null
     */
    public record Field(String path, Set<JsonType> types, List<String> values) {
    }

    public static final int MAX_VALUES = 16;

    private static final ObjectMapper JSON = new ObjectMapper();

    public String toJson() {
        ObjectNode o = JSON.createObjectNode();
        o.put("v", 1);
        o.put("ts", ts.toString());
        o.put("method", method);
        if (operation != null) {
            o.put("operation", operation);
        } else {
            o.put("path", path);
        }
        o.put("status", status);
        if (latencyMs != null) {
            o.put("latency_ms", latencyMs);
        }
        if (client != null) {
            o.put("client", client);
        }
        if (!query.isEmpty() || requestBody != null) {
            ObjectNode request = o.putObject("request");
            if (!query.isEmpty()) {
                write(request.putArray("query"), query);
            }
            if (requestBody != null) {
                write(request.putArray("body"), requestBody);
            }
        }
        if (responseBody != null) {
            ObjectNode response = o.putObject("response");
            write(response.putArray("body"), responseBody);
            if (truncated) {
                response.put("truncated", true);
            }
        }
        return o.toString();
    }

    private static void write(ArrayNode array, List<Field> fields) {
        for (Field f : fields) {
            ObjectNode n = array.addObject();
            n.put("path", f.path());
            ArrayNode types = n.putArray("types");
            for (JsonType t : f.types()) {
                types.add(t.wire());
            }
            if (f.values() != null && !f.values().isEmpty()) {
                ArrayNode values = n.putArray("values");
                f.values().forEach(values::add);
            }
        }
    }

    /** Parses one JSONL line. Throws {@link IllegalArgumentException} with a reason when the line is not valid. */
    public static TrafficEvent fromJson(String line) {
        JsonNode o;
        try {
            o = JSON.readTree(line);
        } catch (Exception e) {
            throw new IllegalArgumentException("not JSON: " + e.getMessage());
        }
        if (o == null || !o.isObject()) {
            throw new IllegalArgumentException("event is not a JSON object");
        }
        if (o.path("v").asInt(0) != 1) {
            throw new IllegalArgumentException("unsupported event version: " + o.path("v"));
        }
        Instant ts;
        try {
            ts = Instant.parse(o.path("ts").asText());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("bad 'ts': " + o.path("ts").asText());
        }
        String method = o.path("method").asText(null);
        String operation = o.path("operation").asText(null);
        String path = o.path("path").asText(null);
        if (method == null || (operation == null && path == null) || !o.path("status").isInt()) {
            throw new IllegalArgumentException("event needs method, status and one of operation/path");
        }
        JsonNode request = o.path("request");
        JsonNode response = o.path("response");
        return new TrafficEvent(ts, method, operation, path, o.get("status").asInt(),
                o.has("latency_ms") ? o.get("latency_ms").asDouble() : null,
                o.path("client").asText(null),
                request.has("query") ? read(request.get("query")) : List.of(),
                request.has("body") ? read(request.get("body")) : null,
                response.has("body") ? read(response.get("body")) : null,
                request.path("truncated").asBoolean(false) || response.path("truncated").asBoolean(false));
    }

    private static List<Field> read(JsonNode array) {
        List<Field> out = new ArrayList<>();
        for (JsonNode n : array) {
            Set<JsonType> types = EnumSet.noneOf(JsonType.class);
            for (JsonNode t : n.path("types")) {
                JsonType type = JsonType.fromWire(t.asText());
                if (type == null) {
                    throw new IllegalArgumentException("unknown type: " + t.asText());
                }
                types.add(type);
            }
            String path = n.path("path").asText(null);
            if (path == null || !path.startsWith("$") || types.isEmpty()) {
                throw new IllegalArgumentException("field needs a '$' path and at least one type");
            }
            List<String> values = null;
            if (n.has("values")) {
                values = new ArrayList<>();
                for (JsonNode v : n.get("values")) {
                    values.add(v.asText());
                }
            }
            out.add(new Field(path, types, values));
        }
        return out;
    }
}
