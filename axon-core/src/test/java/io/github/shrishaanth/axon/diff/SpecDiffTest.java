package io.github.shrishaanth.axon.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.shrishaanth.axon.spec.SpecParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpecDiffTest {

    private static final String BASE = """
            openapi: 3.0.3
            info: { title: Users, version: '1' }
            paths:
              /users:
                get:
                  parameters:
                    - { name: limit, in: query, schema: { type: integer } }
                    - { name: sort, in: query, schema: { type: string, enum: [asc, desc] } }
                  responses:
                    '200':
                      description: ok
                      content:
                        application/json:
                          schema:
                            type: array
                            items: { $ref: '#/components/schemas/User' }
                post:
                  requestBody:
                    content:
                      application/json:
                        schema: { $ref: '#/components/schemas/NewUser' }
                  responses:
                    '201':
                      description: created
                      content:
                        application/json:
                          schema: { $ref: '#/components/schemas/User' }
                    '409':
                      description: conflict
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
                NewUser:
                  type: object
                  required: [email]
                  properties:
                    email: { type: string }
                    phone: { type: string }
                    age: { type: number }
                    plan: { type: string, enum: [free, pro, team] }
                User:
                  type: object
                  required: [id, email]
                  properties:
                    id: { type: integer }
                    email: { type: string }
                    legacy_id: { type: string }
                    score: { type: integer }
                    status: { type: string, enum: [active, blocked] }
                    manager: { $ref: '#/components/schemas/User' }
            """;

    private static SpecDiff.Result diff(String candidate) throws Exception {
        return SpecDiff.diff(SpecParser.parse(BASE), SpecParser.parse(candidate));
    }

    private static Change only(SpecDiff.Result r, String operation) {
        List<Change> matches = r.changes().stream().filter(c -> c.operationKey().equals(operation)).toList();
        assertEquals(1, matches.size(), () -> "changes for " + operation + ": " + matches);
        return matches.get(0);
    }

    @Test
    void identicalSpecsHaveNoChanges() throws Exception {
        SpecDiff.Result r = diff(BASE);
        assertTrue(r.changes().isEmpty(), r.changes().toString());
        assertFalse(r.truncated());
    }

    @Test
    void removedAndAddedOperations() throws Exception {
        SpecDiff.Result r = diff(BASE
                .replace("    delete:\n      parameters:\n        - { name: id, in: path, required: true, "
                        + "schema: { type: integer } }\n      responses:\n        '204': { description: gone }\n", "")
                .replace("  /users/{id}:", "  /health:\n    get:\n      responses:\n        '200': "
                        + "{ description: ok }\n  /users/{id}:"));
        Change removed = only(r, "DELETE /users/{id}");
        assertEquals(ChangeKind.OPERATION_REMOVED, removed.kind());
        assertTrue(removed.breaking());
        Change added = only(r, "GET /health");
        assertEquals(ChangeKind.OPERATION_ADDED, added.kind());
        assertFalse(added.breaking());
    }

    @Test
    void renamingAPathParameterIsNotARemoval() throws Exception {
        SpecDiff.Result r = diff(BASE.replace("/users/{id}", "/users/{userId}").replace("name: id,", "name: userId,"));
        assertTrue(r.changes().isEmpty(), r.changes().toString());
    }

    @Test
    void requestFieldRemovedIsBreaking() throws Exception {
        SpecDiff.Result r = diff(BASE.replace("        phone: { type: string }\n", ""));
        Change c = only(r, "POST /users");
        assertEquals(ChangeKind.REQUEST_FIELD_REMOVED, c.kind());
        assertEquals("$.phone", c.field());
        assertEquals("request.body", c.part());
        assertEquals("/components/schemas/NewUser", c.source());
        assertTrue(c.breaking());
    }

    @Test
    void requestFieldAddedRequiredVersusOptional() throws Exception {
        Change optional = only(diff(BASE.replace("        phone: { type: string }\n",
                "        phone: { type: string }\n        nick: { type: string }\n")), "POST /users");
        assertEquals(ChangeKind.REQUEST_FIELD_ADDED_OPTIONAL, optional.kind());
        assertFalse(optional.breaking());

        Change required = only(diff(BASE.replace("      required: [email]\n", "      required: [email, nick]\n")
                .replace("        phone: { type: string }\n",
                        "        phone: { type: string }\n        nick: { type: string }\n")), "POST /users");
        assertEquals(ChangeKind.REQUEST_FIELD_ADDED_REQUIRED, required.kind());
        assertTrue(required.breaking());
    }

    @Test
    void requiredFlagChangesDependOnDirection() throws Exception {
        Change req = only(diff(BASE.replace("      required: [email]\n", "      required: [email, phone]\n")),
                "POST /users");
        assertEquals(ChangeKind.REQUEST_FIELD_MADE_REQUIRED, req.kind());
        assertTrue(req.breaking());

        SpecDiff.Result r = diff(BASE.replace("      required: [id, email]\n", "      required: [id]\n"));
        assertTrue(r.changes().stream().allMatch(c -> c.kind() == ChangeKind.RESPONSE_FIELD_MADE_OPTIONAL
                && c.breaking()), r.changes().toString());
        Change res = only(r, "GET /users");
        assertEquals("$[].email", res.field());
    }

    @Test
    void typeChangesFollowAcceptance() throws Exception {
        // request: number -> integer rejects 1.5, breaking
        Change narrowed = only(diff(BASE.replace("age: { type: number }", "age: { type: integer }")), "POST /users");
        assertEquals(ChangeKind.REQUEST_FIELD_TYPE_CHANGED, narrowed.kind());
        assertTrue(narrowed.breaking());
        // request: making a field nullable accepts more, safe
        Change widened = only(diff(BASE.replace("phone: { type: string }",
                "phone: { type: string, nullable: true }")), "POST /users");
        assertFalse(widened.breaking());

        // response: integer -> number may now send 1.5, breaking
        SpecDiff.Result response = diff(BASE.replace("score: { type: integer }", "score: { type: number }"));
        assertTrue(response.changes().stream().allMatch(
                c -> c.kind() == ChangeKind.RESPONSE_FIELD_TYPE_CHANGED && c.breaking()));
        assertFalse(response.changes().isEmpty());
        // response: becoming nullable is breaking
        assertTrue(diff(BASE.replace("legacy_id: { type: string }", "legacy_id: { type: string, nullable: true }"))
                .changes().stream().allMatch(Change::breaking));
    }

    @Test
    void enumChangesDependOnDirection() throws Exception {
        Change narrowed = only(diff(BASE.replace("enum: [free, pro, team]", "enum: [free, pro]")), "POST /users");
        assertEquals(ChangeKind.REQUEST_ENUM_NARROWED, narrowed.kind());
        assertTrue(narrowed.breaking());
        Change widened = only(diff(BASE.replace("enum: [free, pro, team]", "enum: [free, pro, team, max]")),
                "POST /users");
        assertEquals(ChangeKind.REQUEST_ENUM_WIDENED, widened.kind());
        assertFalse(widened.breaking());

        SpecDiff.Result more = diff(BASE.replace("enum: [active, blocked]", "enum: [active, blocked, pending]"));
        assertTrue(more.changes().stream().allMatch(
                c -> c.kind() == ChangeKind.RESPONSE_ENUM_WIDENED && c.breaking()));
        SpecDiff.Result fewer = diff(BASE.replace("enum: [active, blocked]", "enum: [active]"));
        assertTrue(fewer.changes().stream().allMatch(
                c -> c.kind() == ChangeKind.RESPONSE_ENUM_NARROWED && !c.breaking()));

        Change param = only(diff(BASE.replace("enum: [asc, desc]", "enum: [asc]")), "GET /users");
        assertEquals(ChangeKind.REQUEST_ENUM_NARROWED, param.kind());
        assertEquals("request.query", param.part());
        assertEquals("$.sort", param.field());
    }

    @Test
    void sharedSchemaChangeIsReportedPerOperationAndOncePerRecursion() throws Exception {
        SpecDiff.Result r = diff(BASE.replace("        legacy_id: { type: string }\n", ""));
        // User is the response of GET /users, POST /users and GET /users/{id}
        assertEquals(List.of("GET /users", "POST /users", "GET /users/{id}"),
                r.changes().stream().map(Change::operationKey).toList());
        assertEquals(List.of("$[].legacy_id", "$.legacy_id", "$.legacy_id"),
                r.changes().stream().map(Change::field).toList(),
                "User.manager is User again; the recursion is reported once, not as $.manager.legacy_id");
        assertTrue(r.changes().stream().allMatch(c -> c.kind() == ChangeKind.RESPONSE_FIELD_REMOVED
                && c.breaking() && c.source().equals("/components/schemas/User") && c.status() != null));
    }

    @Test
    void mutuallyRecursiveSchemasAreReportedFromEveryEntryPoint() throws Exception {
        String spec = """
                openapi: 3.0.3
                info: { title: t, version: '1' }
                paths:
                  /a: { get: { responses: { '200': { description: ok, content: { application/json: { schema: { $ref: '#/components/schemas/A' } } } } } } }
                  /b: { get: { responses: { '200': { description: ok, content: { application/json: { schema: { $ref: '#/components/schemas/B' } } } } } } }
                components:
                  schemas:
                    A: { type: object, properties: { x: { type: string }, b: { $ref: '#/components/schemas/B' }, bs: { type: array, items: { $ref: '#/components/schemas/B' } } } }
                    B: { type: object, properties: { y: { type: string }, a: { $ref: '#/components/schemas/A' } } }
                """;
        SpecDiff.Result r = SpecDiff.diff(SpecParser.parse(spec),
                SpecParser.parse(spec.replace("y: { type: string }, ", "")));
        // from /a, B is reached by two paths and both are reported; B's own cycle back through A is not repeated
        assertEquals(List.of("GET /a $.b.y", "GET /a $.bs[].y", "GET /b $.y"),
                r.changes().stream().map(c -> c.operationKey() + " " + c.field()).toList());
        assertFalse(r.truncated());
    }

    @Test
    void responseFieldAddedIsSafe() throws Exception {
        SpecDiff.Result r = diff(BASE.replace("        legacy_id: { type: string }\n",
                "        legacy_id: { type: string }\n        created_at: { type: string }\n"));
        assertEquals(3, r.changes().size());
        assertTrue(r.changes().stream().allMatch(c -> c.kind() == ChangeKind.RESPONSE_FIELD_ADDED && !c.breaking()));
    }

    @Test
    void statusCodesAndParameters() throws Exception {
        Change status = only(diff(BASE.replace("        '409':\n          description: conflict\n", "")),
                "POST /users");
        assertEquals(ChangeKind.RESPONSE_STATUS_REMOVED, status.kind());
        assertEquals("409", status.status());
        assertTrue(status.breaking());

        Change removed = only(diff(BASE.replace(
                "        - { name: limit, in: query, schema: { type: integer } }\n", "")), "GET /users");
        assertEquals(ChangeKind.REQUEST_FIELD_REMOVED, removed.kind());
        assertEquals("request.query", removed.part());
        assertEquals("$.limit", removed.field());

        Change required = only(diff(BASE.replace("{ name: limit, in: query, schema: { type: integer } }",
                "{ name: limit, in: query, required: true, schema: { type: integer } }")), "GET /users");
        assertEquals(ChangeKind.REQUEST_FIELD_MADE_REQUIRED, required.kind());
        assertTrue(required.breaking());

        Change added = only(diff(BASE.replace("        - { name: limit, in: query, schema: { type: integer } }\n",
                "        - { name: limit, in: query, schema: { type: integer } }\n"
                        + "        - { name: tenant, in: header, required: true, schema: { type: string } }\n")),
                "GET /users");
        assertEquals(ChangeKind.REQUEST_FIELD_ADDED_REQUIRED, added.kind());
        assertEquals("request.header", added.part());
    }

    @Test
    void requestBodyAndMediaTypes() throws Exception {
        Change required = only(diff(BASE.replace("      requestBody:\n        content:",
                "      requestBody:\n        required: true\n        content:")), "POST /users");
        assertEquals(ChangeKind.REQUEST_BODY_MADE_REQUIRED, required.kind());
        assertTrue(required.breaking());

        SpecDiff.Result media = diff(BASE.replace("      requestBody:\n        content:\n          application/json:",
                "      requestBody:\n        content:\n          application/xml:"));
        assertEquals(List.of(ChangeKind.MEDIA_TYPE_REMOVED, ChangeKind.MEDIA_TYPE_ADDED),
                media.changes().stream().map(Change::kind).toList());
        assertEquals(List.of(true, false), media.changes().stream().map(Change::breaking).toList());
    }
}
