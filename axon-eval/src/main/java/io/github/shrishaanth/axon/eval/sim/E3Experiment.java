package io.github.shrishaanth.axon.eval.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.diff.SpecDiff;
import io.github.shrishaanth.axon.drift.DriftEngine;
import io.github.shrishaanth.axon.drift.DriftFinding;
import io.github.shrishaanth.axon.eval.Mutations;
import io.github.shrishaanth.axon.eval.Stats;
import io.github.shrishaanth.axon.eval.Truth;
import io.github.shrishaanth.axon.eval.TruthOracle;
import io.github.shrishaanth.axon.impact.ImpactConfig;
import io.github.shrishaanth.axon.impact.ImpactEngine;
import io.github.shrishaanth.axon.impact.ImpactReport;
import io.github.shrishaanth.axon.impact.ImpactReport.Evidence;
import io.github.shrishaanth.axon.impact.ImpactReport.Row;
import io.github.shrishaanth.axon.impact.Severity;
import io.github.shrishaanth.axon.observe.Aggregate;
import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.traffic.Sanitiser;
import io.github.shrishaanth.axon.traffic.TrafficEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * E3: is usage-aware ranking closer to the truth than ranking without traffic?
 *
 * <p>Usage: {@code e3 <demo spec> <public spec dir> <out dir> --seeds 1-20 --label dev [--tail heavy]}
 *
 * <p>Per world: 30 days of traffic, a candidate spec made by about twenty random breaking edits, and the hidden
 * profile truth of who each edit affects. Axon is shown the last 1, 7 or 30 days.
 */
public final class E3Experiment {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int[] WINDOWS = {1, 7, 30};
    private static final Instant END = Instant.parse("2026-09-30T00:00:00Z");
    private static final int MUTATIONS = 20;
    private static final int RANDOM_SHUFFLES = 1000;

    private E3Experiment() {
    }

    private static final class Out {
        // observed rows
        long estimated;
        long truth;
        long both;
        final List<Double> shareErrors = new ArrayList<>();
        // potential rows
        long listed;
        long listedReaders;
        long readers;
        // ranking, one value per world
        final Map<String, List<Double>> tau = new TreeMap<>();
        final Map<String, List<Double>> top5 = new TreeMap<>();
        // severity: predicted -> truth -> count, observed and potential apart
        final Map<String, Map<String, Integer>> severityObserved = new TreeMap<>();
        final Map<String, Map<String, Integer>> severityPotential = new TreeMap<>();
        int worlds;
        long rows;
        long rowsWithTruth;
        int undocumentedOperationInjected;
        int undocumentedOperationFound;
        final List<Double> clientsSeenShare = new ArrayList<>();

        Out() {
            for (String r : List.of("axon", "axon_severity_first", "client_count", "spec_only", "volume_only",
                    "random")) {
                tau.put(r, new ArrayList<>());
                top5.put(r, new ArrayList<>());
            }
        }
    }

