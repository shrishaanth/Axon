package io.github.shrishaanth.axon.diff;

import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.Body;
import io.github.shrishaanth.axon.spec.Operation;
import io.github.shrishaanth.axon.spec.Parameter;
import io.github.shrishaanth.axon.spec.Response;
import io.github.shrishaanth.axon.spec.Schema;
import io.github.shrishaanth.axon.spec.ShapeFlattener.Direction;
import io.github.shrishaanth.axon.util.FieldPath;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Diffs two specs into atomic {@link Change}s, each classified breaking or safe.
 *
 * <p>Operations are matched by method and path with parameter names ignored, so renaming {@code {id}} to
 * {@code {userId}} is not a removal. Not compared, by design: value constraints, defaults, formats, response
 * headers, security, descriptions.
 */
public final class SpecDiff {

    /**
     * @param truncated true when some shared schema had more changes than {@link SchemaDiff#MAX_CHANGES_PER_PAIR}
     *                  and the list was cut
     */
    public record Result(List<Change> changes, boolean truncated) {
        public List<Change> breaking() {
            return changes.stream().filter(Change::breaking).toList();
        }
    }

    private final List<Change> changes = new ArrayList<>();
    private final SchemaDiff requestDiff = new SchemaDiff(Direction.REQUEST);
    private final SchemaDiff responseDiff = new SchemaDiff(Direction.RESPONSE);

    private SpecDiff() {
    }

    public static Result diff(ApiSpec baseline, ApiSpec candidate) {
        // Recursion depth follows the longest chain of distinct schema pairs, which is long in large specs.
        Object[] out = new Object[1];
        Thread worker = new Thread(null, () -> {
            try {
                SpecDiff d = new SpecDiff();
                d.run(baseline, candidate);
                out[0] = new Result(List.copyOf(d.changes),
                        d.requestDiff.truncated() || d.responseDiff.truncated());
            } catch (Throwable t) {
                out[0] = t;
            }
        }, "axon-spec-diff", 512L * 1024 * 1024);
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
        if (out[0] instanceof Result r) {
            return r;
        }
        if (out[0] instanceof RuntimeException e) {
            throw e;
        }
        if (out[0] instanceof Error e) {
            throw e;
        }
        throw new IllegalStateException((Throwable) out[0]);
    }

    /** Path template with parameter names blanked: {@code /users/{id}} becomes {@code /users/{}}. */
    public static String normalisePath(String path) {
        return path.replaceAll("\\{[^/}]*}", "{}");
    }

    private void run(ApiSpec baseline, ApiSpec candidate) {
        Map<String, Operation> candidateOps = new LinkedHashMap<>();
        for (Operation op : candidate.operations()) {
            candidateOps.putIfAbsent(op.method() + " " + normalisePath(op.path()), op);
        }
        Map<String, Operation> baselineOps = new LinkedHashMap<>();
        for (Operation a : baseline.operations()) {
            String key = a.method() + " " + normalisePath(a.path());
            if (baselineOps.putIfAbsent(key, a) != null) {
                continue; // two baseline templates differing only in parameter names; the first one wins
            }
            Operation b = candidateOps.get(key);
            if (b == null) {
                emit(ChangeKind.OPERATION_REMOVED, true, a, a, "operation", null, null, null,
                        "Operation " + a.key() + " removed.", null, null, null);
            } else {
                compare(a, b);
            }
        }
        for (Map.Entry<String, Operation> e : candidateOps.entrySet()) {
            if (!baselineOps.containsKey(e.getKey())) {
                Operation b = e.getValue();
                emit(ChangeKind.OPERATION_ADDED, false, b, b, "operation", null, null, null,
                        "Operation " + b.key() + " added.", null, null, null);
            }
        }
    }

    private void compare(Operation a, Operation b) {
        parameters(a, b);
        requestBody(a, b);
        responses(a, b);
    }

