package io.github.shrishaanth.axon.impact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shrishaanth.axon.diff.Change;
import io.github.shrishaanth.axon.diff.ChangeKind;
import io.github.shrishaanth.axon.diff.SpecDiff;
import io.github.shrishaanth.axon.impact.ImpactReport.Confidence;
import io.github.shrishaanth.axon.impact.ImpactReport.Evidence;
import io.github.shrishaanth.axon.impact.ImpactReport.Exposure;
import io.github.shrishaanth.axon.impact.ImpactReport.Row;
import io.github.shrishaanth.axon.impact.ImpactReport.TopClient;
import io.github.shrishaanth.axon.observe.Aggregate;
import io.github.shrishaanth.axon.observe.Aggregate.ClientField;
import io.github.shrishaanth.axon.observe.Aggregate.FieldKey;
import io.github.shrishaanth.axon.observe.Aggregate.FieldStat;
import io.github.shrishaanth.axon.observe.Aggregate.OperationStats;
import io.github.shrishaanth.axon.observe.Aggregate.Stat;
import io.github.shrishaanth.axon.spec.JsonType;
import io.github.shrishaanth.axon.spec.Operation;
import io.github.shrishaanth.axon.spec.Response;
import io.github.shrishaanth.axon.util.FieldPath;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Joins a diff with observed traffic: for every breaking change, who is affected, how recently, how severe.
 *
 * <p>The evidence class of a row is fixed by the kind of change (docs/metrics.md section 2). Where traffic
 * cannot answer the observed question after all (a header parameter, a query parameter's type, enum values that
 * were not captured), the row falls back to {@code POTENTIAL} with a note; it never claims {@code OBSERVED}
 * without the observation.
 */
public final class ImpactEngine {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int TOP_CLIENTS = 10;

    /** Affected requests of one client, with the time span they cover. */
    private static final class Hit {
        long requests;
        Instant first;
        Instant last;

        void add(long count, Instant f, Instant l) {
            if (count <= 0) {
                return;
            }
            requests += count;
            if (f != null && (first == null || f.isBefore(first))) {
                first = f;
            }
            if (l != null && (last == null || l.isAfter(last))) {
                last = l;
            }
        }
    }

    private record Found(Evidence evidence, Map<String, Hit> hits, String note) {
    }

    private final SpecDiff.Result diff;
    private final Aggregate aggregate;
    private final ImpactConfig config;
    private final Instant end;
    private final Set<String> activeClients = new HashSet<>();

    private ImpactEngine(SpecDiff.Result diff, Aggregate aggregate, ImpactConfig config) {
        this.diff = diff;
        this.aggregate = aggregate;
        this.config = config;
        this.end = aggregate.windowEnd();
        if (end != null) {
            Instant recentFrom = end.minus(seconds(config.recentDays()));
            for (Map.Entry<String, Stat> e : aggregate.clients().entrySet()) {
                if (!e.getValue().last().isBefore(recentFrom)) {
                    activeClients.add(e.getKey());
                }
            }
        }
    }

    /** {@code aggregate} must have been built against the baseline spec of {@code diff}. */
    public static ImpactReport analyse(SpecDiff.Result diff, Aggregate aggregate, ImpactConfig config) {
        return new ImpactEngine(diff, aggregate, config).run();
    }

