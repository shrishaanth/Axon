package io.github.shrishaanth.axon.impact;

/** Severity of a breaking change, most severe first. The ladder is defined in docs/metrics.md section 6. */
public enum Severity {
    CRITICAL, HIGH, MEDIUM, LOW, DORMANT, NONE_OBSERVED, UNRATED;

    /**
     * First match wins; request volume plays no part.
     *
     * @param hasIdentity     false when traffic carries no client identity: clients cannot be counted
     * @param affected        distinct affected clients in the window
     * @param recent          affected clients last seen within {@code recentDays} of the window end
     * @param stale           affected clients last seen within {@code staleDays} of the window end
     * @param activeClients   distinct clients seen on any operation within {@code recentDays} of the window end
     * @param criticalInStale true when a client marked critical is affected and was seen within {@code staleDays}
     */
    public static Severity decide(boolean hasIdentity, int affected, int recent, int stale, int activeClients,
                                  boolean criticalInStale, ImpactConfig config) {
        if (!hasIdentity) {
            return UNRATED;
        }
        if (affected == 0) {
            return NONE_OBSERVED;
        }
        double shareRecent = activeClients == 0 ? 0 : recent / (double) activeClients;
        if (recent > 0 && shareRecent >= config.criticalShare()) {
            return CRITICAL;
        }
        if (criticalInStale || recent >= config.highMinClients()
                || (recent > 0 && shareRecent >= config.highShare())) {
            return HIGH;
        }
        if (recent >= 1) {
            return MEDIUM;
        }
        if (stale >= 1) {
            return LOW;
        }
        return DORMANT;
    }
}
