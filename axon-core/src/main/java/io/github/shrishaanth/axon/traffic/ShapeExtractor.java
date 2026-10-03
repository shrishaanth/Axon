package io.github.shrishaanth.axon.traffic;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.shrishaanth.axon.spec.JsonType;
import io.github.shrishaanth.axon.spec.Schema;
import io.github.shrishaanth.axon.util.FieldPath;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reduces a JSON body to its shape: which field paths are present and with which types. Values are discarded,
 * except for fields the given schema declares as an enum (the only values an impact question needs).
 */
public final class ShapeExtractor {

    public static final int MAX_FIELDS = 500;
    public static final int MAX_DEPTH = 10;

    public record Shape(List<TrafficEvent.Field> fields, boolean truncated) {
    }

    private final Map<String, Set<JsonType>> types = new LinkedHashMap<>();
    private final Map<String, Set<String>> values = new LinkedHashMap<>();
    private boolean truncated;

    private ShapeExtractor() {
    }

    /** {@code schema} may be null: then no values are kept at all. */
    public static Shape extract(JsonNode body, Schema schema) {
        ShapeExtractor e = new ShapeExtractor();
        e.walk(body, schema, FieldPath.ROOT, 0);
        List<TrafficEvent.Field> fields = new ArrayList<>();
        for (Map.Entry<String, Set<JsonType>> entry : e.types.entrySet()) {
            Set<String> kept = e.values.get(entry.getKey());
            fields.add(new TrafficEvent.Field(entry.getKey(), entry.getValue(),
                    kept == null ? null : List.copyOf(kept)));
        }
        return new Shape(fields, e.truncated);
    }

    /** JSON has no integer type: a number with no fractional part counts as integer. */
    public static JsonType typeOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return JsonType.NULL;
        }
        if (node.isObject()) {
            return JsonType.OBJECT;
        }
        if (node.isArray()) {
            return JsonType.ARRAY;
        }
        if (node.isBoolean()) {
            return JsonType.BOOLEAN;
        }
        if (node.isNumber()) {
            if (node.isIntegralNumber()) {
                return JsonType.INTEGER;
            }
            double d = node.asDouble();
            return d == Math.rint(d) && !Double.isInfinite(d) ? JsonType.INTEGER : JsonType.NUMBER;
        }
        return JsonType.STRING;
    }

    private void walk(JsonNode node, Schema schema, String path, int depth) {
        Set<JsonType> seen = types.get(path);
        if (seen == null) {
            if (types.size() >= MAX_FIELDS) {
                truncated = true;
                return;
            }
            seen = EnumSet.noneOf(JsonType.class);
            types.put(path, seen);
        }
        JsonType type = typeOf(node);
        seen.add(type);

        if (schema != null && node.isValueNode() && !node.isNull() && schema.enumValues() != null) {
            Set<String> kept = values.computeIfAbsent(path, p -> new LinkedHashSet<>());
            if (kept.size() < TrafficEvent.MAX_VALUES) {
                kept.add(node.asText());
            }
        }
        if (depth >= MAX_DEPTH) {
            if (node.isContainerNode() && !node.isEmpty()) {
                truncated = true;
            }
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                Schema child = null;
                if (schema != null) {
                    child = schema.properties().get(e.getKey());
                    if (child == null) {
                        child = schema.additionalSchema();
                    }
                }
                walk(e.getValue(), child, FieldPath.child(path, e.getKey()), depth + 1);
            }
        } else if (node.isArray()) {
            Schema items = schema == null ? null : schema.items();
            String itemPath = FieldPath.items(path);
            for (JsonNode element : node) {
                walk(element, items, itemPath, depth + 1);
            }
        }
    }
}
