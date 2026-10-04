package io.github.shrishaanth.axon.observe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.observe.Aggregate.ClientField;
import io.github.shrishaanth.axon.observe.Aggregate.FieldKey;
import io.github.shrishaanth.axon.observe.Aggregate.FieldStat;
import io.github.shrishaanth.axon.observe.Aggregate.OperationStats;
import io.github.shrishaanth.axon.observe.Aggregate.Stat;
import io.github.shrishaanth.axon.spec.JsonType;
import io.github.shrishaanth.axon.spec.Operation;
import io.github.shrishaanth.axon.spec.Parameter;
import io.github.shrishaanth.axon.spec.Response;
import io.github.shrishaanth.axon.spec.Schema;
import io.github.shrishaanth.axon.spec.ShapeFlattener;
import io.github.shrishaanth.axon.spec.ShapeFlattener.Direction;
import io.github.shrishaanth.axon.util.FieldPath;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * JSON views of the observed contract for the UI: what each operation declares next to what traffic showed, and
 * each client's activity over time. Built from an {@link Aggregate}, so the same code serves the live API and
 * the precomputed demo.
 */
public final class Views {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_DECLARED_DEPTH = 6;
    private static final int MAX_DECLARED_FIELDS = 300;
    private static final int MAX_CLIENTS = 500;

    private Views() {
    }

