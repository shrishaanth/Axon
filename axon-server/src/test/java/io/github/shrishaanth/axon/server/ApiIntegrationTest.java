package io.github.shrishaanth.axon.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * The whole API against a real Postgres, fed with the real demo capture (eval/demo). Needs Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    static {
        // started here, not by @Container: with a per-class test instance Spring builds the context before the
        // Testcontainers extension would start it
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("axon.ingest-requests-per-second", () -> "0");
        registry.add("axon.max-events-per-workspace", () -> "30000");
    }

    private static final Path DEMO = Path.of("..", "eval", "demo");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    TestRestTemplate http;
    @Autowired
    Store store;

    private String id;
    private String ingestKey;
    private String adminKey;
    private List<String> events;

    private ResponseEntity<String> call(HttpMethod method, String url, String body, String header, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        if (key != null) {
            headers.set(header, key);
        }
        return http.exchange(url, method, new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode admin(HttpMethod method, String url, String body) throws Exception {
        ResponseEntity<String> r = call(method, url, body, "X-Axon-Admin", adminKey);
        assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());
        return JSON.readTree(r.getBody());
    }

    private String[] newWorkspace(String name) throws Exception {
        ResponseEntity<String> created = call(HttpMethod.POST, "/api/workspaces", "{\"name\":\"" + name + "\"}", null, null);
        assertEquals(HttpStatus.CREATED, created.getStatusCode(), created.getBody());
        JsonNode body = JSON.readTree(created.getBody());
        return new String[] {body.get("id").asText(), body.get("ingest_key").asText(), body.get("admin_key").asText()};
    }

    @BeforeAll
    void loadDemo() throws Exception {
        events = Files.readAllLines(DEMO.resolve("usage.jsonl"), StandardCharsets.UTF_8);
        String[] w = newWorkspace("recipes");
        id = w[0];
        ingestKey = w[1];
        adminKey = w[2];
        admin(HttpMethod.PUT, "/api/workspaces/" + id + "/spec?name=recipes-v1.yaml",
                Files.readString(DEMO.resolve("recipes-v1.yaml")));
        // shuffled, so that the stored aggregate cannot depend on arrival order
        List<String> shuffled = new ArrayList<>(events);
        Collections.shuffle(shuffled, new Random(11));
        for (int i = 0; i < shuffled.size(); i += 1000) {
            String batch = String.join("\n", shuffled.subList(i, Math.min(i + 1000, shuffled.size())));
            ResponseEntity<String> r = call(HttpMethod.POST, "/api/ingest", batch, "X-Axon-Key", ingestKey);
            assertEquals(HttpStatus.OK, r.getStatusCode(), r.getBody());
            assertEquals(0, JSON.readTree(r.getBody()).get("rejected").asInt());
        }
    }

    @Test
    void impactOverHttpMatchesTheBatchReport() throws Exception {
        JsonNode report = admin(HttpMethod.POST, "/api/workspaces/" + id + "/impact?name=recipes-v2.yaml",
                Files.readString(DEMO.resolve("recipes-v2.yaml")));
        JsonNode batch = JSON.readTree(DEMO.resolve("report.json").toFile());
        assertEquals(batch.get("summary"), report.get("summary"));
        assertEquals(batch.get("inputs").get("usage").get("events"), report.get("inputs").get("usage").get("events"));
        assertEquals(batch.get("changes").size(), report.get("changes").size());
        for (int i = 0; i < batch.get("changes").size(); i++) {
            JsonNode a = batch.get("changes").get(i);
            JsonNode b = report.get("changes").get(i);
            assertEquals(a.get("kind"), b.get("kind"));
            assertEquals(a.get("rank"), b.get("rank"));
            assertEquals(a.get("severity"), b.get("severity"));
            assertEquals(a.path("observed_affected").path("clients"), b.path("observed_affected").path("clients"));
            assertEquals(a.path("potentially_affected").path("requests"), b.path("potentially_affected").path("requests"));
        }
        assertEquals(batch.get("drift").size(), report.get("drift").size());
        assertEquals("ingest", report.get("inputs").get("usage").get("source").asText());
    }

    @Test
    void replayReproducesLiveAggregationExactly() throws Exception {
        UUID workspace = UUID.fromString(id);
        String live = store.cellFingerprint(workspace);
        long cells = store.cellCount(workspace);
        JsonNode replay = admin(HttpMethod.POST, "/api/workspaces/" + id + "/replay", "");
        assertEquals(events.size(), replay.get("events").asInt());
        assertEquals(cells, replay.get("cells").asLong());
        assertEquals(live, store.cellFingerprint(workspace), "counters after replay differ from live ingestion");
        assertNotEquals("empty", live);
    }

    @Test
    void uploadingAnotherSpecRematchesStoredEvents() throws Exception {
        String[] w = newWorkspace("rematch");
        String spec = Files.readString(DEMO.resolve("recipes-v1.yaml"));
        // before any spec, everything is stored but nothing matches
        String batch = String.join("\n", events.subList(0, 500));
        assertEquals(HttpStatus.OK, call(HttpMethod.POST, "/api/ingest", batch, "X-Axon-Key", w[1]).getStatusCode());
        ResponseEntity<String> noSpec = call(HttpMethod.GET, "/api/workspaces/" + w[0] + "/contract", null, "X-Axon-Admin", w[2]);
        assertEquals(HttpStatus.CONFLICT, noSpec.getStatusCode());
        ResponseEntity<String> put = call(HttpMethod.PUT, "/api/workspaces/" + w[0] + "/spec", spec, "X-Axon-Admin", w[2]);
        assertEquals(HttpStatus.OK, put.getStatusCode(), put.getBody());
        assertEquals(500, JSON.readTree(put.getBody()).get("replay").get("events").asInt());
        JsonNode contract = JSON.readTree(call(HttpMethod.GET, "/api/workspaces/" + w[0] + "/contract", null,
                "X-Axon-Admin", w[2]).getBody());
        assertTrue(contract.get("matched_events").asInt() > 450, contract.get("matched_events").toString());
    }

    @Test
    void viewsDescribeTheObservedContract() throws Exception {
        JsonNode contract = admin(HttpMethod.GET, "/api/workspaces/" + id + "/contract", null);
        JsonNode list = null;
        for (JsonNode op : contract.get("operations")) {
            if (op.get("method").asText().equals("GET") && op.get("path").asText().equals("/recipes")) {
                list = op;
            }
        }
        assertEquals(4916, list.get("calls").asInt());
        assertEquals(6, list.get("clients").asInt());
        List<String> states = new ArrayList<>();
        for (JsonNode f : list.get("fields")) {
            states.add(f.get("part").asText() + " " + f.get("path").asText() + " " + f.get("state").asText());
        }
        assertTrue(states.contains("request.query $.debug undocumented"), states.toString());
        assertTrue(states.contains("request.query $.cursor never_seen"), states.toString());
        assertTrue(states.contains("response.body $.items[].internal_score undocumented"), states.toString());
        assertTrue(states.contains("response.body $.items[].subtitle never_seen"), states.toString());
        assertTrue(states.contains("response.body $.items[].title documented_and_used"), states.toString());

        JsonNode clients = admin(HttpMethod.GET, "/api/workspaces/" + id + "/clients", null);
        assertEquals(13, clients.get("clients_total").asInt());

        JsonNode windowed = admin(HttpMethod.GET, "/api/workspaces/" + id + "/clients?from=2026-09-25&to=2026-09-30", null);
        assertEquals(10, windowed.get("clients_total").asInt(),
                "three clients had gone quiet by the last week: " + windowed.get("clients"));

        JsonNode explore = admin(HttpMethod.GET, "/api/workspaces/" + id + "/explore", null);
        assertEquals(9, explore.get("stats").get("operations").asInt());
        JsonNode drift = admin(HttpMethod.GET, "/api/workspaces/" + id + "/drift", null);
        assertEquals(24, drift.get("drift").size());
    }

    @Test
    void keysAreSeparateAndRequired() throws Exception {
        assertEquals(HttpStatus.UNAUTHORIZED, call(HttpMethod.POST, "/api/ingest", events.get(0), "X-Axon-Key", "nope").getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, call(HttpMethod.POST, "/api/ingest", events.get(0), null, null).getStatusCode());
        // the ingest key cannot read, the admin key cannot ingest
        assertEquals(HttpStatus.UNAUTHORIZED, call(HttpMethod.GET, "/api/workspaces/" + id + "/contract", null, "X-Axon-Admin", ingestKey).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, call(HttpMethod.POST, "/api/ingest", events.get(0), "X-Axon-Key", adminKey).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, call(HttpMethod.GET, "/api/workspaces/" + UUID.randomUUID(), null, "X-Axon-Admin", adminKey).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, call(HttpMethod.GET, "/api/workspaces/" + id + "/clients?from=yesterday", null, "X-Axon-Admin", adminKey).getStatusCode());
        // a form content type (curl's default) would have its body eaten as parameters: refuse it with a reason
        HttpHeaders form = new HttpHeaders();
        form.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        form.set("X-Axon-Key", ingestKey);
        ResponseEntity<String> refused = http.exchange("/api/ingest", HttpMethod.POST, new HttpEntity<>(events.get(0), form), String.class);
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, refused.getStatusCode());
        assertTrue(refused.getBody().contains("text/plain"), refused.getBody());
    }

    @Test
    void limitsAreEnforcedAndRawValuesNeverStored() throws Exception {
        String[] w = newWorkspace("limits");
        // an event carrying extra keys: accepted, but only the schema's fields are kept
        String leaky = events.get(1).replaceFirst("\\{", "{\"raw_body\":\"alice@example.com\",");
        String bad = "{\"v\":1,\"method\":\"GET\"}";
        ResponseEntity<String> r = call(HttpMethod.POST, "/api/ingest", leaky + "\n" + bad + "\nnot json", "X-Axon-Key", w[1]);
        JsonNode body = JSON.readTree(r.getBody());
        assertEquals(1, body.get("accepted").asInt());
        assertEquals(2, body.get("rejected").asInt());
        assertEquals(2, body.get("errors").size());
        List<Store.StoredEvent> stored = store.events(UUID.fromString(w[0]), 0, 10);
        assertEquals(1, stored.size());
        assertFalse(stored.get(0).payload().contains("alice"), stored.get(0).payload());

        // more events in one request than allowed
        String many = String.join("\n", Collections.nCopies(1001, events.get(1)));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, call(HttpMethod.POST, "/api/ingest", many, "X-Axon-Key", w[1]).getStatusCode());
        // a body over the byte limit
        String huge = "x".repeat(2 * 1024 * 1024 + 10);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, call(HttpMethod.POST, "/api/ingest", huge, "X-Axon-Key", w[1]).getStatusCode());
        // the workspace row cap (30,000 in this test)
        String thousand = String.join("\n", Collections.nCopies(1000, events.get(1)));
        HttpStatus last = HttpStatus.OK;
        for (int i = 0; i < 31 && last == HttpStatus.OK; i++) {
            last = HttpStatus.valueOf(call(HttpMethod.POST, "/api/ingest", thousand, "X-Axon-Key", w[1]).getStatusCode().value());
        }
        assertEquals(HttpStatus.INSUFFICIENT_STORAGE, last);
        assertTrue(store.eventCount(UUID.fromString(w[0])) <= 30_000);

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, call(HttpMethod.PUT, "/api/workspaces/" + w[0] + "/spec",
                "{\"swagger\":\"2.0\"}", "X-Axon-Admin", w[2]).getStatusCode());
    }

    @Test
    void statelessDiffAndExploreNeedNoKey() throws Exception {
        String v1 = Files.readString(DEMO.resolve("recipes-v1.yaml"));
        String v2 = Files.readString(DEMO.resolve("recipes-v2.yaml"));
        String body = JSON.writeValueAsString(JSON.createObjectNode().put("baseline", v1).put("candidate", v2));
        JsonNode diff = JSON.readTree(call(HttpMethod.POST, "/api/diff", body, null, null).getBody());
        assertEquals(24, diff.get("changes_total").asInt());
        assertEquals(17, diff.get("breaking_total").asInt());
        JsonNode explore = JSON.readTree(call(HttpMethod.POST, "/api/explore", v1, null, null).getBody());
        assertEquals("Recipes API (Axon demo service)", explore.get("title").asText());
        JsonNode one = JSON.readTree(call(HttpMethod.POST, "/api/explore?method=get&path=/recipes/featured", v1, null, null).getBody());
        assertEquals(1, one.get("responses").size());
        assertEquals("ok", JSON.readTree(http.getForObject("/api/health", String.class)).get("status").asText());
    }
}
