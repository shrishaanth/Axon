package io.github.shrishaanth.axon.observe;

import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.JsonType;
import io.github.shrishaanth.axon.spec.Operation;
import io.github.shrishaanth.axon.traffic.Matcher;
import io.github.shrishaanth.axon.traffic.TrafficEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything Axon remembers about traffic: counts with first and last timestamps, keyed by operation, client,
 * status, field, type and (for declared enums) value.
 *
 * <p>Every update is a sum, a minimum or a maximum, so the result does not depend on the order events arrive in.
 * That is what makes replay equal to live ingestion, and what lets the same structure live in Postgres as
 * upserted counters.
 */
public final class Aggregate {

    /** Pseudo-client for events that carry no client identity. */
    public static final String UNIDENTIFIED = "";

    public static final String REQUEST_QUERY = "request.query";
    public static final String REQUEST_BODY = "request.body";
    public static final String RESPONSE_BODY = "response.body";

    /** A counter with the time span it covers. */
    public static final class Stat {
        private long count;
        private Instant first;
        private Instant last;

        /** Folds another counter into this one. */
        void merge(long n, Instant f, Instant l) {
            if (n <= 0) {
                return;
            }
            count += n;
            if (first == null || (f != null && f.isBefore(first))) {
                first = f;
            }
            if (last == null || (l != null && l.isAfter(last))) {
                last = l;
            }
        }

        void add(Instant ts) {
            count++;
            if (first == null || ts.isBefore(first)) {
                first = ts;
            }
            if (last == null || ts.isAfter(last)) {
                last = ts;
            }
        }

        public long count() {
            return count;
        }

        public Instant first() {
            return first;
        }

        public Instant last() {
            return last;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Stat s && s.count == count && java.util.Objects.equals(s.first, first)
                    && java.util.Objects.equals(s.last, last);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(count, first, last);
        }

        @Override
        public String toString() {
            return count + "[" + first + ".." + last + "]";
        }
    }

    /** What one client sent at one field. */
    public static final class ClientField {
        public final Stat present = new Stat();
        public final Map<JsonType, Long> types = new EnumMap<>(JsonType.class);
        public final Map<String, Stat> values = new TreeMap<>();

        @Override
        public boolean equals(Object o) {
            return o instanceof ClientField c && c.present.equals(present) && c.types.equals(types)
                    && c.values.equals(values);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(present, types, values);
        }
    }

    /**
     * One field of one part of one operation. {@code status} is 0 for request parts.
     */
    public record FieldKey(String part, int status, String path) {
    }

    public static final class FieldStat {
        /** Events in which the field was present. */
        public final Stat present = new Stat();
        /** Events per observed type; one event can add to several. */
        public final Map<JsonType, Long> types = new EnumMap<>(JsonType.class);
        /** Per client, for request parts only: response fields say nothing about who reads them. */
        public final Map<String, ClientField> clients = new TreeMap<>();
        /** True once any event carried enum values for this field. */
        public boolean valuesCaptured;

        @Override
        public boolean equals(Object o) {
            return o instanceof FieldStat f && f.present.equals(present) && f.types.equals(types)
                    && f.clients.equals(clients) && f.valuesCaptured == valuesCaptured;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(present, types, clients, valuesCaptured);
        }
    }

    public static final class OperationStats {
        public final Stat calls = new Stat();
        public final Map<String, Stat> callers = new TreeMap<>();
        /** status code to (client to calls). */
        public final Map<Integer, Map<String, Stat>> statuses = new TreeMap<>();
        public final Map<FieldKey, FieldStat> fields = new HashMap<>();
        /** Calls that carried a JSON request body, per client. */
        public final Map<String, Stat> withRequestBody = new TreeMap<>();
        /** Responses that carried a JSON body, per status. */
        public final Map<Integer, Long> withResponseBody = new TreeMap<>();
        /** client to (UTC day to calls): the last-seen timeline. */
        public final Map<String, Map<LocalDate, Long>> days = new TreeMap<>();
        public final LatencyHistogram latency = new LatencyHistogram();

        public long statusCount(int status) {
            Map<String, Stat> byClient = statuses.get(status);
            return byClient == null ? 0 : byClient.values().stream().mapToLong(Stat::count).sum();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof OperationStats s && s.calls.equals(calls) && s.callers.equals(callers)
                    && s.statuses.equals(statuses) && s.fields.equals(fields)
                    && s.withRequestBody.equals(withRequestBody) && s.withResponseBody.equals(withResponseBody)
                    && s.days.equals(days) && s.latency.equals(latency);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(calls, callers, statuses, fields, withRequestBody, withResponseBody,
                    days, latency);
        }
    }

    private final ApiSpec spec;
    private final Matcher matcher;
    private final Map<String, Operation> byKey = new HashMap<>();
    private final Instant windowStart;
    private final Instant windowEnd;

    private final Map<String, OperationStats> operations = new LinkedHashMap<>();
    private final Map<String, Stat> unmatched = new TreeMap<>();
    private final Map<String, Stat> clients = new TreeMap<>();
    private final Stat all = new Stat();
    private long matched;
    private long outsideWindow;
    private long identified;

    /**
     * @param windowStart inclusive, or null for "from the first event"
     * @param windowEnd   inclusive, or null for "to the last event"
     */
    public Aggregate(ApiSpec spec, Instant windowStart, Instant windowEnd) {
        this.spec = spec;
        this.matcher = new Matcher(spec);
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        for (Operation op : spec.operations()) {
            byKey.putIfAbsent(op.key(), op);
        }
    }