    /**
     * Per operation: calls, callers, status codes, latency, and every field with its state:
     * {@code documented_and_used}, {@code never_seen} (documented, not observed) or {@code undocumented}.
     */
    public static ObjectNode contract(Aggregate aggregate) {
        ObjectNode out = JSON.createObjectNode();
        ArrayNode ops = out.putArray("operations");
        for (Operation op : aggregate.spec().operations()) {
            OperationStats s = aggregate.operations().get(op.key());
            ObjectNode o = ops.addObject();
            o.put("method", op.method());
            o.put("path", op.path());
            o.put("calls", s == null ? 0 : s.calls.count());
            o.put("clients", s == null ? 0 : s.callers.keySet().stream()
                    .filter(c -> !c.equals(Aggregate.UNIDENTIFIED)).count());
            if (s != null && s.calls.count() > 0) {
                o.put("first_seen", s.calls.first().toString());
                o.put("last_seen", s.calls.last().toString());
            }
            ArrayNode statuses = o.putArray("statuses");
            Map<String, Long> seen = new TreeMap<>();
            if (s != null) {
                s.statuses.forEach((code, byClient) -> seen.put(Integer.toString(code),
                        byClient.values().stream().mapToLong(Stat::count).sum()));
            }
            Map<String, Boolean> listed = new LinkedHashMap<>();
            for (String documented : op.responses().keySet()) {
                listed.put(documented, true);
            }
            for (String code : seen.keySet()) {
                if (!listed.containsKey(code)) {
                    listed.put(code, op.responseFor(Integer.parseInt(code)) != null);
                }
            }
            for (Map.Entry<String, Boolean> e : listed.entrySet()) {
                ObjectNode st = statuses.addObject();
                st.put("status", e.getKey());
                st.put("documented", e.getValue());
                st.put("count", seen.getOrDefault(e.getKey(), 0L));
            }
            if (s != null && s.latency.count() > 0) {
                ObjectNode latency = o.putObject("latency_ms");
                latency.put("p50", round(s.latency.percentile(0.50)));
                latency.put("p95", round(s.latency.percentile(0.95)));
                latency.put("p99", round(s.latency.percentile(0.99)));
                latency.put("samples", s.latency.count());
                latency.put("note", "upper edge of a logarithmic bucket; can overstate by up to 25%");
            }
            ArrayNode callers = o.putArray("callers");
            if (s != null) {
                s.callers.entrySet().stream()
                        .filter(e -> !e.getKey().equals(Aggregate.UNIDENTIFIED))
                        .sorted((a, b) -> Long.compare(b.getValue().count(), a.getValue().count()))
                        .limit(MAX_CLIENTS)
                        .forEach(e -> {
                            ObjectNode c = callers.addObject();
                            c.put("client", e.getKey());
                            c.put("calls", e.getValue().count());
                            c.put("last_seen", e.getValue().last().toString());
                        });
            }

            ArrayNode fields = o.putArray("fields");
            // query parameters
            Map<String, FieldStat> query = part(s, Aggregate.REQUEST_QUERY, 0);
            for (Parameter p : op.parameters()) {
                if (p.in().equals("query")) {
                    String path = FieldPath.child(FieldPath.ROOT, p.name());
                    field(fields, "request.query", null, path, true, p.required(),
                            p.schema() == null ? List.of() : ShapeFlattener.typeNames(p.schema()),
                            query.remove(path), s == null ? 0 : s.calls.count());
                }
            }
            for (Map.Entry<String, FieldStat> e : query.entrySet()) {
                field(fields, "request.query", null, e.getKey(), false, false, List.of(), e.getValue(),
                        s == null ? 0 : s.calls.count());
            }
            // request body
            Schema request = op.requestBody() == null ? null : op.requestBody().jsonSchema();
            long requestBodies = s == null ? 0 : s.withRequestBody.values().stream().mapToLong(Stat::count).sum();
            body(fields, "request.body", null, request, Direction.REQUEST, part(s, Aggregate.REQUEST_BODY, 0),
                    requestBodies);
            // response bodies: every documented status, then observed statuses that have a body
            Map<String, Integer> responseStatuses = new LinkedHashMap<>();
            for (Response r : op.responses().values()) {
                responseStatuses.put(r.status(), r.status().matches("[1-5][0-9][0-9]")
                        ? Integer.parseInt(r.status()) : -1);
            }
            if (s != null) {
                for (Integer code : s.withResponseBody.keySet()) {
                    responseStatuses.putIfAbsent(Integer.toString(code), code);
                }
            }
            for (Map.Entry<String, Integer> e : responseStatuses.entrySet()) {
                Response declared = e.getValue() > 0 ? op.responseFor(e.getValue()) : op.responses().get(e.getKey());
                Schema schema = declared == null ? null : declared.body().jsonSchema();
                long bodies = s == null || e.getValue() < 0 ? 0 : s.withResponseBody.getOrDefault(e.getValue(), 0L);
                body(fields, "response.body", e.getKey(), schema, Direction.RESPONSE,
                        e.getValue() < 0 ? new TreeMap<>() : part(s, Aggregate.RESPONSE_BODY, e.getValue()), bodies);
            }
        }
        ArrayNode unmatched = out.putArray("unmatched");
        aggregate.unmatched().forEach((where, stat) -> {
            ObjectNode u = unmatched.addObject();
            u.put("request", where);
            u.put("count", stat.count());
        });
        out.put("events", aggregate.events().count());
        out.put("matched_events", aggregate.matched());
        return out;
    }

    private static Map<String, FieldStat> part(OperationStats s, String part, int status) {
        Map<String, FieldStat> out = new TreeMap<>();
        if (s != null) {
            for (Map.Entry<FieldKey, FieldStat> e : s.fields.entrySet()) {
                if (e.getKey().part().equals(part) && e.getKey().status() == status) {
                    out.put(e.getKey().path(), e.getValue());
                }
            }
        }
        return out;
    }

    private static void body(ArrayNode fields, String part, String status, Schema schema, Direction direction,
                             Map<String, FieldStat> observed, long bodies) {
        if (schema != null) {
            for (ShapeFlattener.Field f : ShapeFlattener.flatten(schema, direction, MAX_DECLARED_DEPTH,
                    MAX_DECLARED_FIELDS).fields()) {
                if (f.path().equals(FieldPath.ROOT)) {
                    observed.remove(f.path());
                    continue;
                }
                field(fields, part, status, f.path(), true, f.required(), f.types(), observed.remove(f.path()),
                        bodies);
            }
        }
        observed.remove(FieldPath.ROOT);
        for (Map.Entry<String, FieldStat> e : observed.entrySet()) {
            // under a documented free-form object this is allowed; the drift report is the authority on that
            field(fields, part, status, e.getKey(), false, false, List.of(), e.getValue(), bodies);
        }
    }