    private ImpactReport run() {
        Instant start = aggregate.windowStart();
        double windowDays = start == null || end == null ? 0
                : Math.max(Duration.between(start, end).toMillis() / 86_400_000.0, 1.0 / 86_400);
        boolean identity = aggregate.hasIdentity();

        Map<String, Set<String>> groupOperations = new HashMap<>();
        for (Change c : diff.changes()) {
            if (c.breaking()) {
                groupOperations.computeIfAbsent(group(c), g -> new TreeSet<>()).add(c.operationKey());
            }
        }

        List<Row> rows = new ArrayList<>();
        int index = 0;
        for (Change c : diff.changes()) {
            index++;
            OperationStats stats = aggregate.operations().get(c.operationKey());
            long inScope = stats == null ? 0 : stats.calls.count();
            Confidence confidence = new Confidence(inScope, windowDays, windowDays == 0 ? 0 : 3.0 / windowDays,
                    inScope == 0 ? null : 1 - Math.pow(0.05, 1.0 / inScope), "uncalibrated");
            if (!c.breaking()) {
                rows.add(new Row("c" + index, c, Evidence.NONE, null, null, null, null, 1, confidence, null));
                continue;
            }
            Found found = stats == null ? new Found(evidenceWithoutTraffic(c), Map.of(), null) : affected(c, stats);
            Exposure exposure = exposure(found.hits(), identity);
            boolean critical = false;
            if (end != null) {
                Instant staleFrom = end.minus(seconds(config.staleDays()));
                for (String client : config.criticalClients()) {
                    Hit h = found.hits().get(client);
                    if (h != null && h.last != null && !h.last.isBefore(staleFrom)) {
                        critical = true;
                    }
                }
            }
            Severity severity = Severity.decide(identity, exposure.clients() == null ? 0 : exposure.clients(),
                    exposure.recent() == null ? 0 : exposure.recent(),
                    exposure.stale() == null ? 0 : exposure.stale(), activeClients.size(), critical, config);
            rows.add(new Row("c" + index, c, found.evidence(), exposure, severity, null, null,
                    groupOperations.get(group(c)).size(), confidence, found.note()));
        }

        rows = rank(rows);

        List<String> limitations = new ArrayList<>();
        limitations.add("Response-side changes are 'potential': traffic shows who calls an operation and which "
                + "status they received, not which response fields they read.");
        if (!identity) {
            limitations.add("No client identity in the traffic: clients cannot be counted, so severity is UNRATED "
                    + "and impact is request volume only.");
        } else if (aggregate.identified() < aggregate.events().count()) {
            limitations.add((aggregate.events().count() - aggregate.identified())
                    + " events carried no client identity; they count as requests but not as clients.");
        }
        if (windowDays > 0) {
            limitations.add(String.format(java.util.Locale.ROOT,
                    "Clients calling less often than %.3g times per day may be missing from a %.3g-day window "
                            + "(assumes steady, Poisson-like calls; periodic batch clients can be missed "
                            + "entirely).", 3.0 / windowDays, windowDays));
            if (windowDays < config.staleDays()) {
                limitations.add(String.format(java.util.Locale.ROOT,
                        "The window (%.3g days) is shorter than stale_days (%.3g), so DORMANT cannot occur and "
                                + "absence of a client is weak evidence.", windowDays, config.staleDays()));
            }
        }
        if (aggregate.events().count() == 0) {
            limitations.add("No traffic in the window: every row is NONE_OBSERVED or UNRATED by lack of data, "
                    + "not by evidence of safety.");
        }
        if (diff.truncated()) {
            limitations.add("A shared schema had more than 300 changes; the change list was cut.");
        }
        limitations.add("Not compared by the diff: value constraints (length, range, pattern), defaults, formats, "
                + "response headers, security.");

        return new ImpactReport(config, start, end, windowDays, aggregate.events().count(), aggregate.matched(),
                aggregate.events().count() - aggregate.matched(), aggregate.events().first(),
                aggregate.events().last(), identity, activeClients.size(), rows, limitations);
    }

    // ------------------------------------------------------------------------------------------------------
    // Who is affected

    private static Evidence evidenceWithoutTraffic(Change c) {
        boolean unobservedRequestPart = c.part().startsWith("request.") && !observablePart(c.part());
        return c.kind().name().startsWith("RESPONSE") || c.kind() == ChangeKind.MEDIA_TYPE_REMOVED
                || unobservedRequestPart ? Evidence.POTENTIAL : Evidence.OBSERVED;
    }

