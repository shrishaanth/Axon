package io.github.shrishaanth.axon.observe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.SpecParser;
import io.github.shrishaanth.axon.traffic.TrafficEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Uses the real demo capture (eval/demo): 10,958 events over 60 days from 13 clients. */
class CellsTest {

    private static final Path DEMO = Path.of("..", "eval", "demo");

    private static ApiSpec spec() throws Exception {
        return SpecParser.parse(DEMO.resolve("recipes-v1.yaml"));
    }

    private static List<TrafficEvent> events() throws Exception {
        List<TrafficEvent> out = new ArrayList<>();
        for (String line : Files.readAllLines(DEMO.resolve("usage.jsonl"), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                out.add(TrafficEvent.fromJson(line));
            }
        }
        return out;
    }

    @Test
    void cellsRebuildTheAggregateExactly() throws Exception {
        ApiSpec spec = spec();
        List<TrafficEvent> events = events();
        Aggregate direct = new Aggregate(spec, null, null);
        events.forEach(direct::add);

        Cells cells = new Cells(spec);
        events.forEach(cells::add);
        Aggregate rebuilt = Cells.toAggregate(spec, cells.cells().entrySet(), null, null);

        assertTrue(events.size() > 10_000);
        assertEquals(direct, rebuilt);
        assertEquals(direct.clients().keySet(), rebuilt.clients().keySet());
    }

    @Test
    void batchesInAnyOrderMergeToTheSameCells() throws Exception {
        ApiSpec spec = spec();
        List<TrafficEvent> events = events();
        Cells whole = new Cells(spec);
        events.forEach(whole::add);

        List<TrafficEvent> shuffled = new ArrayList<>(events);
        Collections.shuffle(shuffled, new Random(3));
        Map<Cells.Key, Cells.Value> merged = new HashMap<>();
        for (int i = 0; i < shuffled.size(); i += 137) {
            Cells batch = new Cells(spec);
            shuffled.subList(i, Math.min(i + 137, shuffled.size())).forEach(batch::add);
            batch.cells().forEach((k, v) -> merged.merge(k, v, (a, b) -> {
                a.merge(b);
                return a;
            }));
        }
        assertEquals(whole.cells(), merged);
    }

    @Test
    void dayRangeSelectsTheSameEventsAsAWindow() throws Exception {
        ApiSpec spec = spec();
        List<TrafficEvent> events = events();
        LocalDate from = LocalDate.parse("2026-09-01");
        LocalDate to = LocalDate.parse("2026-09-20");
        Aggregate windowed = new Aggregate(spec, from.atStartOfDay().toInstant(java.time.ZoneOffset.UTC),
                to.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).minusNanos(1));
        events.forEach(windowed::add);

        Cells cells = new Cells(spec);
        events.forEach(cells::add);
        Aggregate rebuilt = Cells.toAggregate(spec, cells.cells().entrySet(), from, to);
        assertEquals(windowed.operations(), rebuilt.operations());
        assertEquals(windowed.clients(), rebuilt.clients());
        assertEquals(windowed.events(), rebuilt.events());
    }
}
