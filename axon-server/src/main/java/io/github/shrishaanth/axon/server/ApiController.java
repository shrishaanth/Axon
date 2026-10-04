package io.github.shrishaanth.axon.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.diff.Change;
import io.github.shrishaanth.axon.diff.SpecDiff;
import io.github.shrishaanth.axon.drift.DriftEngine;
import io.github.shrishaanth.axon.drift.DriftFinding;
import io.github.shrishaanth.axon.impact.ImpactConfig;
import io.github.shrishaanth.axon.impact.ImpactEngine;
import io.github.shrishaanth.axon.impact.ImpactReport;
import io.github.shrishaanth.axon.impact.ReportWriter;
import io.github.shrishaanth.axon.observe.Aggregate;
import io.github.shrishaanth.axon.observe.Views;
import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.Explorer;
import io.github.shrishaanth.axon.spec.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HTTP API.
 *
 * <p>Two keys per workspace: the ingest key ({@code X-Axon-Key}) can only add events; the admin key
 * ({@code X-Axon-Admin}) reads results and changes the spec. Stateless endpoints ({@code /api/diff},
 * {@code /api/explore}) need no key and store nothing.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Workspaces workspaces;
    private final AxonProperties properties;
    private final RateLimiter limiter;

    public ApiController(Workspaces workspaces, AxonProperties properties, RateLimiter limiter) {
        this.workspaces = workspaces;
        this.properties = properties;
        this.limiter = limiter;
    }

    @GetMapping("/health")
    public ObjectNode health() {
        return JSON.createObjectNode().put("status", "ok");
    }

    // ---- workspaces

    @PostMapping("/workspaces")
    public ResponseEntity<ObjectNode> create(HttpServletRequest request) throws IOException {
        JsonNode body = json(read(request, 10_000));
        String name = body.path("name").asText("").trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_name", "Give the workspace a name of 1-100 characters.");
        }
        if (!limiter.allow("create:" + request.getRemoteAddr())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests; slow down.");
        }
        Workspaces.Created created = workspaces.create(name);
        ObjectNode out = JSON.createObjectNode();
        out.put("id", created.id().toString());
        out.put("name", name);
        out.put("ingest_key", created.ingestKey());
        out.put("admin_key", created.adminKey());
        out.put("note", "Both keys are shown once. The ingest key can only add events; the admin key reads results.");
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @GetMapping("/workspaces/{id}")
    public ObjectNode workspace(@PathVariable UUID id, @RequestHeader(value = "X-Axon-Admin", required = false) String key) {
        Store.Workspace w = workspaces.authorise(id, key);
        ObjectNode out = JSON.createObjectNode();
        out.put("id", w.id().toString());
        out.put("name", w.name());
        out.put("events", w.eventCount());
        out.put("max_events", w.maxEvents());
        out.put("retention_days", w.retentionDays());
        out.put("created_at", w.createdAt().toString());
        if (w.specText() == null) {
            out.putNull("spec");
        } else {
            ApiSpec spec = workspaces.spec(w);
            ObjectNode s = out.putObject("spec");
            s.put("name", w.specName());
            s.put("sha256", w.specSha256().trim());
            s.put("title", spec.title());
            s.put("version", spec.version());
            s.put("operations", spec.operations().size());
        }
        return out;
    }

    @PutMapping("/workspaces/{id}/spec")
    public ObjectNode setSpec(@PathVariable UUID id, @RequestHeader(value = "X-Axon-Admin", required = false) String key,
                              @RequestParam(defaultValue = "spec") String name, HttpServletRequest request)
            throws IOException {
        Store.Workspace w = workspaces.authorise(id, key);
        Workspaces.ReplayResult replay = workspaces.setSpec(w, name, read(request, properties.getMaxSpecBytes()));
        ObjectNode out = JSON.createObjectNode();
        out.put("operations", workspaces.spec(workspaces.authorise(id, key)).operations().size());
        out.set("replay", replay(replay));
        return out;
    }

    @PostMapping("/workspaces/{id}/replay")
    public ObjectNode replay(@PathVariable UUID id, @RequestHeader(value = "X-Axon-Admin", required = false) String key) {
        return replay(workspaces.replay(workspaces.authorise(id, key)));
    }

    private static ObjectNode replay(Workspaces.ReplayResult r) {
        return JSON.createObjectNode().put("events", r.events()).put("cells", r.cells()).put("millis", r.millis());
    }

    // ---- ingest

    @PostMapping("/ingest")
    public ObjectNode ingest(@RequestHeader(value = "X-Axon-Key", required = false) String key,
                             HttpServletRequest request) throws IOException {
        Store.Workspace w = workspaces.byIngestKey(key);
        if (!limiter.allow("ingest:" + w.id())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                    "More than " + properties.getIngestRequestsPerSecond() + " ingest requests per second.");
        }
        String body = read(request, properties.getMaxIngestBytes());
        List<String> lines = new ArrayList<>();
        String trimmed = body.stripLeading();
        if (trimmed.startsWith("[")) {
            for (JsonNode event : json(body)) {
                lines.add(event.toString());
            }
        } else {
            for (String line : body.split("\n")) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        Workspaces.IngestResult result = workspaces.ingest(w, lines);
        ObjectNode out = JSON.createObjectNode();
        out.put("accepted", result.accepted());
        out.put("rejected", result.rejected());
        out.set("errors", JSON.valueToTree(result.errors()));
        return out;
    }

    // ---- analysis

    @PostMapping("/workspaces/{id}/impact")
    public ObjectNode impact(@PathVariable UUID id, @RequestHeader(value = "X-Axon-Admin", required = false) String key,
                             @RequestParam(required = false) LocalDate from,
                             @RequestParam(required = false) LocalDate to,
                             @RequestParam(defaultValue = "candidate") String name,
                             @RequestParam(required = false) String critical,
                             @RequestParam(defaultValue = "info") String serverErrors,
                             HttpServletRequest request) throws IOException {
        Store.Workspace w = workspaces.authorise(id, key);
        requireSpec(w);
        String candidateText = read(request, properties.getMaxSpecBytes());
        ApiSpec baseline = workspaces.spec(w);
        ApiSpec candidate = workspaces.parse(candidateText);
        Aggregate aggregate = workspaces.aggregate(w, from, to);
        ImpactConfig config = config(aggregate, critical, serverErrors);
        ImpactReport report = ImpactEngine.analyse(SpecDiff.diff(baseline, candidate), aggregate, config);
        List<DriftFinding> drift = DriftEngine.analyse(aggregate, config);
        return ReportWriter.write(report, drift,
                new ReportWriter.SpecRef(w.specName(), w.specSha256().trim(), baseline),
                new ReportWriter.SpecRef(name, Store.sha256(candidateText), candidate), "ingest", Instant.now());
    }

    @GetMapping("/workspaces/{id}/drift")
    public ObjectNode drift(@PathVariable UUID id, @RequestHeader(value = "X-Axon-Admin", required = false) String key,
                            @RequestParam(required = false) LocalDate from,
                            @RequestParam(required = false) LocalDate to,
                            @RequestParam(defaultValue = "info") String serverErrors) {
        Store.Workspace w = workspaces.authorise(id, key);
        requireSpec(w);
        Aggregate aggregate = workspaces.aggregate(w, from, to);
        ObjectNode out = JSON.createObjectNode();
        ArrayNode list = out.putArray("drift");
        DriftEngine.analyse(aggregate, config(aggregate, null, serverErrors)).forEach(d -> list.add(d.toJson()));
        out.put("events", aggregate.events().count());
        return out;
    }

    @GetMapping("/workspaces/{id}/contract")
    public ObjectNode contract(@PathVariable UUID id, @RequestHeader(value = "X-Axon-Admin", required = false) String key,
                               @RequestParam(required = false) LocalDate from,
                               @RequestParam(required = false) LocalDate to) {
        Store.Workspace w = workspaces.authorise(id, key);
        requireSpec(w);
        return Views.contract(workspaces.aggregate(w, from, to));
    }

    @GetMapping("/workspaces/{id}/clients")
    public ObjectNode clients(@PathVariable UUID id, @RequestHeader(value = "X-Axon-Admin", required = false) String key,
                              @RequestParam(required = false) LocalDate from,
                              @RequestParam(required = false) LocalDate to) {
        Store.Workspace w = workspaces.authorise(id, key);
        return Views.clients(workspaces.aggregate(w, from, to));
    }

    @GetMapping("/workspaces/{id}/explore")
    public ObjectNode explore(@PathVariable UUID id, @RequestHeader(value = "X-Axon-Admin", required = false) String key,
                              @RequestParam(required = false) String method,
                              @RequestParam(required = false) String path) {
        Store.Workspace w = workspaces.authorise(id, key);
        requireSpec(w);
        return explore(workspaces.spec(w), method, path);
    }

    // ---- stateless

    @PostMapping("/explore")
    public ObjectNode exploreUploaded(@RequestParam(required = false) String method,
                                      @RequestParam(required = false) String path, HttpServletRequest request)
            throws IOException {
        return explore(workspaces.parse(read(request, properties.getMaxSpecBytes())), method, path);
    }

    /** Body: {@code {"baseline": "<spec text>", "candidate": "<spec text>"}}. */
    @PostMapping("/diff")
    public ObjectNode diff(HttpServletRequest request) throws IOException {
        JsonNode body = json(read(request, properties.getMaxSpecBytes() * 2));
        ApiSpec baseline = workspaces.parse(body.path("baseline").asText(null));
        ApiSpec candidate = workspaces.parse(body.path("candidate").asText(null));
        SpecDiff.Result result = SpecDiff.diff(baseline, candidate);
        ObjectNode out = JSON.createObjectNode();
        out.put("changes_total", result.changes().size());
        out.put("breaking_total", result.breaking().size());
        out.put("truncated", result.truncated());
        ArrayNode changes = out.putArray("changes");
        for (Change c : result.changes()) {
            if (changes.size() >= 2000) {
                out.put("listed", 2000);
                break;
            }
            ObjectNode n = changes.addObject();
            n.put("kind", c.kind().wire());
            n.put("breaking", c.breaking());
            n.put("method", c.method());
            n.put("path", c.path());
            n.put("part", c.part());
            if (c.status() != null) {
                n.put("status", c.status());
            }
            if (c.field() != null) {
                n.put("field", c.field());
            }
            n.put("description", c.description());
        }
        return out;
    }

    private static ObjectNode explore(ApiSpec spec, String method, String path) {
        if (method == null || path == null) {
            return Explorer.describe(spec);
        }
        Operation op = spec.operation(method.toUpperCase(java.util.Locale.ROOT), path);
        if (op == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "no_such_operation", "No " + method + " " + path + " in the spec.");
        }
        return Explorer.describe(op);
    }

    private static void requireSpec(Store.Workspace w) {
        if (w.specText() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "no_spec",
                    "Upload the workspace's current spec first (PUT /api/workspaces/{id}/spec).");
        }
    }

    private static ImpactConfig config(Aggregate aggregate, String critical, String serverErrors) {
        if (!serverErrors.equals("info") && !serverErrors.equals("drift")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_parameter", "serverErrors must be info or drift.");
        }
        ImpactConfig config = ImpactConfig.defaults()
                .withIdentity(aggregate.hasIdentity() ? "header" : "none", null)
                .withServerErrors(serverErrors);
        if (critical != null && !critical.isBlank()) {
            Set<String> ids = new HashSet<>();
            for (String c : critical.split(",")) {
                if (!c.trim().matches("[0-9a-f]{16}")) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "bad_parameter",
                            "critical takes client pseudonyms (16 hex characters), comma-separated.");
                }
                ids.add(c.trim());
            }
            config = config.withCriticalClients(ids);
        }
        return config;
    }

    /** Reads the body, refusing anything over {@code limit} bytes without buffering the excess. */
    private static String read(HttpServletRequest request, int limit) throws IOException {
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            // The servlet container reads a form body as parameters, leaving nothing here. curl sends this type by
            // default with --data-binary.
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "form_content_type",
                    "Send the body as text/plain, application/json or application/yaml, not as a form "
                            + "(with curl: -H 'Content-Type: text/plain').");
        }
        long declared = request.getContentLengthLong();
        if (declared > limit) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "The body is larger than " + limit + " bytes.");
        }
        try (InputStream in = request.getInputStream()) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large",
                        "The body is larger than " + limit + " bytes.");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static JsonNode json(String text) {
        try {
            JsonNode node = JSON.readTree(text);
            if (node == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "bad_json", "The body is empty.");
            }
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_json", "The body is not valid JSON.");
        }
    }

    // ---- errors

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ObjectNode> handle(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(JSON.createObjectNode().put("error", e.code()).put("message", e.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ObjectNode> handle(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(JSON.createObjectNode().put("error", "bad_parameter")
                .put("message", "Parameter '" + e.getName() + "' is not valid (dates are YYYY-MM-DD)."));
    }
}