    public void add(TrafficEvent e) {
        if ((windowStart != null && e.ts().isBefore(windowStart)) || (windowEnd != null && e.ts().isAfter(windowEnd))) {
            outsideWindow++;
            return;
        }
        all.add(e.ts());
        String client = e.client() == null ? UNIDENTIFIED : e.client();
        if (e.client() != null) {
            identified++;
            clients.computeIfAbsent(client, c -> new Stat()).add(e.ts());
        }
        Operation op = resolve(e);
        if (op == null) {
            String where = e.operation() != null ? e.operation() : e.method() + " " + e.path();
            unmatched.computeIfAbsent(where, w -> new Stat()).add(e.ts());
            return;
        }
        matched++;
        OperationStats s = operations.computeIfAbsent(op.key(), k -> new OperationStats());
        s.calls.add(e.ts());
        s.callers.computeIfAbsent(client, c -> new Stat()).add(e.ts());
        s.statuses.computeIfAbsent(e.status(), st -> new TreeMap<>()).computeIfAbsent(client, c -> new Stat())
                .add(e.ts());
        s.days.computeIfAbsent(client, c -> new TreeMap<>())
                .merge(LocalDate.ofInstant(e.ts(), ZoneOffset.UTC), 1L, Long::sum);
        if (e.latencyMs() != null) {
            s.latency.add(e.latencyMs());
        }
        for (TrafficEvent.Field f : e.query()) {
            field(s, new FieldKey(REQUEST_QUERY, 0, f.path()), f, client, e.ts());
        }
        if (e.requestBody() != null) {
            s.withRequestBody.computeIfAbsent(client, c -> new Stat()).add(e.ts());
            for (TrafficEvent.Field f : e.requestBody()) {
                field(s, new FieldKey(REQUEST_BODY, 0, f.path()), f, client, e.ts());
            }
        }
        if (e.responseBody() != null) {
            s.withResponseBody.merge(e.status(), 1L, Long::sum);
            for (TrafficEvent.Field f : e.responseBody()) {
                field(s, new FieldKey(RESPONSE_BODY, e.status(), f.path()), f, null, e.ts());
            }
        }
    }

    private static void field(OperationStats s, FieldKey key, TrafficEvent.Field f, String client, Instant ts) {
        FieldStat stat = s.fields.computeIfAbsent(key, k -> new FieldStat());
        stat.present.add(ts);
        for (JsonType t : f.types()) {
            stat.types.merge(t, 1L, Long::sum);
        }
        if (f.values() != null) {
            stat.valuesCaptured = true;
        }
        if (client == null) {
            return;
        }
        ClientField cf = stat.clients.computeIfAbsent(client, c -> new ClientField());
        cf.present.add(ts);
        for (JsonType t : f.types()) {
            cf.types.merge(t, 1L, Long::sum);
        }
        if (f.values() != null) {
            for (String v : f.values()) {
                cf.values.computeIfAbsent(v, x -> new Stat()).add(ts);
            }
        }
    }

    Operation resolve(TrafficEvent e) {
        if (e.operation() != null) {
            Operation op = byKey.get(e.operation());
            if (op != null) {
                return op;
            }
            // sanitised against another version of the spec: match its template, parameters as wildcards
            int space = e.operation().indexOf(' ');
            if (space < 0) {
                return null;
            }
            String template = e.operation().substring(space + 1).replaceAll("\\{[^/}]*}", "{*}");
            return matcher.match(e.method(), template);
        }
        return matcher.match(e.method(), e.path());
    }

    public ApiSpec spec() {
        return spec;
    }

    // Used by Cells to rebuild an aggregate from stored counters.

    OperationStats operation(String key) {
        return operations.computeIfAbsent(key, k -> new OperationStats());
    }

    void event(String client, long n, Instant first, Instant last) {
        all.merge(n, first, last);
        if (!client.equals(UNIDENTIFIED)) {
            identified += n;
            clients.computeIfAbsent(client, c -> new Stat()).merge(n, first, last);
        }
    }

    void unmatched(String where, long n, Instant first, Instant last) {
        unmatched.computeIfAbsent(where, w -> new Stat()).merge(n, first, last);
    }

    void matched(long n) {
        matched += n;
    }

    public Map<String, OperationStats> operations() {
        return operations;
    }

    /** Events that matched no operation, by "METHOD path". */
    public Map<String, Stat> unmatched() {
        return unmatched;
    }

    /** Every identified client with its first and last event on any operation, matched or not. */
    public Map<String, Stat> clients() {
        return clients;
    }

    /** All events inside the window. */
    public Stat events() {
        return all;
    }

    public long matched() {
        return matched;
    }

    public long outsideWindow() {
        return outsideWindow;
    }

    /** True when at least one event carried a client identity. */
    public boolean hasIdentity() {
        return identified > 0;
    }

    public long identified() {
        return identified;
    }

    public Instant windowStart() {
        return windowStart != null ? windowStart : all.first();
    }

    public Instant windowEnd() {
        return windowEnd != null ? windowEnd : all.last();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Aggregate a && a.operations.equals(operations) && a.unmatched.equals(unmatched)
                && a.clients.equals(clients) && a.all.equals(all) && a.matched == matched
                && a.outsideWindow == outsideWindow && a.identified == identified;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(operations, unmatched, clients, all, matched);
    }
}
