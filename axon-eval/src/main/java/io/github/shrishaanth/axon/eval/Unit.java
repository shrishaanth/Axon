package io.github.shrishaanth.axon.eval;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.shrishaanth.axon.diff.Change;
import io.github.shrishaanth.axon.diff.SpecDiff;
import io.github.shrishaanth.axon.util.FieldPath;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One reported change, from either tool, in a common vocabulary so the two can be compared.
 *
 * @param kind   Axon's wire name for the change kind, or {@code unmodelled} for an oasdiff rule Axon has no
 *               counterpart for
 * @param status response status the change is about, or "" when not applicable or not stated
 * @param leaves candidate names of the field or parameter concerned (last path segment); empty when the change
 *               is about the whole body or operation
 */
record Unit(String tool, String method, String path, String kind, String status, Set<String> leaves,
            boolean breaking, String rawId, String text) {

    static final String UNMODELLED = "unmodelled";

    private static final Pattern BACKTICK = Pattern.compile("`([^`]*)`");
    private static final Pattern STATUS =
            Pattern.compile("status(?:es)? `([^`]+)`|`([^`]+)` (?:response )?status");

    /** Key used for set comparison: which operation, what kind of change, which response. */
    String key() {
        return method + " " + path + " | " + (kind.equals(UNMODELLED) ? rawId : kind) + " | " + status;
    }

    boolean sameLeaf(Unit other) {
        if (leaves.isEmpty() || other.leaves.isEmpty()) {
            return true;
        }
        for (String leaf : leaves) {
            if (other.leaves.contains(leaf)) {
                return true;
            }
        }
        return false;
    }

    static Unit fromAxon(Change c) {
        Set<String> leaves = new LinkedHashSet<>();
        if (c.field() != null) {
            String leaf = leaf(c.field());
            if (leaf != null) {
                leaves.add(leaf);
            }
        }
        return new Unit("axon", c.method(), SpecDiff.normalisePath(c.path()), c.kind().wire(),
                c.status() == null ? "" : c.status(), leaves, c.breaking(), c.kind().wire(), c.description());
    }

    private static String leaf(String field) {
        List<FieldPath.Segment> segments = FieldPath.parse(field);
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (!segments.get(i).isItems()) {
                return segments.get(i).name();
            }
        }
        return null;
    }

    /** {@code entry} is one element of {@code oasdiff changelog -f json}. Levels: 3 error, 2 warning, 1 info. */
    static Unit fromOasdiff(JsonNode entry) {
        String id = entry.path("id").asText();
        String text = entry.path("text").asText();
        String kind = canon(id);
        if (kind.endsWith("_field_type_changed") && text.contains("`format` changed")) {
            // oasdiff's type-changed rules also fire when only "format" changes, which Axon does not model
            kind = UNMODELLED;
            id = id + ":format";
        }
        String status = "";
        Matcher sm = STATUS.matcher(text);
        if (sm.find()) {
            status = sm.group(1) != null ? sm.group(1) : sm.group(2);
        }
        if (!kind.startsWith("response") && !kind.equals("media_type_removed") && !kind.equals("media_type_added")) {
            status = "";
        }
        if (kind.startsWith("media_type") && !id.startsWith("response")) {
            status = "";
        }
        Set<String> leaves = new LinkedHashSet<>();
        Matcher m = BACKTICK.matcher(text);
        while (m.find()) {
            String token = m.group(1);
            if (token.equals(status) || token.isEmpty()) {
                continue;
            }
            int slash = token.lastIndexOf('/');
            leaves.add(slash >= 0 ? token.substring(slash + 1) : token);
        }
        if (kind.equals("operation_removed") || kind.equals("operation_added")
                || kind.startsWith("response_status") || kind.startsWith("request_body")) {
            leaves.clear();
        }
        return new Unit("oasdiff", entry.path("operation").asText(), SpecDiff.normalisePath(entry.path("path").asText()),
                kind, status, leaves, entry.path("level").asInt() >= 2, id, text);
    }

    /** Maps an oasdiff rule id to Axon's change kind. Anything else is {@link #UNMODELLED}. */
    static String canon(String id) {
        if (id.matches("api-(path-)?removed-(without-deprecation|with-deprecation|before-sunset)")) {
            return "operation_removed";
        }
        if (id.equals("endpoint-added")) {
            return "operation_added";
        }
        if (id.matches("request-(property|parameter)-removed(-with-deprecation|-before-sunset)?")) {
            return "request_field_removed";
        }
        if (id.matches("new-required-request-(property|parameter|header-property)(-with-default)?")) {
            return "request_field_added_required";
        }
        if (id.matches("new-optional-request-(property|parameter)")) {
            return "request_field_added_optional";
        }
        if (id.matches("request-(property|parameter|header-property)-became-required(-with-default)?")) {
            return "request_field_made_required";
        }
        if (id.matches("request-(property|parameter)-became-optional")) {
            return "request_field_made_optional";
        }
        String requestSubject = "request-(body|property|parameter|parameter-property)-";
        if (id.matches(requestSubject + "(type-changed|type-generalized|type-specialized|type-compatible"
                + "|list-of-types-narrowed|list-of-types-widened|became-nullable|became-not-nullable)")) {
            return "request_field_type_changed";
        }
        if (id.matches(requestSubject + "(enum-value-removed|became-enum|const-added|const-changed)")) {
            return "request_enum_narrowed";
        }
        if (id.matches(requestSubject + "(enum-value-added|const-removed)")) {
            return "request_enum_widened";
        }
        if (id.matches("request-body-added-(required|optional)")) {
            return "request_body_added";
        }
        if (id.equals("request-body-removed")) {
            return "request_body_removed";
        }
        if (id.equals("request-body-became-required")) {
            return "request_body_made_required";
        }
        if (id.equals("request-body-became-optional")) {
            return "request_body_made_optional";
        }
        if (id.equals("request-body-media-type-removed") || id.equals("response-media-type-removed")) {
            return "media_type_removed";
        }
        if (id.equals("request-body-media-type-added") || id.equals("response-media-type-added")) {
            return "media_type_added";
        }
        if (id.matches("response-(required|optional)-property-removed")) {
            return "response_field_removed";
        }
        if (id.matches("response-(required|optional)-property-added")) {
            return "response_field_added";
        }
        if (id.equals("response-property-became-optional")) {
            return "response_field_made_optional";
        }
        if (id.equals("response-property-became-required")) {
            return "response_field_made_required";
        }
        String responseSubject = "response-(body|property)-";
        if (id.matches(responseSubject + "(type-changed|type-generalized|type-specialized|type-compatible"
                + "|list-of-types-narrowed|list-of-types-widened|became-nullable|became-not-nullable)")) {
            return "response_field_type_changed";
        }
        if (id.matches(responseSubject + "(enum-value-added|const-removed|const-changed)")) {
            return "response_enum_widened";
        }
        if (id.matches(responseSubject + "(enum-value-removed|const-added)")) {
            return "response_enum_narrowed";
        }
        if (id.matches("response-(success|non-success)-status-removed")) {
            return "response_status_removed";
        }
        if (id.matches("response-(success|non-success)-status-added")) {
            return "response_status_added";
        }
        return UNMODELLED;
    }
}
