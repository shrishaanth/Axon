package io.github.shrishaanth.axon.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.diff.ChangeKind;
import io.github.shrishaanth.axon.spec.Body;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Applies one known mutation to a raw OpenAPI document (E1a). It works on the JSON tree, not on Axon's model, so
 * the ground truth does not depend on the code under test.
 *
 * <p>To keep the expected outcome unambiguous, the target schema is first copied inline into the chosen
 * operation, so exactly one operation and one direction change. Only plain objects (with {@code properties} and
 * no {@code allOf}/{@code oneOf}/{@code anyOf}) or arrays of them are mutated; composition is covered by unit
 * tests, not by this experiment.
 */
final class Mutator {

    enum Kind {
        REMOVE_RESPONSE_FIELD(ChangeKind.RESPONSE_FIELD_REMOVED, true),
        REMOVE_REQUEST_FIELD(ChangeKind.REQUEST_FIELD_REMOVED, true),
        ADD_REQUIRED_REQUEST_FIELD(ChangeKind.REQUEST_FIELD_ADDED_REQUIRED, true),
        CHANGE_REQUEST_FIELD_TYPE(ChangeKind.REQUEST_FIELD_TYPE_CHANGED, true),
        CHANGE_RESPONSE_FIELD_TYPE(ChangeKind.RESPONSE_FIELD_TYPE_CHANGED, true),
        NARROW_REQUEST_ENUM(ChangeKind.REQUEST_ENUM_NARROWED, true),
        WIDEN_REQUEST_ENUM(ChangeKind.REQUEST_ENUM_WIDENED, false),
        WIDEN_RESPONSE_ENUM(ChangeKind.RESPONSE_ENUM_WIDENED, true),
        REMOVE_STATUS(ChangeKind.RESPONSE_STATUS_REMOVED, true),
        REMOVE_OPERATION(ChangeKind.OPERATION_REMOVED, true),
        MAKE_REQUEST_FIELD_REQUIRED(ChangeKind.REQUEST_FIELD_MADE_REQUIRED, true),
        MAKE_REQUEST_FIELD_OPTIONAL(ChangeKind.REQUEST_FIELD_MADE_OPTIONAL, false),
        ADD_OPTIONAL_REQUEST_FIELD(ChangeKind.REQUEST_FIELD_ADDED_OPTIONAL, false),
        ADD_RESPONSE_FIELD(ChangeKind.RESPONSE_FIELD_ADDED, false);

        final ChangeKind expected;
        final boolean breaking;

        Kind(ChangeKind expected, boolean breaking) {
            this.expected = expected;
            this.breaking = breaking;
        }

        boolean onRequest() {
            return name().contains("REQUEST");
        }
    }

    /**
     * @param leaf   name of the field concerned, or null
     * @param status response status concerned, or null
     * @param note   extra fact about the site (for example whether a removed field was required)
     */
    record Applied(Kind kind, String method, String path, String status, String leaf, String note) {
    }

    static final String NEW_FIELD = "axon_mut_field";
    static final String NEW_VALUE = "axon_mut_value";
    private static final List<String> METHODS =
            List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    private final ObjectNode root;
    private final Random random;

    /** {@code root} is mutated in place; pass a deep copy. */
    Mutator(ObjectNode root, Random random) {
        this.root = root;
        this.random = random;
    }

    private record Op(String path, String method, ObjectNode pathItem, ObjectNode node) {
    }

    /** A JSON body of one operation: the object holding the {@code schema} key, plus the response status. */
    private record Site(Op op, String status, ObjectNode mediaType) {
    }

    Applied apply(Kind kind) {
        List<Op> ops = operations();
        if (ops.isEmpty()) {
            return null;
        }
        switch (kind) {
            case REMOVE_OPERATION -> {
                Op op = pick(ops);
                op.pathItem().remove(op.method());
                return new Applied(kind, op.method(), op.path(), null, null, null);
            }
            case REMOVE_STATUS -> {
                List<Op> eligible = ops.stream()
                        .filter(o -> o.node().path("responses").isObject() && o.node().get("responses").size() >= 2)
                        .toList();
                if (eligible.isEmpty()) {
                    return null;
                }
                Op op = pick(eligible);
                List<String> statuses = new ArrayList<>();
                op.node().get("responses").fieldNames().forEachRemaining(s -> {
                    if (!s.startsWith("x-")) {
                        statuses.add(s);
                    }
                });
                if (statuses.size() < 2) {
                    return null;
                }
                String status = pick(statuses);
                ((ObjectNode) op.node().get("responses")).remove(status);
                return new Applied(kind, op.method(), op.path(), status, null,
                        status.startsWith("2") ? "success status" : "non-success status");
            }
            default -> {
                return mutateField(kind, ops);
            }
        }
    }

