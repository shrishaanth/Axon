package io.github.shrishaanth.axon.diff;

import io.github.shrishaanth.axon.spec.JsonType;
import io.github.shrishaanth.axon.spec.Schema;
import io.github.shrishaanth.axon.spec.ShapeFlattener;
import io.github.shrishaanth.axon.spec.ShapeFlattener.Direction;
import io.github.shrishaanth.axon.util.FieldPath;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares two schema graphs by walking them in step. Results are relative to the pair of schemas compared, so a
 * shared component is compared once and its changes are re-used under every path that reaches it.
 *
 * <p>Compatibility rules (integer counts as a subset of number; "no type" accepts everything):
 * <ul>
 *   <li>request: breaking when the candidate rejects something the baseline accepted</li>
 *   <li>response: breaking when the candidate may send something the baseline did not allow, or stops
 *       guaranteeing something it did</li>
 * </ul>
 */
final class SchemaDiff {

    /** A change located relative to the compared schemas: {@code field} starts with {@code $}. */
    record Rel(ChangeKind kind, boolean breaking, String field, String from, String to, String source) {
        Rel under(String parentPath) {
            return new Rel(kind, breaking, parentPath + field.substring(1), from, to, source);
        }
    }

    /** One pair's change list never grows past this; the result is then flagged truncated. */
    static final int MAX_CHANGES_PER_PAIR = 300;

    private record Pair(Schema a, Schema b) {
    }

    private final Direction direction;
    private final Map<Pair, List<Rel>> done = new HashMap<>();
    private final Set<Pair> inProgress = new HashSet<>();
    private Set<Pair> cuts = new HashSet<>();
    private boolean truncated;

    SchemaDiff(Direction direction) {
        this.direction = direction;
    }

    boolean truncated() {
        return truncated;
    }

    List<Rel> diff(Schema a, Schema b) {
        if (a == null && b == null) {
            return List.of();
        }
        Pair pair = new Pair(a == null ? Schema.any() : a, b == null ? Schema.any() : b);
        List<Rel> cached = done.get(pair);
        if (cached != null) {
            return cached;
        }
        if (!inProgress.add(pair)) {
            // Already comparing this pair further up: a recursive schema. Its changes are reported there.
            cuts.add(pair);
            return List.of();
        }
        Set<Pair> outer = cuts;
        cuts = new HashSet<>();
        List<Rel> result = List.copyOf(compute(pair.a(), pair.b()));
        inProgress.remove(pair);
        cuts.remove(pair);
        if (cuts.isEmpty()) {
            done.put(pair, result); // complete: no cut below depends on a pair still being compared
        }
        outer.addAll(cuts);
        cuts = outer;
        return result;
    }

