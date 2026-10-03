package io.github.shrishaanth.axon.impact;

import io.github.shrishaanth.axon.diff.Change;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** The result of an impact analysis. {@link ReportWriter} turns it into the published JSON. */
public record ImpactReport(
        ImpactConfig config,
        Instant windowStart,
        Instant windowEnd,
        double windowDays,
        long events,
        long matchedEvents,
        long unmatchedEvents,
        Instant firstEvent,
        Instant lastEvent,
        boolean hasIdentity,
        int activeClients,
        List<Row> rows,
        List<String> limitations) {

    /** How a change's affected set was established. The two are never merged (docs/metrics.md section 2). */
    public enum Evidence {
        /** Traffic shows the affected clients directly: they sent the field or called the operation. */
        OBSERVED,
        /** Traffic shows who calls the operation, not who depends on the changed part. An upper bound. */
        POTENTIAL,
        /** A safe change; no impact is computed. */
        NONE
    }

    /**
     * @param clients     distinct identified clients, or null when traffic carries no identity
     * @param clientIds   every affected client id (not only the top ones); empty without identity
     * @param shareRecent {@code recent / active clients}, or null when it cannot be computed
     */
    public record Exposure(Integer clients, long requests, Double shareRecent, Integer recent, Integer stale,
                           Instant firstSeen, Instant lastSeen, List<TopClient> topClients,
                           Set<String> clientIds) {
    }

    public record TopClient(String client, long requests, Instant lastSeen) {
    }

    /**
     * @param eventsInScope         matched requests for the operation in the window
     * @param minDetectableRate     {@code 3 / windowDays}: clients calling less often may be missing
     * @param unseenRateUpper95     95% upper bound on the rate of something never seen in {@code eventsInScope}
     *                              requests; null when there were none
     */
    public record Confidence(long eventsInScope, double windowDays, double minDetectableRate,
                             Double unseenRateUpper95, String tier) {
    }

    /**
     * @param rank         position among breaking changes in Axon's ranking; null for safe changes
     * @param specOnlyRank position under the spec-only baseline; null for safe changes
     * @param note         a caveat specific to how this row's exposure was computed, or null
     */
    public record Row(String id, Change change, Evidence evidence, Exposure exposure, Severity severity,
                      Integer rank, Integer specOnlyRank, int operationsTouched, Confidence confidence,
                      String note) {
    }

    public List<Row> breaking() {
        return rows.stream().filter(r -> r.change().breaking()).toList();
    }
}
