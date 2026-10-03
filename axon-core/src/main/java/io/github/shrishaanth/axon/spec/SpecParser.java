package io.github.shrishaanth.axon.spec;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns an OpenAPI 3.0 or 3.1 document into an {@link ApiSpec}.
 *
 * <p>Anything recognised but not modelled exactly ends up in {@link ApiSpec#unsupported()}; nothing is dropped
 * silently. Value constraints (length, range, pattern) and response headers are outside the model by design and
 * are documented as such rather than listed per occurrence.
 */
public final class SpecParser {

    /**
     * Larger inputs are rejected before parsing. 32 MB is set from measurement: the largest spec tried below it
     * (25.8 MB of YAML, 11,422 operations) parses in a 256 MB heap, and a 58 MB one does not fit in 384 MB.
     * Override with the system property {@code axon.maxSpecBytes} when memory is not a concern.
     */
    public static final long MAX_BYTES = Long.getLong("axon.maxSpecBytes", 32L * 1024 * 1024);

    private static final List<String> METHODS =
            List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private static final List<String> IGNORED_KEYWORDS = List.of(
            "not", "if", "then", "else", "patternProperties", "dependentSchemas", "dependentRequired",
            "prefixItems", "unevaluatedProperties", "unevaluatedItems", "contains", "propertyNames",
            "$dynamicRef", "$recursiveRef");

    private final JsonNode root;
    private final Map<String, Schema> schemas = new HashMap<>();
    private final Map<Schema, Integer> flags = new IdentityHashMap<>();
    private final List<Unsupported> unsupported = new ArrayList<>();
    private final Set<String> reported = new HashSet<>();

    private static final int FLAG_IGNORED = 1;
    private static final int FLAG_APPROX = 2;

    private SpecParser(JsonNode root) {
        this.root = root;
    }

    public static ApiSpec parse(Path file) throws SpecParseException, IOException {
        long size = Files.size(file);
        if (size > MAX_BYTES) {
            throw new SpecParseException(SpecParseException.Category.TOO_LARGE,
                    "document is " + size + " bytes; the limit is " + MAX_BYTES);
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static ApiSpec parse(String text) throws SpecParseException {
        if (text.length() > MAX_BYTES) {
            throw new SpecParseException(SpecParseException.Category.TOO_LARGE,
                    "document is larger than " + MAX_BYTES + " bytes");
        }
        // Schema graphs nest deeply (thousands of chained $refs in large specs), so build on a big stack.
        Object[] out = new Object[1];
        Thread worker = new Thread(null, () -> {
            try {
                JsonNode tree = SpecLoader.load(text);
                out[0] = new SpecParser(tree).build();
            } catch (Throwable t) {
                out[0] = t;
            }
        }, "axon-spec-parser", 512L * 1024 * 1024);
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpecParseException(SpecParseException.Category.NOT_PARSEABLE, "interrupted");
        }
        if (out[0] instanceof ApiSpec spec) {
            return spec;
        }
        if (out[0] instanceof SpecParseException e) {
            throw e;
        }
        if (out[0] instanceof OutOfMemoryError e) {
            throw new SpecParseException(SpecParseException.Category.TOO_LARGE, "out of memory while parsing", e);
        }
        Throwable t = (Throwable) out[0];
        throw new SpecParseException(SpecParseException.Category.INVALID_STRUCTURE,
                t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage()), t);
    }

    private ApiSpec build() throws SpecParseException {
        if (root == null || !root.isObject()) {
            throw new SpecParseException(SpecParseException.Category.NOT_OPENAPI, "top level is not an object");
        }
        String openapi = root.path("openapi").asText("");
        if (openapi.isEmpty()) {
            if (root.has("swagger")) {
                throw new SpecParseException(SpecParseException.Category.UNSUPPORTED_VERSION,
                        "Swagger " + root.path("swagger").asText() + " is not supported; convert to OpenAPI 3");
            }
            throw new SpecParseException(SpecParseException.Category.NOT_OPENAPI, "no 'openapi' field");
        }
        if (!(openapi.startsWith("3.0") || openapi.startsWith("3.1"))) {
            throw new SpecParseException(SpecParseException.Category.UNSUPPORTED_VERSION,
                    "OpenAPI " + openapi + " is not supported (3.0 and 3.1 are)");
        }
        JsonNode paths = root.path("paths");
        if (!paths.isObject() && !paths.isMissingNode() && !paths.isNull()) {
            throw new SpecParseException(SpecParseException.Category.INVALID_STRUCTURE, "'paths' is not an object");
        }

        if (root.has("webhooks")) {
            report("webhooks", "/webhooks", "Webhooks are not modelled.", Unsupported.IGNORED);
        }

        List<Operation> operations = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = paths.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String path = entry.getKey();
            if (!path.startsWith("/")) {
                continue; // x- extensions
            }
            String pathPointer = "/paths/" + escape(path);
            JsonNode item = entry.getValue();
            if (item.has("$ref")) {
                Target t = resolve(item.get("$ref").asText(), pathPointer);
                if (t == null) {
                    continue;
                }
                item = t.node;
                pathPointer = t.pointer;
            }
            if (!item.isObject()) {
                continue;
            }
            List<Parameter> shared = parameters(item.get("parameters"), pathPointer + "/parameters");
            for (String method : METHODS) {
                JsonNode op = item.get(method);
                if (op == null || !op.isObject()) {
                    continue;
                }
                operations.add(operation(method, path, op, pathPointer + "/" + method, shared));
            }
        }

        JsonNode info = root.path("info");
        int componentSchemas = root.path("components").path("schemas").size();
        return new ApiSpec(
                textOrNull(info.get("title")),
                textOrNull(info.get("version")),
                openapi,
                basePaths(),
                Collections.unmodifiableList(operations),
                Collections.unmodifiableList(unsupported),
                componentSchemas);
    }

    private Operation operation(String method, String path, JsonNode op, String pointer, List<Parameter> shared) {
        Map<String, Parameter> params = new LinkedHashMap<>();
        for (Parameter p : shared) {
            params.put(p.key(), p);
        }
        for (Parameter p : parameters(op.get("parameters"), pointer + "/parameters")) {
            params.put(p.key(), p);
        }

        Body requestBody = null;
        JsonNode rb = op.get("requestBody");
        if (rb != null && rb.isObject()) {
            String rbPointer = pointer + "/requestBody";
            if (rb.has("$ref")) {
                Target t = resolve(rb.get("$ref").asText(), rbPointer);
                rb = t == null ? null : t.node;
                rbPointer = t == null ? rbPointer : t.pointer;
            }
            if (rb != null) {
                requestBody = new Body(rb.path("required").asBoolean(false), content(rb.get("content"), rbPointer));
            }
        }

        Map<String, Response> responses = new LinkedHashMap<>();
        JsonNode rs = op.get("responses");
        if (rs != null && rs.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = rs.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String status = e.getKey();
                if (status.startsWith("x-")) {
                    continue;
                }
                JsonNode r = e.getValue();
                String rPointer = pointer + "/responses/" + escape(status);
                if (r.has("$ref")) {
                    Target t = resolve(r.get("$ref").asText(), rPointer);
                    if (t == null) {
                        responses.put(status, new Response(status, new Body(false, Map.of())));
                        continue;
                    }
                    r = t.node;
                    rPointer = t.pointer;
                }
                if (r.has("links")) {
                    report("links", rPointer + "/links", "Links are not modelled.", Unsupported.IGNORED);
                }
                responses.put(status, new Response(status, new Body(false, content(r.get("content"), rPointer))));
            }
        }

        if (op.has("callbacks")) {
            report("callbacks", pointer + "/callbacks", "Callbacks are not modelled.", Unsupported.IGNORED);
        }

        int reach = op.has("callbacks") ? FLAG_IGNORED : 0;
        Set<Schema> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Parameter p : params.values()) {
            reach |= reachableFlags(p.schema(), seen);
        }
        if (requestBody != null) {
            for (Schema s : requestBody.content().values()) {
                reach |= reachableFlags(s, seen);
            }
        }
        for (Response r : responses.values()) {
            for (Schema s : r.body().content().values()) {
                reach |= reachableFlags(s, seen);
            }
        }

        return new Operation(
                method.toUpperCase(java.util.Locale.ROOT),
                path,
                textOrNull(op.get("operationId")),
                op.path("deprecated").asBoolean(false),
                List.copyOf(params.values()),
                requestBody,
                Collections.unmodifiableMap(responses),
                (reach & FLAG_IGNORED) != 0,
                (reach & FLAG_APPROX) != 0);
    }

    private List<Parameter> parameters(JsonNode array, String pointer) {
        List<Parameter> out = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return out;
        }
        for (int i = 0; i < array.size(); i++) {
            JsonNode p = array.get(i);
            String pPointer = pointer + "/" + i;
            if (p.has("$ref")) {
                Target t = resolve(p.get("$ref").asText(), pPointer);
                if (t == null) {
                    continue;
                }
                p = t.node;
                pPointer = t.pointer;
            }
            String name = textOrNull(p.get("name"));
            String in = textOrNull(p.get("in"));
            if (name == null || in == null) {
                continue;
            }
            Schema schema = null;
            if (p.has("schema")) {
                schema = schema(p.get("schema"), pPointer + "/schema");
            } else if (p.path("content").isObject() && p.get("content").size() > 0) {
                String mediaType = p.get("content").fieldNames().next();
                schema = schema(p.get("content").get(mediaType).get("schema"),
                        pPointer + "/content/" + escape(mediaType) + "/schema");
                report("parameter_content", pPointer + "/content",
                        "Parameter described with 'content'; only the schema of its first media type is used.",
                        Unsupported.APPROXIMATED);
                if (schema != null) {
                    flags.merge(schema, FLAG_APPROX, (a, b) -> a | b);
                }
            }
            boolean required = "path".equals(in) || p.path("required").asBoolean(false);
            out.add(new Parameter(name, in, required, schema));
        }
        return out;
    }

    private Map<String, Schema> content(JsonNode content, String pointer) {
        Map<String, Schema> out = new LinkedHashMap<>();
        if (content == null || !content.isObject()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> it = content.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode s = e.getValue().get("schema");
            out.put(e.getKey(), s == null ? null
                    : schema(s, pointer + "/content/" + escape(e.getKey()) + "/schema"));
        }
        return Collections.unmodifiableMap(out);
    }

    private Schema schema(JsonNode node, String pointer) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject() && node.has("$ref") && node.get("$ref").isTextual()) {
            String ref = node.get("$ref").asText();
            Target t = resolve(ref, pointer);
            if (t == null) {
                Schema any = new Schema(pointer);
                any.unresolved = true;
                flags.put(any, FLAG_IGNORED);
                return any;
            }
            return schema(t.node, t.pointer);
        }
        Schema existing = schemas.get(pointer);
        if (existing != null) {
            return existing;
        }
        Schema s = new Schema(pointer);
        schemas.put(pointer, s);
        if (!node.isObject()) {
            // boolean schemas (3.1): true accepts anything; false accepts nothing and is not modelled
            s.unresolved = true;
            if (node.isBoolean() && !node.asBoolean()) {
                mark(s, "false_schema", pointer, "Boolean schema 'false' is not modelled.", Unsupported.IGNORED);
            }
            return s;
        }

        JsonNode type = node.get("type");
        if (type != null) {
            if (type.isTextual()) {
                addType(s, type.asText());
            } else if (type.isArray()) {
                for (JsonNode t : type) {
                    addType(s, t.asText());
                }
            }
        }
        if (node.path("nullable").asBoolean(false)) {
            s.ownTypes.add(JsonType.NULL);
        }
        s.format = textOrNull(node.get("format"));
        s.readOnly = node.path("readOnly").asBoolean(false);
        s.writeOnly = node.path("writeOnly").asBoolean(false);

        JsonNode props = node.get("properties");
        if (props != null && props.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = props.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                Schema child = schema(e.getValue(), pointer + "/properties/" + escape(e.getKey()));
                if (child != null) {
                    s.ownProperties.put(e.getKey(), child);
                }
            }
        }
        JsonNode required = node.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode r : required) {
                s.ownRequired.add(r.asText());
            }
        }
        JsonNode items = node.get("items");
        if (items != null) {
            if (items.isArray()) {
                mark(s, "tuple_items", pointer + "/items", "Tuple-style 'items' arrays are not modelled.",
                        Unsupported.IGNORED);
            } else {
                s.ownItems = schema(items, pointer + "/items");
            }
        }
        JsonNode additional = node.get("additionalProperties");
        if (additional != null) {
            if (additional.isBoolean()) {
                s.ownAdditionalAllowed = additional.asBoolean();
            } else if (additional.isObject()) {
                s.ownAdditionalSchema = schema(additional, pointer + "/additionalProperties");
            }
        }
        JsonNode en = node.get("enum");
        if (en != null && en.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode v : en) {
                values.add(v.toString());
            }
            s.ownEnum = List.copyOf(new LinkedHashSet<>(values));
        } else if (node.has("const")) {
            s.ownEnum = List.of(node.get("const").toString());
        }

        JsonNode allOf = node.get("allOf");
        if (allOf != null && allOf.isArray()) {
            for (int i = 0; i < allOf.size(); i++) {
                Schema part = schema(allOf.get(i), pointer + "/allOf/" + i);
                if (part != null) {
                    s.allOf.add(part);
                }
            }
        }
        for (String keyword : List.of("oneOf", "anyOf")) {
            JsonNode group = node.get(keyword);
            if (group == null || !group.isArray() || group.isEmpty()) {
                continue;
            }
            List<Schema> variants = new ArrayList<>();
            for (int i = 0; i < group.size(); i++) {
                Schema variant = schema(group.get(i), pointer + "/" + keyword + "/" + i);
                if (variant != null) {
                    variants.add(variant);
                }
            }
            s.unions.add(variants);
            long real = variants.stream().filter(v -> !isNullOnly(v)).count();
            if (real > 1) {
                // "X or null" is exact; a real choice between shapes is only approximated
                mark(s, keyword, pointer + "/" + keyword,
                        "'" + keyword + "' is modelled as the union of its variants: a field is required only "
                                + "if every variant requires it, and which variant applies is not tracked.",
                        Unsupported.APPROXIMATED);
            }
        }
        if (node.has("discriminator")) {
            mark(s, "discriminator", pointer + "/discriminator",
                    "Discriminator mapping is not used to select a variant.", Unsupported.APPROXIMATED);
        }
        for (String keyword : IGNORED_KEYWORDS) {
            if (node.has(keyword)) {
                mark(s, keyword, pointer + "/" + escape(keyword), "'" + keyword + "' is not modelled.",
                        Unsupported.IGNORED);
            }
        }
        return s;
    }

    private static boolean isNullOnly(Schema s) {
        return s.ownTypes.size() == 1 && s.ownTypes.contains(JsonType.NULL)
                && s.allOf.isEmpty() && s.unions.isEmpty() && s.ownProperties.isEmpty();
    }

    private void addType(Schema s, String name) {
        JsonType t = JsonType.fromWire(name);
        if (t != null) {
            s.ownTypes.add(t);
        }
    }

    private void mark(Schema s, String construct, String location, String reason, String handling) {
        flags.merge(s, Unsupported.IGNORED.equals(handling) ? FLAG_IGNORED : FLAG_APPROX, (a, b) -> a | b);
        report(construct, location, reason, handling);
    }

    private void report(String construct, String location, String reason, String handling) {
        if (reported.add(construct + "@" + location)) {
            unsupported.add(new Unsupported(construct, location, reason, handling));
        }
    }

    /** OR of the flags of every schema reachable from {@code start}; {@code seen} is shared per operation. */
    private int reachableFlags(Schema start, Set<Schema> seen) {
        if (start == null) {
            return 0;
        }
        int result = 0;
        Deque<Schema> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            Schema s = stack.pop();
            if (!seen.add(s)) {
                continue;
            }
            result |= flags.getOrDefault(s, 0);
            stack.addAll(s.ownProperties.values());
            if (s.ownItems != null) {
                stack.push(s.ownItems);
            }
            if (s.ownAdditionalSchema != null) {
                stack.push(s.ownAdditionalSchema);
            }
            stack.addAll(s.allOf);
            for (List<Schema> group : s.unions) {
                stack.addAll(group);
            }
        }
        return result;
    }

    private record Target(JsonNode node, String pointer) {
    }

    /** Resolves a local reference, following chains. Reports and returns null for external or dangling ones. */
    private Target resolve(String ref, String from) {
        Set<String> chain = new HashSet<>();
        String current = ref;
        while (true) {
            if (!current.startsWith("#")) {
                report("external_ref", from,
                        "Reference '" + current + "' points outside the document; external references are not "
                                + "followed.", Unsupported.IGNORED);
                return null;
            }
            String pointer = URLDecoder.decode(current.substring(1).replace("+", "%2B"), StandardCharsets.UTF_8);
            if (!chain.add(pointer)) {
                report("cyclic_ref", from, "Reference '" + ref + "' refers to itself.", Unsupported.IGNORED);
                return null;
            }
            JsonNode node;
            try {
                node = pointer.isEmpty() ? root : root.at(pointer);
            } catch (IllegalArgumentException e) {
                node = null;
            }
            if (node == null || node.isMissingNode()) {
                report("unresolved_ref", from, "Reference '" + current + "' does not resolve.",
                        Unsupported.IGNORED);
                return null;
            }
            if (node.isObject() && node.has("$ref") && node.get("$ref").isTextual()) {
                current = node.get("$ref").asText();
                continue;
            }
            return new Target(node, pointer);
        }
    }

    private List<String> basePaths() {
        Set<String> out = new LinkedHashSet<>();
        JsonNode servers = root.get("servers");
        if (servers != null && servers.isArray()) {
            for (JsonNode server : servers) {
                String url = server.path("url").asText("");
                int scheme = url.indexOf("://");
                String rest = url;
                if (scheme >= 0) {
                    int slash = url.indexOf('/', scheme + 3);
                    rest = slash < 0 ? "" : url.substring(slash);
                }
                while (rest.endsWith("/")) {
                    rest = rest.substring(0, rest.length() - 1);
                }
                if (!rest.isEmpty() && rest.startsWith("/")) {
                    out.add(rest);
                }
            }
        }
        return List.copyOf(out);
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() || node.isContainerNode() ? null : node.asText();
    }

    static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }
}
