package io.github.shrishaanth.axon.eval.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shrishaanth.axon.eval.Truth;
import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.Body;
import io.github.shrishaanth.axon.spec.JsonType;
import io.github.shrishaanth.axon.spec.Operation;
import io.github.shrishaanth.axon.spec.Parameter;
import io.github.shrishaanth.axon.spec.Response;
import io.github.shrishaanth.axon.spec.Schema;
import io.github.shrishaanth.axon.traffic.Sanitiser;
import io.github.shrishaanth.axon.traffic.TrafficEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A synthetic API population with hidden ground truth (dataset T-sim; parameters in eval/generator.md).
 *
 * <p>One server model decides which response fields appear and how often, and carries the injected drift.
 * Clients belong to cohorts (think SDK versions); a cohort fixes which operations are used, which request
 * fields are sent, which enum values are preferred and which response fields the client's code reads. Client
 * activity is heavy-tailed, with a share of rare clients on top.
 */
public final class World {

    public static final String SALT = "axon-sim";
    public static final String CLIENT_HEADER = "X-Client-Id";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Parameters drawn per seed from fixed ranges. */
    public record Params(int clients, int cohorts, double zipf, double rareShare, double requestsPerDay) {
    }

    public static final class Cohort {
        final String name;
        final BodyModel requests = new BodyModel();
        final Map<Operation, Double> weights = new LinkedHashMap<>();
        /** operation to (query parameter to probability of sending it). */
        final Map<Operation, Map<String, Double>> query = new IdentityHashMap<>();
        final Map<Operation, Map<String, String>> queryFixed = new IdentityHashMap<>();
        /** operation to the response field paths this cohort's code reads from the success response. */
        final Map<Operation, Set<String>> reads = new IdentityHashMap<>();

        Cohort(String name) {
            this.name = name;
        }
    }

    public record Client(String name, Cohort cohort, double ratePerDay, boolean rare) {
    }

    /** How one operation answers. */
    public static final class StatusModel {
        final List<Integer> codes = new ArrayList<>();
        final List<Double> probabilities = new ArrayList<>();
        int success;
        /** Undocumented status codes injected, with their rates. */
        public final Map<Integer, Double> undocumented = new TreeMap<>();

        public Map<Integer, Double> all() {
            Map<Integer, Double> out = new TreeMap<>();
            for (int i = 0; i < codes.size(); i++) {
                out.put(codes.get(i), probabilities.get(i));
            }
            return out;
        }
    }

    public final ApiSpec spec;
    public final long seed;
    public final Params params;
    public final List<Operation> operations = new ArrayList<>();
    public final BodyModel server = new BodyModel();
    public final Map<Operation, StatusModel> statuses = new IdentityHashMap<>();
    public final List<Cohort> cohorts = new ArrayList<>();
    public final List<Client> clients = new ArrayList<>();
    /** Query parameters sent by some cohort that the spec does not declare: operation key to names. */
    public final Map<String, Set<String>> undocumentedQuery = new TreeMap<>();
    /** Share of requests that go to a path the spec does not document. */
    public double undocumentedOperationRate;

    private final Random random;
    private final Sanitiser sanitiser;

    public World(ApiSpec spec, long seed, boolean alternativeTail) {
        this.spec = spec;
        this.seed = seed;
        this.random = new Random(seed * 7919L + 17);
        // The held-out parameterisation uses a much heavier tail and more rare clients; it is never tuned on.
        this.params = alternativeTail
                ? new Params(150 + random.nextInt(151), 3 + random.nextInt(4), 1.6 + random.nextDouble() * 0.6,
                        0.35 + random.nextDouble() * 0.15, 800 + random.nextInt(1200))
                : new Params(100 + random.nextInt(201), 4 + random.nextInt(5), 0.8 + random.nextDouble() * 0.6,
                        0.10 + random.nextDouble() * 0.10, 800 + random.nextInt(1200));
        this.sanitiser = new Sanitiser(new Sanitiser.Config("header", CLIENT_HEADER, SALT), spec);
        for (Operation op : spec.operations()) {
            operations.add(op);
        }
        buildServer();
        buildCohorts();
        buildClients();
        undocumentedOperationRate = random.nextDouble() < 0.5 ? 0.002 : 0.0;
    }

    // ------------------------------------------------------------------------------------------------------

