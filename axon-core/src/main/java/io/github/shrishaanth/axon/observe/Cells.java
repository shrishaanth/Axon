package io.github.shrishaanth.axon.observe;

import io.github.shrishaanth.axon.observe.Aggregate.ClientField;
import io.github.shrishaanth.axon.observe.Aggregate.FieldKey;
import io.github.shrishaanth.axon.observe.Aggregate.FieldStat;
import io.github.shrishaanth.axon.observe.Aggregate.OperationStats;
import io.github.shrishaanth.axon.observe.Aggregate.Stat;
import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.JsonType;
import io.github.shrishaanth.axon.spec.Operation;
import io.github.shrishaanth.axon.traffic.TrafficEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * An {@link Aggregate} taken apart into additive counters, one per (kind, operation, part, status, field,
 * detail, client, UTC day). This is the form that is stored: a batch of events becomes a map of cells, cells are
 * upserted by adding counts and widening the time span, and any set of cells can be folded back into an
 * aggregate.
 *
 * <p>Because every cell update is a sum, a minimum or a maximum, the stored cells are the same whatever the
 * order or batching of the events. That is what makes replay reproduce live ingestion exactly.
 */
public final class Cells {

    /** What a cell counts. The names are stored, so they must not change. */
    public enum Kind {
        /** Every event: client, day. */
        EVENT,
        /** An event that matched no operation: field holds "METHOD path". */
        UNMATCHED,
        /** A matched call: operation, client, day. */
        CALL,
        /** A response status: operation, status, client. */
        STATUS,
        /** A call that carried a JSON request body: operation, client. */
        REQUEST_BODY,
        /** A response that carried a JSON body: operation, status. */
        RESPONSE_BODY,
        /** A field was present: operation, part, status, field, client (empty for response parts). */
        FIELD,
        /** A field had a type: detail holds the type. */
        TYPE,
        /** A declared-enum field had a value: detail holds the value. */
        VALUE,
        /** Enum values were captured for the field at all. */
        CAPTURED,
        /** A latency fell in a bucket: detail holds the bucket number. */
        LATENCY
    }

    /** Empty strings and status 0 stand for "does not apply", so the key has no nulls. */
    public record Key(Kind kind, String operation, String part, int status, String field, String detail,
                      String client, LocalDate day) {
    }

    public static final class Value {
        public long count;
        public Instant first;
        public Instant last;

        public Value() {
        }