    private void parameters(Operation a, Operation b) {
        List<String> namesA = templateParameters(a.path());
        List<String> namesB = templateParameters(b.path());
        for (int i = 0; i < Math.min(namesA.size(), namesB.size()); i++) {
            Parameter pa = a.parameter("path", namesA.get(i));
            Parameter pb = b.parameter("path", namesB.get(i));
            if (pa != null && pb != null) {
                schema(requestDiff, a, b, "request.path", null, null,
                        FieldPath.child(FieldPath.ROOT, pa.name()), pa.schema(), pb.schema());
            }
        }
        for (Parameter pa : a.parameters()) {
            if (pa.in().equals("path")) {
                continue;
            }
            String part = "request." + pa.in();
            String field = FieldPath.child(FieldPath.ROOT, pa.name());
            Parameter pb = b.parameter(pa.in(), pa.name());
            if (pb == null) {
                emit(ChangeKind.REQUEST_FIELD_REMOVED, true, a, b, part, null, null, field,
                        capital(pa.in()) + " parameter '" + pa.name() + "' removed from " + a.key() + ".",
                        pa.required() ? "required" : "optional", null, null);
                continue;
            }
            if (pa.required() != pb.required()) {
                emit(pb.required() ? ChangeKind.REQUEST_FIELD_MADE_REQUIRED : ChangeKind.REQUEST_FIELD_MADE_OPTIONAL,
                        pb.required(), a, b, part, null, null, field,
                        capital(pa.in()) + " parameter '" + pa.name() + "' of " + a.key() + " became "
                                + (pb.required() ? "required." : "optional."),
                        pa.required() ? "required" : "optional", pb.required() ? "required" : "optional", null);
            }
            schema(requestDiff, a, b, part, null, null, field, pa.schema(), pb.schema());
        }
        for (Parameter pb : b.parameters()) {
            if (pb.in().equals("path") || a.parameter(pb.in(), pb.name()) != null) {
                continue;
            }
            emit(pb.required() ? ChangeKind.REQUEST_FIELD_ADDED_REQUIRED : ChangeKind.REQUEST_FIELD_ADDED_OPTIONAL,
                    pb.required(), a, b, "request." + pb.in(), null, null,
                    FieldPath.child(FieldPath.ROOT, pb.name()),
                    (pb.required() ? "Required " : "Optional ") + pb.in() + " parameter '" + pb.name()
                            + "' added to " + a.key() + ".",
                    null, pb.required() ? "required" : "optional", null);
        }
    }

    private void requestBody(Operation a, Operation b) {
        Body ba = a.requestBody();
        Body bb = b.requestBody();
        if (ba == null && bb == null) {
            return;
        }
        if (ba == null) {
            emit(ChangeKind.REQUEST_BODY_ADDED, bb.required(), a, b, "request.body", null, null, null,
                    (bb.required() ? "Required" : "Optional") + " request body added to " + a.key() + ".",
                    null, bb.required() ? "required" : "optional", null);
            return;
        }
        if (bb == null) {
            emit(ChangeKind.REQUEST_BODY_REMOVED, true, a, b, "request.body", null, null, null,
                    "Request body removed from " + a.key() + ".", null, null, null);
            return;
        }
        if (ba.required() != bb.required()) {
            emit(bb.required() ? ChangeKind.REQUEST_BODY_MADE_REQUIRED : ChangeKind.REQUEST_BODY_MADE_OPTIONAL,
                    bb.required(), a, b, "request.body", null, null, null,
                    "Request body of " + a.key() + " became " + (bb.required() ? "required." : "optional."),
                    ba.required() ? "required" : "optional", bb.required() ? "required" : "optional", null);
        }
        content(requestDiff, a, b, "request.body", null, ba, bb);
    }

    private void responses(Operation a, Operation b) {
        for (Response ra : a.responses().values()) {
            Response rb = b.responses().get(ra.status());
            if (rb == null) {
                emit(ChangeKind.RESPONSE_STATUS_REMOVED, true, a, b, "response.status", ra.status(), null, null,
                        "Response " + ra.status() + " removed from " + a.key() + ".", null, null, null);
                continue;
            }
            content(responseDiff, a, b, "response.body", ra.status(), ra.body(), rb.body());
        }
        for (Response rb : b.responses().values()) {
            if (!a.responses().containsKey(rb.status())) {
                emit(ChangeKind.RESPONSE_STATUS_ADDED, false, a, b, "response.status", rb.status(), null, null,
                        "Response " + rb.status() + " added to " + a.key() + ".", null, null, null);
            }
        }
    }

