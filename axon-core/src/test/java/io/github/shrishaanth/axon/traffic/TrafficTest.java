package io.github.shrishaanth.axon.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.JsonType;
import io.github.shrishaanth.axon.spec.Operation;
import io.github.shrishaanth.axon.spec.SpecParser;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TrafficTest {

    private static final String SPEC = """
            openapi: 3.0.3
            info: { title: t, version: '1' }
            servers:
              - url: https://api.example.com/v1
            paths:
              /: { get: { responses: { '200': { description: ok } } } }
              /users: { get: { responses: { '200': { description: ok } } } }
              /users/me: { get: { responses: { '200': { description: ok } } } }
              /users/{id}:
                get: { responses: { '200': { description: ok } } }
                delete: { responses: { '204': { description: ok } } }
              /users/{id}/posts: { get: { responses: { '200': { description: ok } } } }
              /users/me/settings: { get: { responses: { '200': { description: ok } } } }
              /files/{name}.json: { get: { responses: { '200': { description: ok } } } }
              /files/{name}: { get: { responses: { '200': { description: ok } } } }
              /reports/{from}-{to}: { get: { responses: { '200': { description: ok } } } }
              /orders:
                post:
                  parameters:
                    - { name: mode, in: query, schema: { type: string, enum: [fast, slow] } }
                    - { name: note, in: query, schema: { type: string } }
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          properties:
                            email: { type: string }
                            plan: { type: string, enum: [free, pro] }
                            items:
                              type: array
                              items:
                                type: object
                                properties:
                                  sku: { type: string }
                                  qty: { type: integer }
                  responses:
                    '201':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: object
                            properties:
                              id: { type: integer }
            """;

    private static String key(Matcher m, String method, String path) {
        Operation op = m.match(method, path);
        return op == null ? null : op.key();
    }

    @Test
    void literalSegmentsBeatParameters() throws Exception {
        Matcher m = new Matcher(SpecParser.parse(SPEC));
        assertEquals("GET /users/me", key(m, "GET", "/users/me"));
        assertEquals("GET /users/{id}", key(m, "GET", "/users/42"));
        assertEquals("GET /users/{id}", key(m, "get", "/users/meow"), "a literal must match the whole segment");
    }

    @Test
    void backtracksWhenTheLiteralBranchDeadEnds() throws Exception {
        Matcher m = new Matcher(SpecParser.parse(SPEC));
        // "me" is a literal child of /users, but only /users/{id}/posts exists
        assertEquals("GET /users/{id}/posts", key(m, "GET", "/users/me/posts"));
        assertEquals("GET /users/me/settings", key(m, "GET", "/users/me/settings"));
        assertNull(key(m, "GET", "/users/42/settings"));
        // /users/me has no DELETE; the method lookup must fall back to the parameter template
        assertEquals("DELETE /users/{id}", key(m, "DELETE", "/users/me"));
    }

    @Test
    void handlesBasePathsTrailingSlashesQueriesAndRoot() throws Exception {
        Matcher m = new Matcher(SpecParser.parse(SPEC));
        assertEquals("GET /users", key(m, "GET", "/v1/users"));
        assertEquals("GET /users", key(m, "GET", "/users/"));
        assertEquals("GET /users", key(m, "GET", "/v1/users?limit=3#frag"));
        assertEquals("GET /", key(m, "GET", "/"));
        assertEquals("GET /", key(m, "GET", "/v1"));
        assertNull(key(m, "GET", "/v2/users"));
        assertNull(key(m, "POST", "/users"), "path matches but the method is not documented");
        assertNull(key(m, "GET", "/users//posts"), "an empty segment is not a parameter value");
    }

    @Test
    void mixedSegmentsSitBetweenLiteralsAndParameters() throws Exception {
        Matcher m = new Matcher(SpecParser.parse(SPEC));
        assertEquals("GET /files/{name}.json", key(m, "GET", "/files/report.json"));
        assertEquals("GET /files/{name}", key(m, "GET", "/files/report.csv"));
        assertEquals("GET /reports/{from}-{to}", key(m, "GET", "/reports/2024-2025"));
    }

    @Test
    void redactedSegmentsMatchOnlyParameters() throws Exception {
        Matcher m = new Matcher(SpecParser.parse(SPEC));
        assertEquals("GET /users/{id}", key(m, "GET", "/users/{*}"));
        assertEquals("GET /users/{id}/posts", key(m, "GET", "/users/{*}/posts"));
        assertNull(key(m, "GET", "/{*}"));
    }

    @Test
    void redactionReplacesValueLikeSegments() {
        assertEquals("/users/{*}/posts", Sanitiser.redact("/users/12345/posts"));
        assertEquals("/users/{*}", Sanitiser.redact("/users/3f2b8c1a-9d4e-4b6a-8f1e-2c3d4e5f6a7b"));
        assertEquals("/t/{*}", Sanitiser.redact("/t/deadbeef01"));
        assertEquals("/mail/{*}", Sanitiser.redact("/mail/alice@example.com"));
        assertEquals("/users/alice", Sanitiser.redact("/users/alice"),
                "known limit: a short word used as an id is not recognised");
        assertEquals("/", Sanitiser.redact("/"));
    }

    @Test
    void shapeKeepsPathsAndTypesAndOnlyEnumValues() throws Exception {
        ApiSpec spec = SpecParser.parse(SPEC);
        var schema = spec.operation("POST", "/orders").requestBody().jsonSchema();
        var body = new ObjectMapper().readTree("""
                {"email":"alice@example.com","plan":"pro","extra":null,"total":12.0,"ratio":0.5,
                 "items":[{"sku":"A1","qty":2},{"sku":"B2","qty":"three","gift":true}],"empty":[]}
                """);
        ShapeExtractor.Shape shape = ShapeExtractor.extract(body, schema);
        Map<String, TrafficEvent.Field> byPath = shape.fields().stream()
                .collect(Collectors.toMap(TrafficEvent.Field::path, f -> f));
        assertEquals(EnumSet.of(JsonType.OBJECT), byPath.get("$").types());
        assertEquals(EnumSet.of(JsonType.STRING), byPath.get("$.email").types());
        assertNull(byPath.get("$.email").values(), "free-form values are never kept");
        assertEquals(List.of("pro"), byPath.get("$.plan").values(), "declared enum values are kept");
        assertEquals(EnumSet.of(JsonType.NULL), byPath.get("$.extra").types());
        assertEquals(EnumSet.of(JsonType.INTEGER), byPath.get("$.total").types(), "12.0 has no fraction");
        assertEquals(EnumSet.of(JsonType.NUMBER), byPath.get("$.ratio").types());
        assertEquals(EnumSet.of(JsonType.OBJECT), byPath.get("$.items[]").types());
        assertEquals(EnumSet.of(JsonType.INTEGER, JsonType.STRING), byPath.get("$.items[].qty").types());
        assertNull(byPath.get("$.items[].qty").values());
        assertTrue(byPath.containsKey("$.items[].gift"));
        assertEquals(EnumSet.of(JsonType.ARRAY), byPath.get("$.empty").types());
        assertFalse(byPath.containsKey("$.empty[]"));
        assertFalse(shape.truncated());
        assertFalse(shape.fields().toString().contains("alice"), "no raw value may survive");
    }

    @Test
    void sanitiserMatchesLocallyHashesIdentityAndDropsValues() throws Exception {
        ApiSpec spec = SpecParser.parse(SPEC);
        Sanitiser sanitiser = new Sanitiser(new Sanitiser.Config("header", "X-Client-Id", "salt"), spec);
        TrafficEvent e = sanitiser.sanitise(new Sanitiser.RawExchange(Instant.parse("2026-09-30T08:15:02Z"), "post",
                "https://api.example.com/v1/orders?mode=fast&note=call%20me&mode=slow",
                Map.of("x-client-id", "acme-mobile", "Authorization", "Bearer secret-token"),
                "application/json; charset=utf-8", "{\"email\":\"alice@example.com\",\"plan\":\"free\"}", 201,
                "application/json", "{\"id\":7,\"token\":\"s3cr3t\"}", 12.5));
        assertEquals("POST /orders", e.operation());
        assertNull(e.path(), "with a spec the raw path never leaves the machine");
        assertEquals(Sanitiser.pseudonym("salt", "acme-mobile"), e.client());
        assertEquals(16, e.client().length());
        String json = e.toJson();
        for (String secret : List.of("alice", "acme-mobile", "secret-token", "s3cr3t", "call me")) {
            assertFalse(json.contains(secret), secret + " leaked into " + json);
        }
        assertTrue(json.contains("\"values\":[\"fast\",\"slow\"]"), json);
        assertTrue(json.contains("\"values\":[\"free\"]"), json);

        TrafficEvent back = TrafficEvent.fromJson(json);
        assertEquals(e, back, "the JSONL form round-trips");
    }

    @Test
    void sanitiserWithoutSpecRedactsThePathAndWithoutIdentityHasNoClient() {
        Sanitiser sanitiser = new Sanitiser(Sanitiser.Config.none(), null);
        TrafficEvent e = sanitiser.sanitise(new Sanitiser.RawExchange(Instant.parse("2026-09-30T08:15:02Z"), "GET",
                "/users/991/posts?page=2", Map.of("X-Client-Id", "acme"), null, null, 200,
                "text/html", "<html>hi</html>", null));
        assertNull(e.operation());
        assertEquals("/users/{*}/posts", e.path());
        assertNull(e.client());
        assertNull(e.responseBody(), "a non-JSON body is not analysed");
        assertEquals(1, e.query().size());
        assertNull(e.query().get(0).values());
        assertEquals(e, TrafficEvent.fromJson(e.toJson()));
    }

    @Test
    void rejectsMalformedEvents() {
        assertThrows(IllegalArgumentException.class, () -> TrafficEvent.fromJson("not json"));
        assertThrows(IllegalArgumentException.class, () -> TrafficEvent.fromJson("{\"v\":2}"));
        assertThrows(IllegalArgumentException.class, () -> TrafficEvent.fromJson(
                "{\"v\":1,\"ts\":\"2026-09-30T08:15:02Z\",\"method\":\"GET\",\"status\":200}"));
        assertNotNull(TrafficEvent.fromJson(
                "{\"v\":1,\"ts\":\"2026-09-30T08:15:02Z\",\"method\":\"GET\",\"path\":\"/x\",\"status\":200}"));
    }

    @Test
    void readsHarEntries() throws Exception {
        var har = new ObjectMapper().readTree("""
                {"log":{"entries":[
                  {"startedDateTime":"2026-09-30T10:00:00.000+02:00","time":31.5,
                   "request":{"method":"POST","url":"http://localhost:8080/orders",
                     "headers":[{"name":"X-Client-Id","value":"c1"}],
                     "postData":{"mimeType":"application/json","text":"{\\"email\\":\\"a@b.c\\"}"}},
                   "response":{"status":201,"content":{"mimeType":"application/json",
                     "text":"eyJpZCI6MX0=","encoding":"base64"}}},
                  {"request":{"method":"GET"},"response":{}}
                ]}}
                """);
        HarReader.Result r = HarReader.read(har);
        assertEquals(1, r.exchanges().size());
        assertEquals(1, r.skipped());
        Sanitiser.RawExchange x = r.exchanges().get(0);
        assertEquals(Instant.parse("2026-09-30T08:00:00Z"), x.ts());
        assertEquals("{\"id\":1}", x.responseBody());
        assertEquals("c1", x.requestHeaders().get("X-Client-Id"));
        assertEquals(31.5, x.latencyMs());
    }
}