    private static void field(ArrayNode fields, String part, String status, String path, boolean documented,
                              boolean required, List<String> declaredTypes, FieldStat stat, long denominator) {
        ObjectNode f = fields.addObject();
        f.put("part", part);
        if (status != null) {
            f.put("status", status);
        }
        f.put("path", path);
        f.put("state", !documented ? "undocumented" : stat == null ? "never_seen" : "documented_and_used");
        if (documented) {
            f.put("required", required);
            f.set("declared_types", JSON.valueToTree(declaredTypes));
        }
        long count = stat == null ? 0 : stat.present.count();
        f.put("count", count);
        f.put("of", denominator);
        if (denominator > 0) {
            f.put("presence_rate", Math.min(1.0, count / (double) denominator));
        }
        if (stat != null) {
            ObjectNode types = f.putObject("observed_types");
            for (Map.Entry<JsonType, Long> t : stat.types.entrySet()) {
                types.put(t.getKey().wire(), t.getValue());
            }
            if (!stat.clients.isEmpty()) {
                ObjectNode clients = f.putObject("clients");
                stat.clients.entrySet().stream()
                        .filter(e -> !e.getKey().equals(Aggregate.UNIDENTIFIED))
                        .sorted(Comparator.comparingLong(
                                (Map.Entry<String, ClientField> e) -> e.getValue().present.count()).reversed())
                        .limit(MAX_CLIENTS)
                        .forEach(e -> clients.put(e.getKey(), e.getValue().present.count()));
            }
        }
    }

    /** Every identified client: first and last seen, total calls, and calls per UTC day. */
    public static ObjectNode clients(Aggregate aggregate) {
        ObjectNode out = JSON.createObjectNode();
        Map<String, Map<LocalDate, Long>> days = new TreeMap<>();
        Map<String, Map<String, Long>> operations = new TreeMap<>();
        aggregate.operations().forEach((op, s) -> s.days.forEach((client, byDay) -> {
            if (client.equals(Aggregate.UNIDENTIFIED)) {
                return;
            }
            Map<LocalDate, Long> mine = days.computeIfAbsent(client, c -> new TreeMap<>());
            long total = 0;
            for (Map.Entry<LocalDate, Long> e : byDay.entrySet()) {
                mine.merge(e.getKey(), e.getValue(), Long::sum);
                total += e.getValue();
            }
            operations.computeIfAbsent(client, c -> new TreeMap<>()).put(op, total);
        }));
        ArrayNode list = out.putArray("clients");
        aggregate.clients().entrySet().stream()
                .sorted((a, b) -> b.getValue().last().compareTo(a.getValue().last()))
                .limit(MAX_CLIENTS)
                .forEach(e -> {
                    ObjectNode c = list.addObject();
                    c.put("client", e.getKey());
                    c.put("events", e.getValue().count());
                    c.put("first_seen", e.getValue().first().toString());
                    c.put("last_seen", e.getValue().last().toString());
                    ObjectNode d = c.putObject("days");
                    days.getOrDefault(e.getKey(), Map.of()).forEach((day, n) -> d.put(day.toString(), n));
                    c.set("operations", JSON.valueToTree(operations.getOrDefault(e.getKey(), Map.of())));
                });
        out.put("clients_total", aggregate.clients().size());
        if (aggregate.events().count() > 0) {
            out.put("window_start", aggregate.windowStart().toString());
            out.put("window_end", aggregate.windowEnd().toString());
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
