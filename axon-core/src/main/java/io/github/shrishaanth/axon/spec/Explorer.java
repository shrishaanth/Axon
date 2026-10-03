package io.github.shrishaanth.axon.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.spec.ShapeFlattener.Direction;
import java.util.List;
import java.util.Map;

/**
 * The "Promise" view: what a spec declares, as JSON for the UI and the CLI.
 *
 * <p>Split into a summary of the whole spec and a per-operation detail, because listing every field of every
 * operation at once is far larger than the spec itself (55 MB of JSON for Stripe's 8 MB spec).
 */
public final class Explorer {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Field listings stop here; the result says so with {@code truncated}. */
    public static final int MAX_DEPTH = 6;
    public static final int MAX_FIELDS = 300;
    private static final int MAX_UNSUPPORTED_LISTED = 200;

    private Explorer() {
    }

    /** Spec-level summary: one line per operation, no field listings. */
    public static ObjectNode describe(ApiSpec spec) {
        ObjectNode out = JSON.createObjectNode();
        out.put("title", spec.title());
        out.put("version", spec.version());
        out.put("openapi", spec.openapi());
        out.set("base_paths", JSON.valueToTree(spec.basePaths()));

        ObjectNode stats = out.putObject("stats");
        stats.put("operations", spec.operations().size());
        stats.put("component_schemas", spec.componentSchemas());
        stats.put("partially_analysed_operations",
                spec.operations().stream().filter(Operation::partiallyAnalysed).count());
        stats.put("approximated_operations", spec.operations().stream().filter(Operation::approximated).count());
        stats.set("unsupported_by_construct", JSON.valueToTree(spec.unsupportedCounts()));

        ArrayNode ops = out.putArray("operations");
        for (Operation op : spec.operations()) {
            ObjectNode o = ops.addObject();
            header(o, op);
            o.put("parameters", op.parameters().size());
            o.put("has_request_body", op.requestBody() != null);
            o.set("statuses", JSON.valueToTree(op.responses().keySet()));
        }

        ArrayNode unsupported = out.putArray("unsupported");
        int listed = 0;
        for (Unsupported u : spec.unsupported()) {
            if (listed++ >= MAX_UNSUPPORTED_LISTED) {
                break;
            }
            ObjectNode un = unsupported.addObject();
            un.put("construct", u.construct());
            un.put("location", u.location());
            un.put("reason", u.reason());
            un.put("handling", u.handling());
        }
        out.put("unsupported_total", spec.unsupported().size());
        return out;
    }

    /** Full detail for one operation: parameters and the field listing of each body. */
    public static ObjectNode describe(Operation op) {
        ObjectNode o = JSON.createObjectNode();
        header(o, op);
        ArrayNode params = o.putArray("parameters");
        for (Parameter p : op.parameters()) {
            ObjectNode pn = params.addObject();
            pn.put("name", p.name());
            pn.put("in", p.in());
            pn.put("required", p.required());
            pn.set("types", JSON.valueToTree(
                    p.schema() == null ? List.of() : ShapeFlattener.typeNames(p.schema())));
        }
        if (op.requestBody() != null) {
            ObjectNode rb = o.putObject("request");
            rb.put("required", op.requestBody().required());
            body(rb, op.requestBody(), Direction.REQUEST);
        }
        ArrayNode responses = o.putArray("responses");
        for (Response r : op.responses().values()) {
            ObjectNode rn = responses.addObject();
            rn.put("status", r.status());
            body(rn, r.body(), Direction.RESPONSE);
        }
        return o;
    }

    private static void header(ObjectNode o, Operation op) {
        o.put("method", op.method());
        o.put("path", op.path());
        if (op.operationId() != null) {
            o.put("operation_id", op.operationId());
        }
        o.put("deprecated", op.deprecated());
        o.put("partially_analysed", op.partiallyAnalysed());
        o.put("approximated", op.approximated());
    }

    private static void body(ObjectNode target, Body body, Direction direction) {
        target.set("media_types", JSON.valueToTree(body.content().keySet()));
        Schema schema = body.jsonSchema();
        if (schema == null) {
            // fall back to the first media type that has a schema, so form-encoded APIs are still explorable
            for (Map.Entry<String, Schema> e : body.content().entrySet()) {
                if (e.getValue() != null) {
                    schema = e.getValue();
                    break;
                }
            }
        }
        ShapeFlattener.Result flat = ShapeFlattener.flatten(schema, direction, MAX_DEPTH, MAX_FIELDS);
        ArrayNode fields = target.putArray("fields");
        for (ShapeFlattener.Field f : flat.fields()) {
            ObjectNode fn = fields.addObject();
            fn.put("path", f.path());
            fn.set("types", JSON.valueToTree(f.types()));
            fn.put("required", f.required());
            if (f.enumValues() != null) {
                ArrayNode values = fn.putArray("enum");
                for (String v : f.enumValues()) {
                    try {
                        values.add(JSON.readTree(v));
                    } catch (Exception e) {
                        values.add(v);
                    }
                }
            }
            if (f.format() != null) {
                fn.put("format", f.format());
            }
            if (f.recursive()) {
                fn.put("recursive", true);
            }
        }
        target.put("truncated", flat.truncated());
    }

    public static String toJson(JsonNode node) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
