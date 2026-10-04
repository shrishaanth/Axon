package io.github.shrishaanth.axon.eval.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.drift.DriftEngine;
import io.github.shrishaanth.axon.drift.DriftFinding;
import io.github.shrishaanth.axon.eval.Stats;
import io.github.shrishaanth.axon.impact.ImpactConfig;
import io.github.shrishaanth.axon.observe.Aggregate;
import io.github.shrishaanth.axon.observe.Aggregate.FieldKey;
import io.github.shrishaanth.axon.observe.Aggregate.FieldStat;
import io.github.shrishaanth.axon.observe.Aggregate.OperationStats;
import io.github.shrishaanth.axon.spec.JsonType;
import io.github.shrishaanth.axon.spec.Operation;
import io.github.shrishaanth.axon.spec.Parameter;
import io.github.shrishaanth.axon.spec.Response;
import io.github.shrishaanth.axon.spec.Schema;
import io.github.shrishaanth.axon.util.FieldPath;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * E2: how much traffic the observed contract needs, and whether injected drift is found.
 *
 * <p>Usage: {@code e2 <demo spec> <public spec dir> <out dir> --seeds 1-20 --label dev [--max 100000] [--ops 6]}
 *
 * <p>For each world and each of a few operations, requests are generated one operation at a time and the
 * aggregate is scored at 100, 1,000, 10,000 and 100,000 requests against the generator's analytic truth.
 */
public final class E2Experiment {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int[] CHECKPOINTS = {100, 1_000, 10_000, 100_000};
    private static final String[] STRATA = {"p<0.01", "0.01<=p<0.1", "p>=0.1"};
    private static final String[] DRIFT_KINDS = {DriftFinding.UNDOCUMENTED_FIELD, DriftFinding.TYPE_MISMATCH,
            DriftFinding.UNDOCUMENTED_STATUS, DriftFinding.UNUSED_FIELD};

    private E2Experiment() {
    }

    /** What is true about one part (a request body or a success response body) of one operation. */
    private record PartTruth(Map<String, Double> rates, Map<String, Set<String>> types, Set<String> undocumented,
                             Set<String> mismatched, Set<String> neverSent) {
    }

    /** Counters for one checkpoint, summed over operations and worlds. */
    private static final class Cell {
        final long[] found = new long[3];
        final long[] total = new long[3];
        final double[] predicted = new double[3];
        long observed;
        long observedTrue;
        long typesRight;
        long typesTotal;
        long statusFound;
        long statusTotal;
        final Map<String, long[]> drift = new TreeMap<>(); // kind -> {tp, fn, fp}
        final long[] unusedNoThreshold = new long[3];
        long serverErrorFindings;
        long serverErrorWarningsIfDrift;
        long statements;
        long covered;
        long statementsNonZero;
        long coveredNonZero;
        long eligibleNonZero;
        final List<Double> recallPerSeed = new ArrayList<>();
        final List<Double> rareRecallPerSeed = new ArrayList<>();

        Cell() {
            for (String k : DRIFT_KINDS) {
                drift.put(k, new long[3]);
            }
        }

        void add(Cell o) {
            for (int i = 0; i < 3; i++) {
                found[i] += o.found[i];
                total[i] += o.total[i];
                predicted[i] += o.predicted[i];
                unusedNoThreshold[i] += o.unusedNoThreshold[i];
            }
            observed += o.observed;
            observedTrue += o.observedTrue;
            typesRight += o.typesRight;
            typesTotal += o.typesTotal;
            statusFound += o.statusFound;
            statusTotal += o.statusTotal;
            o.drift.forEach((k, v) -> {
                long[] mine = drift.get(k);
                for (int i = 0; i < 3; i++) {
                    mine[i] += v[i];
                }
            });
            serverErrorFindings += o.serverErrorFindings;
            serverErrorWarningsIfDrift += o.serverErrorWarningsIfDrift;
            statements += o.statements;
            covered += o.covered;
            statementsNonZero += o.statementsNonZero;
            coveredNonZero += o.coveredNonZero;
            eligibleNonZero += o.eligibleNonZero;
        }

        double recall() {
            long f = found[0] + found[1] + found[2];
            long t = total[0] + total[1] + total[2];
            return t == 0 ? Double.NaN : f / (double) t;
        }
    }

