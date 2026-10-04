package io.github.shrishaanth.axon.spec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One schema node in the resolved schema graph. {@code $ref}s are resolved to the same instance, so the graph
 * may contain cycles; walkers must guard against them.
 *
 * <p>Composition is resolved lazily: {@code allOf} parts are merged, {@code oneOf}/{@code anyOf} variants are
 * approximated as a union (a field is required only if every variant requires it). The accessors below return
 * that effective view.
 */
public final class Schema {

    private static final ThreadLocal<int[]> CUTS = ThreadLocal.withInitial(() -> new int[1]);
    /** Schemas whose effective view the current thread is computing; per thread, so specs can be shared. */
    private static final ThreadLocal<Set<Schema>> IN_PROGRESS =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

    private final String pointer;

    // Raw keywords, filled by the parser.
    final Set<JsonType> ownTypes = EnumSet.noneOf(JsonType.class);
    final Map<String, Schema> ownProperties = new LinkedHashMap<>();
    final Set<String> ownRequired = new LinkedHashSet<>();
    Schema ownItems;
    Boolean ownAdditionalAllowed;
    Schema ownAdditionalSchema;
    List<String> ownEnum;
    String format;
    boolean readOnly;
    boolean writeOnly;
    final List<Schema> allOf = new ArrayList<>();
    final List<List<Schema>> unions = new ArrayList<>();
    /** True when the schema has no constraints we model at all (e.g. {@code {}} or an unresolved ref). */
    boolean unresolved;

    private volatile Set<JsonType> types;
    private volatile Map<String, Schema> properties;
    private volatile Set<String> required;
    private volatile List<String> enumValues;
    private volatile boolean enumComputed;

    Schema(String pointer) {
        this.pointer = pointer;
    }

    /** A fresh schema that accepts anything; stands in for "no schema declared". */
    public static Schema any() {
        Schema s = new Schema("");
        s.unresolved = true;
        return s;
    }

    /** JSON pointer of the definition in the source document; identifies the node. */
    public String pointer() {
        return pointer;
    }

    public String format() {
        return format;
    }

    public boolean readOnly() {
        return readOnly;
    }

    public boolean writeOnly() {
        return writeOnly;
    }

    public boolean hasUnion() {
        return !unions.isEmpty();
    }

    /** Effective type set, including NULL when nullable. Empty means "any type". */
    public Set<JsonType> types() {
        if (types != null) {
            return types;
        }
        if (!enter()) {
            return Set.of();
        }
        int before = cuts();
        Set<JsonType> result = EnumSet.noneOf(JsonType.class);
        result.addAll(ownTypes);
        boolean concrete = !withoutNull(result).isEmpty();
        for (Schema part : allOf) {
            Set<JsonType> t = part.types();
            if (!concrete && !withoutNull(t).isEmpty()) {
                result.addAll(withoutNull(t));
                concrete = true;
            }
            if (t.contains(JsonType.NULL) && ownTypes.isEmpty()) {
                result.add(JsonType.NULL);
            }
        }
        if (!concrete) {
            for (List<Schema> group : unions) {
                Set<JsonType> union = EnumSet.noneOf(JsonType.class);
                boolean any = false;
                for (Schema variant : group) {
                    Set<JsonType> t = variant.types();
                    if (t.isEmpty()) {
                        any = true;
                    }
                    union.addAll(t);
                }
                if (any) {
                    // one variant accepts anything, so the union does too (nullability is kept)
                    union.retainAll(EnumSet.of(JsonType.NULL));
                    result.addAll(union);
                } else {
                    result.addAll(union);
                    concrete = concrete || !withoutNull(union).isEmpty();
                }
            }
        } else {
            for (List<Schema> group : unions) {
                for (Schema variant : group) {
                    if (variant.types().equals(EnumSet.of(JsonType.NULL))) {
                        result.add(JsonType.NULL);
                    }
                }
            }
        }
        if (!concrete) {
            // Sloppy but common: no "type", yet the keywords imply one.
            if (!ownProperties.isEmpty() || ownAdditionalSchema != null) {
                result.add(JsonType.OBJECT);
            } else if (ownItems != null) {
                result.add(JsonType.ARRAY);
            }
        }
        if (ownEnum != null && ownEnum.contains("null")) {
            result.add(JsonType.NULL);
        }
        result = Collections.unmodifiableSet(result);
        if (leave(before)) {
            types = result;
        }
        return result;
    }

    public boolean nullable() {
        return types().contains(JsonType.NULL);
    }

    /** Effective properties: own, then {@code allOf} parts, then the union of all variants. */
    public Map<String, Schema> properties() {
        if (properties != null) {
            return properties;
        }
        if (!enter()) {
            return Map.of();
        }
        int before = cuts();
        Map<String, Schema> result = new LinkedHashMap<>(ownProperties);
        for (Schema part : allOf) {
            part.properties().forEach(result::putIfAbsent);
        }
        for (List<Schema> group : unions) {
            for (Schema variant : group) {
                variant.properties().forEach(result::putIfAbsent);
            }
        }
        result = Collections.unmodifiableMap(result);
        if (leave(before)) {
            properties = result;
        }
        return result;
    }

