package io.github.shrishaanth.axon.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.diff.Change;
import io.github.shrishaanth.axon.diff.SpecDiff;
import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.SpecParser;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

/**
 * E1a: apply known mutations to real specs and check each tool reports them with the expected class.
 *
 * <p>Usage: {@code e1a <spec dir> <out dir> [--oasdiff path] [--seed n]}
 *
 * <p>Three predictors are scored on the same mutations: Axon, oasdiff, and a text-diff baseline that calls a
 * change breaking whenever a line of the pretty-printed baseline is missing from the candidate.
 */
final class MutationExperiment {

    private static final ObjectMapper JSON = new ObjectMapper();

    private MutationExperiment() {
    }

    private static final class Tally {
        int n;
        int found;
        int foundRightClass;
        int anyBreaking;
        int extras;
        int errors;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);
        Path oasdiffBinary = null;
        long seed = 20261003L;
        for (int i = 2; i < args.length - 1; i++) {
            if (args[i].equals("--oasdiff")) {
                oasdiffBinary = Path.of(args[i + 1]);
            } else if (args[i].equals("--seed")) {
                seed = Long.parseLong(args[i + 1]);
            }
        }
        Oasdiff oasdiff = oasdiffBinary == null ? null : new Oasdiff(oasdiffBinary);
        Files.createDirectories(outDir);

        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        Map<Mutator.Kind, Tally> axon = new EnumMap<>(Mutator.Kind.class);
        Map<Mutator.Kind, Tally> other = new EnumMap<>(Mutator.Kind.class);
        Map<Mutator.Kind, Tally> text = new EnumMap<>(Mutator.Kind.class);
        Map<Mutator.Kind, Integer> noSite = new EnumMap<>(Mutator.Kind.class);
        for (Mutator.Kind k : Mutator.Kind.values()) {
            axon.put(k, new Tally());
            other.put(k, new Tally());
            text.put(k, new Tally());
            noSite.put(k, 0);
        }
        Map<String, Integer> oasdiffNotes = new HashMap<>();

        Path work = Files.createTempDirectory("axon-e1a");
        Path baseFile = work.resolve("base.json");
        Path mutFile = work.resolve("mut.json");
        try (BufferedWriter rows = Files.newBufferedWriter(outDir.resolve("e1a-mutations.jsonl"),
                StandardCharsets.UTF_8)) {
            for (int f = 0; f < files.size(); f++) {
                Path file = files.get(f);
                JsonNode tree;
                ApiSpec baseSpec;
                String baseJson;
                try {
                    tree = SpecParser.loadTree(Files.readString(file, StandardCharsets.UTF_8));
                    baseJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
                    baseSpec = SpecParser.parse(baseJson);
                } catch (Exception e) {
                    System.err.println("skip " + file.getFileName() + ": " + e.getMessage());
                    continue;
                }
                Files.writeString(baseFile, baseJson, StandardCharsets.UTF_8);
                String[] baseLines = baseJson.split("\n");
                for (Mutator.Kind kind : Mutator.Kind.values()) {
                    Random random = new Random(seed * 1_000_003L + f * 131L + kind.ordinal());
                    ObjectNode copy = tree.deepCopy();
                    Mutator.Applied applied = new Mutator(copy, random).apply(kind);
                    if (applied == null) {
                        noSite.merge(kind, 1, Integer::sum);
                        continue;
                    }
                    String mutJson = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(copy);
                    ObjectNode row = JSON.createObjectNode();
                    row.put("spec", file.getFileName().toString());
                    row.put("mutation", kind.name());
                    row.put("expected_kind", kind.expected.wire());
                    row.put("expected_breaking", kind.breaking);
                    row.put("method", applied.method().toUpperCase(Locale.ROOT));
                    row.put("path", applied.path());
                    row.put("status", applied.status());
                    row.put("leaf", applied.leaf());
                    row.put("note", applied.note());

                    // Axon
                    Tally ta = axon.get(kind);
                    ta.n++;
                    ObjectNode an = row.putObject("axon");
                    try {
                        SpecDiff.Result result = SpecDiff.diff(baseSpec, SpecParser.parse(mutJson));
                        List<Unit> units = result.changes().stream().map(Unit::fromAxon).toList();
                        score(units, kind, applied, ta, an);
                        ArrayNode all = an.putArray("reported");
                        for (Change c : result.changes()) {
                            if (all.size() < 20) {
                                all.add(c.kind().wire() + (c.breaking() ? "!" : "") + " " + c.operationKey()
                                        + (c.field() == null ? "" : " " + c.field()));
                            }
                        }
                    } catch (Exception e) {
                        ta.errors++;
                        an.put("error", e.toString());
                    }

                    // oasdiff
                    if (oasdiff != null) {
                        Tally to = other.get(kind);
                        to.n++;
                        ObjectNode on = row.putObject("oasdiff");
                        Files.writeString(mutFile, mutJson, StandardCharsets.UTF_8);
                        Oasdiff.Run run = oasdiff.changelog(baseFile, mutFile);
                        if (run.error() != null) {
                            to.errors++;
                            on.put("error", run.error());
                        } else {
                            score(run.units(), kind, applied, to, on);
                            ArrayNode all = on.putArray("reported");
                            for (Unit u : run.units()) {
                                if (all.size() < 20) {
                                    all.add(u.rawId() + (u.breaking() ? "!" : "") + " " + u.method() + " "
                                            + u.path());
                                }
                                if (u.kind().equals(Unit.UNMODELLED)) {
                                    oasdiffNotes.merge(u.rawId(), 1, Integer::sum);
                                }
                            }
                        }
                    }

                    // text-diff baseline
                    Tally tt = text.get(kind);
                    tt.n++;
                    boolean removedLine = removedLine(baseLines, mutJson.split("\n"));
                    row.put("textdiff_breaking", removedLine);
                    if (removedLine) {
                        tt.anyBreaking++;
                    }
                    rows.write(JSON.writeValueAsString(row));
                    rows.newLine();
                }
                System.err.println((f + 1) + "/" + files.size() + " " + file.getFileName());
            }
        }