    public static void main(String[] args) throws Exception {
        List<SimSpecs.Entry> specs = SimSpecs.load(Path.of(args[0]), Path.of(args[1]));
        Path outDir = Path.of(args[2]);
        int from = 1;
        int to = 20;
        String label = "dev";
        boolean heavy = false;
        for (int i = 3; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--seeds" -> {
                    String[] range = args[i + 1].split("-");
                    from = Integer.parseInt(range[0]);
                    to = Integer.parseInt(range[range.length - 1]);
                }
                case "--label" -> label = args[i + 1];
                case "--tail" -> heavy = args[i + 1].equals("heavy");
                default -> {
                }
            }
        }
        Files.createDirectories(outDir);
        final boolean heavyTail = heavy;
        List<Map<Integer, Out>> perSeed = IntStream.rangeClosed(from, to).parallel()
                .mapToObj(seed -> {
                    try {
                        return runSeed(specs.get(seed % specs.size()), seed, heavyTail);
                    } catch (Exception e) {
                        throw new IllegalStateException("seed " + seed + ": " + e, e);
                    }
                })
                .collect(Collectors.toList());

        ObjectNode summary = JSON.createObjectNode();
        summary.put("label", label);
        summary.put("seeds", from + "-" + to);
        summary.put("tail", heavy ? "heavy (held out)" : "standard");
        summary.set("specs", JSON.valueToTree(specs.stream().map(SimSpecs.Entry::name).toList()));
        summary.put("traffic_days", 30);
        summary.put("edits_attempted_per_world", MUTATIONS);
        ArrayNode windows = summary.putArray("by_window_days");
        for (int w : WINDOWS) {
            Out total = new Out();
            for (Map<Integer, Out> m : perSeed) {
                merge(total, m.get(w));
            }
            windows.add(describe(w, total));
        }
        String text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
        Files.writeString(outDir.resolve("e3-" + label + ".json"), text + "\n", StandardCharsets.UTF_8);
        System.out.println(text);
    }

    private static void merge(Out total, Out o) {
        total.estimated += o.estimated;
        total.truth += o.truth;
        total.both += o.both;
        total.shareErrors.addAll(o.shareErrors);
        total.listed += o.listed;
        total.listedReaders += o.listedReaders;
        total.readers += o.readers;
        o.tau.forEach((k, v) -> total.tau.get(k).addAll(v));
        o.top5.forEach((k, v) -> total.top5.get(k).addAll(v));
        mergeMatrix(total.severityObserved, o.severityObserved);
        mergeMatrix(total.severityPotential, o.severityPotential);
        total.worlds += o.worlds;
        total.rows += o.rows;
        total.rowsWithTruth += o.rowsWithTruth;
        total.undocumentedOperationInjected += o.undocumentedOperationInjected;
        total.undocumentedOperationFound += o.undocumentedOperationFound;
        total.clientsSeenShare.addAll(o.clientsSeenShare);
    }

    private static void mergeMatrix(Map<String, Map<String, Integer>> into, Map<String, Map<String, Integer>> from) {
        from.forEach((predicted, row) -> row.forEach((truth, n) ->
                into.computeIfAbsent(predicted, p -> new TreeMap<>()).merge(truth, n, Integer::sum)));
    }

    private static ObjectNode describe(int window, Out o) {
        ObjectNode n = JSON.createObjectNode();
        n.put("window_days", window);
        n.put("worlds", o.worlds);
        n.put("breaking_rows", o.rows);
        n.put("rows_with_defined_truth", o.rowsWithTruth);
        n.set("share_of_population_seen_in_window", Stats.summary(o.clientsSeenShare));
        ObjectNode observed = n.putObject("observed_rows");
        Stats.put(observed, "client_set_precision", o.estimated == 0 ? Double.NaN : o.both / (double) o.estimated);
        Stats.put(observed, "client_set_recall", o.truth == 0 ? Double.NaN : o.both / (double) o.truth);
        ObjectNode share = observed.putObject("affected_share_absolute_error");
        Stats.put(share, "mean", Stats.mean(o.shareErrors));
        Stats.put(share, "p90", Stats.quantile(o.shareErrors, 0.9));
        share.put("rows", o.shareErrors.size());
        ObjectNode potential = n.putObject("potential_rows");
        potential.put("clients_listed", o.listed);
        potential.put("of_which_truly_read_the_field", o.listedReaders);
        Stats.put(potential, "reader_share_of_listed", o.listed == 0 ? Double.NaN : o.listedReaders / (double) o.listed);
        Stats.put(potential, "readers_found", o.readers == 0 ? Double.NaN : o.listedReaders / (double) o.readers);
        ObjectNode ranking = n.putObject("ranking");
        for (String r : o.tau.keySet()) {
            ObjectNode rn = ranking.putObject(r);
            rn.set("kendall_tau", Stats.summary(o.tau.get(r)));
            rn.set("top5_precision", Stats.summary(o.top5.get(r)));
        }
        n.set("severity_observed_predicted_vs_truth", JSON.valueToTree(o.severityObserved));
        n.set("severity_potential_predicted_vs_truth", JSON.valueToTree(o.severityPotential));
        Stats.put(n, "severity_agreement_observed", agreement(o.severityObserved));
        Stats.put(n, "severity_agreement_potential", agreement(o.severityPotential));
        ObjectNode undocumented = n.putObject("undocumented_operation");
        undocumented.put("worlds_where_injected", o.undocumentedOperationInjected);
        undocumented.put("found", o.undocumentedOperationFound);
        return n;
    }

    private static double agreement(Map<String, Map<String, Integer>> matrix) {
        long same = 0;
        long all = 0;
        for (Map.Entry<String, Map<String, Integer>> e : matrix.entrySet()) {
            for (Map.Entry<String, Integer> c : e.getValue().entrySet()) {
                all += c.getValue();
                if (c.getKey().equals(e.getKey())) {
                    same += c.getValue();
                }
            }
        }
        return all == 0 ? Double.NaN : same / (double) all;
    }

    private static Map<Integer, Out> runSeed(SimSpecs.Entry entry, int seed, boolean heavy) throws Exception {
        World world = new World(entry.spec(), seed, heavy);
        Truth truth = world.truth();
        List<TrafficEvent> events = world.traffic(END, 30, seed * 31L + 1);
        ApiSpec candidate = Mutations.candidate(entry.text(), MUTATIONS, new Random(seed * 13L + 5));
        SpecDiff.Result diff = SpecDiff.diff(world.spec, candidate);

        Map<String, String> pseudonym = new HashMap<>();
        for (World.Client c : world.clients) {
            pseudonym.put(c.name(), Sanitiser.pseudonym(World.SALT, c.name()));
        }
        ImpactConfig config = ImpactConfig.defaults().withIdentity("header", World.CLIENT_HEADER);
        Random shuffler = new Random(seed * 17L + 3);

        Map<Integer, Out> out = new TreeMap<>();
        for (int window : WINDOWS) {
            Out o = new Out();
            o.worlds = 1;
            Aggregate aggregate = new Aggregate(world.spec, END.minus(Duration.ofDays(window)), END);
            events.forEach(aggregate::add);
            ImpactReport report = ImpactEngine.analyse(diff, aggregate, config);
            Set<String> seen = aggregate.clients().keySet();
            o.clientsSeenShare.add(seen.size() / (double) world.clients.size());
            Instant recentFrom = END.minus(Duration.ofSeconds(Math.round(config.recentDays() * 86_400)));
            Instant staleFrom = END.minus(Duration.ofSeconds(Math.round(config.staleDays() * 86_400)));

            List<Row> scored = new ArrayList<>();
            List<Double> trueCounts = new ArrayList<>();
            for (Row row : report.breaking()) {
                o.rows++;
                Set<String> names = TruthOracle.affected(row.change(), truth);
                if (names == null) {
                    continue;
                }
                o.rowsWithTruth++;
                Set<String> trueIds = names.stream().map(pseudonym::get).collect(Collectors.toSet());
                Set<String> estimated = row.exposure().clientIds();
                Set<String> overlap = new HashSet<>(estimated);
                overlap.retainAll(trueIds);
                scored.add(row);
                trueCounts.add((double) trueIds.size());

                int recent = 0;
                int stale = 0;
                for (String id : trueIds) {
                    Aggregate.Stat s = aggregate.clients().get(id);
                    if (s != null && !s.last().isBefore(recentFrom)) {
                        recent++;
                    }
                    if (s != null && !s.last().isBefore(staleFrom)) {
                        stale++;
                    }
                }
                Severity truthSeverity = Severity.decide(true, trueIds.size(), recent, stale, report.activeClients(),
                        false, config);
                if (row.evidence() == Evidence.OBSERVED) {
                    o.estimated += estimated.size();
                    o.truth += trueIds.size();
                    o.both += overlap.size();
                    double estimatedShare = seen.isEmpty() ? 0 : estimated.size() / (double) seen.size();
                    o.shareErrors.add(Math.abs(estimatedShare - trueIds.size() / (double) world.clients.size()));
                    o.severityObserved.computeIfAbsent(row.severity().name(), p -> new TreeMap<>())
                            .merge(truthSeverity.name(), 1, Integer::sum);
                } else {
                    o.listed += estimated.size();
                    o.listedReaders += overlap.size();
                    o.readers += trueIds.size();
                    o.severityPotential.computeIfAbsent(row.severity().name(), p -> new TreeMap<>())
                            .merge(truthSeverity.name(), 1, Integer::sum);
                }
            }

            if (scored.size() >= 8) {
                double[] truthArray = Stats.toArray(trueCounts);
                double[] axon = new double[scored.size()];
                double[] specOnly = new double[scored.size()];
                double[] volume = new double[scored.size()];
                double[] clientCount = new double[scored.size()];
                double[] observedFirst = new double[scored.size()];
                for (int i = 0; i < scored.size(); i++) {
                    axon[i] = -scored.get(i).rank();
                    specOnly[i] = -scored.get(i).specOnlyRank();
                    volume[i] = scored.get(i).exposure().requests();
                    // the simplest usage-aware rule: sort by how many clients the row lists
                    clientCount[i] = scored.get(i).exposure().clients();
                    // the ordering first shipped: severity tier before evidence class (kept as an ablation)
                    observedFirst[i] = -(scored.get(i).severity().ordinal() * 1_000_000L + scored.get(i).rank());
                }
                o.tau.get("client_count").add(Stats.kendallTauB(clientCount, truthArray));
                o.tau.get("axon_severity_first").add(Stats.kendallTauB(observedFirst, truthArray));
                o.top5.get("client_count").add(top5(clientCount, truthArray));
                o.top5.get("axon_severity_first").add(top5(observedFirst, truthArray));
                o.tau.get("axon").add(Stats.kendallTauB(axon, truthArray));
                o.tau.get("spec_only").add(Stats.kendallTauB(specOnly, truthArray));
                o.tau.get("volume_only").add(Stats.kendallTauB(volume, truthArray));
                o.top5.get("axon").add(top5(axon, truthArray));
                o.top5.get("spec_only").add(top5(specOnly, truthArray));
                o.top5.get("volume_only").add(top5(volume, truthArray));
                double tauSum = 0;
                double topSum = 0;
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < scored.size(); i++) {
                    order.add(i);
                }
                for (int k = 0; k < RANDOM_SHUFFLES; k++) {
                    Collections.shuffle(order, shuffler);
                    double[] random = new double[scored.size()];
                    for (int i = 0; i < random.length; i++) {
                        random[i] = order.get(i);
                    }
                    double t = Stats.kendallTauB(random, truthArray);
                    tauSum += Double.isNaN(t) ? 0 : t;
                    double top = top5(random, truthArray);
                    topSum += Double.isNaN(top) ? 0 : top;
                }
                o.tau.get("random").add(tauSum / RANDOM_SHUFFLES);
                o.top5.get("random").add(Double.isNaN(top5(axon, truthArray)) ? Double.NaN : topSum / RANDOM_SHUFFLES);
            }

            if (world.undocumentedOperationRate > 0) {
                o.undocumentedOperationInjected = 1;
                boolean found = DriftEngine.analyse(aggregate, config).stream()
                        .anyMatch(d -> d.kind().equals(DriftFinding.UNDOCUMENTED_OPERATION));
                o.undocumentedOperationFound = found ? 1 : 0;
            }
            out.put(window, o);
        }
        return out;
    }

    /**
     * Of the five rows a ranking puts first, the share that belong in the true top five. Ties at the fifth place
     * of the truth all count, so a ranking is not punished for an arbitrary tie-break.
     */
    private static double top5(double[] score, double[] truth) {
        int n = score.length;
        Integer[] byScore = new Integer[n];
        Integer[] byTruth = new Integer[n];
        for (int i = 0; i < n; i++) {
            byScore[i] = i;
            byTruth[i] = i;
        }
        java.util.Arrays.sort(byScore, (a, b) -> Double.compare(score[b], score[a]));
        java.util.Arrays.sort(byTruth, (a, b) -> Double.compare(truth[b], truth[a]));
        double threshold = truth[byTruth[4]];
        if (threshold <= 0) {
            return Double.NaN; // fewer than five edits affect anyone: every ranking would score 1
        }
        int hits = 0;
        for (int i = 0; i < 5; i++) {
            if (truth[byScore[i]] >= threshold) {
                hits++;
            }
        }
        return hits / 5.0;
    }
}