    private Found affected(Change c, OperationStats s) {
        switch (c.kind()) {
            case OPERATION_REMOVED:
                return new Found(Evidence.OBSERVED, fromStats(s.callers), null);
            case REQUEST_FIELD_REMOVED: {
                if (!observablePart(c.part())) {
                    return potentialCallers(s, "Traffic does not record " + c.part().substring(8)
                            + " parameters; every caller of the operation is listed.");
                }
                FieldStat f = s.fields.get(new FieldKey(c.part(), 0, c.field()));
                return new Found(Evidence.OBSERVED, f == null ? Map.of() : senders(f), null);
            }
            case REQUEST_FIELD_ADDED_REQUIRED:
            case REQUEST_FIELD_MADE_REQUIRED: {
                if (!observablePart(c.part())) {
                    return potentialCallers(s, "Traffic does not record " + c.part().substring(8)
                            + " parameters; every caller of the operation is listed.");
                }
                return missing(c, s);
            }
            case REQUEST_FIELD_TYPE_CHANGED: {
                FieldStat f = s.fields.get(new FieldKey(c.part(), 0, c.field()));
                if (!c.part().equals(Aggregate.REQUEST_BODY)) {
                    if (!observablePart(c.part())) {
                        return potentialCallers(s, "Traffic does not record " + c.part().substring(8)
                                + " parameters; every caller of the operation is listed.");
                    }
                    return new Found(Evidence.POTENTIAL, f == null ? Map.of() : senders(f),
                            "A query string carries text only, so the sent type is unknown; every client that "
                                    + "sent the parameter is listed.");
                }
                if (f == null) {
                    return new Found(Evidence.OBSERVED, Map.of(), null);
                }
                Set<JsonType> accepted = parseTypes(c.to());
                Map<String, Hit> hits = new TreeMap<>();
                for (Map.Entry<String, ClientField> e : f.clients.entrySet()) {
                    long rejected = 0;
                    for (Map.Entry<JsonType, Long> t : e.getValue().types.entrySet()) {
                        if (!accepted.contains(t.getKey())) {
                            rejected += t.getValue();
                        }
                    }
                    if (rejected > 0) {
                        // timestamps are those of the client's use of the field, an upper bound on recency
                        hit(hits, e.getKey()).add(Math.min(rejected, e.getValue().present.count()),
                                e.getValue().present.first(), e.getValue().present.last());
                    }
                }
                return new Found(Evidence.OBSERVED, hits, null);
            }
            case REQUEST_ENUM_NARROWED: {
                if (!observablePart(c.part())) {
                    return potentialCallers(s, "Traffic does not record " + c.part().substring(8)
                            + " parameters; every caller of the operation is listed.");
                }
                FieldStat f = s.fields.get(new FieldKey(c.part(), 0, c.field()));
                if (f == null) {
                    return new Found(Evidence.OBSERVED, Map.of(), null);
                }
                Set<String> before = enumValues(c.from());
                Set<String> after = enumValues(c.to());
                if (!f.valuesCaptured || before == null || after == null) {
                    return new Found(Evidence.POTENTIAL, senders(f),
                            "The values sent were not captured (they are kept only for fields the spec declared "
                                    + "as an enum when the traffic was sanitised); every client that sent the "
                                    + "field is listed.");
                }
                Set<String> removed = new HashSet<>(before);
                removed.removeAll(after);
                Map<String, Hit> hits = new TreeMap<>();
                for (Map.Entry<String, ClientField> e : f.clients.entrySet()) {
                    for (Map.Entry<String, Stat> v : e.getValue().values.entrySet()) {
                        if (removed.contains(v.getKey())) {
                            hit(hits, e.getKey()).add(v.getValue().count(), v.getValue().first(),
                                    v.getValue().last());
                        }
                    }
                }
                return new Found(Evidence.OBSERVED, hits, null);
            }
            case REQUEST_BODY_REMOVED:
                return new Found(Evidence.OBSERVED, fromStats(s.withRequestBody),
                        "Only JSON request bodies are observed.");
            case REQUEST_BODY_ADDED:
            case REQUEST_BODY_MADE_REQUIRED: {
                Map<String, Hit> hits = new TreeMap<>();
                for (Map.Entry<String, Stat> e : s.callers.entrySet()) {
                    Stat with = s.withRequestBody.get(e.getKey());
                    long without = e.getValue().count() - (with == null ? 0 : with.count());
                    if (without > 0) {
                        hit(hits, e.getKey()).add(without, e.getValue().first(), e.getValue().last());
                    }
                }
                return new Found(Evidence.OBSERVED, hits,
                        "Only JSON request bodies are observed; a client sending another media type counts as "
                                + "sending no body.");
            }
            default:
                break;
        }
        if (c.kind().name().startsWith("RESPONSE") || (c.status() != null && c.kind() == ChangeKind.MEDIA_TYPE_REMOVED)) {
            return potentialByStatus(c, s);
        }
        return potentialCallers(s, null);
    }

    private static boolean observablePart(String part) {
        return part.equals(Aggregate.REQUEST_QUERY) || part.equals(Aggregate.REQUEST_BODY);
    }

