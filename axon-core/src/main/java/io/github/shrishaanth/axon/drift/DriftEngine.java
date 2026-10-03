package io.github.shrishaanth.axon.drift;

import io.github.shrishaanth.axon.impact.ImpactConfig;
import io.github.shrishaanth.axon.observe.Aggregate;
import io.github.shrishaanth.axon.observe.Aggregate.FieldKey;
import io.github.shrishaanth.axon.observe.Aggregate.FieldStat;
import io.github.shrishaanth.axon.observe.Aggregate.OperationStats;
import io.github.shrishaanth.axon.observe.Aggregate.Stat;
import io.github.shrishaanth.axon.spec.Body;
import io.github.shrishaanth.axon.spec.JsonType;
import io.github.shrishaanth.axon.spec.Operation;
import io.github.shrishaanth.axon.spec.Parameter;
import io.github.shrishaanth.axon.spec.Response;
import io.github.shrishaanth.axon.spec.Schema;
import io.github.shrishaanth.axon.spec.ShapeFlattener;
import io.github.shrishaanth.axon.spec.ShapeFlattener.Direction;
import io.github.shrishaanth.axon.util.FieldPath;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares the observed contract with the spec the traffic was aggregated against.
 *
 * <p>Deliberately quiet where the spec leaves room: a field under an object that declares
 * {@code additionalProperties}, under an object with no declared properties, or under a schema with no
 * constraints is not "undocumented". An undocumented object is reported once, not once per child.
 */
public final class DriftEngine {

    private final Aggregate aggregate;
    private final ImpactConfig config;
    private final List<DriftFinding> out = new ArrayList<>();

    private DriftEngine(Aggregate aggregate, ImpactConfig config) {
        this.aggregate = aggregate;
        this.config = config;
    }

    public static List<DriftFinding> analyse(Aggregate aggregate, ImpactConfig config) {
        DriftEngine e = new DriftEngine(aggregate, config);
        e.run();
        return e.out;
    }

    private void run() {
        for (Operation op : aggregate.spec().operations()) {
            OperationStats s = aggregate.operations().get(op.key());
            if (s == null || s.calls.count() == 0) {
                out.add(new DriftFinding(DriftFinding.UNUSED_OPERATION, "info", op.method(), op.path(), null, null,
                        null, null, null, null, 0, null,
                        "Documented, but no request matched it in the window."));
                continue;
            }
            statuses(op, s);
            query(op, s);
            Body body = op.requestBody();
            long withBody = s.withRequestBody.values().stream().mapToLong(Stat::count).sum();
            fields(op, s, Aggregate.REQUEST_BODY, 0, "request.body", body == null ? null : body.jsonSchema(),
                    body != null, withBody, Direction.REQUEST);
            for (Map.Entry<Integer, Long> e : s.withResponseBody.entrySet()) {
                Response r = op.responseFor(e.getKey());
                if (r == null) {
                    continue; // already reported as an undocumented status
                }
                fields(op, s, Aggregate.RESPONSE_BODY, e.getKey(), "response." + e.getKey() + ".body",
                        r.body().jsonSchema(), true, e.getValue(), Direction.RESPONSE);
            }
        }
        Map<String, Long> unmatched = new TreeMap<>();
        aggregate.unmatched().forEach((where, stat) -> unmatched.merge(where, stat.count(), Long::sum));
        for (Map.Entry<String, Long> e : unmatched.entrySet()) {
            out.add(new DriftFinding(DriftFinding.UNDOCUMENTED_OPERATION, "warning", null, e.getKey(), null, null,
                    null, null, null, null, e.getValue(),
                    null, e.getValue() + " requests matched no operation in the spec."));
        }
    }

    private void statuses(Operation op, OperationStats s) {
        for (Integer status : s.statuses.keySet()) {
            if (op.responseFor(status) != null) {
                continue;
            }
            long n = s.statusCount(status);
            boolean serverError = status >= 500;
            String severity = serverError && !"drift".equals(config.serverErrors()) ? "info" : "warning";
            out.add(new DriftFinding(DriftFinding.UNDOCUMENTED_STATUS, severity, op.method(), op.path(),
                    "response.status", null, Integer.toString(status), null, null,
                    n / (double) s.calls.count(), n, null,
                    "Status " + status + " returned " + n + " times of " + s.calls.count()
                            + "; the spec does not declare it"
                            + (serverError && severity.equals("info") ? " (server errors are informational)." : ".")));
        }
    }

