package io.github.shrishaanth.axon.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.Explorer;
import io.github.shrishaanth.axon.spec.Operation;
import io.github.shrishaanth.axon.spec.SpecParseException;
import io.github.shrishaanth.axon.spec.SpecParser;
import io.github.shrishaanth.axon.spec.Unsupported;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * M1 survey: parse every spec in a directory and record what happened, including failures.
 *
 * <p>Usage: {@code survey <dir> <out.json>}
 */
final class ParseSurvey {

    private ParseSurvey() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        Path out = Path.of(args[1]);
        ObjectMapper json = new ObjectMapper();
        ObjectNode result = json.createObjectNode();
        ArrayNode rows = result.putArray("specs");

        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        int ok = 0;
        Map<String, Integer> failures = new TreeMap<>();
        Map<String, Integer> constructSpecs = new TreeMap<>();
        Map<String, Integer> constructTotal = new TreeMap<>();
        long operations = 0;
        long partial = 0;
        long approximated = 0;

        for (Path file : files) {
            ObjectNode row = rows.addObject();
            row.put("file", file.getFileName().toString());
            row.put("bytes", Files.size(file));
            long start = System.nanoTime();
            try {
                ApiSpec spec = SpecParser.parse(file);
                // exercise the explorer too: a spec that parses but cannot be described is a failure
                int described = Explorer.describe(spec).path("operations").size();
                row.put("ok", true);
                row.put("ms", (System.nanoTime() - start) / 1_000_000);
                row.put("openapi", spec.openapi());
                row.put("operations", described);
                row.put("component_schemas", spec.componentSchemas());
                long p = spec.operations().stream().filter(Operation::partiallyAnalysed).count();
                long a = spec.operations().stream().filter(Operation::approximated).count();
                row.put("partially_analysed_operations", p);
                row.put("approximated_operations", a);
                ObjectNode constructs = row.putObject("unsupported");
                Map<String, Integer> counts = new TreeMap<>();
                for (Unsupported u : spec.unsupported()) {
                    counts.merge(u.construct() + " (" + u.handling() + ")", 1, Integer::sum);
                }
                counts.forEach((k, v) -> {
                    constructs.put(k, v);
                    constructSpecs.merge(k, 1, Integer::sum);
                    constructTotal.merge(k, v, Integer::sum);
                });
                ok++;
                operations += described;
                partial += p;
                approximated += a;
            } catch (SpecParseException e) {
                row.put("ok", false);
                row.put("ms", (System.nanoTime() - start) / 1_000_000);
                row.put("category", e.category().name());
                row.put("message", e.getMessage());
                failures.merge(e.category().name(), 1, Integer::sum);
            }
        }

        ObjectNode summary = result.putObject("summary");
        summary.put("files", files.size());
        summary.put("parsed", ok);
        summary.put("failed", files.size() - ok);
        summary.set("failures_by_category", json.valueToTree(failures));
        summary.put("operations", operations);
        summary.put("partially_analysed_operations", partial);
        summary.put("approximated_operations", approximated);
        summary.set("specs_with_construct", json.valueToTree(constructSpecs));
        summary.set("occurrences_of_construct", json.valueToTree(constructTotal));
        summary.put("java", System.getProperty("java.version"));

        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, json.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n",
                StandardCharsets.UTF_8);
        System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    }
}