        public Value(long count, Instant first, Instant last) {
            this.count = count;
            this.first = first;
            this.last = last;
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

        public void merge(Value other) {
            count += other.count;
            if (first == null || (other.first != null && other.first.isBefore(first))) {
                first = other.first;
            }
            if (last == null || (other.last != null && other.last.isAfter(last))) {
                last = other.last;
            }
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Value v && v.count == count && java.util.Objects.equals(v.first, first)
                    && java.util.Objects.equals(v.last, last);
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

    private final Aggregate matcher;
    private final Map<Key, Value> cells = new HashMap<>();

    /** Events are matched against {@code spec}, exactly as {@link Aggregate#add} matches them. */
    public Cells(ApiSpec spec) {
        this.matcher = new Aggregate(spec, null, null);
    }

    public Map<Key, Value> cells() {
        return cells;
    }

    private void bump(Kind kind, String operation, String part, int status, String field, String detail,
                      String client, Instant ts) {
        Key key = new Key(kind, operation, part, status, field, detail, client,
                LocalDate.ofInstant(ts, ZoneOffset.UTC));
        cells.computeIfAbsent(key, k -> new Value()).add(ts);
    }

    public void add(TrafficEvent e) {
        Instant ts = e.ts();
        String client = e.client() == null ? Aggregate.UNIDENTIFIED : e.client();
        bump(Kind.EVENT, "", "", 0, "", "", client, ts);
        Operation op = matcher.resolve(e);
        if (op == null) {
            String where = e.operation() != null ? e.operation() : e.method() + " " + e.path();
            bump(Kind.UNMATCHED, "", "", 0, where, "", "", ts);
            return;
        }
        String key = op.key();
        bump(Kind.CALL, key, "", 0, "", "", client, ts);
        bump(Kind.STATUS, key, "", e.status(), "", "", client, ts);
        if (e.latencyMs() != null) {
            bump(Kind.LATENCY, key, "", 0, "", Integer.toString(LatencyHistogram.bucket(e.latencyMs())), "", ts);
        }
        for (TrafficEvent.Field f : e.query()) {
            field(key, Aggregate.REQUEST_QUERY, 0, f, client, ts);
        }
        if (e.requestBody() != null) {
            bump(Kind.REQUEST_BODY, key, "", 0, "", "", client, ts);
            for (TrafficEvent.Field f : e.requestBody()) {
                field(key, Aggregate.REQUEST_BODY, 0, f, client, ts);
            }
        }
        if (e.responseBody() != null) {
            bump(Kind.RESPONSE_BODY, key, "", e.status(), "", "", "", ts);
            for (TrafficEvent.Field f : e.responseBody()) {
                // response fields carry no client: traffic does not show who reads them
                field(key, Aggregate.RESPONSE_BODY, e.status(), f, "", ts);
            }
        }
    }

    private void field(String operation, String part, int status, TrafficEvent.Field f, String client, Instant ts) {
        bump(Kind.FIELD, operation, part, status, f.path(), "", client, ts);
        for (JsonType t : f.types()) {
            bump(Kind.TYPE, operation, part, status, f.path(), t.wire(), client, ts);
        }
        if (f.values() != null) {
            bump(Kind.CAPTURED, operation, part, status, f.path(), "", "", ts);
            if (!part.equals(Aggregate.RESPONSE_BODY)) {
                for (String v : f.values()) {
                    bump(Kind.VALUE, operation, part, status, f.path(), v, client, ts);
                }
            }
        }
    }

    /**
     * Folds cells into an aggregate. Cells whose day lies outside {@code [from, to]} are skipped; pass null for
     * no bound. The window is therefore day-granular: a stored aggregate cannot be cut inside a UTC day.
     */
    public static Aggregate toAggregate(ApiSpec spec, Iterable<Map.Entry<Key, Value>> cells, LocalDate from,
                                        LocalDate to) {
        Aggregate a = new Aggregate(spec, null, null);
        for (Map.Entry<Key, Value> entry : cells) {
            Key k = entry.getKey();
            Value v = entry.getValue();
            if ((from != null && k.day().isBefore(from)) || (to != null && k.day().isAfter(to))) {
                continue;
            }
            switch (k.kind()) {
                case EVENT -> a.event(k.client(), v.count, v.first, v.last);
                case UNMATCHED -> a.unmatched(k.field(), v.count, v.first, v.last);
                case CALL -> {
                    OperationStats s = a.operation(k.operation());
                    s.calls.merge(v.count, v.first, v.last);
                    s.callers.computeIfAbsent(k.client(), c -> new Stat()).merge(v.count, v.first, v.last);
                    s.days.computeIfAbsent(k.client(), c -> new TreeMap<>()).merge(k.day(), v.count, Long::sum);
                    a.matched(v.count);
                }
                case STATUS -> a.operation(k.operation()).statuses.computeIfAbsent(k.status(), st -> new TreeMap<>())
                        .computeIfAbsent(k.client(), c -> new Stat()).merge(v.count, v.first, v.last);
                case REQUEST_BODY -> a.operation(k.operation()).withRequestBody
                        .computeIfAbsent(k.client(), c -> new Stat()).merge(v.count, v.first, v.last);
                case RESPONSE_BODY -> a.operation(k.operation()).withResponseBody.merge(k.status(), v.count, Long::sum);
                case LATENCY -> a.operation(k.operation()).latency.add(Integer.parseInt(k.detail()), v.count);
                case FIELD -> {
                    FieldStat f = fieldStat(a, k);
                    f.present.merge(v.count, v.first, v.last);
                    if (!k.part().equals(Aggregate.RESPONSE_BODY)) {
                        f.clients.computeIfAbsent(k.client(), c -> new ClientField()).present
                                .merge(v.count, v.first, v.last);
                    }
                }
                case TYPE -> {
                    FieldStat f = fieldStat(a, k);
                    JsonType type = JsonType.fromWire(k.detail());
                    f.types.merge(type, v.count, Long::sum);
                    if (!k.part().equals(Aggregate.RESPONSE_BODY)) {
                        f.clients.computeIfAbsent(k.client(), c -> new ClientField()).types
                                .merge(type, v.count, Long::sum);
                    }
                }
                case VALUE -> fieldStat(a, k).clients.computeIfAbsent(k.client(), c -> new ClientField()).values
                        .computeIfAbsent(k.detail(), x -> new Stat()).merge(v.count, v.first, v.last);
                case CAPTURED -> fieldStat(a, k).valuesCaptured = true;
                default -> throw new IllegalStateException(k.kind().name());
            }
        }
        return a;
    }

    private static FieldStat fieldStat(Aggregate a, Key k) {
        return a.operation(k.operation()).fields
                .computeIfAbsent(new FieldKey(k.part(), k.status(), k.field()), x -> new FieldStat());
    }
}