        ObjectNode summary = JSON.createObjectNode();
        summary.put("seed", seed);
        summary.put("specs", files.size());
        summary.put("oasdiff", oasdiff == null ? null : oasdiff.version());
        ArrayNode kinds = summary.putArray("by_mutation");
        int[][] confusion = new int[3][4]; // tool x {TP, FP, FN, TN}
        for (Mutator.Kind kind : Mutator.Kind.values()) {
            ObjectNode k = kinds.addObject();
            k.put("mutation", kind.name());
            k.put("expected_kind", kind.expected.wire());
            k.put("expected_breaking", kind.breaking);
            k.put("specs_without_a_site", noSite.get(kind));
            k.set("axon", tally(axon.get(kind)));
            if (oasdiff != null) {
                k.set("oasdiff", tally(other.get(kind)));
            }
            k.set("textdiff", tally(text.get(kind)));
            List<Tally> tools = Arrays.asList(axon.get(kind), other.get(kind), text.get(kind));
            for (int t = 0; t < 3; t++) {
                Tally tl = tools.get(t);
                int scored = tl.n - tl.errors;
                if (kind.breaking) {
                    confusion[t][0] += tl.anyBreaking;
                    confusion[t][2] += scored - tl.anyBreaking;
                } else {
                    confusion[t][1] += tl.anyBreaking;
                    confusion[t][3] += scored - tl.anyBreaking;
                }
            }
        }
        ObjectNode overall = summary.putObject("breaking_label");
        String[] names = {"axon", "oasdiff", "textdiff"};
        for (int t = 0; t < 3; t++) {
            if (t == 1 && oasdiff == null) {
                continue;
            }
            int tp = confusion[t][0];
            int fp = confusion[t][1];
            int fn = confusion[t][2];
            int tn = confusion[t][3];
            ObjectNode o = overall.putObject(names[t]);
            o.put("tp", tp);
            o.put("fp", fp);
            o.put("fn", fn);
            o.put("tn", tn);
            o.put("precision", tp + fp == 0 ? Double.NaN : tp / (double) (tp + fp));
            o.put("recall", tp + fn == 0 ? Double.NaN : tp / (double) (tp + fn));
            o.put("accuracy", (tp + tn) / (double) Math.max(1, tp + fp + fn + tn));
        }
        summary.set("oasdiff_unmodelled_ids_seen", JSON.valueToTree(new java.util.TreeMap<>(oasdiffNotes)));
        String text2 = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary);
        Files.writeString(outDir.resolve("e1a-summary.json"), text2 + "\n", StandardCharsets.UTF_8);
        System.out.println(text2);
    }

    private static ObjectNode tally(Tally t) {
        ObjectNode o = JSON.createObjectNode();
        o.put("n", t.n);
        o.put("found_expected_kind", t.found);
        o.put("found_with_expected_class", t.foundRightClass);
        o.put("reported_any_breaking", t.anyBreaking);
        o.put("instances_with_other_changes", t.extras);
        o.put("errors", t.errors);
        return o;
    }

    /** Looks for the expected change among a tool's units and records what else it reported. */
    private static void score(List<Unit> units, Mutator.Kind kind, Mutator.Applied applied, Tally tally,
                              ObjectNode out) {
        String method = applied.method().toUpperCase(Locale.ROOT);
        String path = SpecDiff.normalisePath(applied.path());
        Unit match = null;
        int others = 0;
        boolean anyBreaking = false;
        for (Unit u : units) {
            anyBreaking |= u.breaking();
            boolean same = u.kind().equals(kind.expected.wire())
                    && u.method().equalsIgnoreCase(method)
                    && u.path().equals(path)
                    && (applied.leaf() == null || u.leaves().isEmpty() || u.leaves().contains(applied.leaf()))
                    && (applied.status() == null || u.status().isEmpty() || u.status().equals(applied.status()));
            if (same && match == null) {
                match = u;
            } else if (!same) {
                others++;
            }
        }
        out.put("found", match != null);
        if (match != null) {
            out.put("breaking", match.breaking());
            out.put("id", match.rawId());
            tally.found++;
            if (match.breaking() == kind.breaking) {
                tally.foundRightClass++;
            }
        }
        out.put("any_breaking", anyBreaking);
        out.put("other_changes", others);
        if (anyBreaking) {
            tally.anyBreaking++;
        }
        if (others > 0) {
            tally.extras++;
        }
    }

    private static boolean removedLine(String[] base, String[] mutated) {
        Map<String, Integer> counts = new HashMap<>();
        for (String line : mutated) {
            counts.merge(line, 1, Integer::sum);
        }
        for (String line : base) {
            Integer c = counts.get(line);
            if (c == null || c == 0) {
                return true;
            }
            counts.put(line, c - 1);
        }
        return false;
    }
}