    private record SeedResult(long seed, String spec, int operations, Cell[] cells, long selfCheckPaths,
                              long selfCheckViolations, double selfCheckMaxError) {
    }

    public static void main(String[] args) throws Exception {
        List<SimSpecs.Entry> specs = SimSpecs.load(Path.of(args[0]), Path.of(args[1]));
        Path outDir = Path.of(args[2]);
        int from = 1;
        int to = 20;
        String label = "dev";
        int max = 100_000;
        int opsPerWorld = 6;
        for (int i = 3; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--seeds" -> {
                    String[] range = args[i + 1].split("-");
                    from = Integer.parseInt(range[0]);
                    to = Integer.parseInt(range[range.length - 1]);
                }
                case "--label" -> label = args[i + 1];
                case "--max" -> max = Integer.parseInt(args[i + 1]);
                case "--ops" -> opsPerWorld = Integer.parseInt(args[i + 1]);
                default -> {
                }
            }
        }
        Files.createDirectories(outDir);
        final int maxN = max;
        final int ops = opsPerWorld;
        List<SeedResult> results = IntStream.rangeClosed(from, to).parallel()
                .mapToObj(seed -> {
                    SeedResult r = runSeed(specs.get(seed % specs.size()), seed, maxN, ops);
                    System.err.println("seed " + seed + " done (" + r.spec() + ", " + r.operations() + " operations)");
                    return r;
                })
                .sorted((a, b) -> Long.compare(a.seed(), b.seed()))
                .collect(Collectors.toList());

        ObjectNode summary = JSON.createObjectNode();
        summary.put("label", label);
        summary.put("seeds", from + "-" + to);
        summary.put("operations_per_world", opsPerWorld);
        summary.set("specs", JSON.valueToTree(specs.stream().map(SimSpecs.Entry::name).toList()));
        summary.put("worlds", results.size());
        summary.put("operation_runs", results.stream().mapToInt(SeedResult::operations).sum());
        ObjectNode self = summary.putObject("generator_self_check");
        self.put("paths_checked", results.stream().mapToLong(SeedResult::selfCheckPaths).sum());
        self.put("paths_off_by_more_than_5_standard_errors",
                results.stream().mapToLong(SeedResult::selfCheckViolations).sum());
        self.put("largest_absolute_error",
                Stats.round(results.stream().mapToDouble(SeedResult::selfCheckMaxError).max().orElse(0)));

        ArrayNode curve = summary.putArray("by_requests");
        for (int c = 0; c < CHECKPOINTS.length; c++) {
            if (CHECKPOINTS[c] > max) {
                break;
            }
            Cell sum = new Cell();
            for (SeedResult r : results) {
                sum.add(r.cells()[c]);
                sum.recallPerSeed.add(r.cells()[c].recall());
                Cell cell = r.cells()[c];
                sum.rareRecallPerSeed.add(cell.total[0] == 0 ? Double.NaN : cell.found[0] / (double) cell.total[0]);
            }
            curve.add(describe(CHECKPOINTS[c], sum));
        }
        String text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
        Files.writeString(outDir.resolve("e2-" + label + ".json"), text + "\n", StandardCharsets.UTF_8);
        System.out.println(text);
    }

    private static ObjectNode describe(int n, Cell c) {
        ObjectNode o = JSON.createObjectNode();
        o.put("requests_per_operation", n);
        ObjectNode fields = o.putObject("field_recall");
        Stats.put(fields, "all", c.recall());
        fields.set("across_worlds", Stats.summary(c.recallPerSeed));
        ObjectNode strata = fields.putObject("by_true_presence_rate");
        for (int i = 0; i < 3; i++) {
            ObjectNode s = strata.putObject(STRATA[i]);
            s.put("fields", c.total[i]);
            Stats.put(s, "recall", c.total[i] == 0 ? Double.NaN : c.found[i] / (double) c.total[i]);
            Stats.put(s, "predicted_recall", c.total[i] == 0 ? Double.NaN : c.predicted[i] / c.total[i]);
        }
        fields.set("rare_fields_across_worlds", Stats.summary(c.rareRecallPerSeed));
        Stats.put(o, "field_precision", c.observed == 0 ? Double.NaN : c.observedTrue / (double) c.observed);
        Stats.put(o, "type_accuracy", c.typesTotal == 0 ? Double.NaN : c.typesRight / (double) c.typesTotal);
        Stats.put(o, "status_code_recall", c.statusTotal == 0 ? Double.NaN : c.statusFound / (double) c.statusTotal);
        ObjectNode drift = o.putObject("drift_detection");
        c.drift.forEach((kind, v) -> drift.set(kind, prf(v)));
        drift.set("unused_field_without_minimum_requests", prf(c.unusedNoThreshold));
        ObjectNode fiveXx = o.putObject("undocumented_5xx");
        fiveXx.put("findings", c.serverErrorFindings);
        fiveXx.put("warnings_under_default_rule", 0);
        fiveXx.put("warnings_if_server_errors_were_drift", c.serverErrorWarningsIfDrift);
        ObjectNode cal = o.putObject("unseen_bound_calibration");
        cal.put("statements", c.statements);
        Stats.put(cal, "coverage", c.statements == 0 ? Double.NaN : c.covered / (double) c.statements);
        cal.put("statements_about_fields_that_do_occur", c.statementsNonZero);
        Stats.put(cal, "coverage_among_those",
                c.statementsNonZero == 0 ? Double.NaN : c.coveredNonZero / (double) c.statementsNonZero);
        // The guarantee itself: a field that does occur should end up "unseen with a bound below its true rate"
        // at most 5% of the time. Denominator: occurring fields whose parent was seen often enough to qualify.
        cal.put("occurring_fields_that_could_have_been_called_unused", c.eligibleNonZero);
        cal.put("wrong_statements", c.statementsNonZero - c.coveredNonZero);
        Stats.put(cal, "wrong_statement_rate", c.eligibleNonZero == 0 ? Double.NaN
                : (c.statementsNonZero - c.coveredNonZero) / (double) c.eligibleNonZero);
        return o;
    }

    private static ObjectNode prf(long[] v) {
        ObjectNode o = JSON.createObjectNode();
        o.put("injected_found", v[0]);
        o.put("injected_missed", v[1]);
        o.put("false_positives", v[2]);
        Stats.put(o, "detection_rate", v[0] + v[1] == 0 ? Double.NaN : v[0] / (double) (v[0] + v[1]));
        return o;
    }

    // ------------------------------------------------------------------------------------------------------

    private static SeedResult runSeed(SimSpecs.Entry entry, int seed, int max, int opsPerWorld) {
        World world = new World(entry.spec(), seed, false);
        List<Operation> candidates = new ArrayList<>();
        for (Operation op : world.operations) {
            Schema response = successSchema(world, op);
            if (response != null && world.server.rates(response).size() >= 4) {
                candidates.add(op);
            }
        }
        candidates.sort((a, b) -> Boolean.compare(requestSchema(b) != null, requestSchema(a) != null));
        List<Operation> chosen = candidates.subList(0, Math.min(opsPerWorld, candidates.size()));

        Cell[] cells = new Cell[CHECKPOINTS.length];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = new Cell();
        }
        long paths = 0;
        long violations = 0;
        double maxError = 0;
        ImpactConfig config = ImpactConfig.defaults();
        ImpactConfig noThreshold = new ImpactConfig(config.identityMode(), config.identityHeader(),
                config.recentDays(), config.staleDays(), config.highMinClients(), config.highShare(),
                config.criticalShare(), config.criticalClients(), "drift", 1);
        Instant ts = Instant.parse("2026-09-30T00:00:00Z");
        int index = 0;
        for (Operation op : chosen) {
            Random r = new Random(seed * 1_000_003L + (index++));
            Aggregate aggregate = new Aggregate(world.spec, null, null);
            PartTruth response = responseTruth(world, op);
            PartTruth request = requestTruth(world, op);
            int done = 0;
            for (int c = 0; c < CHECKPOINTS.length && CHECKPOINTS[c] <= max; c++) {
                while (done < CHECKPOINTS[c]) {
                    aggregate.add(world.eventFor(op, r, ts));
                    done++;
                }
                score(world, op, aggregate, response, request, config, noThreshold, cells[c]);
            }
            // self-check of the generator: empirical presence against the analytic rate
            OperationStats s = aggregate.operations().get(op.key());
            int success = world.statuses.get(op).success;
            long bodies = s.withResponseBody.getOrDefault(success, 0L);
            for (Map.Entry<String, Double> e : response.rates().entrySet()) {
                FieldStat f = s.fields.get(new FieldKey(Aggregate.RESPONSE_BODY, success, e.getKey()));
                double empirical = bodies == 0 ? 0 : (f == null ? 0 : f.present.count()) / (double) bodies;
                double p = e.getValue();
                double error = Math.abs(empirical - p);
                paths++;
                maxError = Math.max(maxError, error);
                if (bodies > 0 && error > 5 * Math.sqrt(p * (1 - p) / bodies) + 1e-6) {
                    violations++;
                }
            }
        }
        return new SeedResult(seed, entry.name(), chosen.size(), cells, paths, violations, maxError);
    }

    private static Schema successSchema(World world, Operation op) {
        Response r = op.responseFor(world.statuses.get(op).success);
        return r == null ? null : r.body().jsonSchema();
    }

    private static Schema requestSchema(Operation op) {
        return op.requestBody() == null ? null : op.requestBody().jsonSchema();
    }

    private static PartTruth responseTruth(World world, Operation op) {
        Schema schema = successSchema(world, op);
        return truth(schema, List.of(world.server), true);
    }

    private static PartTruth requestTruth(World world, Operation op) {
        Schema schema = requestSchema(op);
        if (schema == null) {
            return null;
        }
        List<BodyModel> models = new ArrayList<>();
        for (World.Cohort c : world.cohorts) {
            if (c.weights.containsKey(op)) {
                models.add(c.requests);
            }
        }
        if (models.isEmpty()) {
            models.add(world.cohorts.get(0).requests);
        }
        return truth(schema, models, false);
    }

    /** Truth for a body filled by one of {@code models}, chosen uniformly per request. */
    private static PartTruth truth(Schema schema, List<BodyModel> models, boolean response) {
        Map<String, Double> rates = new TreeMap<>();
        Map<String, Set<String>> types = new TreeMap<>();
        Set<String> undocumented = new TreeSet<>();
        for (BodyModel model : models) {
            for (Map.Entry<String, BodyModel.PathTruth> e : model.rates(schema).entrySet()) {
                if (e.getValue().rate() <= 0) {
                    continue;
                }
                rates.merge(e.getKey(), e.getValue().rate() / models.size(), Double::sum);
                Set<String> t = types.computeIfAbsent(e.getKey(), k -> new TreeSet<>());
                e.getValue().types().forEach((type, p) -> {
                    if (p > 0) {
                        t.add(type);
                    }
                });
                if (!e.getValue().documented()) {
                    undocumented.add(e.getKey());
                }
            }
        }
        Set<String> mismatched = new TreeSet<>();
        Set<String> neverSent = new TreeSet<>();
        for (Map.Entry<String, Set<String>> e : types.entrySet()) {
            if (undocumented.contains(e.getKey())) {
                continue;
            }
            Schema declared = BodyModel.resolve(schema, e.getKey());
            if (declared == null) {
                continue;
            }
            Set<JsonType> accepted = EnumSet.noneOf(JsonType.class);
            accepted.addAll(declared.types());
            Set<JsonType> concrete = EnumSet.copyOf(accepted.isEmpty() ? EnumSet.noneOf(JsonType.class) : accepted);
            concrete.remove(JsonType.NULL);
            if (!concrete.isEmpty()) {
                if (accepted.contains(JsonType.NUMBER)) {
                    accepted.add(JsonType.INTEGER);
                }
                for (String type : e.getValue()) {
                    if (!accepted.contains(JsonType.fromWire(type))) {
                        mismatched.add(e.getKey());
                    }
                }
            }
            if (e.getValue().contains("object")) {
                for (Map.Entry<String, Schema> p : declared.properties().entrySet()) {
                    boolean visible = response ? !p.getValue().writeOnly() : !p.getValue().readOnly();
                    String child = FieldPath.child(e.getKey(), p.getKey());
                    if (visible && !rates.containsKey(child)) {
                        neverSent.add(child);
                    }
                }
            }
        }
        return new PartTruth(rates, types, undocumented, mismatched, neverSent);
    }

    private static int stratum(double p) {
        return p < 0.01 ? 0 : p < 0.1 ? 1 : 2;
    }

    private static void score(World world, Operation op, Aggregate aggregate, PartTruth response, PartTruth request,
                              ImpactConfig config, ImpactConfig noThreshold, Cell cell) {
        OperationStats s = aggregate.operations().get(op.key());
        World.StatusModel statusModel = world.statuses.get(op);
        int success = statusModel.success;
        long responseBodies = s.withResponseBody.getOrDefault(success, 0L);
        long requestBodies = s.withRequestBody.values().stream().mapToLong(Aggregate.Stat::count).sum();

        Map<String, FieldStat> seenResponse = new TreeMap<>();
        Map<String, FieldStat> seenRequest = new TreeMap<>();
        for (Map.Entry<FieldKey, FieldStat> e : s.fields.entrySet()) {
            if (e.getKey().part().equals(Aggregate.RESPONSE_BODY) && e.getKey().status() == success) {
                seenResponse.put(e.getKey().path(), e.getValue());
            } else if (e.getKey().part().equals(Aggregate.REQUEST_BODY)) {
                seenRequest.put(e.getKey().path(), e.getValue());
            }
        }
        scorePart(response, seenResponse, responseBodies, cell);
        if (request != null) {
            scorePart(request, seenRequest, requestBodies, cell);
        }

        for (Map.Entry<Integer, Double> e : statusModel.all().entrySet()) {
            if (e.getValue() > 0) {
                cell.statusTotal++;
                if (s.statuses.containsKey(e.getKey())) {
                    cell.statusFound++;
                }
            }
        }

        // drift: what should be found for this operation
        String responseLocation = "response." + success + ".body";
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        for (String k : DRIFT_KINDS) {
            expected.put(k, new TreeSet<>());
        }
        response.undocumented().forEach(p -> expected.get(DriftFinding.UNDOCUMENTED_FIELD).add(responseLocation + "|" + p));
        response.mismatched().forEach(p -> expected.get(DriftFinding.TYPE_MISMATCH).add(responseLocation + "|" + p));
        response.neverSent().forEach(p -> expected.get(DriftFinding.UNUSED_FIELD).add(responseLocation + "|" + p));
        if (request != null) {
            request.undocumented().forEach(p -> expected.get(DriftFinding.UNDOCUMENTED_FIELD).add("request.body|" + p));
            request.mismatched().forEach(p -> expected.get(DriftFinding.TYPE_MISMATCH).add("request.body|" + p));
            request.neverSent().forEach(p -> expected.get(DriftFinding.UNUSED_FIELD).add("request.body|" + p));
        }
        Set<String> querySent = new HashSet<>();
        for (World.Cohort c : world.cohorts) {
            if (c.weights.containsKey(op)) {
                querySent.addAll(c.query.getOrDefault(op, Map.of()).keySet());
            }
        }
        for (String name : querySent) {
            if (op.parameter("query", name) == null) {
                expected.get(DriftFinding.UNDOCUMENTED_FIELD).add("request.query|" + FieldPath.child("$", name));
            }
        }
        for (Parameter p : op.parameters()) {
            if (p.in().equals("query") && !querySent.contains(p.name())) {
                expected.get(DriftFinding.UNUSED_FIELD).add("request.query|" + FieldPath.child("$", p.name()));
            }
        }
        statusModel.undocumented.keySet()
                .forEach(c -> expected.get(DriftFinding.UNDOCUMENTED_STATUS).add("response.status|" + c));

        Map<String, Set<String>> reported = reported(DriftEngine.analyse(aggregate, config), op, responseLocation);
        for (String kind : DRIFT_KINDS) {
            tally(expected.get(kind), reported.get(kind), cell.drift.get(kind));
        }
        List<DriftFinding> loose = DriftEngine.analyse(aggregate, noThreshold);
        tally(expected.get(DriftFinding.UNUSED_FIELD), reported(loose, op, responseLocation).get(DriftFinding.UNUSED_FIELD),
                cell.unusedNoThreshold);
        for (DriftFinding d : loose) {
            if (d.kind().equals(DriftFinding.UNDOCUMENTED_STATUS) && d.status() != null && d.status().startsWith("5")) {
                cell.serverErrorFindings++;
                if (d.severity().equals("warning")) {
                    cell.serverErrorWarningsIfDrift++;
                }
            }
        }

        for (int part = 0; part < 2; part++) {
            PartTruth truth = part == 0 ? response : request;
            Map<String, FieldStat> seen = part == 0 ? seenResponse : seenRequest;
            if (truth == null) {
                continue;
            }
            for (String path : truth.rates().keySet()) {
                String parent = FieldPath.parent(path);
                FieldStat parentStat = parent == null ? null : seen.get(parent);
                Long asObject = parentStat == null ? null : parentStat.types.get(JsonType.OBJECT);
                if (asObject != null && asObject >= config.unusedMinRequests() && !truth.undocumented().contains(path)
                        && !path.endsWith("[]")) {
                    cell.eligibleNonZero++;
                }
            }
        }

        // calibration of "never seen, so its rate is below u with 95% confidence"
        for (DriftFinding d : DriftEngine.analyse(aggregate, config)) {
            if (!d.kind().equals(DriftFinding.UNUSED_FIELD) || d.unseenRateUpper95() == null
                    || !op.key().equals(d.operationKey())) {
                continue;
            }
            PartTruth part;
            if (responseLocation.equals(d.location())) {
                part = response;
            } else if ("request.body".equals(d.location())) {
                part = request;
            } else {
                continue;
            }
            if (part == null) {
                continue;
            }
            String parent = FieldPath.parent(d.field());
            double parentRate = parent == null ? 0 : part.rates().getOrDefault(parent, 0.0);
            double childRate = part.rates().getOrDefault(d.field(), 0.0);
            double conditional = parentRate == 0 ? 0 : Math.min(1.0, childRate / parentRate);
            cell.statements++;
            boolean covered = conditional <= d.unseenRateUpper95();
            if (covered) {
                cell.covered++;
            }
            if (conditional > 0) {
                cell.statementsNonZero++;
                if (covered) {
                    cell.coveredNonZero++;
                }
            }
        }
    }

    private static void scorePart(PartTruth truth, Map<String, FieldStat> seen, long bodies, Cell cell) {
        for (Map.Entry<String, Double> e : truth.rates().entrySet()) {
            int stratum = stratum(e.getValue());
            cell.total[stratum]++;
            cell.predicted[stratum] += 1 - Math.pow(1 - e.getValue(), bodies);
            FieldStat f = seen.get(e.getKey());
            if (f != null) {
                cell.found[stratum]++;
                cell.typesTotal++;
                Set<String> inferred = new TreeSet<>();
                f.types.keySet().forEach(t -> inferred.add(t.wire()));
                if (inferred.equals(truth.types().get(e.getKey()))) {
                    cell.typesRight++;
                }
            }
        }
        for (String path : seen.keySet()) {
            cell.observed++;
            if (truth.rates().containsKey(path)) {
                cell.observedTrue++;
            }
        }
    }

    private static Map<String, Set<String>> reported(List<DriftFinding> findings, Operation op, String responseLocation) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String k : DRIFT_KINDS) {
            out.put(k, new TreeSet<>());
        }
        for (DriftFinding d : findings) {
            if (!op.key().equals(d.operationKey()) || !out.containsKey(d.kind())) {
                continue;
            }
            if (d.kind().equals(DriftFinding.UNDOCUMENTED_STATUS)) {
                out.get(d.kind()).add("response.status|" + d.status());
            } else if (d.location().equals(responseLocation) || d.location().startsWith("request.")) {
                out.get(d.kind()).add(d.location() + "|" + d.field());
            }
        }
        return out;
    }

    private static void tally(Set<String> expected, Set<String> reported, long[] counts) {
        for (String e : expected) {
            if (reported.contains(e)) {
                counts[0]++;
            } else {
                counts[1]++;
            }
        }
        for (String r : reported) {
            if (!expected.contains(r)) {
                counts[2]++;
            }
        }
    }
}
