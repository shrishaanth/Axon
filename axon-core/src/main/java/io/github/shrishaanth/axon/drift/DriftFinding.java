package io.github.shrishaanth.axon.drift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * One place where observed traffic and the spec disagree (docs/metrics.md section 10).
 *
 * @param kind             undocumented_field, unused_field, type_mismatch, undocumented_status,
 *                         undocumented_operation or unused_operation
 * @param severity         warning or info
 * @param method           null for undocumented_operation
 * @param location         for example {@code request.body}, {@code request.query}, {@code response.200.body}
 * @param requests         how many requests the finding rests on (its denominator or its count, see detail)
 * @param unseenRateUpper95 for "never seen" findings: 95% upper bound on the true rate
 */
public record DriftFinding(
        String kind,
        String severity,
        String method,
        String path,
        String location,
        String field,
        String status,
        List<String> observed,
        List<String> documented,
        Double presenceRate,
        long requests,
        Double unseenRateUpper95,
        String detail) {

    public static final String UNDOCUMENTED_FIELD = "undocumented_field";
    public static final String UNUSED_FIELD = "unused_field";
    public static final String TYPE_MISMATCH = "type_mismatch";
    public static final String UNDOCUMENTED_STATUS = "undocumented_status";
    public static final String UNDOCUMENTED_OPERATION = "undocumented_operation";
    public static final String UNUSED_OPERATION = "unused_operation";

    private static final ObjectMapper JSON = new ObjectMapper();

    public String operationKey() {
        return method == null ? null : method + " " + path;
    }

    public ObjectNode toJson() {
        ObjectNode n = JSON.createObjectNode();
        n.put("kind", kind);
        n.put("severity", severity);
        if (method == null) {
            n.putNull("operation");
            n.put("path", path);
        } else {
            ObjectNode op = n.putObject("operation");
            op.put("method", method);
            op.put("path", path);
        }
        if (location != null) {
            n.put("location", location);
        }
        if (field != null) {
            n.put("field", field);
        }
        if (status != null) {
            n.put("status", status);
        }
        if (observed != null) {
            ArrayNode a = n.putArray("observed");
            observed.forEach(a::add);
        }
        if (documented != null) {
            ArrayNode a = n.putArray("documented");
            documented.forEach(a::add);
        }
        if (presenceRate != null) {
            n.put("presence_rate", presenceRate);
        }
        n.put("requests", requests);
        if (unseenRateUpper95 != null) {
            n.put("unseen_rate_upper_95", unseenRateUpper95);
        }
        n.put("detail", detail);
        return n;
    }
}
