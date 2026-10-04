package io.github.shrishaanth.axon.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.shrishaanth.axon.spec.ShapeFlattener.Direction;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SpecParserTest {

    private static final String SPEC = """
            openapi: 3.0.3
            info:
              title: Recipes
              version: 2022-11-28
            servers:
              - url: https://api.example.com/v1/
            paths:
              /recipes/{id}:
                parameters:
                  - $ref: '#/components/parameters/Id'
                  - name: verbose
                    in: query
                    schema: { type: boolean }
                get:
                  operationId: getRecipe
                  parameters:
                    - name: verbose
                      in: query
                      required: true
                      schema: { type: string, enum: [yes, no] }
                  responses:
                    200:
                      description: ok
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/Recipe'
                    default:
                      $ref: '#/components/responses/Error'
                put:
                  requestBody:
                    required: true
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/Recipe'
                  callbacks:
                    onDone: {}
                  responses:
                    '204':
                      description: done
              /recipes:
                post:
                  requestBody:
                    content:
                      application/x-www-form-urlencoded:
                        schema:
                          type: object
                          properties:
                            title: { type: string }
                  responses:
                    '201':
                      description: created
                      content:
                        application/json:
                          schema:
                            $ref: 'other.yaml#/components/schemas/Thing'
            components:
              parameters:
                Id:
                  name: id
                  in: path
                  schema: { type: integer }
              responses:
                Error:
                  description: error
                  content:
                    application/problem+json:
                      schema:
                        type: object
                        properties:
                          message: { type: string }
              schemas:
                Base:
                  type: object
                  required: [id]
                  properties:
                    id: { type: integer, readOnly: true }
                Recipe:
                  allOf:
                    - $ref: '#/components/schemas/Base'
                    - type: object
                      required: [title]
                      properties:
                        title: { type: string }
                        note: { type: string, nullable: true }
                        secret: { type: string, writeOnly: true }
                        tags:
                          type: array
                          items: { type: string }
                        author:
                          oneOf:
                            - $ref: '#/components/schemas/User'
                            - $ref: '#/components/schemas/Bot'
                        related:
                          type: array
                          items:
                            $ref: '#/components/schemas/Recipe'
                        labels:
                          type: object
                          additionalProperties: { type: string }
                User:
                  type: object
                  required: [name, email]
                  properties:
                    name: { type: string }
                    email: { type: string }
                Bot:
                  type: object
                  required: [name]
                  properties:
                    name: { type: string }
                    version: { type: integer }
            """;

    @Test
    void readsInfoWithoutYamlCoercion() throws Exception {
        ApiSpec spec = SpecParser.parse(SPEC);
        assertEquals("Recipes", spec.title());
        assertEquals("2022-11-28", spec.version(), "a date-like version must stay a string");
        assertEquals("3.0.3", spec.openapi());
        assertEquals(List.of("/v1"), spec.basePaths());
        assertEquals(4, spec.componentSchemas());
        assertEquals(3, spec.operations().size());
    }

    @Test
    void mergesPathAndOperationParameters() throws Exception {
        Operation get = SpecParser.parse(SPEC).operation("GET", "/recipes/{id}");
        assertEquals("getRecipe", get.operationId());
        assertEquals(2, get.parameters().size());
        Parameter id = get.parameter("path", "id");
        assertTrue(id.required(), "path parameters are always required");
        assertEquals(EnumSet.of(JsonType.INTEGER), id.schema().types());
        Parameter verbose = get.parameter("query", "verbose");
        assertTrue(verbose.required(), "the operation-level parameter overrides the path-level one");
        assertEquals(List.of("\"yes\"", "\"no\""), verbose.schema().enumValues(),
                "yes/no must stay strings, not become booleans");
    }

    @Test
    void mergesAllOfAndResolvesRefsToOneNode() throws Exception {
        Operation get = SpecParser.parse(SPEC).operation("GET", "/recipes/{id}");
        Schema recipe = get.responses().get("200").body().jsonSchema();
        assertEquals(Set.of("id", "title"), recipe.required());
        assertTrue(recipe.properties().keySet().containsAll(Set.of("id", "title", "note", "tags", "author")));
        assertEquals(EnumSet.of(JsonType.OBJECT), recipe.types());
        assertTrue(recipe.properties().get("note").nullable());
        assertEquals(EnumSet.of(JsonType.STRING, JsonType.NULL), recipe.properties().get("note").types());
        assertSame(recipe, recipe.properties().get("related").items(), "a recursive $ref is the same node");
        assertEquals(EnumSet.of(JsonType.STRING), recipe.properties().get("labels").additionalSchema().types());
        assertTrue(recipe.properties().get("labels").declaresOpenMap());
    }

    @Test
    void approximatesOneOfAsUnion() throws Exception {
        ApiSpec spec = SpecParser.parse(SPEC);
        Schema author = spec.operation("GET", "/recipes/{id}").responses().get("200").body().jsonSchema()
                .properties().get("author");
        assertEquals(Set.of("name", "email", "version"), author.properties().keySet());
        assertEquals(Set.of("name"), author.required(), "required only if every variant requires it");
        assertTrue(spec.unsupported().stream().anyMatch(
                u -> u.construct().equals("oneOf") && u.handling().equals(Unsupported.APPROXIMATED)));
    }

    @Test
    void reportsUnsupportedConstructsAndFlagsOperations() throws Exception {
        ApiSpec spec = SpecParser.parse(SPEC);
        Map<String, Integer> counts = spec.unsupportedCounts();
        assertEquals(1, counts.get("callbacks"));
        assertEquals(1, counts.get("external_ref"));
        assertEquals(1, counts.get("oneOf"));

        Operation put = spec.operation("PUT", "/recipes/{id}");
        assertTrue(put.partiallyAnalysed(), "callbacks are ignored");
        assertTrue(put.approximated(), "the body reaches a oneOf");
        Operation post = spec.operation("POST", "/recipes");
        assertTrue(post.partiallyAnalysed(), "the response uses an external ref");
        assertFalse(post.approximated());
        Operation get = spec.operation("GET", "/recipes/{id}");
        assertFalse(get.partiallyAnalysed());
        assertTrue(get.approximated());
    }

    @Test
    void keepsNonJsonSchemasButJsonSchemaIsNull() throws Exception {
        Operation post = SpecParser.parse(SPEC).operation("POST", "/recipes");
        assertNull(post.requestBody().jsonSchema());
        assertNotNull(post.requestBody().content().get("application/x-www-form-urlencoded"));
        assertFalse(post.requestBody().required());
    }

    @Test
    void picksResponseByExactCodeThenRangeThenDefault() throws Exception {
        Operation get = SpecParser.parse(SPEC).operation("GET", "/recipes/{id}");
        assertEquals("200", get.responseFor(200).status());
        assertEquals("default", get.responseFor(404).status());
        assertNotNull(get.responseFor(500).body().jsonSchema(), "application/problem+json counts as JSON");
        assertNull(SpecParser.parse(SPEC).operation("PUT", "/recipes/{id}").responseFor(500));
    }

    @Test
    void flattenRespectsDirectionAndStopsAtCycles() throws Exception {
        Schema recipe = SpecParser.parse(SPEC).operation("GET", "/recipes/{id}")
                .responses().get("200").body().jsonSchema();
        ShapeFlattener.Result response = ShapeFlattener.flatten(recipe, Direction.RESPONSE, 10, 1000);
        Set<String> paths = response.fields().stream().map(ShapeFlattener.Field::path).collect(Collectors.toSet());
        assertTrue(paths.containsAll(Set.of("$", "$.id", "$.title", "$.tags", "$.tags[]", "$.author.email",
                "$.related[]", "$.labels.*")));
        assertFalse(paths.contains("$.secret"), "writeOnly fields are not in responses");
        assertFalse(paths.contains("$.related[].title"), "recursion stops at the cycle");
        assertTrue(response.fields().stream().anyMatch(f -> f.path().equals("$.related[]") && f.recursive()));
        assertFalse(response.truncated());

        Set<String> request = ShapeFlattener.flatten(recipe, Direction.REQUEST, 10, 1000).fields().stream()
                .map(ShapeFlattener.Field::path).collect(Collectors.toSet());
        assertFalse(request.contains("$.id"), "readOnly fields are not in requests");
        assertTrue(request.contains("$.secret"));
    }

    @Test
    void understandsOpenApi31TypeArraysAndConst() throws Exception {
        ApiSpec spec = SpecParser.parse("""
                {"openapi":"3.1.0","info":{"title":"t","version":"1"},
                 "webhooks":{"x":{}},
                 "paths":{"/a":{"get":{"responses":{"200":{"description":"d","content":{"application/json":{"schema":{
                   "type":"object","properties":{
                     "n":{"type":["string","null"]},
                     "k":{"const":"fixed"},
                     "m":{"anyOf":[{"$ref":"#/components/schemas/S"},{"type":"null"}]},
                     "c":{"type":"object","not":{"required":["x"]}}
                   }}}}}}}}},
                 "components":{"schemas":{"S":{"type":"object","required":["a"],"properties":{"a":{"type":"integer"}}}}}}
                """);
        Schema root = spec.operations().get(0).responses().get("200").body().jsonSchema();
        assertEquals(EnumSet.of(JsonType.STRING, JsonType.NULL), root.properties().get("n").types());
        assertEquals(List.of("\"fixed\""), root.properties().get("k").enumValues());
        Schema m = root.properties().get("m");
        assertEquals(EnumSet.of(JsonType.OBJECT, JsonType.NULL), m.types());
        assertEquals(Set.of("a"), m.required(), "'X or null' keeps X's required set");
        Map<String, Integer> counts = spec.unsupportedCounts();
        assertNull(counts.get("anyOf"), "'X or null' is exact and is not reported");
        assertEquals(1, counts.get("not"));
        assertEquals(1, counts.get("webhooks"));
        assertTrue(spec.operations().get(0).partiallyAnalysed());
    }

    @Test
    void rejectsWhatItCannotModel() {
        assertEquals(SpecParseException.Category.UNSUPPORTED_VERSION, assertThrows(SpecParseException.class,
                () -> SpecParser.parse("{\"swagger\":\"2.0\",\"paths\":{}}")).category());
        assertEquals(SpecParseException.Category.NOT_OPENAPI, assertThrows(SpecParseException.class,
                () -> SpecParser.parse("{\"hello\":1}")).category());
        assertEquals(SpecParseException.Category.NOT_PARSEABLE, assertThrows(SpecParseException.class,
                () -> SpecParser.parse("openapi: [unclosed")).category());
        assertEquals(SpecParseException.Category.NOT_OPENAPI, assertThrows(SpecParseException.class,
                () -> SpecParser.parse("just a string")).category());
    }

    @Test
    void aParsedSpecCanBeReadFromManyThreads() throws Exception {
        // the effective view is computed lazily; a per-object "in progress" flag once made concurrent readers
        // see an empty schema
        for (int round = 0; round < 20; round++) {
            Schema recipe = SpecParser.parse(SPEC).operation("GET", "/recipes/{id}")
                    .responses().get("200").body().jsonSchema();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
            List<java.util.concurrent.Future<String>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 64; i++) {
                results.add(pool.submit(() -> recipe.properties().keySet() + " " + recipe.required() + " "
                        + recipe.types() + " " + recipe.properties().get("author").properties().keySet()));
            }
            for (java.util.concurrent.Future<String> f : results) {
                assertEquals("[id, title, note, secret, tags, author, related, labels] [id, title] [OBJECT] "
                        + "[name, email, version]", f.get());
            }
            pool.shutdown();
        }
    }

    @Test
    void survivesCompositionCycles() throws Exception {
        ApiSpec spec = SpecParser.parse("""
                {"openapi":"3.0.0","info":{"title":"t","version":"1"},
                 "paths":{"/a":{"get":{"responses":{"200":{"description":"d","content":{"application/json":{"schema":
                   {"$ref":"#/components/schemas/A"}}}}}}}},
                 "components":{"schemas":{
                   "A":{"allOf":[{"$ref":"#/components/schemas/B"}],"properties":{"a":{"type":"string"}}},
                   "B":{"allOf":[{"$ref":"#/components/schemas/A"}],"properties":{"b":{"type":"string"}}},
                   "Loop":{"$ref":"#/components/schemas/Loop"}}}}
                """);
        Schema a = spec.operations().get(0).responses().get("200").body().jsonSchema();
        assertEquals(Set.of("a", "b"), a.properties().keySet());
        assertEquals(EnumSet.of(JsonType.OBJECT), a.types());
    }
}
