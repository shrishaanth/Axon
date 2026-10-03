package io.github.shrishaanth.axon.eval.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.spec.JsonType;
import io.github.shrishaanth.axon.spec.Schema;
import io.github.shrishaanth.axon.util.FieldPath;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * How one party fills in JSON bodies for a schema: for each property, the probability that it is present. The
 * same object both generates bodies and states, analytically, how often each field path appears, which is the
 * hidden truth the inferred contract is scored against.
 *
 * <p>Generation rules, mirrored exactly by {@link #rates}: arrays hold 1 to 3 items; below {@link #MAX_DEPTH}
 * optional properties are left out and required objects and arrays are empty; of a {@code oneOf}/{@code anyOf}
 * every property is treated as an ordinary optional property.
 */
public final class BodyModel {

    public static final int MAX_DEPTH = 4;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** An undocumented property added to objects of one schema. */
    public record Extra(String name, double probability) {
    }

    /** A documented property sometimes sent with another type. */
    public record WrongType(JsonType type, double probability) {
    }

    private final Map<Schema, Map<String, Double>> presence = new IdentityHashMap<>();
    private final Map<Schema, List<Extra>> extras = new IdentityHashMap<>();
    private final Map<Schema, Map<String, WrongType>> wrongTypes = new IdentityHashMap<>();
    private final Map<Schema, Map<String, String>> fixedValues = new IdentityHashMap<>();
    private final Map<Schema, Boolean> fractional = new IdentityHashMap<>();
    private double nullRate = 0.1;

    /** Probability that {@code name} is present in an object of schema {@code parent}. */
    public void presence(Schema parent, String name, double probability) {
        presence.computeIfAbsent(parent, p -> new LinkedHashMap<>()).put(name, probability);
    }

    public double presence(Schema parent, String name) {
        Map<String, Double> m = presence.get(parent);
        if (m != null && m.containsKey(name)) {
            return m.get(name);
        }
        return parent.required().contains(name) ? 1.0 : 0.0;
    }

    public void extra(Schema parent, String name, double probability) {
        extras.computeIfAbsent(parent, p -> new ArrayList<>()).add(new Extra(name, probability));
    }

    public void wrongType(Schema parent, String name, JsonType type, double probability) {
        wrongTypes.computeIfAbsent(parent, p -> new LinkedHashMap<>()).put(name, new WrongType(type, probability));
    }

    /** Always send this value for an enum property (a client's habit). */
    public void fixedValue(Schema parent, String name, String value) {
        fixedValues.computeIfAbsent(parent, p -> new LinkedHashMap<>()).put(name, value);
    }

    /** The value fixed with {@link #fixedValue}, or null. */
    public String fixedValueOf(Schema parent, String name) {
        return fixedValues.getOrDefault(parent, Map.of()).get(name);
    }

    /** Whether numbers of this schema are sent with a fractional part (true) or whole (false). */
    public void fractional(Schema numberSchema, boolean value) {
        fractional.put(numberSchema, value);
    }

    public void nullRate(double rate) {
        this.nullRate = rate;
    }

    public Map<Schema, List<Extra>> extras() {
        return extras;
    }

    public Map<Schema, Map<String, WrongType>> wrongTypes() {
        return wrongTypes;
    }

    // ------------------------------------------------------------------------------------------------------
    // Generation

    public JsonNode generate(Schema schema, Random random) {
        return value(schema, random, 0);
    }

    private JsonNode value(Schema s, Random random, int depth) {
        JsonType type = primary(s);
        if (s.nullable() && nullRate > 0 && random.nextDouble() < nullRate) {
            return NODES.nullNode();
        }
        switch (type) {
            case OBJECT: {
                ObjectNode o = NODES.objectNode();
                if (depth >= MAX_DEPTH) {
                    return o;
                }
                for (Map.Entry<String, Schema> e : s.properties().entrySet()) {
                    double p = presence(s, e.getKey());
                    if (p <= 0 || (p < 1 && random.nextDouble() >= p)) {
                        continue;
                    }
                    WrongType wrong = wrongTypes.getOrDefault(s, Map.of()).get(e.getKey());
                    if (wrong != null && random.nextDouble() < wrong.probability()) {
                        o.set(e.getKey(), scalar(wrong.type(), null, random));
                        continue;
                    }
                    String fixed = fixedValues.getOrDefault(s, Map.of()).get(e.getKey());
                    o.set(e.getKey(), fixed != null ? NODES.textNode(fixed) : value(e.getValue(), random, depth + 1));
                }
                for (Extra extra : extras.getOrDefault(s, List.of())) {
                    if (random.nextDouble() < extra.probability()) {
                        o.put(extra.name(), "x");
                    }
                }
                return o;
            }
            case ARRAY: {
                ArrayNode a = NODES.arrayNode();
                if (depth >= MAX_DEPTH || s.items() == null) {
                    return a;
                }
                int n = 1 + random.nextInt(3);
                for (int i = 0; i < n; i++) {
                    a.add(value(s.items(), random, depth + 1));
                }
                return a;
            }
            default:
                return scalar(type, s, random);
        }
    }

    private JsonNode scalar(JsonType type, Schema s, Random random) {
        List<String> values = s == null ? null : s.enumValues();
        if (values != null && !values.isEmpty()) {
            List<String> usable = values.stream().filter(v -> !v.equals("null")).toList();
            if (!usable.isEmpty()) {
                try {
                    return JSON.readTree(usable.get(random.nextInt(usable.size())));
                } catch (Exception e) {
                    return NODES.textNode("x");
                }
            }
        }
        return switch (type) {
            case INTEGER -> NODES.numberNode(random.nextInt(1000));
            case NUMBER -> {
                boolean frac = s == null || fractional.getOrDefault(s, Boolean.TRUE);
                yield frac ? NODES.numberNode(random.nextInt(1000) + 0.5) : NODES.numberNode(random.nextInt(1000));
            }
            case BOOLEAN -> NODES.booleanNode(random.nextBoolean());
            case NULL -> NODES.nullNode();
            default -> NODES.textNode("v" + random.nextInt(50));
        };
    }

    /** The one type this model emits for a schema: the first concrete type, or string when unconstrained. */
    public static JsonType primary(Schema s) {
        Set<JsonType> types = s.types();
        for (JsonType t : List.of(JsonType.OBJECT, JsonType.ARRAY, JsonType.STRING, JsonType.INTEGER,
                JsonType.NUMBER, JsonType.BOOLEAN)) {
            if (types.contains(t)) {
                return t;
            }
        }
        if (!s.properties().isEmpty()) {
            return JsonType.OBJECT;
        }
        return types.contains(JsonType.NULL) && types.size() == 1 ? JsonType.NULL : JsonType.STRING;
    }

    // ------------------------------------------------------------------------------------------------------
    // Analytic truth

    /**
     * What this model can put at a path.
     *
     * @param rate       probability that a body contains the path at least once
     * @param types      type name to the probability that a body shows that type at the path at least once
     * @param documented false for an injected undocumented property
     */
    public record PathTruth(double rate, Map<String, Double> types, boolean documented) {
    }

    private record Step(boolean array, double probability) {
    }

    /** Every path this model can produce for a body of {@code root}, with exact presence rates. */
    public Map<String, PathTruth> rates(Schema root) {
        Map<String, PathTruth> out = new TreeMap<>();
        walk(root, FieldPath.ROOT, new ArrayList<>(), 0, 1.0, true, null, out);
        return out;
    }

    /**
     * @param steps           the chain from the root to this node: an object step with the property's
     *                        probability, or an array step
     * @param documentedShare probability that one occurrence has its documented type (below 1 only when a wrong
     *                        type is injected at this property)
     */
    private void walk(Schema s, String path, List<Step> steps, int depth, double documentedShare,
                      boolean documented, WrongType wrong, Map<String, PathTruth> out) {
        JsonType type = primary(s);
        Map<String, Double> types = new TreeMap<>();
        double nullP = s.nullable() ? nullRate : 0;
        // per-occurrence probabilities of each type at this node
        Map<String, Double> perOccurrence = new TreeMap<>();
        double right = documentedShare;
        if (wrong != null) {
            perOccurrence.merge(wrong.type().wire(), wrong.probability(), Double::sum);
        }
        if (nullP > 0) {
            perOccurrence.merge("null", right * nullP, Double::sum);
        }
        perOccurrence.merge(emitted(type, s), right * (1 - nullP), Double::sum);
        for (Map.Entry<String, Double> e : perOccurrence.entrySet()) {
            if (e.getValue() > 0) {
                types.put(e.getKey(), atLeastOnce(steps, e.getValue()));
            }
        }
        out.put(path, new PathTruth(atLeastOnce(steps, 1.0), types, documented));

        double below = right * (1 - nullP); // children exist only when this occurrence has its real type
        if (below <= 0) {
            return;
        }
        if (type == JsonType.OBJECT && depth < MAX_DEPTH) {
            for (Map.Entry<String, Schema> e : s.properties().entrySet()) {
                double p = presence(s, e.getKey());
                if (p <= 0) {
                    continue;
                }
                WrongType w = wrongTypes.getOrDefault(s, Map.of()).get(e.getKey());
                String fixed = fixedValues.getOrDefault(s, Map.of()).get(e.getKey());
                List<Step> next = new ArrayList<>(steps);
                next.add(new Step(false, p * below));
                if (fixed != null) {
                    Map<String, Double> t = new TreeMap<>();
                    t.put("string", atLeastOnce(next, w == null ? 1.0 : 1 - w.probability()));
                    if (w != null) {
                        t.merge(w.type().wire(), atLeastOnce(next, w.probability()), Double::sum);
                    }
                    out.put(FieldPath.child(path, e.getKey()), new PathTruth(atLeastOnce(next, 1.0), t, true));
                    continue;
                }
                walk(e.getValue(), FieldPath.child(path, e.getKey()), next, depth + 1,
                        w == null ? 1.0 : 1 - w.probability(), true, w, out);
            }
            for (Extra extra : extras.getOrDefault(s, List.of())) {
                List<Step> next = new ArrayList<>(steps);
                next.add(new Step(false, extra.probability() * below));
                Map<String, Double> t = new TreeMap<>();
                t.put("string", atLeastOnce(next, 1.0));
                out.put(FieldPath.child(path, extra.name()), new PathTruth(atLeastOnce(next, 1.0), t, false));
            }
        } else if (type == JsonType.ARRAY && depth < MAX_DEPTH && s.items() != null) {
            List<Step> next = new ArrayList<>(steps);
            if (below < 1) {
                next.add(new Step(false, below));
            }
            next.add(new Step(true, 1.0));
            walk(s.items(), FieldPath.items(path), next, depth + 1, 1.0, documented, null, out);
        }
    }

    private String emitted(JsonType type, Schema s) {
        if (type == JsonType.NUMBER) {
            // the shape extractor calls a whole number "integer", so that is the true observed type
            return fractional.getOrDefault(s, Boolean.TRUE) ? "number" : "integer";
        }
        return type.wire();
    }

    /**
     * Probability that a body holds at least one occurrence that reaches the end of the chain and then passes a
     * final per-occurrence test of probability {@code last}. Array steps hold 1 to 3 independent items.
     */
    private static double atLeastOnce(List<Step> steps, double last) {
        double h = last;
        for (int i = steps.size() - 1; i >= 0; i--) {
            Step step = steps.get(i);
            if (step.array()) {
                double miss = 1 - h;
                h = 1 - (miss + miss * miss + miss * miss * miss) / 3.0;
            } else {
                h = step.probability() * h;
            }
        }
        return h;
    }

    /** Paths of {@code root} this model never produces although the schema documents them. */
    public Set<String> neverSent(Schema root) {
        Set<String> out = new TreeSet<>();
        Map<String, PathTruth> produced = rates(root);
        for (String path : produced.keySet()) {
            // documented children of a produced object that have probability zero
            collectMissing(root, path, produced, out);
        }
        return out;
    }

    private void collectMissing(Schema root, String path, Map<String, PathTruth> produced, Set<String> out) {
        Schema s = resolve(root, path);
        if (s == null || primary(s) != JsonType.OBJECT) {
            return;
        }
        for (String name : s.properties().keySet()) {
            String child = FieldPath.child(path, name);
            if (!produced.containsKey(child)) {
                out.add(child);
            }
        }
    }

    /** The schema at a path of {@code root}, or null. */
    public static Schema resolve(Schema root, String path) {
        Schema s = root;
        for (FieldPath.Segment seg : FieldPath.parse(path)) {
            if (s == null) {
                return null;
            }
            s = seg.isItems() ? s.items() : s.properties().get(seg.name());
        }
        return s;
    }
}