    private Applied mutateField(Kind kind, List<Op> ops) {
        // Try sites in random order until one has an eligible field.
        List<Site> sites = new ArrayList<>();
        for (Op op : ops) {
            if (kind.onRequest()) {
                ObjectNode media = requestMedia(op);
                if (media != null) {
                    sites.add(new Site(op, null, media));
                }
            } else {
                JsonNode responses = op.node().get("responses");
                if (responses == null || !responses.isObject()) {
                    continue;
                }
                Iterator<String> names = responses.fieldNames();
                List<String> statuses = new ArrayList<>();
                names.forEachRemaining(statuses::add);
                for (String status : statuses) {
                    ObjectNode media = responseMedia(op, status);
                    if (media != null) {
                        sites.add(new Site(op, status, media));
                    }
                }
            }
        }
        java.util.Collections.shuffle(sites, random);
        int tried = 0;
        for (Site site : sites) {
            if (tried++ > 200) {
                break;
            }
            ObjectNode object = plainObject(site.mediaType(), "schema", 0);
            if (object == null) {
                continue;
            }
            Applied applied = mutateObject(kind, site, object);
            if (applied != null) {
                return applied;
            }
        }
        return null;
    }

    private Applied mutateObject(Kind kind, Site site, ObjectNode object) {
        ObjectNode properties = (ObjectNode) object.get("properties");
        boolean request = kind.onRequest();
        List<String> names = new ArrayList<>();
        properties.fieldNames().forEachRemaining(names::add);
        java.util.Collections.shuffle(names, random);
        Op op = site.op();

        switch (kind) {
            case ADD_REQUIRED_REQUEST_FIELD, ADD_OPTIONAL_REQUEST_FIELD, ADD_RESPONSE_FIELD -> {
                if (properties.has(NEW_FIELD)) {
                    return null;
                }
                properties.putObject(NEW_FIELD).put("type", "string");
                if (kind == Kind.ADD_REQUIRED_REQUEST_FIELD) {
                    requiredArray(object).add(NEW_FIELD);
                }
                return new Applied(kind, op.method(), op.path(), site.status(), NEW_FIELD, null);
            }
            default -> {
            }
        }

        for (String name : names) {
            JsonNode resolved = deref(properties.get(name));
            if (resolved == null || !resolved.isObject()) {
                continue;
            }
            // a readOnly field is not part of a request, a writeOnly one not part of a response
            if (resolved.path(request ? "readOnly" : "writeOnly").asBoolean(false)) {
                continue;
            }
            boolean required = isRequired(object, name);
            switch (kind) {
                case REMOVE_RESPONSE_FIELD, REMOVE_REQUEST_FIELD -> {
                    properties.remove(name);
                    removeRequired(object, name);
                    return new Applied(kind, op.method(), op.path(), site.status(), name,
                            required ? "was required" : "was optional");
                }
                case MAKE_REQUEST_FIELD_REQUIRED -> {
                    if (required) {
                        continue;
                    }
                    requiredArray(object).add(name);
                    return new Applied(kind, op.method(), op.path(), null, name, null);
                }
                case MAKE_REQUEST_FIELD_OPTIONAL -> {
                    if (!required) {
                        continue;
                    }
                    removeRequired(object, name);
                    return new Applied(kind, op.method(), op.path(), null, name, null);
                }
                case CHANGE_REQUEST_FIELD_TYPE, CHANGE_RESPONSE_FIELD_TYPE -> {
                    JsonNode type = resolved.get("type");
                    if (type == null || !type.isTextual() || resolved.has("enum") || resolved.has("const")
                            || resolved.has("allOf") || resolved.has("oneOf") || resolved.has("anyOf")
                            || resolved.path("nullable").asBoolean(false)) {
                        continue;
                    }
                    String from = type.asText();
                    String to;
                    switch (from) {
                        case "string" -> to = "integer";
                        case "integer", "number", "boolean" -> to = "string";
                        default -> {
                            continue;
                        }
                    }
                    ObjectNode copy = ((ObjectNode) resolved).deepCopy();
                    copy.retain("type", "description");
                    copy.put("type", to);
                    properties.set(name, copy);
                    return new Applied(kind, op.method(), op.path(), site.status(), name, from + " -> " + to);
                }
                case NARROW_REQUEST_ENUM, WIDEN_REQUEST_ENUM, WIDEN_RESPONSE_ENUM -> {
                    JsonNode values = resolved.get("enum");
                    if (values == null || !values.isArray() || !"string".equals(resolved.path("type").asText())
                            || resolved.has("allOf") || resolved.has("oneOf") || resolved.has("anyOf")) {
                        continue;
                    }
                    long distinctNonNull = 0;
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (JsonNode v : values) {
                        if (!v.isNull() && seen.add(v.toString())) {
                            distinctNonNull++;
                        }
                    }
                    ObjectNode copy = ((ObjectNode) resolved).deepCopy();
                    ArrayNode copied = (ArrayNode) copy.get("enum");
                    if (kind == Kind.NARROW_REQUEST_ENUM) {
                        if (distinctNonNull < 2 || seen.size() != values.size()) {
                            continue;
                        }
                        int index = copied.size() - 1;
                        while (index >= 0 && copied.get(index).isNull()) {
                            index--;
                        }
                        String removed = copied.get(index).asText();
                        copied.remove(index);
                        properties.set(name, copy);
                        return new Applied(kind, op.method(), op.path(), site.status(), name, "removed " + removed);
                    }
                    if (seen.contains("\"" + NEW_VALUE + "\"")) {
                        continue;
                    }
                    copied.add(NEW_VALUE);
                    properties.set(name, copy);
                    return new Applied(kind, op.method(), op.path(), site.status(), name, "added " + NEW_VALUE);
                }
                default -> throw new IllegalStateException(kind.name());
            }
        }
        return null;
    }