    /** Clients that called with the parent present and the field absent. */
    private Found missing(Change c, OperationStats s) {
        FieldStat field = s.fields.get(new FieldKey(c.part(), 0, c.field()));
        Map<String, Hit> hits = new TreeMap<>();
        String note;
        Map<String, Stat> parents = new TreeMap<>();
        if (c.part().equals(Aggregate.REQUEST_QUERY)) {
            parents.putAll(s.callers);
            note = "Last seen is the client's last call, an upper bound on when it last omitted the parameter.";
        } else {
            String parentPath = FieldPath.parent(c.field());
            FieldStat parent = parentPath == null ? null
                    : s.fields.get(new FieldKey(Aggregate.REQUEST_BODY, 0, parentPath));
            if (parent != null) {
                parent.clients.forEach((client, cf) -> parents.put(client, cf.present));
            }
            note = "Counts requests whose body had the parent object but not the field. Last seen is the last "
                    + "such body from the client, an upper bound."
                    + (c.field().contains("[]") ? " Inside arrays a field missing from only some items is not "
                    + "detected." : "");
        }
        for (Map.Entry<String, Stat> e : parents.entrySet()) {
            ClientField cf = field == null ? null : field.clients.get(e.getKey());
            long without = e.getValue().count() - (cf == null ? 0 : cf.present.count());
            if (without > 0) {
                hit(hits, e.getKey()).add(without, e.getValue().first(), e.getValue().last());
            }
        }
        return new Found(Evidence.OBSERVED, hits, note);
    }

    private Found potentialCallers(OperationStats s, String note) {
        return new Found(Evidence.POTENTIAL, fromStats(s.callers), note);
    }

    /** Callers that received the response the change is about. */
    private Found potentialByStatus(Change c, OperationStats s) {
        if (c.status() == null) {
            return potentialCallers(s, null);
        }
        Operation op = aggregate.spec().operation(c.method(), c.path());
        Map<String, Hit> hits = new TreeMap<>();
        for (Map.Entry<Integer, Map<String, Stat>> e : s.statuses.entrySet()) {
            boolean applies;
            if (c.status().equals(Integer.toString(e.getKey()))) {
                applies = true;
            } else if (op != null) {
                Response declared = op.responseFor(e.getKey());
                applies = declared != null && declared.status().equals(c.status());
            } else {
                applies = false;
            }
            if (applies) {
                e.getValue().forEach((client, stat) -> hit(hits, client).add(stat.count(), stat.first(),
                        stat.last()));
            }
        }
        return new Found(Evidence.POTENTIAL, hits, null);
    }

    private static Map<String, Hit> senders(FieldStat f) {
        Map<String, Hit> hits = new TreeMap<>();
        f.clients.forEach((client, cf) -> hit(hits, client).add(cf.present.count(), cf.present.first(),
                cf.present.last()));
        return hits;
    }

    private static Map<String, Hit> fromStats(Map<String, Stat> stats) {
        Map<String, Hit> hits = new TreeMap<>();
        stats.forEach((client, stat) -> hit(hits, client).add(stat.count(), stat.first(), stat.last()));
        return hits;
    }

    private static Hit hit(Map<String, Hit> hits, String client) {
        return hits.computeIfAbsent(client, c -> new Hit());
    }

    /** Types a schema described as "integer|null" accepts; "any" accepts all; number includes integer. */
    static Set<JsonType> parseTypes(String text) {
        if (text == null || text.equals("any")) {
            return EnumSet.allOf(JsonType.class);
        }
        Set<JsonType> out = EnumSet.noneOf(JsonType.class);
        for (String name : text.split("\\|")) {
            JsonType t = JsonType.fromWire(name);
            if (t != null) {
                out.add(t);
            }
        }
        if (out.contains(JsonType.NUMBER)) {
            out.add(JsonType.INTEGER);
        }
        Set<JsonType> withoutNull = EnumSet.copyOf(out.isEmpty() ? EnumSet.noneOf(JsonType.class) : out);
        withoutNull.remove(JsonType.NULL);
        return withoutNull.isEmpty() ? EnumSet.allOf(JsonType.class) : out;
    }

