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
 * Compares two schema graphs. Each pair of schemas is compared once and its own changes are kept apart from the
 * pairs below it, so a shared or recursive component costs nothing extra however often it is reached.
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

    /** One comparison never reports more changes than this; the result is then flagged truncated. */
    static final int MAX_CHANGES_PER_PAIR = 300;
    /** Nor does it follow more schema pairs than this while placing changes under their paths. */
    static final int MAX_STEPS = 50_000;

    private record Pair(Schema a, Schema b) {
    }

    /** A step from one pair of schemas to the pair below it: a property, the items, or the map values. */
    private record Edge(String suffix, Node child) {
    }

    /**
     * One compared pair. {@code local} holds the changes found at this level only; {@code dirty} is true when
     * a change exists here or anywhere below.
     */
    private static final class Node {
        final Pair pair;
        List<Rel> local = List.of();
        final List<Edge> edges = new ArrayList<>();
        final List<Node> parents = new ArrayList<>();
        boolean dirty;
        /** True when a breaking change exists here or anywhere below. */
        boolean dirtyBreaking;

        Node(Pair pair) {
            this.pair = pair;
        }
    }

    private static final Schema ANY = Schema.any();

    private final Direction direction;
    private final Map<Pair, Node> nodes = new HashMap<>();
    private final java.util.ArrayDeque<Node> pending = new java.util.ArrayDeque<>();
    private boolean truncated;

    SchemaDiff(Direction direction) {
        this.direction = direction;
    }

    boolean truncated() {
        return truncated;
    }

    /**
     * Changes between two schemas, located by field path from {@code $}.
     *
     * <p>Each pair of schemas is compared exactly once, however many operations and paths reach it, which keeps
     * large mutually recursive specs linear. The changes are then placed under every path that leads to them;
     * a recursive schema is entered once per path (its changes are not repeated at deeper and deeper paths).
     */
    List<Rel> diff(Schema a, Schema b) {
        if (a == null && b == null) {
            return List.of();
        }
        Node root = node(a, b);
        build();
        if (!root.dirty) {
            return List.of();
        }
        // Breaking changes first, each pass with its own budget: when a large shared schema overflows the cap,
        // what gets cut is safe changes, never the breaking ones behind them.
        List<Rel> out = new ArrayList<>();
        emit(root, FieldPath.ROOT, new HashSet<>(), out, new int[1], true);
        emit(root, FieldPath.ROOT, new HashSet<>(), out, new int[1], false);
        return out;
    }

    private Node node(Schema a, Schema b) {
        Pair pair = new Pair(a == null ? ANY : a, b == null ? ANY : b);
        Node n = nodes.get(pair);
        if (n == null) {
            n = new Node(pair);
            nodes.put(pair, n);
            pending.add(n);
        }
        return n;
    }

    /** Compares every pair not yet compared, then marks which pairs lead to a change. */
    private void build() {
        List<Node> fresh = new ArrayList<>();
        while (!pending.isEmpty()) {
            Node n = pending.poll();
            n.local = List.copyOf(compute(n));
            fresh.add(n);
        }
        propagate(fresh, false);
        propagate(fresh, true);
    }

    /** Marks every pair from which a change (or, with {@code breakingOnly}, a breaking change) can be reached. */
    private static void propagate(List<Node> fresh, boolean breakingOnly) {
        java.util.ArrayDeque<Node> queue = new java.util.ArrayDeque<>();
        for (Node n : fresh) {
            boolean own = breakingOnly ? n.local.stream().anyMatch(Rel::breaking) : !n.local.isEmpty();
            boolean below = false;
            for (Edge e : n.edges) {
                below |= breakingOnly ? e.child().dirtyBreaking : e.child().dirty;
            }
            if ((own || below) && !flag(n, breakingOnly)) {
                set(n, breakingOnly);
                queue.add(n);
            }
        }
        while (!queue.isEmpty()) {
            for (Node parent : queue.poll().parents) {
                if (!flag(parent, breakingOnly)) {
                    set(parent, breakingOnly);
                    queue.add(parent);
                }
            }
        }
    }

    private static boolean flag(Node n, boolean breakingOnly) {
        return breakingOnly ? n.dirtyBreaking : n.dirty;
    }

    private static void set(Node n, boolean breakingOnly) {
        if (breakingOnly) {
            n.dirtyBreaking = true;
        } else {
            n.dirty = true;
        }
    }

    private void emit(Node n, String path, Set<Node> onPath, List<Rel> out, int[] steps, boolean breaking) {
        if (++steps[0] > MAX_STEPS) {
            truncated = true;
            return;
        }
        for (Rel rel : n.local) {
            if (rel.breaking() != breaking) {
                continue;
            }
            if (out.size() >= MAX_CHANGES_PER_PAIR) {
                truncated = true;
                return;
            }
            out.add(rel.under(path));
        }
        onPath.add(n);
        for (Edge e : n.edges) {
            // skip subtrees with nothing of this class, and do not re-enter a schema already on this path
            if (flag(e.child(), breaking) && !onPath.contains(e.child())) {
                emit(e.child(), path + e.suffix(), onPath, out, steps, breaking);
            }
        }
        onPath.remove(n);
    }

    private void edge(Node from, String childPath, Schema a, Schema b) {
        Node child = node(a, b);
        from.edges.add(new Edge(childPath.substring(1), child));
        child.parents.add(from);
    }

    /** Changes at this level only; pairs below become edges. */
    private List<Rel> compute(Node node) {
        Schema a = node.pair.a();
        Schema b = node.pair.b();
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
            edge(node, field, e.getValue(), childB);
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
            edge(node, FieldPath.wildcard(FieldPath.ROOT), a.additionalSchema(), b.additionalSchema());
        }
        if (a.items() != null && b.items() != null) {
            edge(node, FieldPath.items(FieldPath.ROOT), a.items(), b.items());
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
        out.add(rel);
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