    /**
     * Returns the plain object schema under {@code holder[key]}, copying referenced schemas inline on the way so
     * that later edits touch only this operation. Looks through one level of array.
     */
    private ObjectNode plainObject(ObjectNode holder, String key, int depth) {
        JsonNode node = holder.get(key);
        if (node == null || depth > 2) {
            return null;
        }
        JsonNode resolved = deref(node);
        if (resolved == null || !resolved.isObject()) {
            return null;
        }
        if (resolved.has("allOf") || resolved.has("oneOf") || resolved.has("anyOf") || resolved.has("not")) {
            return null;
        }
        ObjectNode copy = resolved == node ? (ObjectNode) node : ((ObjectNode) resolved).deepCopy();
        JsonNode type = copy.get("type");
        if (copy.path("properties").isObject() && copy.get("properties").size() > 0
                && (type == null || "object".equals(type.asText()))) {
            holder.set(key, copy);
            return copy;
        }
        if (copy.has("items") && (type == null || "array".equals(type.asText()))) {
            ObjectNode inner = plainObject(copy, "items", depth + 1);
            if (inner != null) {
                holder.set(key, copy);
            }
            return inner;
        }
        return null;
    }

    private ObjectNode requestMedia(Op op) {
        JsonNode body = op.node().get("requestBody");
        if (body == null) {
            return null;
        }
        JsonNode resolved = deref(body);
        if (resolved == null || !resolved.isObject()) {
            return null;
        }
        ObjectNode copy = resolved == body ? (ObjectNode) body : ((ObjectNode) resolved).deepCopy();
        ObjectNode media = jsonMedia(copy);
        if (media != null) {
            op.node().set("requestBody", copy);
        }
        return media;
    }

    private ObjectNode responseMedia(Op op, String status) {
        ObjectNode responses = (ObjectNode) op.node().get("responses");
        JsonNode response = responses.get(status);
        JsonNode resolved = deref(response);
        if (resolved == null || !resolved.isObject()) {
            return null;
        }
        ObjectNode copy = resolved == response ? (ObjectNode) response : ((ObjectNode) resolved).deepCopy();
        ObjectNode media = jsonMedia(copy);
        if (media != null) {
            responses.set(status, copy);
        }
        return media;
    }

    private static ObjectNode jsonMedia(ObjectNode bodyOrResponse) {
        JsonNode content = bodyOrResponse.get("content");
        if (content == null || !content.isObject()) {
            return null;
        }
        Iterator<Map.Entry<String, JsonNode>> it = content.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (Body.isJson(e.getKey()) && e.getValue().isObject() && e.getValue().has("schema")) {
                return (ObjectNode) e.getValue();
            }
        }
        return null;
    }

    private JsonNode deref(JsonNode node) {
        int hops = 0;
        while (node != null && node.isObject() && node.has("$ref") && hops++ < 50) {
            String ref = node.get("$ref").asText();
            if (!ref.startsWith("#")) {
                return null;
            }
            try {
                node = root.at(ref.substring(1));
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (node.isMissingNode()) {
                return null;
            }
        }
        return node;
    }

    private static boolean isRequired(ObjectNode object, String name) {
        JsonNode required = object.get("required");
        if (required != null && required.isArray()) {
            for (JsonNode r : required) {
                if (name.equals(r.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ArrayNode requiredArray(ObjectNode object) {
        JsonNode required = object.get("required");
        return required != null && required.isArray() ? (ArrayNode) required : object.putArray("required");
    }

    private static void removeRequired(ObjectNode object, String name) {
        JsonNode required = object.get("required");
        if (required == null || !required.isArray()) {
            return;
        }
        ArrayNode array = (ArrayNode) required;
        for (int i = array.size() - 1; i >= 0; i--) {
            if (name.equals(array.get(i).asText())) {
                array.remove(i);
            }
        }
        if (array.isEmpty()) {
            object.remove("required"); // an empty "required" is invalid in OpenAPI 3.0
        }
    }

    private List<Op> operations() {
        List<Op> ops = new ArrayList<>();
        JsonNode paths = root.get("paths");
        if (paths == null || !paths.isObject()) {
            return ops;
        }
        Iterator<Map.Entry<String, JsonNode>> it = paths.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getKey().startsWith("/") || !e.getValue().isObject() || e.getValue().has("$ref")) {
                continue;
            }
            for (String method : METHODS) {
                JsonNode op = e.getValue().get(method);
                if (op != null && op.isObject()) {
                    ops.add(new Op(e.getKey(), method, (ObjectNode) e.getValue(), (ObjectNode) op));
                }
            }
        }
        return ops;
    }

    private <T> T pick(List<T> list) {
        return list.get(random.nextInt(list.size()));
    }
}