    /** Effective required set. A name required by only some variants of a union is not required. */
    public Set<String> required() {
        if (required != null) {
            return required;
        }
        if (!enter()) {
            return Set.of();
        }
        int before = cuts();
        Set<String> result = new LinkedHashSet<>(ownRequired);
        for (Schema part : allOf) {
            result.addAll(part.required());
        }
        for (List<Schema> group : unions) {
            Set<String> common = null;
            for (Schema variant : group) {
                if (variant.types().equals(EnumSet.of(JsonType.NULL))) {
                    continue; // a bare null variant only adds nullability
                }
                if (common == null) {
                    common = new LinkedHashSet<>(variant.required());
                } else {
                    common.retainAll(variant.required());
                }
            }
            if (common != null) {
                result.addAll(common);
            }
        }
        result = Collections.unmodifiableSet(result);
        if (leave(before)) {
            required = result;
        }
        return result;
    }

    /** Item schema for arrays, looking through composition. Null if not declared. */
    public Schema items() {
        if (ownItems != null) {
            return ownItems;
        }
        if (!enter()) {
            return null;
        }
        try {
            for (Schema part : allOf) {
                Schema i = part.items();
                if (i != null) {
                    return i;
                }
            }
            for (List<Schema> group : unions) {
                for (Schema variant : group) {
                    Schema i = variant.items();
                    if (i != null) {
                        return i;
                    }
                }
            }
            return null;
        } finally {
            IN_PROGRESS.get().remove(this);
        }
    }

    /** Schema for undeclared properties ({@code additionalProperties: {...}}), looking through composition. */
    public Schema additionalSchema() {
        if (ownAdditionalSchema != null) {
            return ownAdditionalSchema;
        }
        if (!enter()) {
            return null;
        }
        try {
            for (Schema part : allOf) {
                Schema a = part.additionalSchema();
                if (a != null) {
                    return a;
                }
            }
            for (List<Schema> group : unions) {
                for (Schema variant : group) {
                    Schema a = variant.additionalSchema();
                    if (a != null) {
                        return a;
                    }
                }
            }
            return null;
        } finally {
            IN_PROGRESS.get().remove(this);
        }
    }

    /** True only when the schema itself says {@code additionalProperties: false}. */
    public boolean closed() {
        return Boolean.FALSE.equals(ownAdditionalAllowed);
    }

    /**
     * True when undeclared properties are explicitly expected: {@code additionalProperties} is {@code true} or a
     * schema, here or in a composed part. Drift does not flag undeclared fields under such an object.
     */
    public boolean declaresOpenMap() {
        if (Boolean.TRUE.equals(ownAdditionalAllowed) || ownAdditionalSchema != null) {
            return true;
        }
        if (!enter()) {
            return false;
        }
        try {
            for (Schema part : allOf) {
                if (part.declaresOpenMap()) {
                    return true;
                }
            }
            for (List<Schema> group : unions) {
                for (Schema variant : group) {
                    if (variant.declaresOpenMap()) {
                        return true;
                    }
                }
            }
            return false;
        } finally {
            IN_PROGRESS.get().remove(this);
        }
    }

    /**
     * Effective enum as canonical JSON texts (strings keep their quotes), or null when unconstrained. A union is
     * an enum only if every non-null variant is.
     */
    public List<String> enumValues() {
        if (enumComputed) {
            return enumValues;
        }
        if (!enter()) {
            return null;
        }
        int before = cuts();
        List<String> result = ownEnum;
        if (result == null) {
            for (Schema part : allOf) {
                if (part.enumValues() != null) {
                    result = part.enumValues();
                    break;
                }
            }
        }
        if (result == null) {
            for (List<Schema> group : unions) {
                Set<String> all = new LinkedHashSet<>();
                boolean every = !group.isEmpty();
                boolean some = false;
                for (Schema variant : group) {
                    if (variant.types().equals(EnumSet.of(JsonType.NULL))) {
                        continue;
                    }
                    List<String> e = variant.enumValues();
                    if (e == null) {
                        every = false;
                        break;
                    }
                    some = true;
                    all.addAll(e);
                }
                if (every && some) {
                    result = List.copyOf(all);
                    break;
                }
            }
        }
        if (leave(before)) {
            enumValues = result;
            enumComputed = true;
        }
        return result;
    }

    /** True for a schema that constrains nothing we model: any type, no properties, no items, no enum. */
    public boolean isAny() {
        return types().isEmpty() && properties().isEmpty() && items() == null && enumValues() == null;
    }

    private static Set<JsonType> withoutNull(Set<JsonType> in) {
        Set<JsonType> out = EnumSet.noneOf(JsonType.class);
        out.addAll(in);
        out.remove(JsonType.NULL);
        return out;
    }

    // Cycle guard. A composition cycle (allOf pointing back at an ancestor) cuts the recursion; results computed
    // while a cut happened below are not cached unless this node is the outermost one being computed.
    private boolean enter() {
        if (!IN_PROGRESS.get().add(this)) {
            CUTS.get()[0]++;
            return false;
        }
        return true;
    }

    private static int cuts() {
        return CUTS.get()[0];
    }

    private boolean leave(int cutsBefore) {
        IN_PROGRESS.get().remove(this);
        return cuts() == cutsBefore;
    }

    @Override
    public String toString() {
        return "Schema[" + pointer + "]";
    }
}
