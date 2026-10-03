package io.github.shrishaanth.axon.spec;

import io.github.shrishaanth.axon.util.FieldPath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Lists the fields a schema declares, as {@code $.a.b[].c} paths. Used by the explorer; the diff and drift
 * engines walk schemas directly instead, because flattening a large recursive schema explodes.
 */
public final class ShapeFlattener {

    /** Which side of the wire a schema describes. {@code readOnly} fields are absent from requests. */
    public enum Direction { REQUEST, RESPONSE }

    /**
     * @param recursive true when the walk stopped here because the schema is already on the path
     */
    public record Field(String path, List<String> types, boolean required, List<String> enumValues,
                        String format, boolean recursive) {
    }

    public record Result(List<Field> fields, boolean truncated) {
    }

    private final Direction direction;
    private final int maxDepth;
    private final int maxFields;
    private final List<Field> out = new ArrayList<>();
    private final Set<Schema> onPath = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean truncated;

    private ShapeFlattener(Direction direction, int maxDepth, int maxFields) {
        this.direction = direction;
        this.maxDepth = maxDepth;
        this.maxFields = maxFields;
    }

    public static Result flatten(Schema root, Direction direction, int maxDepth, int maxFields) {
        ShapeFlattener f = new ShapeFlattener(direction, maxDepth, maxFields);
        if (root != null) {
            f.walk(root, FieldPath.ROOT, true, 0);
        }
        return new Result(List.copyOf(f.out), f.truncated);
    }

    public static boolean visible(Schema property, Direction direction) {
        return direction == Direction.REQUEST ? !property.readOnly() : !property.writeOnly();
    }

    public static List<String> typeNames(Schema s) {
        Set<String> names = new TreeSet<>();
        for (JsonType t : s.types()) {
            names.add(t.wire());
        }
        return List.copyOf(names);
    }

    private void walk(Schema s, String path, boolean required, int depth) {
        if (out.size() >= maxFields) {
            truncated = true;
            return;
        }
        boolean cycle = onPath.contains(s);
        out.add(new Field(path, typeNames(s), required, s.enumValues(), s.format(), cycle));
        if (cycle) {
            return;
        }
        if (depth >= maxDepth) {
            if (!s.properties().isEmpty() || s.items() != null) {
                truncated = true;
            }
            return;
        }
        onPath.add(s);
        Set<String> req = s.required();
        for (Map.Entry<String, Schema> e : s.properties().entrySet()) {
            if (visible(e.getValue(), direction)) {
                walk(e.getValue(), FieldPath.child(path, e.getKey()), req.contains(e.getKey()), depth + 1);
            }
        }
        Schema additional = s.additionalSchema();
        if (additional != null) {
            walk(additional, FieldPath.wildcard(path), false, depth + 1);
        }
        Schema items = s.items();
        if (items != null) {
            walk(items, FieldPath.items(path), false, depth + 1);
        }
        onPath.remove(s);
    }
}