    private List<Rel> compute(Schema a, Schema b) {
        List<Rel> out = new ArrayList<>();
        boolean request = direction == Direction.REQUEST;

        Set<JsonType> accA = accepted(a);
        Set<JsonType> accB = accepted(b);
        if (!accA.equals(accB)) {
            boolean breaking = request ? !accB.containsAll(accA) : !accA.containsAll(accB);
            add(out, new Rel(request ? ChangeKind.REQUEST_FIELD_TYPE_CHANGED : ChangeKind.RESPONSE_FIELD_TYPE_CHANGED,
                    breaking, FieldPath.ROOT, typeText(a), typeText(b), a.pointer()));
        }

        List<String> enumA = a.enumValues();
        List<String> enumB = b.enumValues();
        if (enumA != null || enumB != null) {
            boolean restricted = enumB != null && (enumA == null || !enumB.containsAll(enumA));
            boolean widened = enumA != null && (enumB == null || !enumA.containsAll(enumB));
            // JSON arrays of the allowed values; null stands for "any value"
            String from = enumA == null ? null : "[" + String.join(",", enumA) + "]";
            String to = enumB == null ? null : "[" + String.join(",", enumB) + "]";
            if (request && restricted) {
                add(out, new Rel(ChangeKind.REQUEST_ENUM_NARROWED, true, FieldPath.ROOT, from, to, a.pointer()));
            } else if (request && widened) {
                add(out, new Rel(ChangeKind.REQUEST_ENUM_WIDENED, false, FieldPath.ROOT, from, to, a.pointer()));
            } else if (!request && widened) {
                add(out, new Rel(ChangeKind.RESPONSE_ENUM_WIDENED, true, FieldPath.ROOT, from, to, a.pointer()));
            } else if (!request && restricted) {
                add(out, new Rel(ChangeKind.RESPONSE_ENUM_NARROWED, false, FieldPath.ROOT, from, to, a.pointer()));
            }
        }

        Map<String, Schema> propsA = visible(a.properties());
        Map<String, Schema> propsB = visible(b.properties());
        Set<String> requiredA = a.required();
        Set<String> requiredB = b.required();
        for (Map.Entry<String, Schema> e : propsA.entrySet()) {
            String name = e.getKey();
            String field = FieldPath.child(FieldPath.ROOT, name);
            Schema childB = propsB.get(name);
            if (childB == null) {
                add(out, new Rel(request ? ChangeKind.REQUEST_FIELD_REMOVED : ChangeKind.RESPONSE_FIELD_REMOVED,
                        true, field, requiredA.contains(name) ? "required" : "optional", null, a.pointer()));
                continue;
            }
            boolean wasRequired = requiredA.contains(name);
            boolean isRequired = requiredB.contains(name);
            if (wasRequired != isRequired) {
                if (request) {
                    add(out, new Rel(isRequired ? ChangeKind.REQUEST_FIELD_MADE_REQUIRED
                            : ChangeKind.REQUEST_FIELD_MADE_OPTIONAL, isRequired, field,
                            wasRequired ? "required" : "optional", isRequired ? "required" : "optional",
                            a.pointer()));
                } else {
                    add(out, new Rel(isRequired ? ChangeKind.RESPONSE_FIELD_MADE_REQUIRED
                            : ChangeKind.RESPONSE_FIELD_MADE_OPTIONAL, !isRequired, field,
                            wasRequired ? "required" : "optional", isRequired ? "required" : "optional",
                            a.pointer()));
                }
            }
            addAll(out, diff(e.getValue(), childB), field);
        }
        for (Map.Entry<String, Schema> e : propsB.entrySet()) {
            String name = e.getKey();
            if (propsA.containsKey(name)) {
                continue;
            }
            String field = FieldPath.child(FieldPath.ROOT, name);
            if (request) {
                boolean required = requiredB.contains(name);
                add(out, new Rel(required ? ChangeKind.REQUEST_FIELD_ADDED_REQUIRED
                        : ChangeKind.REQUEST_FIELD_ADDED_OPTIONAL, required, field, null,
                        required ? "required" : "optional", a.pointer()));
            } else {
                add(out, new Rel(ChangeKind.RESPONSE_FIELD_ADDED, false, field, null,
                        requiredB.contains(name) ? "required" : "optional", a.pointer()));
            }
        }

        if (a.additionalSchema() != null && b.additionalSchema() != null) {
            addAll(out, diff(a.additionalSchema(), b.additionalSchema()), FieldPath.wildcard(FieldPath.ROOT));
        }
        if (a.items() != null && b.items() != null) {
            addAll(out, diff(a.items(), b.items()), FieldPath.items(FieldPath.ROOT));
        }
        return out;
    }

    private Map<String, Schema> visible(Map<String, Schema> properties) {
        Map<String, Schema> out = new LinkedHashMap<>();
        for (Map.Entry<String, Schema> e : properties.entrySet()) {
            if (ShapeFlattener.visible(e.getValue(), direction)) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private void add(List<Rel> out, Rel rel) {
        if (out.size() >= MAX_CHANGES_PER_PAIR) {
            truncated = true;
            return;
        }
        out.add(rel);
    }

    private void addAll(List<Rel> out, List<Rel> children, String under) {
        for (Rel child : children) {
            add(out, child.under(under));
        }
    }

    /** The set of JSON types a schema accepts: no declared type means all; number includes integer. */
    static Set<JsonType> accepted(Schema s) {
        Set<JsonType> types = s.types();
        Set<JsonType> withoutNull = EnumSet.noneOf(JsonType.class);
        withoutNull.addAll(types);
        withoutNull.remove(JsonType.NULL);
        if (withoutNull.isEmpty()) {
            // "nullable: true" alone, or nothing at all, constrains nothing
            return EnumSet.allOf(JsonType.class);
        }
        Set<JsonType> out = EnumSet.copyOf(types);
        if (out.contains(JsonType.NUMBER)) {
            out.add(JsonType.INTEGER);
        }
        return out;
    }

    private static String typeText(Schema s) {
        List<String> names = ShapeFlattener.typeNames(s);
        return names.isEmpty() ? "any" : String.join("|", names);
    }
}