    private void query(Operation op, OperationStats s) {
        long calls = s.calls.count();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<FieldKey, FieldStat> e : s.fields.entrySet()) {
            if (!e.getKey().part().equals(Aggregate.REQUEST_QUERY)) {
                continue;
            }
            List<FieldPath.Segment> segments = FieldPath.parse(e.getKey().path());
            if (segments.size() != 1) {
                continue;
            }
            String name = segments.get(0).name();
            seen.add(name);
            if (op.parameter("query", name) == null) {
                long n = e.getValue().present.count();
                out.add(new DriftFinding(DriftFinding.UNDOCUMENTED_FIELD, "warning", op.method(), op.path(),
                        "request.query", e.getKey().path(), null, null, null, n / (double) calls, calls, null,
                        "Query parameter sent in " + n + " of " + calls + " requests; not in the spec."));
            }
        }
        if (calls >= config.unusedMinRequests()) {
            for (Parameter p : op.parameters()) {
                if (p.in().equals("query") && !seen.contains(p.name())) {
                    out.add(unused(op, "request.query", FieldPath.child(FieldPath.ROOT, p.name()), calls,
                            "Documented query parameter never sent in " + calls + " requests."));
                }
            }
        }
    }

    private DriftFinding unused(Operation op, String location, String field, long n, String detail) {
        return new DriftFinding(DriftFinding.UNUSED_FIELD, "info", op.method(), op.path(), location, field, null,
                null, null, 0.0, n, 1 - Math.pow(0.05, 1.0 / n), detail);
    }

    private void fields(Operation op, OperationStats s, String part, int status, String location, Schema schema,
                        boolean bodyDeclared, long bodies, Direction direction) {
        if (bodies == 0) {
            return;
        }
        Map<String, FieldStat> observed = new TreeMap<>();
        for (Map.Entry<FieldKey, FieldStat> e : s.fields.entrySet()) {
            if (e.getKey().part().equals(part) && e.getKey().status() == status) {
                observed.put(e.getKey().path(), e.getValue());
            }
        }
        if (schema == null) {
            if (observed.containsKey(FieldPath.ROOT)) {
                out.add(new DriftFinding(DriftFinding.UNDOCUMENTED_FIELD, "warning", op.method(), op.path(),
                        location, FieldPath.ROOT, status == 0 ? null : Integer.toString(status), null, null, 1.0,
                        bodies, null, "A JSON body was seen " + bodies + " times; the spec declares "
                        + (bodyDeclared ? "no JSON schema for it." : "no body here.")));
            }
            return;
        }

        Map<String, Schema> resolved = new HashMap<>();
        Set<String> undocumented = new TreeSet<>();
        for (String path : observed.keySet()) { // sorted: parents come before children
            String parent = FieldPath.parent(path);
            if (parent == null) {
                resolved.put(path, schema);
                continue;
            }
            Schema parentSchema = resolved.get(parent);
            if (parentSchema == null) {
                continue; // the parent is undocumented or free-form: say nothing about its children
            }
            List<FieldPath.Segment> segments = FieldPath.parse(path);
            FieldPath.Segment last = segments.get(segments.size() - 1);
            if (last.isItems()) {
                if (parentSchema.items() != null) {
                    resolved.put(path, parentSchema.items());
                }
                continue;
            }
            Schema child = parentSchema.properties().get(last.name());
            if (child != null) {
                resolved.put(path, child);
            } else if (parentSchema.additionalSchema() != null) {
                resolved.put(path, parentSchema.additionalSchema());
            } else if (!parentSchema.declaresOpenMap() && !parentSchema.properties().isEmpty()) {
                undocumented.add(path);
            }
        }

        for (String path : undocumented) {
            FieldStat f = observed.get(path);
            long n = f.present.count();
            out.add(new DriftFinding(DriftFinding.UNDOCUMENTED_FIELD, "warning", op.method(), op.path(), location,
                    path, status == 0 ? null : Integer.toString(status), typeNames(f.types.keySet()), null,
                    n / (double) bodies, bodies, null,
                    "Present in " + n + " of " + bodies + " bodies; not in the spec."));
        }

        for (Map.Entry<String, Schema> e : resolved.entrySet()) {
            String path = e.getKey();
            Schema declared = e.getValue();
            FieldStat f = observed.get(path);

            Set<JsonType> accepted = accepted(declared);
            if (accepted != null) {
                Set<JsonType> wrong = EnumSet.noneOf(JsonType.class);
                long wrongCount = 0;
                for (Map.Entry<JsonType, Long> t : f.types.entrySet()) {
                    if (!accepted.contains(t.getKey())) {
                        wrong.add(t.getKey());
                        wrongCount += t.getValue();
                    }
                }
                if (!wrong.isEmpty()) {
                    out.add(new DriftFinding(DriftFinding.TYPE_MISMATCH, "warning", op.method(), op.path(),
                            location, path, status == 0 ? null : Integer.toString(status), typeNames(wrong),
                            ShapeFlattener.typeNames(declared),
                            Math.min(1.0, wrongCount / (double) f.present.count()), f.present.count(), null,
                            "Observed " + String.join("|", typeNames(wrong)) + " in " + wrongCount + " of "
                                    + f.present.count() + " occurrences; the spec declares "
                                    + String.join("|", ShapeFlattener.typeNames(declared)) + "."));
                }
            }

            // documented children of an object that was seen, but never seen themselves
            Long asObject = f.types.get(JsonType.OBJECT);
            if (asObject != null && asObject >= config.unusedMinRequests()) {
                for (Map.Entry<String, Schema> p : declared.properties().entrySet()) {
                    if (!ShapeFlattener.visible(p.getValue(), direction)) {
                        continue;
                    }
                    String child = FieldPath.child(path, p.getKey());
                    if (!observed.containsKey(child)) {
                        out.add(new DriftFinding(DriftFinding.UNUSED_FIELD, "info", op.method(), op.path(),
                                location, child, status == 0 ? null : Integer.toString(status), null,
                                ShapeFlattener.typeNames(p.getValue()), 0.0, asObject,
                                1 - Math.pow(0.05, 1.0 / asObject),
                                "Documented, never present in " + asObject + " occurrences of its parent."));
                    }
                }
            }
        }
    }

    /** Types the schema allows, with integer under number; null when the schema does not constrain the type. */
    private static Set<JsonType> accepted(Schema s) {
        Set<JsonType> types = s.types();
        Set<JsonType> concrete = EnumSet.noneOf(JsonType.class);
        concrete.addAll(types);
        concrete.remove(JsonType.NULL);
        if (concrete.isEmpty()) {
            return null;
        }
        Set<JsonType> out = EnumSet.copyOf(types);
        if (out.contains(JsonType.NUMBER)) {
            out.add(JsonType.INTEGER);
        }
        return out;
    }

    private static List<String> typeNames(Set<JsonType> types) {
        Set<String> names = new TreeSet<>();
        for (JsonType t : types) {
            names.add(t.wire().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(names);
    }
}
