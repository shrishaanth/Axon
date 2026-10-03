package io.github.shrishaanth.axon.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.shrishaanth.axon.diff.Change;
import io.github.shrishaanth.axon.drift.DriftFinding;
import io.github.shrishaanth.axon.impact.ImpactReport.Evidence;
import io.github.shrishaanth.axon.impact.ImpactReport.Exposure;
import io.github.shrishaanth.axon.impact.ImpactReport.Row;
import io.github.shrishaanth.axon.impact.ImpactReport.TopClient;
import io.github.shrishaanth.axon.spec.ApiSpec;
import io.github.shrishaanth.axon.spec.Unsupported;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes the impact and drift report in the format of schemas/impact-report.schema.json. */
public final class ReportWriter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_UNSUPPORTED = 200;

    /** Identity of a spec file as shown in the report. */
    public record SpecRef(String path, String sha256, ApiSpec spec) {
    }

    private ReportWriter() {
    }

    public static ObjectNode write(ImpactReport report, List<DriftFinding> drift, SpecRef baseline,
                                   SpecRef candidate, String usageSource, Instant generatedAt) {
        ObjectNode out = JSON.createObjectNode();
        out.put("schema_version", "0.2");
        out.put("generated_at", generatedAt.toString());

        ObjectNode inputs = out.putObject("inputs");
        inputs.set("baseline", specRef(baseline));
        inputs.set("candidate", specRef(candidate));
        ObjectNode usage = inputs.putObject("usage");
        usage.put("source", usageSource);
        usage.put("events", report.events());
        usage.put("matched_events", report.matchedEvents());
        usage.put("unmatched_events", report.unmatchedEvents());
        usage.put("first_event", text(report.firstEvent()));
        usage.put("last_event", text(report.lastEvent()));

        ImpactConfig c = report.config();
        ObjectNode config = out.putObject("config");
        ObjectNode window = config.putObject("window");
        window.put("start", text(report.windowStart()));
        window.put("end", text(report.windowEnd()));
        window.put("days", report.windowDays());
        ObjectNode identity = config.putObject("client_identity");
        identity.put("mode", report.hasIdentity() ? c.identityMode() : "none");
        if (report.hasIdentity() && c.identityHeader() != null) {
            identity.put("header", c.identityHeader());
        }
        ObjectNode thresholds = config.putObject("thresholds");
        thresholds.put("recent_days", c.recentDays());
        thresholds.put("stale_days", c.staleDays());
        thresholds.put("high_min_clients", c.highMinClients());
        thresholds.put("high_share", c.highShare());
        thresholds.put("critical_share", c.criticalShare());
        thresholds.put("unused_min_requests", c.unusedMinRequests());
        config.set("critical_clients", JSON.valueToTree(c.criticalClients().stream().sorted().toList()));
        config.put("server_errors", c.serverErrors());

        ObjectNode summary = out.putObject("summary");
        summary.put("changes_total", report.rows().size());
        summary.put("breaking_total", report.breaking().size());
        if (report.hasIdentity()) {
            summary.put("active_clients", report.activeClients());
        } else {
            summary.putNull("active_clients");
        }
        ObjectNode bySeverity = summary.putObject("by_severity");
        ImpactEngine.bySeverity(report).forEach((evidence, counts) -> {
            ObjectNode bucket = bySeverity.putObject(evidence.name().toLowerCase(Locale.ROOT));
            counts.forEach((severity, n) -> bucket.put(severity.name(), n));
        });

        ArrayNode changes = out.putArray("changes");
        for (Row r : report.rows()) {
            changes.add(row(r));
        }

        ArrayNode driftArray = out.putArray("drift");
        if (drift != null) {
            for (DriftFinding d : drift) {
                driftArray.add(d.toJson());
            }
        }

        ArrayNode unsupported = out.putArray("unsupported");
        Map<String, Unsupported> merged = new LinkedHashMap<>();
        for (SpecRef ref : List.of(baseline, candidate)) {
            for (Unsupported u : ref.spec().unsupported()) {
                merged.putIfAbsent(u.construct() + "@" + u.location(), u);
            }
        }
        int listed = 0;
        for (Unsupported u : merged.values()) {
            if (listed++ >= MAX_UNSUPPORTED) {
                break;
            }
            ObjectNode n = unsupported.addObject();
            n.put("construct", u.construct());
            n.put("location", u.location());
            n.put("reason", u.reason());
            n.put("handling", u.handling());
        }
        out.put("unsupported_total", merged.size());
        out.set("limitations", JSON.valueToTree(report.limitations()));
        return out;
    }

    private static ObjectNode specRef(SpecRef ref) {
        ObjectNode n = JSON.createObjectNode();
        n.put("path", ref.path());
        n.put("sha256", ref.sha256());
        if (ref.spec().title() != null) {
            n.put("title", ref.spec().title());
        }
        if (ref.spec().version() != null) {
            n.put("version", ref.spec().version());
        }
        n.put("openapi", ref.spec().openapi());
        return n;
    }

    private static ObjectNode row(Row r) {
        Change c = r.change();
        ObjectNode n = JSON.createObjectNode();
        n.put("id", r.id());
        n.put("kind", c.kind().wire());
        n.put("breaking", c.breaking());
        if (c.partiallyAnalysed()) {
            n.put("partially_analysed", true);
        }
        if (c.approximated()) {
            n.put("approximated", true);
        }
        ObjectNode op = n.putObject("operation");
        op.put("method", c.method());
        op.put("path", c.path());
        if (c.operationId() != null) {
            op.put("operation_id", c.operationId());
        }
        ObjectNode location = n.putObject("location");
        location.put("part", c.part());
        if (c.status() != null) {
            location.put("status", c.status());
        }
        if (c.mediaType() != null) {
            location.put("media_type", c.mediaType());
        }
        if (c.field() != null) {
            location.put("field", c.field());
        }
        n.put("description", c.description());
        if (c.from() != null) {
            n.put("from", c.from());
        }
        if (c.to() != null) {
            n.put("to", c.to());
        }
        if (c.source() != null) {
            n.put("source", c.source());
        }
        n.put("evidence", r.evidence().name().toLowerCase(Locale.ROOT));
        if (r.evidence() == Evidence.OBSERVED) {
            n.set("observed_affected", exposure(r.exposure()));
        } else if (r.evidence() == Evidence.POTENTIAL) {
            n.set("potentially_affected", exposure(r.exposure()));
        }
        if (r.note() != null) {
            n.put("evidence_note", r.note());
        }
        if (r.severity() == null) {
            n.putNull("severity");
        } else {
            n.put("severity", r.severity().name());
        }
        if (r.rank() == null) {
            n.putNull("rank");
            n.putNull("spec_only_rank");
        } else {
            n.put("rank", r.rank());
            n.put("spec_only_rank", r.specOnlyRank());
            n.put("operations_touched", r.operationsTouched());
        }
        ObjectNode confidence = n.putObject("confidence");
        confidence.put("events_in_scope", r.confidence().eventsInScope());
        confidence.put("window_days", r.confidence().windowDays());
        confidence.put("min_detectable_rate_per_day", r.confidence().minDetectableRate());
        if (r.confidence().unseenRateUpper95() == null) {
            confidence.putNull("unseen_rate_upper_95");
        } else {
            confidence.put("unseen_rate_upper_95", r.confidence().unseenRateUpper95());
        }
        confidence.put("tier", r.confidence().tier());
        return n;
    }

    private static ObjectNode exposure(Exposure e) {
        ObjectNode n = JSON.createObjectNode();
        if (e.clients() == null) {
            n.putNull("clients");
        } else {
            n.put("clients", e.clients());
        }
        n.put("requests", e.requests());
        if (e.shareRecent() == null) {
            n.putNull("share_recent");
        } else {
            n.put("share_recent", e.shareRecent());
        }
        if (e.recent() == null) {
            n.putNull("k_recent");
        } else {
            n.put("k_recent", e.recent());
        }
        n.put("first_seen", text(e.firstSeen()));
        n.put("last_seen", text(e.lastSeen()));
        ArrayNode top = n.putArray("top_clients");
        for (TopClient t : e.topClients()) {
            ObjectNode tn = top.addObject();
            tn.put("client", t.client());
            tn.put("requests", t.requests());
            tn.put("last_seen", text(t.lastSeen()));
        }
        return n;
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    public static String toJson(ObjectNode node) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
