package io.github.shrishaanth.axon.impact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.diff.ChangeKind;
import io.github.shrishaanth.axon.diff.SpecDiff;
import io.github.shrishaanth.axon.drift.DriftEngine;
import io.github.shrishaanth.axon.drift.DriftFinding;
import io.github.shrishaanth.axon.impact.ImpactReport.Evidence;
import io.github.shrishaanth.axon.impact.ImpactReport.Row;
import io.github.shrishaanth.axon.observe.Aggregate;
import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.SpecParser;
import io.github.shrishaanth.axon.traffic.Sanitiser;
import io.github.shrishaanth.axon.traffic.TrafficEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImpactEngineTest {

    private static final String V1 = """
            openapi: 3.0.3
            info: { title: Users, version: '1' }
            paths:
              /users:
                post:
                  parameters:
                    - { name: dry_run, in: query, schema: { type: boolean } }
                  requestBody:
                    content:
                      application/json:
                        schema:
                          type: object
                          required: [email]
                          properties:
                            email: { type: string }
                            phone: { type: string }
                            age: { type: number }
                            plan: { type: string, enum: [free, pro, team] }
                  responses:
                    '201':
                      description: created
                      content:
                        application/json:
                          schema: { $ref: '#/components/schemas/User' }
                    '400': { description: bad }
              /users/{id}:
                get:
                  parameters:
                    - { name: id, in: path, required: true, schema: { type: integer } }
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema: { $ref: '#/components/schemas/User' }
                delete:
                  parameters:
                    - { name: id, in: path, required: true, schema: { type: integer } }
                  responses:
                    '204': { description: gone }
            components:
              schemas:
                User:
                  type: object
                  required: [id, email]
                  properties:
                    id: { type: integer }
                    email: { type: string }
                    legacy_id: { type: string }
                    unused_note: { type: string }
            """;

    /** v2 removes phone, requires age (as integer), drops plan "team", removes legacy_id and DELETE. */
    private static final String V2 = V1
            .replace("                phone: { type: string }\n", "")
            .replace("required: [email]", "required: [email, age]")
            .replace("age: { type: number }", "age: { type: integer }")
            .replace("enum: [free, pro, team]", "enum: [free, pro]")
            .replace("        legacy_id: { type: string }\n", "")
            .replace("    delete:\n      parameters:\n        - { name: id, in: path, required: true, "
                    + "schema: { type: integer } }\n      responses:\n        '204': { description: gone }\n", "");

    private static final Instant END = Instant.parse("2026-09-30T12:00:00Z");

    private final ApiSpec v1;
    private final Sanitiser sanitiser;
    private final List<TrafficEvent> events = new ArrayList<>();

    ImpactEngineTest() throws Exception {
        v1 = SpecParser.parse(V1);
        sanitiser = new Sanitiser(new Sanitiser.Config("header", "X-Client", "s"), v1);
    }

    private static String id(String client) {
        return Sanitiser.pseudonym("s", client);
    }

    private void call(String client, int daysAgo, String method, String url, String body, int status,
                      String responseBody) {
        events.add(sanitiser.sanitise(new Sanitiser.RawExchange(END.minusSeconds(daysAgo * 86_400L), method, url,
                client == null ? Map.of() : Map.of("X-Client", client), body == null ? null : "application/json",
                body, status, responseBody == null ? null : "application/json", responseBody, 5.0)));
    }

    /**
     * web: recent, sends phone and plan=pro. mobile: recent, no phone, plan=team, fractional age.
     * batch: last seen 20 days ago, sends phone. legacy: last seen 45 days ago, calls DELETE and GET.
     * reader: recent, only GETs.
     */
    private void traffic() {
        String user = "{\"id\":1,\"email\":\"a@b.c\",\"legacy_id\":\"L1\"}";
        for (int day = 0; day < 5; day++) {
            call("web", day, "POST", "/users", "{\"email\":\"a@b.c\",\"phone\":\"1\",\"age\":30,\"plan\":\"pro\"}",
                    201, user);
            call("mobile", day, "POST", "/users?dry_run=true", "{\"email\":\"a@b.c\",\"age\":30.5,\"plan\":\"team\"}",
                    201, user);
            call("reader", day, "GET", "/users/7", null, 200, user);
        }
        call("mobile", 1, "POST", "/users", "{\"email\":\"a@b.c\"}", 400, null);
        call("batch", 20, "POST", "/users", "{\"email\":\"a@b.c\",\"phone\":\"2\",\"age\":41,\"plan\":\"free\"}", 201,
                user);
        call("legacy", 45, "DELETE", "/users/9", null, 204, null);
        call("legacy", 45, "GET", "/users/9", null, 200, user);
    }

    private ImpactReport analyse(ImpactConfig config) throws Exception {
        Aggregate aggregate = new Aggregate(v1, null, null);
        events.forEach(aggregate::add);
        return ImpactEngine.analyse(SpecDiff.diff(v1, SpecParser.parse(V2)), aggregate, config);
    }

    private static Row row(ImpactReport r, ChangeKind kind, String operation) {
        List<Row> rows = r.rows().stream()
                .filter(x -> x.change().kind() == kind && x.change().operationKey().equals(operation)).toList();
        assertEquals(1, rows.size(), kind + " " + operation + ": " + rows);
        return rows.get(0);
    }

    @Test
    void requestFieldRemovalListsTheClientsThatSentIt() throws Exception {
        traffic();
        ImpactReport r = analyse(ImpactConfig.defaults());
        Row phone = row(r, ChangeKind.REQUEST_FIELD_REMOVED, "POST /users");
        assertEquals(Evidence.OBSERVED, phone.evidence());
        assertEquals(Set.of(id("web"), id("batch")), phone.exposure().clientIds());
        assertEquals(6, phone.exposure().requests());
        assertEquals(1, phone.exposure().recent(), "batch was last seen 20 days ago");
        assertEquals(2, phone.exposure().stale());
        assertEquals(3, r.activeClients(), "web, mobile and reader were seen in the last 7 days");
        assertEquals(1.0 / 3, phone.exposure().shareRecent(), 1e-9);
        assertEquals(Severity.HIGH, phone.severity(), "one of three active clients is over the 10% share");
    }

    @Test
    void newlyRequiredFieldListsTheClientsThatOmitIt() throws Exception {
        traffic();
        Row age = row(analyse(ImpactConfig.defaults()), ChangeKind.REQUEST_FIELD_MADE_REQUIRED, "POST /users");
        assertEquals(Evidence.OBSERVED, age.evidence());
        assertEquals(Set.of(id("mobile")), age.exposure().clientIds(), "only mobile sent a body without age");
        assertEquals(1, age.exposure().requests());
        assertNotNull(age.note());
    }

    @Test
    void typeNarrowingListsTheClientsThatSentARejectedType() throws Exception {
        traffic();
        Row age = row(analyse(ImpactConfig.defaults()), ChangeKind.REQUEST_FIELD_TYPE_CHANGED, "POST /users");
        assertEquals(Evidence.OBSERVED, age.evidence());
        assertEquals(Set.of(id("mobile")), age.exposure().clientIds(), "30.5 is not an integer; 30 and 41 are");
        assertEquals(5, age.exposure().requests());
    }

    @Test
    void enumNarrowingListsTheClientsThatSentARemovedValue() throws Exception {
        traffic();
        Row plan = row(analyse(ImpactConfig.defaults()), ChangeKind.REQUEST_ENUM_NARROWED, "POST /users");
        assertEquals(Evidence.OBSERVED, plan.evidence());
        assertEquals(Set.of(id("mobile")), plan.exposure().clientIds());
        assertNull(plan.note());
    }

    @Test
    void removedOperationAndRecencyLadder() throws Exception {
        traffic();
        ImpactReport r = analyse(ImpactConfig.defaults());
        Row delete = row(r, ChangeKind.OPERATION_REMOVED, "DELETE /users/{id}");
        assertEquals(Evidence.OBSERVED, delete.evidence());
        assertEquals(Set.of(id("legacy")), delete.exposure().clientIds());
        assertEquals(Severity.DORMANT, delete.severity(), "its only caller was last seen 45 days ago");

        ImpactReport critical = analyse(ImpactConfig.defaults().withCriticalClients(Set.of(id("batch"))));
        assertEquals(Severity.HIGH, row(critical, ChangeKind.REQUEST_FIELD_REMOVED, "POST /users").severity());
        // a critical client that is itself dormant does not raise severity
        assertEquals(Severity.DORMANT, row(analyse(ImpactConfig.defaults().withCriticalClients(Set.of(id("legacy")))),
                ChangeKind.OPERATION_REMOVED, "DELETE /users/{id}").severity());
    }

    @Test
    void responseFieldRemovalIsPotentialAndScopedToTheStatus() throws Exception {
        traffic();
        ImpactReport r = analyse(ImpactConfig.defaults());
        Row post = row(r, ChangeKind.RESPONSE_FIELD_REMOVED, "POST /users");
        assertEquals(Evidence.POTENTIAL, post.evidence());
        assertEquals(Set.of(id("web"), id("mobile"), id("batch")), post.exposure().clientIds());
        assertEquals(11, post.exposure().requests(), "the 400 response is not a 201");
        Row get = row(r, ChangeKind.RESPONSE_FIELD_REMOVED, "GET /users/{id}");
        assertEquals(Set.of(id("reader"), id("legacy")), get.exposure().clientIds());
        assertEquals(2, post.operationsTouched(), "one edit to User, seen from two operations");
    }

    @Test
    void ranksObservedSeverityFirstAndBaselineByOperationsTouched() throws Exception {
        traffic();
        ImpactReport r = analyse(ImpactConfig.defaults());
        List<Row> breaking = r.breaking();
        assertEquals(7, breaking.size());
        Row first = breaking.stream().filter(x -> x.rank() == 1).findFirst().orElseThrow();
        assertEquals(Evidence.POTENTIAL, first.evidence(), "POST 201 callers are 2 of 3 active clients: CRITICAL");
        assertEquals(Severity.CRITICAL, first.severity());
        Row delete = row(r, ChangeKind.OPERATION_REMOVED, "DELETE /users/{id}");
        assertEquals(7, delete.rank(), "the dormant change is last");
        List<Row> baselineTop = breaking.stream().filter(x -> x.specOnlyRank() <= 2).toList();
        assertTrue(baselineTop.stream().allMatch(x -> x.change().kind() == ChangeKind.RESPONSE_FIELD_REMOVED),
                "the spec-only baseline puts the edit touching two operations first");
        assertTrue(r.rows().stream().filter(x -> !x.change().breaking())
                .allMatch(x -> x.rank() == null && x.severity() == null && x.evidence() == Evidence.NONE));
    }

    @Test
    void severityNeverDropsWhenAClientIsAddedOrMoreRecent() {
        ImpactConfig c = ImpactConfig.defaults();
        for (int active = 1; active <= 40; active++) {
            for (int affected = 0; affected <= active; affected++) {
                for (int stale = 0; stale <= affected; stale++) {
                    for (int recent = 0; recent <= stale; recent++) {
                        Severity now = Severity.decide(true, affected, recent, stale, active, false, c);
                        if (affected < active) {
                            assertTrue(Severity.decide(true, affected + 1, recent + 1, stale + 1, active, false, c)
                                    .ordinal() <= now.ordinal());
                        }
                        if (recent < stale) {
                            assertTrue(Severity.decide(true, affected, recent + 1, stale, active, false, c)
                                    .ordinal() <= now.ordinal());
                        }
                    }
                }
            }
        }
        assertEquals(Severity.MEDIUM, Severity.decide(true, 1, 1, 1, 5000, false, c),
                "one client seen recently is MEDIUM however small its share");
        assertEquals(Severity.UNRATED, Severity.decide(false, 0, 0, 0, 0, false, c));
        assertEquals(Severity.NONE_OBSERVED, Severity.decide(true, 0, 0, 0, 10, false, c));
    }

    @Test
    void withoutIdentityOnlyVolumeIsReported() throws Exception {
        String user = "{\"id\":1,\"email\":\"a@b.c\"}";
        call(null, 1, "POST", "/users", "{\"email\":\"a@b.c\",\"phone\":\"1\"}", 201, user);
        call(null, 2, "POST", "/users", "{\"email\":\"a@b.c\",\"phone\":\"1\"}", 201, user);
        ImpactReport r = analyse(ImpactConfig.defaults());
        assertFalse(r.hasIdentity());
        Row phone = row(r, ChangeKind.REQUEST_FIELD_REMOVED, "POST /users");
        assertEquals(Severity.UNRATED, phone.severity());
        assertNull(phone.exposure().clients());
        assertEquals(2, phone.exposure().requests());
        assertTrue(r.limitations().stream().anyMatch(l -> l.contains("No client identity")));
    }

    @Test
    void aggregationDoesNotDependOnEventOrder() {
        traffic();
        Aggregate inOrder = new Aggregate(v1, null, null);
        events.forEach(inOrder::add);
        List<TrafficEvent> shuffled = new ArrayList<>(events);
        Collections.shuffle(shuffled, new Random(7));
        Aggregate reordered = new Aggregate(v1, null, null);
        shuffled.forEach(reordered::add);
        assertEquals(inOrder, reordered);
        // and the JSONL form loses nothing the aggregate needs
        Aggregate replayed = new Aggregate(v1, null, null);
        events.forEach(e -> replayed.add(TrafficEvent.fromJson(e.toJson())));
        assertEquals(inOrder, replayed);
    }

    @Test
    void windowDropsEventsOutsideIt() {
        traffic();
        Aggregate a = new Aggregate(v1, END.minusSeconds(10 * 86_400L), END);
        events.forEach(a::add);
        assertEquals(3, a.outsideWindow(), "batch (20 days) and legacy's two calls (45 days)");
        assertFalse(a.clients().containsKey(id("legacy")));
    }

    @Test
    void driftFindsWhatTheSpecDoesNotSay() throws Exception {
        traffic();
        String user = "{\"id\":\"seven\",\"email\":\"a@b.c\",\"internal_flag\":true}";
        for (int i = 0; i < 300; i++) {
            call("reader", 0, "GET", "/users/7?expand=all", null, 200, user);
        }
        call("reader", 0, "GET", "/users/7", null, 503, null);
        call("reader", 0, "GET", "/users/7", null, 404, null);
        call("reader", 0, "GET", "/reports/2026", null, 200, null);
        Aggregate aggregate = new Aggregate(v1, null, null);
        events.forEach(aggregate::add);
        List<DriftFinding> drift = DriftEngine.analyse(aggregate, ImpactConfig.defaults());

        assertTrue(has(drift, DriftFinding.UNDOCUMENTED_FIELD, "GET /users/{id}", "$.internal_flag", "warning"));
        assertTrue(has(drift, DriftFinding.UNDOCUMENTED_FIELD, "GET /users/{id}", "$.expand", "warning"));
        assertTrue(has(drift, DriftFinding.TYPE_MISMATCH, "GET /users/{id}", "$.id", "warning"));
        assertTrue(has(drift, DriftFinding.UNUSED_FIELD, "GET /users/{id}", "$.unused_note", "info"));
        assertFalse(has(drift, DriftFinding.UNUSED_FIELD, "POST /users", "$.unused_note", "info"),
                "eleven responses are too few to call a field unused");
        assertTrue(drift.stream().anyMatch(d -> d.kind().equals(DriftFinding.UNDOCUMENTED_STATUS)
                && d.status().equals("503") && d.severity().equals("info")), "5xx is informational by default");
        assertTrue(drift.stream().anyMatch(d -> d.kind().equals(DriftFinding.UNDOCUMENTED_STATUS)
                && d.status().equals("404") && d.severity().equals("warning")));
        assertTrue(drift.stream().anyMatch(d -> d.kind().equals(DriftFinding.UNDOCUMENTED_OPERATION)
                && d.path().equals("GET /reports/{*}")));
        assertTrue(DriftEngine.analyse(aggregate, ImpactConfig.defaults().withServerErrors("drift")).stream()
                .anyMatch(d -> "503".equals(d.status()) && d.severity().equals("warning")));

        Aggregate quiet = new Aggregate(v1, null, null);
        assertTrue(DriftEngine.analyse(quiet, ImpactConfig.defaults()).stream()
                .allMatch(d -> d.kind().equals(DriftFinding.UNUSED_OPERATION)));
    }

    private static boolean has(List<DriftFinding> drift, String kind, String operation, String field,
                               String severity) {
        return drift.stream().anyMatch(d -> d.kind().equals(kind) && operation.equals(d.operationKey())
                && field.equals(d.field()) && d.severity().equals(severity));
    }

    @Test
    void reportJsonSeparatesObservedFromPotential() throws Exception {
        traffic();
        ImpactReport r = analyse(ImpactConfig.defaults());
        ApiSpec v2 = SpecParser.parse(V2);
        ObjectNode json = ReportWriter.write(r, List.of(), new ReportWriter.SpecRef("v1.yaml", "0".repeat(64), v1),
                new ReportWriter.SpecRef("v2.yaml", "1".repeat(64), v2), "jsonl", Instant.parse("2026-10-01T00:00:00Z"));
        for (var change : json.get("changes")) {
            String evidence = change.get("evidence").asText();
            assertEquals(evidence.equals("observed"), change.has("observed_affected"));
            assertEquals(evidence.equals("potential"), change.has("potentially_affected"));
        }
        assertEquals(7, json.get("summary").get("breaking_total").asInt());
        assertTrue(json.get("summary").get("by_severity").has("observed"));
        assertTrue(json.get("summary").get("by_severity").has("potential"));
        assertFalse(json.toString().contains("a@b.c"));
    }
}