    private void content(SchemaDiff differ, Operation a, Operation b, String part, String status, Body ba,
                         Body bb) {
        Map<String, Map.Entry<String, Schema>> candidate = new LinkedHashMap<>();
        for (Map.Entry<String, Schema> e : bb.content().entrySet()) {
            candidate.putIfAbsent(mediaKey(e.getKey()), e);
        }
        Map<String, Boolean> seen = new LinkedHashMap<>();
        String where = (status == null ? "request body" : "response " + status) + " of " + a.key();
        for (Map.Entry<String, Schema> e : ba.content().entrySet()) {
            String key = mediaKey(e.getKey());
            if (seen.put(key, true) != null) {
                continue;
            }
            Map.Entry<String, Schema> other = candidate.get(key);
            if (other == null) {
                emit(ChangeKind.MEDIA_TYPE_REMOVED, true, a, b, part, status, e.getKey(), null,
                        "Media type " + e.getKey() + " removed from the " + where + ".", null, null, null);
                continue;
            }
            schema(differ, a, b, part, status, e.getKey(), FieldPath.ROOT, e.getValue(), other.getValue());
        }
        for (Map.Entry<String, Map.Entry<String, Schema>> e : candidate.entrySet()) {
            if (!seen.containsKey(e.getKey())) {
                emit(ChangeKind.MEDIA_TYPE_ADDED, false, a, b, part, status, e.getValue().getKey(), null,
                        "Media type " + e.getValue().getKey() + " added to the " + where + ".", null, null, null);
            }
        }
    }

    private void schema(SchemaDiff differ, Operation a, Operation b, String part, String status, String mediaType,
                        String at, Schema sa, Schema sb) {
        for (SchemaDiff.Rel rel : differ.diff(sa, sb)) {
            String field = at + rel.field().substring(1);
            emit(rel.kind(), rel.breaking(), a, b, part, status, mediaType, field,
                    describe(rel, field, part, status, a), rel.from(), rel.to(), rel.source());
        }
    }

    private static String describe(SchemaDiff.Rel rel, String field, String part, String status, Operation op) {
        String where = switch (part) {
            case "request.body" -> "request body";
            case "response.body" -> "response " + status;
            default -> part.substring("request.".length()) + " parameters";
        };
        String subject = "Field " + field + " in the " + where + " of " + op.key();
        return switch (rel.kind()) {
            case REQUEST_FIELD_REMOVED, RESPONSE_FIELD_REMOVED -> subject + " removed.";
            case REQUEST_FIELD_ADDED_OPTIONAL -> subject + " added (optional).";
            case REQUEST_FIELD_ADDED_REQUIRED -> subject + " added and required.";
            case RESPONSE_FIELD_ADDED -> subject + " added.";
            case REQUEST_FIELD_MADE_REQUIRED, RESPONSE_FIELD_MADE_REQUIRED -> subject + " became required.";
            case REQUEST_FIELD_MADE_OPTIONAL, RESPONSE_FIELD_MADE_OPTIONAL -> subject + " became optional.";
            case REQUEST_FIELD_TYPE_CHANGED, RESPONSE_FIELD_TYPE_CHANGED ->
                    subject + " changed type from " + rel.from() + " to " + rel.to() + ".";
            case REQUEST_ENUM_NARROWED, RESPONSE_ENUM_NARROWED -> subject + " allows fewer values.";
            case REQUEST_ENUM_WIDENED, RESPONSE_ENUM_WIDENED -> subject + " allows more values.";
            default -> subject + " changed.";
        };
    }

    private void emit(ChangeKind kind, boolean breaking, Operation a, Operation b, String part, String status,
                      String mediaType, String field, String description, String from, String to, String source) {
        changes.add(new Change(kind, breaking, a.method(), a.path(), a.operationId(), part, status, mediaType,
                field, description, from, to, source,
                a.partiallyAnalysed() || b.partiallyAnalysed(), a.approximated() || b.approximated()));
    }

    private static List<String> templateParameters(String path) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while ((i = path.indexOf('{', i)) >= 0) {
            int end = path.indexOf('}', i);
            if (end < 0) {
                break;
            }
            out.add(path.substring(i + 1, end));
            i = end + 1;
        }
        return out;
    }

    private static String mediaKey(String mediaType) {
        String m = mediaType.toLowerCase(Locale.ROOT);
        int semi = m.indexOf(';');
        return (semi >= 0 ? m.substring(0, semi) : m).trim();
    }

    private static String capital(String s) {
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }
}