    private void buildServer() {
        Set<Schema> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Operation op : operations) {
            StatusModel model = new StatusModel();
            List<String> documented = new ArrayList<>(op.responses().keySet());
            String success = documented.stream().filter(s -> s.startsWith("2")).findFirst()
                    .orElse(documented.isEmpty() ? "200" : documented.get(0));
            model.success = code(success);
            double left = 1.0;
            for (String status : documented) {
                if (status.equals(success)) {
                    continue;
                }
                int c = code(status);
                if (c == model.success || model.codes.contains(c)) {
                    continue;
                }
                // a documented error happens rarely, and one in four never happens at all
                double p = random.nextDouble() < 0.25 ? 0 : 0.005 + random.nextDouble() * 0.045;
                if (p > 0) {
                    model.codes.add(c);
                    model.probabilities.add(p);
                    left -= p;
                }
            }
            if (random.nextDouble() < 0.3) {
                int c = firstUndocumented(op, List.of(429, 418, 409, 422));
                double p = random.nextBoolean() ? 0.02 : 0.002;
                if (c > 0) {
                    model.codes.add(c);
                    model.probabilities.add(p);
                    model.undocumented.put(c, p);
                    left -= p;
                }
            }
            if (random.nextDouble() < 0.2) {
                int c = firstUndocumented(op, List.of(503, 502));
                if (c > 0) {
                    model.codes.add(c);
                    model.probabilities.add(0.01);
                    model.undocumented.put(c, 0.01);
                    left -= 0.01;
                }
            }
            model.codes.add(model.success);
            model.probabilities.add(left);
            statuses.put(op, model);

            for (Response r : op.responses().values()) {
                Schema s = r.body().jsonSchema();
                if (s != null) {
                    assignServer(s, seen, 0);
                    if (BodyModel.primary(s) == JsonType.OBJECT && random.nextDouble() < 0.3
                            && server.extras().get(s) == null) {
                        server.extra(s, "x_undoc_" + random.nextInt(100), pickRate());
                    }
                }
            }
        }
    }

    private double pickRate() {
        return List.of(0.5, 0.05, 0.005).get(random.nextInt(3));
    }

    private void assignServer(Schema s, Set<Schema> seen, int depth) {
        if (s == null || depth > BodyModel.MAX_DEPTH || !seen.add(s)) {
            return;
        }
        for (Map.Entry<String, Schema> e : s.properties().entrySet()) {
            boolean required = s.required().contains(e.getKey());
            double u = random.nextDouble();
            double p;
            if (required) {
                p = u < 0.03 ? 0.0 : 1.0; // a required field the server never sends is injected drift
            } else if (u < 0.50) {
                p = 1.0;
            } else if (u < 0.80) {
                p = 0.1 + random.nextDouble() * 0.8;
            } else if (u < 0.95) {
                p = Math.pow(10, -3 + random.nextDouble() * 2); // 0.001 to 0.1, log-uniform
            } else {
                p = 0.0; // documented, never sent
            }
            server.presence(s, e.getKey(), p);
            JsonType type = BodyModel.primary(e.getValue());
            if (p > 0 && type != JsonType.OBJECT && type != JsonType.ARRAY && e.getValue().enumValues() == null
                    && random.nextDouble() < 0.05) {
                server.wrongType(s, e.getKey(), type == JsonType.STRING ? JsonType.INTEGER : JsonType.STRING,
                        pickRate());
            }
            assignServer(e.getValue(), seen, depth + 1);
        }
        assignServer(s.items(), seen, depth + 1);
    }

    private static int firstUndocumented(Operation op, List<Integer> candidates) {
        for (int c : candidates) {
            if (op.responseFor(c) == null) {
                return c;
            }
        }
        return -1;
    }

    private static int code(String status) {
        if (status.matches("[1-5][0-9][0-9]")) {
            return Integer.parseInt(status);
        }
        if (status.matches("[1-5][xX][xX]")) {
            return (status.charAt(0) - '0') * 100;
        }
        return 400; // "default"
    }

    private void buildCohorts() {
        for (int i = 0; i < params.cohorts(); i++) {
            Cohort cohort = new Cohort("cohort-" + (i + 1));
            double readShare = 0.05 + random.nextDouble() * 0.45;
            boolean sendsExtra = random.nextDouble() < 0.2;
            boolean sendsDebug = random.nextDouble() < 0.1;
            Set<Schema> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Operation op : operations) {
                if (random.nextDouble() < 0.5) {
                    cohort.weights.put(op, 0.2 + random.nextDouble() * 2);
                }
            }
            if (cohort.weights.isEmpty()) {
                cohort.weights.put(operations.get(random.nextInt(operations.size())), 1.0);
            }
            for (Operation op : cohort.weights.keySet()) {
                Body body = op.requestBody();
                Schema request = body == null ? null : body.jsonSchema();
                if (request != null) {
                    assignCohort(cohort, request, seen, 0);
                    if (sendsExtra && BodyModel.primary(request) == JsonType.OBJECT
                            && cohort.requests.extras().get(request) == null) {
                        cohort.requests.extra(request, "x_client_extra", 1.0);
                    }
                }
                Map<String, Double> query = new LinkedHashMap<>();
                Map<String, String> fixed = new LinkedHashMap<>();
                for (Parameter p : op.parameters()) {
                    if (!p.in().equals("query")) {
                        continue;
                    }
                    double send = p.required() ? 1.0
                            : random.nextDouble() < 0.4 ? (random.nextDouble() < 0.7 ? 1.0 : 0.05 + random.nextDouble() * 0.85)
                            : 0.0;
                    if (send > 0) {
                        query.put(p.name(), send);
                        List<String> values = p.schema() == null ? null : p.schema().enumValues();
                        if (values != null && !values.isEmpty() && random.nextDouble() < 0.6) {
                            fixed.put(p.name(), plain(values.get(random.nextInt(values.size()))));
                        }
                    }
                }
                if (sendsDebug && random.nextDouble() < 0.5 && op.parameter("query", "x_debug") == null) {
                    query.put("x_debug", 1.0);
                    undocumentedQuery.computeIfAbsent(op.key(), k -> new TreeSet<>()).add("x_debug");
                }
                cohort.query.put(op, query);
                cohort.queryFixed.put(op, fixed);

                Set<String> reads = new TreeSet<>();
                Response success = op.responseFor(statuses.get(op).success);
                Schema response = success == null ? null : success.body().jsonSchema();
                if (response != null) {
                    for (Map.Entry<String, BodyModel.PathTruth> e : server.rates(response).entrySet()) {
                        if (!e.getKey().equals("$") && random.nextDouble() < readShare) {
                            reads.add(e.getKey());
                        }
                    }
                }
                cohort.reads.put(op, reads);
            }
            cohorts.add(cohort);
        }
    }

    private void assignCohort(Cohort cohort, Schema s, Set<Schema> seen, int depth) {
        if (s == null || depth > BodyModel.MAX_DEPTH || !seen.add(s)) {
            return;
        }
        if (BodyModel.primary(s) == JsonType.NUMBER) {
            cohort.requests.fractional(s, random.nextBoolean());
        }
        cohort.requests.nullRate(0);
        for (Map.Entry<String, Schema> e : s.properties().entrySet()) {
            if (e.getValue().readOnly()) {
                cohort.requests.presence(s, e.getKey(), 0);
                continue;
            }
            double p;
            if (s.required().contains(e.getKey())) {
                p = 1.0;
            } else if (random.nextDouble() < 0.5) {
                p = random.nextDouble() < 0.7 ? 1.0 : 0.05 + random.nextDouble() * 0.85;
            } else {
                p = 0.0;
            }
            cohort.requests.presence(s, e.getKey(), p);
            List<String> values = e.getValue().enumValues();
            if (p > 0 && values != null && !values.isEmpty() && BodyModel.primary(e.getValue()) == JsonType.STRING
                    && random.nextDouble() < 0.6) {
                List<String> usable = values.stream().filter(v -> v.startsWith("\"")).toList();
                if (!usable.isEmpty()) {
                    cohort.requests.fixedValue(s, e.getKey(), plain(usable.get(random.nextInt(usable.size()))));
                }
            }
            assignCohort(cohort, e.getValue(), seen, depth + 1);
        }
        assignCohort(cohort, s.items(), seen, depth + 1);
    }

    private static String plain(String jsonText) {
        try {
            return JSON.readTree(jsonText).asText();
        } catch (Exception e) {
            return jsonText;
        }
    }

    private void buildClients() {
        int rare = (int) Math.round(params.clients() * params.rareShare());
        int regular = params.clients() - rare;
        double harmonic = 0;
        for (int i = 1; i <= regular; i++) {
            harmonic += Math.pow(i, -params.zipf());
        }
        for (int i = 1; i <= regular; i++) {
            double rate = params.requestsPerDay() * Math.pow(i, -params.zipf()) / harmonic;
            clients.add(new Client("client-" + i, cohorts.get(random.nextInt(cohorts.size())), rate, false));
        }
        for (int i = 1; i <= rare; i++) {
            // between once in a hundred days and once in five days
            double rate = Math.pow(10, -2 + random.nextDouble() * 1.3);
            clients.add(new Client("rare-" + i, cohorts.get(random.nextInt(cohorts.size())), rate, true));
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Traffic

    /** One request by {@code client} at {@code ts}, through the real sanitiser. */
    public TrafficEvent event(Client client, Instant ts, Random r) {
        Cohort cohort = client.cohort();
        if (undocumentedOperationRate > 0 && r.nextDouble() < undocumentedOperationRate) {
            return sanitiser.sanitise(new Sanitiser.RawExchange(ts, "GET", "/internal/metrics",
                    Map.of(CLIENT_HEADER, client.name()), null, null, 200, null, null, 3.0));
        }
        Operation op = pickOperation(cohort, r);
        return exchange(op, cohort, client.name(), ts, r);
    }

    /** One request to a given operation, for experiments that fix the number of requests per operation. */
    public TrafficEvent eventFor(Operation op, Random r, Instant ts) {
        List<Cohort> users = cohorts.stream().filter(c -> c.weights.containsKey(op)).toList();
        Cohort cohort = users.isEmpty() ? cohorts.get(0) : users.get(r.nextInt(users.size()));
        return exchange(op, cohort, cohort.name, ts, r);
    }

    private TrafficEvent exchange(Operation op, Cohort cohort, String clientName, Instant ts, Random r) {
        StringBuilder url = new StringBuilder(op.path().replaceAll("\\{[^/}]*}", Integer.toString(1 + r.nextInt(5000))));
        char sep = '?';
        for (Map.Entry<String, Double> q : cohort.query.getOrDefault(op, Map.of()).entrySet()) {
            if (q.getValue() >= 1 || r.nextDouble() < q.getValue()) {
                String fixed = cohort.queryFixed.getOrDefault(op, Map.of()).get(q.getKey());
                String value = fixed;
                if (value == null) {
                    Parameter p = op.parameter("query", q.getKey());
                    List<String> values = p == null || p.schema() == null ? null : p.schema().enumValues();
                    value = values == null || values.isEmpty() ? "1" : plain(values.get(r.nextInt(values.size())));
                }
                url.append(sep).append(q.getKey()).append('=').append(value);
                sep = '&';
            }
        }
        String requestBody = null;
        Schema request = op.requestBody() == null ? null : op.requestBody().jsonSchema();
        if (request != null) {
            requestBody = cohort.requests.generate(request, r).toString();
        }
        StatusModel model = statuses.get(op);
        int status = model.success;
        double u = r.nextDouble();
        double acc = 0;
        for (int i = 0; i < model.codes.size(); i++) {
            acc += model.probabilities.get(i);
            if (u < acc) {
                status = model.codes.get(i);
                break;
            }
        }
        String responseBody = null;
        Response declared = op.responseFor(status);
        if (declared != null && declared.body().jsonSchema() != null) {
            JsonNode body = server.generate(declared.body().jsonSchema(), r);
            responseBody = body.toString();
        }
        return sanitiser.sanitise(new Sanitiser.RawExchange(ts, op.method(), url.toString(),
                Map.of(CLIENT_HEADER, clientName), requestBody == null ? null : "application/json", requestBody,
                status, responseBody == null ? null : "application/json", responseBody,
                5 + r.nextDouble() * 200));
    }

    private Operation pickOperation(Cohort cohort, Random r) {
        double total = 0;
        for (double w : cohort.weights.values()) {
            total += w;
        }
        double u = r.nextDouble() * total;
        Operation last = null;
        for (Map.Entry<Operation, Double> e : cohort.weights.entrySet()) {
            last = e.getKey();
            u -= e.getValue();
            if (u <= 0) {
                return last;
            }
        }
        return last;
    }

    /** All events of the {@code days} ending at {@code end}: Poisson counts per client, uniform times. */
    public List<TrafficEvent> traffic(Instant end, int days, long trafficSeed) {
        Random r = new Random(trafficSeed);
        List<TrafficEvent> out = new ArrayList<>();
        long span = days * 86_400L;
        for (Client client : clients) {
            int n = poisson(client.ratePerDay() * days, r);
            for (int i = 0; i < n; i++) {
                Instant ts = end.minusSeconds((long) (r.nextDouble() * span));
                out.add(event(client, ts, r));
            }
        }
        return out;
    }

    private static int poisson(double mean, Random r) {
        if (mean > 50) {
            return (int) Math.max(0, Math.round(mean + Math.sqrt(mean) * r.nextGaussian()));
        }
        double limit = Math.exp(-mean);
        double p = 1;
        int k = 0;
        do {
            k++;
            p *= r.nextDouble();
        } while (p > limit);
        return k - 1;
    }

    // ------------------------------------------------------------------------------------------------------
    // Ground truth

    private static final long SCALE = 1_000_000L;

    /**
     * What every client would do given unlimited time, from the profiles. Counts are probabilities scaled to
     * a million: a field with {@code sent < bodies} is one the client sometimes omits.
     */
    public Truth truth() {
        Truth truth = new Truth();
        for (Client client : clients) {
            Cohort cohort = client.cohort();
            double total = cohort.weights.values().stream().mapToDouble(Double::doubleValue).sum();
            for (Map.Entry<Operation, Double> w : cohort.weights.entrySet()) {
                Operation op = w.getKey();
                Truth.OpTruth t = truth.op(client.name(), op.key());
                t.calls = SCALE;
                t.ratePerDay = client.ratePerDay() * w.getValue() / total;
                for (Map.Entry<String, Double> q : cohort.query.getOrDefault(op, Map.of()).entrySet()) {
                    t.sent.put("?" + q.getKey(), Math.round(q.getValue() * SCALE));
                    Map<String, Long> values = t.values.computeIfAbsent("?" + q.getKey(), k -> new TreeMap<>());
                    String fixed = cohort.queryFixed.getOrDefault(op, Map.of()).get(q.getKey());
                    Parameter p = op.parameter("query", q.getKey());
                    List<String> all = p == null || p.schema() == null ? null : p.schema().enumValues();
                    if (fixed != null) {
                        values.put(fixed, 1L);
                    } else if (all != null) {
                        all.forEach(v -> values.put(plain(v), 1L));
                    }
                }
                Schema request = op.requestBody() == null ? null : op.requestBody().jsonSchema();
                if (request != null) {
                    t.bodies = SCALE;
                    for (Map.Entry<String, BodyModel.PathTruth> e : cohort.requests.rates(request).entrySet()) {
                        long count = Math.round(e.getValue().rate() * SCALE);
                        if (count <= 0) {
                            continue;
                        }
                        t.sent.put(e.getKey(), count);
                        Map<String, Long> types = t.types.computeIfAbsent(e.getKey(), k -> new TreeMap<>());
                        e.getValue().types().forEach((type, p) -> {
                            if (p > 0) {
                                types.put(type, 1L);
                            }
                        });
                    }
                    enumValues(cohort.requests, request, "$", 0, t);
                }
                Set<String> reads = cohort.reads.get(op);
                if (reads != null && !reads.isEmpty()) {
                    Response success = op.responseFor(statuses.get(op).success);
                    t.reads.put(success.status(), new TreeSet<>(reads));
                }
            }
        }
        return truth;
    }

    /** Which enum values a cohort can send at each enum field of a request schema. */
    private void enumValues(BodyModel model, Schema s, String path, int depth, Truth.OpTruth t) {
        if (s == null || depth > BodyModel.MAX_DEPTH || !t.sent.containsKey(path)) {
            return;
        }
        List<String> values = s.enumValues();
        if (values != null && BodyModel.primary(s) == JsonType.STRING) {
            Map<String, Long> out = t.values.computeIfAbsent(path, k -> new TreeMap<>());
            if (out.isEmpty()) {
                values.stream().filter(v -> v.startsWith("\"")).forEach(v -> out.put(plain(v), 1L));
            }
        }
        for (Map.Entry<String, Schema> e : s.properties().entrySet()) {
            String child = io.github.shrishaanth.axon.util.FieldPath.child(path, e.getKey());
            String fixed = model.fixedValueOf(s, e.getKey());
            if (fixed != null && t.sent.containsKey(child)) {
                Map<String, Long> out = t.values.computeIfAbsent(child, k -> new TreeMap<>());
                out.clear();
                out.put(fixed, 1L);
                continue;
            }
            enumValues(model, e.getValue(), child, depth + 1, t);
        }
        if (s.items() != null) {
            enumValues(model, s.items(), io.github.shrishaanth.axon.util.FieldPath.items(path), depth + 1, t);
        }
    }
}