    /** Parses the JSON array the diff stores for an enum; values are returned as plain text. */
    static Set<String> enumValues(String json) {
        if (json == null) {
            return null;
        }
        try {
            Set<String> out = new HashSet<>();
            for (JsonNode v : JSON.readTree(json)) {
                out.add(v.asText());
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Exposure, ranking

    private Exposure exposure(Map<String, Hit> hits, boolean identity) {
        long requests = 0;
        Instant first = null;
        Instant last = null;
        int recent = 0;
        int stale = 0;
        Instant recentFrom = end == null ? null : end.minus(seconds(config.recentDays()));
        Instant staleFrom = end == null ? null : end.minus(seconds(config.staleDays()));
        List<TopClient> top = new ArrayList<>();
        Set<String> ids = new TreeSet<>();
        for (Map.Entry<String, Hit> e : hits.entrySet()) {
            Hit h = e.getValue();
            if (h.requests <= 0) {
                continue;
            }
            requests += h.requests;
            if (h.first != null && (first == null || h.first.isBefore(first))) {
                first = h.first;
            }
            if (h.last != null && (last == null || h.last.isAfter(last))) {
                last = h.last;
            }
            if (e.getKey().equals(Aggregate.UNIDENTIFIED)) {
                continue;
            }
            ids.add(e.getKey());
            top.add(new TopClient(e.getKey(), h.requests, h.last));
            if (recentFrom != null && h.last != null && !h.last.isBefore(recentFrom)) {
                recent++;
            }
            if (staleFrom != null && h.last != null && !h.last.isBefore(staleFrom)) {
                stale++;
            }
        }
        top.sort(Comparator.comparingLong(TopClient::requests).reversed().thenComparing(TopClient::client));
        if (top.size() > TOP_CLIENTS) {
            top = new ArrayList<>(top.subList(0, TOP_CLIENTS));
        }
        if (!identity) {
            return new Exposure(null, requests, null, null, null, first, last, List.of(), Set.of());
        }
        Double share = activeClients.isEmpty() ? null : recent / (double) activeClients.size();
        return new Exposure(ids.size(), requests, share, recent, stale, first, last, List.copyOf(top), ids);
    }

    /** Changes with the same source schema, kind and leaf are one edit seen from several operations. */
    private static String group(Change c) {
        if (c.source() == null || c.field() == null) {
            return c.operationKey() + "|" + c.kind() + "|" + c.part() + "|" + c.status() + "|" + c.field();
        }
        List<FieldPath.Segment> segments = FieldPath.parse(c.field());
        String leaf = "";
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (!segments.get(i).isItems()) {
                leaf = segments.get(i).name();
                break;
            }
        }
        boolean request = c.part().startsWith("request");
        return c.source() + "|" + c.kind() + "|" + leaf + "|" + (request ? "request" : "response");
    }

    private List<Row> rank(List<Row> rows) {
        List<Row> breaking = new ArrayList<>();
        Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            order.put(rows.get(i).id(), i);
            if (rows.get(i).change().breaking()) {
                breaking.add(rows.get(i));
            }
        }
        Comparator<Row> bySpecOrder = Comparator.comparingInt(r -> order.get(r.id()));

        // Axon: severity, observed before potential, recent clients, recency, volume, spec order
        List<Row> ranked = new ArrayList<>(breaking);
        ranked.sort(Comparator.<Row>comparingInt(r -> r.severity().ordinal())
                .thenComparingInt(r -> r.evidence() == Evidence.OBSERVED ? 0 : 1)
                .thenComparing(r -> r.exposure().recent() == null ? 0 : -r.exposure().recent())
                .thenComparing(r -> r.exposure().lastSeen() == null ? Instant.MIN : r.exposure().lastSeen(),
                        Comparator.reverseOrder())
                .thenComparing(r -> -r.exposure().requests())
                .thenComparing(bySpecOrder));
        Map<String, Integer> axonRank = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            axonRank.put(ranked.get(i).id(), i + 1);
        }

        // spec-only baseline: operations touched, then spec order
        List<Row> baseline = new ArrayList<>(breaking);
        baseline.sort(Comparator.<Row>comparingInt(r -> -r.operationsTouched()).thenComparing(bySpecOrder));
        Map<String, Integer> specRank = new HashMap<>();
        for (int i = 0; i < baseline.size(); i++) {
            specRank.put(baseline.get(i).id(), i + 1);
        }

        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(new Row(r.id(), r.change(), r.evidence(), r.exposure(), r.severity(), axonRank.get(r.id()),
                    specRank.get(r.id()), r.operationsTouched(), r.confidence(), r.note()));
        }
        return out;
    }

    private static Duration seconds(double days) {
        return Duration.ofSeconds(Math.round(days * 86_400));
    }

    /** Summary counts by severity, observed and potential kept apart. */
    public static Map<Evidence, Map<Severity, Integer>> bySeverity(ImpactReport report) {
        Map<Evidence, Map<Severity, Integer>> out = new LinkedHashMap<>();
        out.put(Evidence.OBSERVED, new TreeMap<>());
        out.put(Evidence.POTENTIAL, new TreeMap<>());
        for (Row r : report.breaking()) {
            out.get(r.evidence()).merge(r.severity(), 1, Integer::sum);
        }
        return out;
    }
}
