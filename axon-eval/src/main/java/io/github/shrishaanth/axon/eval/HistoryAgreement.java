package io.github.shrishaanth.axon.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.diff.SpecDiff;
import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.SpecParser;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * E1b: run Axon and oasdiff on real consecutive spec versions and compare what each calls breaking.
 *
 * <p>Usage: {@code e1b <histories.json> <data dir> <out dir> --oasdiff path [--only source]}
 *
 * <p>Comparison is on sets of keys {@code (operation, kind, response status)}. oasdiff "breaking" means level
 * error or warning, which is what its {@code breaking} command prints.
 */
final class HistoryAgreement {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int EXAMPLES_PER_CLASS = 5;

    private HistoryAgreement() {
    }

    private static final class Totals {
        int pairs;
        int errors;
        int bothEmpty;
        long agree;
        long axonOnly;
        long oasdiffOnly;
        long oasdiffOnlyModelled;
        int truncated;
        long axonMillis;
        long oasdiffMillis;
    }

    private static final class DisagreementClass {
        long count;
        final List<ObjectNode> examples = new ArrayList<>();
    }

    public static void main(String[] args) throws Exception {
        JsonNode manifest = JSON.readTree(Path.of(args[0]).toFile());
        Path data = Path.of(args[1]);
        Path outDir = Path.of(args[2]);
        Path binary = null;
        String only = null;
        Path dumpKeys = null;
        for (int i = 3; i < args.length - 1; i++) {
            if (args[i].equals("--oasdiff")) {
                binary = Path.of(args[i + 1]);
            } else if (args[i].equals("--only")) {
                only = args[i + 1];
            } else if (args[i].equals("--dump-keys")) {
                dumpKeys = Path.of(args[i + 1]);
            }
        }
        if (binary == null) {
            throw new IllegalArgumentException("--oasdiff is required");
        }
        Oasdiff oasdiff = new Oasdiff(binary);
        Files.createDirectories(outDir);
        String suffix = only == null ? "" : "-" + only;

        Map<String, Totals> bySource = new LinkedHashMap<>();
        Map<String, DisagreementClass> classes = new TreeMap<>();

        BufferedWriter keys = dumpKeys == null ? null : Files.newBufferedWriter(dumpKeys, StandardCharsets.UTF_8);
        try (BufferedWriter rows = Files.newBufferedWriter(outDir.resolve("e1b-pairs" + suffix + ".jsonl"),
                StandardCharsets.UTF_8);
             BufferedWriter diffs = Files.newBufferedWriter(outDir.resolve("e1b-disagreements" + suffix + ".jsonl"),
                     StandardCharsets.UTF_8)) {
            int index = 0;
            for (JsonNode pair : manifest.get("pairs")) {
                String source = pair.get("source").asText();
                if (only != null && !only.equals(source)) {
                    continue;
                }
                if (pair.has("usable") && !pair.get("usable").asBoolean()) {
                    continue;
                }
                index++;
                Totals totals = bySource.computeIfAbsent(source, s -> new Totals());
                totals.pairs++;
                Path base = data.resolve(pair.get("base").get("file").asText());
                Path revision = data.resolve(pair.get("revision").get("file").asText());
                ObjectNode row = JSON.createObjectNode();
                row.put("source", source);
                row.put("base", pair.get("base").get("file").asText());
                row.put("revision", pair.get("revision").get("file").asText());

                List<Unit> axon;
                boolean truncated;
                long start = System.nanoTime();
                try {
                    ApiSpec a = SpecParser.parse(base);
                    ApiSpec b = SpecParser.parse(revision);
                    SpecDiff.Result result = SpecDiff.diff(a, b);
                    axon = result.changes().stream().map(Unit::fromAxon).toList();
                    truncated = result.truncated();
                } catch (Exception e) {
                    totals.errors++;
                    row.put("axon_error", e.toString());
                    rows.write(JSON.writeValueAsString(row));
                    rows.newLine();
                    continue;
                }
                long axonMillis = (System.nanoTime() - start) / 1_000_000;
                Oasdiff.Run run = oasdiff.changelog(base, revision);
                if (run.error() != null) {
                    totals.errors++;
                    row.put("oasdiff_error", run.error());
                    rows.write(JSON.writeValueAsString(row));
                    rows.newLine();
                    continue;
                }
                totals.axonMillis += axonMillis;
                totals.oasdiffMillis += run.millis();
                if (truncated) {
                    totals.truncated++;
                }

                Map<String, List<Unit>> axonByKey = byKey(axon);
                Map<String, List<Unit>> oasdiffByKey = byKey(run.units());
                Set<String> axonBreaking = breakingKeys(axonByKey);
                Set<String> oasdiffBreaking = breakingKeys(oasdiffByKey);
                Map<String, List<Unit>> oasdiffByOperation = new HashMap<>();
                for (Unit u : run.units()) {
                    oasdiffByOperation.computeIfAbsent(u.method() + " " + u.path(), k -> new ArrayList<>()).add(u);
                }
                Map<String, List<Unit>> axonByOperation = new HashMap<>();
                for (Unit u : axon) {
                    axonByOperation.computeIfAbsent(u.method() + " " + u.path(), k -> new ArrayList<>()).add(u);
                }

                if (keys != null) {
                    // every change either tool calls breaking, for the hand-labelled sample (E1c)
                    Set<String> union = new TreeSet<>(axonBreaking);
                    union.addAll(oasdiffBreaking);
                    for (String key : union) {
                        ObjectNode k = JSON.createObjectNode();
                        k.put("source", source);
                        k.put("base", pair.get("base").get("file").asText());
                        k.put("revision", pair.get("revision").get("file").asText());
                        k.put("key", key);
                        k.put("axon_breaking", axonBreaking.contains(key));
                        k.put("oasdiff_breaking", oasdiffBreaking.contains(key));
                        if (axonByKey.containsKey(key)) {
                            k.put("axon_text", firstBreaking(axonByKey.get(key)).text());
                        }
                        if (oasdiffByKey.containsKey(key)) {
                            k.put("oasdiff_text", firstBreaking(oasdiffByKey.get(key)).text());
                        }
                        keys.write(JSON.writeValueAsString(k));
                        keys.newLine();
                    }
                }

                long agree = 0;
                long axonOnly = 0;
                long oasdiffOnly = 0;
                long oasdiffOnlyModelled = 0;
                for (String key : axonBreaking) {
                    if (oasdiffBreaking.contains(key)) {
                        agree++;
                        continue;
                    }
                    axonOnly++;
                    Unit u = firstBreaking(axonByKey.get(key));
                    String label;
                    List<Unit> same = oasdiffByKey.get(key);
                    List<Unit> onOperation = oasdiffByOperation.get(u.method() + " " + u.path());
                    if (same != null) {
                        label = "axon-only | " + u.kind() + " | oasdiff rates it non-breaking ("
                                + ids(same) + ")";
                    } else if (onOperation == null) {
                        label = "axon-only | " + u.kind() + " | oasdiff reports nothing for the operation";
                    } else {
                        label = "axon-only | " + u.kind() + " | oasdiff reports other changes for the operation";
                    }
                    record(classes, diffs, label, source, row, u, onOperation);
                }
                for (String key : oasdiffBreaking) {
                    if (axonBreaking.contains(key)) {
                        continue;
                    }
                    oasdiffOnly++;
                    Unit u = firstBreaking(oasdiffByKey.get(key));
                    String label;
                    List<Unit> same = axonByKey.get(key);
                    List<Unit> onOperation = axonByOperation.get(u.method() + " " + u.path());
                    if (u.kind().equals(Unit.UNMODELLED)) {
                        label = "oasdiff-only | not modelled by Axon | " + u.rawId();
                    } else {
                        oasdiffOnlyModelled++;
                        if (same != null) {
                            label = "oasdiff-only | " + u.kind() + " (" + u.rawId() + ") | Axon rates it safe";
                        } else if (onOperation == null) {
                            label = "oasdiff-only | " + u.kind() + " (" + u.rawId()
                                    + ") | Axon reports nothing for the operation";
                        } else {
                            label = "oasdiff-only | " + u.kind() + " (" + u.rawId()
                                    + ") | Axon reports other changes for the operation";
                        }
                    }
                    record(classes, diffs, label, source, row, u, onOperation);
                }
                totals.agree += agree;
                totals.axonOnly += axonOnly;
                totals.oasdiffOnly += oasdiffOnly;
                totals.oasdiffOnlyModelled += oasdiffOnlyModelled;
                if (axonBreaking.isEmpty() && oasdiffBreaking.isEmpty()) {
                    totals.bothEmpty++;
                }
                row.put("axon_changes", axon.size());
                row.put("axon_breaking_keys", axonBreaking.size());
                row.put("oasdiff_changes", run.units().size());
                row.put("oasdiff_breaking_keys", oasdiffBreaking.size());
                row.put("agree", agree);
                row.put("axon_only", axonOnly);
                row.put("oasdiff_only", oasdiffOnly);
                row.put("oasdiff_only_modelled", oasdiffOnlyModelled);
                row.put("axon_truncated", truncated);
                row.put("axon_ms", axonMillis);
                row.put("oasdiff_ms", run.millis());
                rows.write(JSON.writeValueAsString(row));
                rows.newLine();
                rows.flush();
                System.err.println(index + " " + source + " agree=" + agree + " axonOnly=" + axonOnly
                        + " oasdiffOnly=" + oasdiffOnly + " (" + axonMillis + " ms / " + run.millis() + " ms)");
            }
        }

        if (keys != null) {
            keys.close();
        }

        ObjectNode summary = JSON.createObjectNode();
        summary.put("oasdiff", oasdiff.version());
        summary.put("key", "(operation, change kind, response status); breaking only");
        ObjectNode sources = summary.putObject("by_source");
        Totals all = new Totals();
        for (Map.Entry<String, Totals> e : bySource.entrySet()) {
            Totals t = e.getValue();
            sources.set(e.getKey(), totals(t));
            all.pairs += t.pairs;
            all.errors += t.errors;
            all.bothEmpty += t.bothEmpty;
            all.agree += t.agree;
            all.axonOnly += t.axonOnly;
            all.oasdiffOnly += t.oasdiffOnly;
            all.oasdiffOnlyModelled += t.oasdiffOnlyModelled;
            all.truncated += t.truncated;
            all.axonMillis += t.axonMillis;
            all.oasdiffMillis += t.oasdiffMillis;
        }
        summary.set("all", totals(all));
        ArrayNode classList = summary.putArray("disagreement_classes");
        classes.entrySet().stream()
                .sorted((x, y) -> Long.compare(y.getValue().count, x.getValue().count))
                .forEach(e -> {
                    ObjectNode c = classList.addObject();
                    c.put("class", e.getKey());
                    c.put("count", e.getValue().count);
                    c.set("examples", JSON.valueToTree(e.getValue().examples));
                });
        String text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
        Files.writeString(outDir.resolve("e1b-summary" + suffix + ".json"), text + "\n", StandardCharsets.UTF_8);
        ObjectNode brief = summary.deepCopy();
        brief.remove("disagreement_classes");
        System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(brief));
    }

    private static ObjectNode totals(Totals t) {
        ObjectNode o = JSON.createObjectNode();
        o.put("pairs", t.pairs);
        o.put("pairs_with_tool_error", t.errors);
        o.put("pairs_where_neither_reports_breaking", t.bothEmpty);
        o.put("pairs_with_truncated_axon_output", t.truncated);
        o.put("agree", t.agree);
        o.put("axon_only", t.axonOnly);
        o.put("oasdiff_only", t.oasdiffOnly);
        o.put("oasdiff_only_of_kinds_axon_models", t.oasdiffOnlyModelled);
        long axonTotal = t.agree + t.axonOnly;
        long oasdiffTotal = t.agree + t.oasdiffOnly;
        long oasdiffModelled = t.agree + t.oasdiffOnlyModelled;
        o.put("precision_vs_oasdiff", axonTotal == 0 ? Double.NaN : t.agree / (double) axonTotal);
        o.put("recall_vs_oasdiff", oasdiffTotal == 0 ? Double.NaN : t.agree / (double) oasdiffTotal);
        o.put("recall_vs_oasdiff_on_modelled_kinds",
                oasdiffModelled == 0 ? Double.NaN : t.agree / (double) oasdiffModelled);
        long union = t.agree + t.axonOnly + t.oasdiffOnly;
        o.put("jaccard", union == 0 ? Double.NaN : t.agree / (double) union);
        o.put("axon_seconds", t.axonMillis / 1000.0);
        o.put("oasdiff_seconds", t.oasdiffMillis / 1000.0);
        return o;
    }

    private static void record(Map<String, DisagreementClass> classes, BufferedWriter diffs, String label,
                               String source, ObjectNode pair, Unit unit, List<Unit> otherToolOnOperation)
            throws java.io.IOException {
        DisagreementClass c = classes.computeIfAbsent(label, k -> new DisagreementClass());
        c.count++;
        ObjectNode d = JSON.createObjectNode();
        d.put("class", label);
        d.put("source", source);
        d.put("base", pair.get("base").asText());
        d.put("revision", pair.get("revision").asText());
        d.put("tool", unit.tool());
        d.put("operation", unit.method() + " " + unit.path());
        d.put("kind", unit.kind());
        d.put("id", unit.rawId());
        d.put("status", unit.status());
        d.put("text", unit.text());
        ArrayNode other = d.putArray("other_tool_on_operation");
        if (otherToolOnOperation != null) {
            for (Unit u : otherToolOnOperation) {
                if (other.size() >= 8) {
                    break;
                }
                other.add(u.rawId() + (u.breaking() ? "!" : "") + ": " + u.text());
            }
        }
        if (c.examples.size() < EXAMPLES_PER_CLASS) {
            c.examples.add(d);
        }
        diffs.write(JSON.writeValueAsString(d));
        diffs.newLine();
    }

    private static Map<String, List<Unit>> byKey(List<Unit> units) {
        Map<String, List<Unit>> out = new HashMap<>();
        for (Unit u : units) {
            out.computeIfAbsent(u.key(), k -> new ArrayList<>()).add(u);
        }
        return out;
    }

    private static Set<String> breakingKeys(Map<String, List<Unit>> byKey) {
        Set<String> out = new TreeSet<>();
        for (Map.Entry<String, List<Unit>> e : byKey.entrySet()) {
            if (e.getValue().stream().anyMatch(Unit::breaking)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    private static Unit firstBreaking(List<Unit> units) {
        return units.stream().filter(Unit::breaking).findFirst().orElse(units.get(0));
    }

    private static String ids(List<Unit> units) {
        Set<String> ids = new TreeSet<>();
        for (Unit u : units) {
            ids.add(u.rawId());
        }
        return String.join(", ", ids);
    }
}
