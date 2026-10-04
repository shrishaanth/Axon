package io.github.shrishaanth.axon.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * E4: sustained ingestion over HTTP into one Postgres, and replay time. Not part of the normal test run.
 *
 * <pre>
 * mvn -pl axon-server test -Dtest=E4Benchmark -Daxon.e4=true -Dsurefire.failIfNoSpecifiedTests=false \
 *     [-Daxon.e4.seconds=600] [-Daxon.e4.replay=100000,1000000]
 * </pre>
 *
 * <p>Traffic is the real demo capture, repeated. Each repetition gets its own client pseudonyms and is moved
 * back by a day (wrapping after 60), so the counter table keeps growing instead of settling on existing rows.
 * One driver thread; the driver, the API and Postgres (in Docker) share one machine.
 */
@EnabledIfSystemProperty(named = "axon.e4", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class E4Benchmark {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    static {
        if (Boolean.getBoolean("axon.e4")) {
            POSTGRES.start();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("axon.ingest-requests-per-second", () -> "0");
        registry.add("axon.max-events-per-workspace", () -> "100000000");
        registry.add("axon.max-workspaces", () -> "1000");
    }

    private static final Path DEMO = Path.of("..", "eval", "demo");
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;
    @Autowired
    Store store;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private List<JsonNode> template;
    private long generated;

    private HttpResponse<String> send(String method, String path, String body, String header, String key)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofMinutes(60)).header("Content-Type", "text/plain");
        if (key != null) {
            b.header(header, key);
        }
        return http.send(b.method(method, HttpRequest.BodyPublishers.ofString(body == null ? "" : body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String[] workspace(String name) throws Exception {
        JsonNode w = JSON.readTree(send("POST", "/api/workspaces", "{\"name\":\"" + name + "\"}", null, null).body());
        String[] out = {w.get("id").asText(), w.get("ingest_key").asText(), w.get("admin_key").asText()};
        HttpResponse<String> spec = send("PUT", "/api/workspaces/" + out[0] + "/spec",
                Files.readString(DEMO.resolve("recipes-v1.yaml")), "X-Axon-Admin", out[2]);
        assertEquals(200, spec.statusCode(), spec.body());
        return out;
    }

    /** The next event of the endless stream described in the class comment. */
    private String next() throws Exception {
        int size = template.size();
        long cycle = generated / size;
        ObjectNode e = template.get((int) (generated % size)).deepCopy();
        generated++;
        if (cycle > 0) {
            if (e.has("client")) {
                byte[] hash = MessageDigest.getInstance("SHA-256")
                        .digest((e.get("client").asText() + ":" + (cycle % 50)).getBytes(StandardCharsets.UTF_8));
                e.put("client", HexFormat.of().formatHex(hash, 0, 8));
            }
            e.put("ts", Instant.parse(e.get("ts").asText()).minus(Duration.ofDays(cycle % 60)).toString());
        }
        return e.toString();
    }

    private String batch(int n) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(next()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void run() throws Exception {
        template = new ArrayList<>();
        for (String line : Files.readAllLines(DEMO.resolve("usage.jsonl"), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                template.add(JSON.readTree(line));
            }
        }
        int seconds = Integer.getInteger("axon.e4.seconds", 600);
        long[] replaySizes = Arrays.stream(System.getProperty("axon.e4.replay", "100000,1000000").split(","))
                .mapToLong(Long::parseLong).toArray();

        ObjectNode result = JSON.createObjectNode();
        result.put("started", Instant.now().toString());
        result.put("seconds_per_configuration", seconds);
        result.put("postgres", "17 (Docker, default settings)");
        result.put("java", System.getProperty("java.version"));
        result.put("processors", Runtime.getRuntime().availableProcessors());
        result.put("max_heap_mb", Runtime.getRuntime().maxMemory() / (1024 * 1024));
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        result.put("traffic", "eval/demo/usage.jsonl repeated; new client pseudonyms and a one-day shift per repeat");

        // warm-up, not measured
        String[] warm = workspace("warm-up");
        for (int i = 0; i < 20; i++) {
            send("POST", "/api/ingest", batch(500), "X-Axon-Key", warm[1]);
        }

        ArrayNode ingest = result.putArray("ingest");
        for (int batchSize : new int[] {1, 100, 1000}) {
            generated = 0;
            String[] w = workspace("batch-" + batchSize);
            List<Long> latencies = new ArrayList<>();
            long events = 0;
            long peakHeap = 0;
            long start = System.nanoTime();
            long deadline = start + seconds * 1_000_000_000L;
            while (System.nanoTime() < deadline) {
                String body = batch(batchSize);
                long t0 = System.nanoTime();
                HttpResponse<String> r = send("POST", "/api/ingest", body, "X-Axon-Key", w[1]);
                latencies.add(System.nanoTime() - t0);
                assertEquals(200, r.statusCode(), r.body());
                events += batchSize;
                if (latencies.size() % 20 == 0 || batchSize >= 100) {
                    Runtime rt = Runtime.getRuntime();
                    peakHeap = Math.max(peakHeap, rt.totalMemory() - rt.freeMemory());
                }
            }
            double elapsed = (System.nanoTime() - start) / 1e9;
            long cells = store.cellCount(UUID.fromString(w[0]));
            latencies.sort(null);
            ObjectNode row = ingest.addObject();
            row.put("events_per_request", batchSize);
            row.put("requests", latencies.size());
            row.put("events", events);
            row.put("seconds", Math.round(elapsed * 10) / 10.0);
            row.put("events_per_second", Math.round(events / elapsed));
            row.put("request_latency_p50_ms", latencies.get(latencies.size() / 2) / 1e6);
            row.put("request_latency_p95_ms", latencies.get((int) (latencies.size() * 0.95)) / 1e6);
            row.put("request_latency_p99_ms", latencies.get((int) (latencies.size() * 0.99)) / 1e6);
            row.put("counter_rows_at_end", cells);
            row.put("peak_used_heap_mb_sampled", peakHeap / (1024 * 1024));
            row.put("stored_events", store.eventCount(UUID.fromString(w[0])));
            System.err.println(row);
        }

        ArrayNode replay = result.putArray("replay");
        for (long n : replaySizes) {
            generated = 0;
            String[] w = workspace("replay-" + n);
            long loadStart = System.nanoTime();
            for (long sent = 0; sent < n; sent += 1000) {
                HttpResponse<String> r = send("POST", "/api/ingest", batch(1000), "X-Axon-Key", w[1]);
                assertEquals(200, r.statusCode(), r.body());
            }
            double loadSeconds = (System.nanoTime() - loadStart) / 1e9;
            UUID id = UUID.fromString(w[0]);
            String live = store.cellFingerprint(id);
            long liveCells = store.cellCount(id);
            JsonNode replayed = JSON.readTree(send("POST", "/api/workspaces/" + w[0] + "/replay", "", "X-Axon-Admin", w[2]).body());
            String after = store.cellFingerprint(id);
            ObjectNode row = replay.addObject();
            row.put("events", n);
            row.put("load_seconds", Math.round(loadSeconds * 10) / 10.0);
            row.put("replay_seconds", replayed.get("millis").asLong() / 1000.0);
            row.put("replay_events_per_second", Math.round(n / (replayed.get("millis").asLong() / 1000.0)));
            row.put("counter_rows", liveCells);
            row.put("replay_equals_live", live.equals(after) && liveCells == replayed.get("cells").asLong());
            System.err.println(row);
            assertEquals(live, after, "replay must reproduce the live counters exactly");
        }
        result.put("finished", Instant.now().toString());
        Path out = Path.of("..", "eval", "results", "e4");
        Files.createDirectories(out);
        Files.writeString(out.resolve("e4.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n", StandardCharsets.UTF_8);
    }
}
