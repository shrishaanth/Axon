package io.github.shrishaanth.axon.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.Explorer;
import io.github.shrishaanth.axon.spec.SpecParser;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Parses one spec and prints heap figures as one JSON line. Run it under different {@code -Xmx} values to find
 * the smallest heap that works; an OutOfMemoryError is reported as a result, not hidden.
 *
 * <p>Usage: {@code memory <spec>}
 */
final class MemoryProbe {

    private MemoryProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        ObjectNode row = new ObjectMapper().createObjectNode();
        row.put("file", file.getFileName().toString());
        row.put("bytes", Files.size(file));
        row.put("max_heap_mb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        long before = usedAfterGc();
        long start = System.nanoTime();
        try {
            ApiSpec spec = SpecParser.parse(file);
            row.put("parse_ms", (System.nanoTime() - start) / 1_000_000);
            long peak = peakHeap();
            long retained = usedAfterGc() - before;
            long describeStart = System.nanoTime();
            int chars = Explorer.toJson(Explorer.describe(spec)).length();
            row.put("ok", true);
            row.put("operations", spec.operations().size());
            row.put("component_schemas", spec.componentSchemas());
            row.put("retained_model_mb", retained / (1024.0 * 1024.0));
            row.put("peak_heap_during_parse_mb", peak / (1024.0 * 1024.0));
            row.put("describe_ms", (System.nanoTime() - describeStart) / 1_000_000);
            row.put("explorer_json_chars", chars);
            row.put("peak_heap_total_mb", peakHeap() / (1024.0 * 1024.0));
        } catch (Exception | OutOfMemoryError e) {
            row.put("ok", false);
            row.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        System.out.println(row);
    }

    private static long usedAfterGc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(50);
        }
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** Sum of per-pool peaks: an upper bound on the true simultaneous peak, which is the safe direction. */
    private static long peakHeap() {
        long total = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                total += pool.getPeakUsage().getUsed();
            }
        }
        return total;
    }
}
