package io.github.shrishaanth.axon.impact;

import java.util.Set;

/**
 * Everything that decides a severity, recorded in the report so a result can be reproduced. Defaults are the
 * ones fixed in docs/metrics.md section 6.
 *
 * @param identityMode       {@code header}, {@code api_key_hash} or {@code none}; informational, copied to the
 *                           report
 * @param serverErrors       {@code info} or {@code drift}: how an undocumented 5xx is reported
 * @param unusedMinRequests  a documented field is reported unused only above this many requests
 */
public record ImpactConfig(
        String identityMode,
        String identityHeader,
        double recentDays,
        double staleDays,
        int highMinClients,
        double highShare,
        double criticalShare,
        Set<String> criticalClients,
        String serverErrors,
        int unusedMinRequests) {

    public static ImpactConfig defaults() {
        return new ImpactConfig("header", null, 7, 30, 3, 0.10, 0.50, Set.of(), "info", 300);
    }

    public ImpactConfig withIdentity(String mode, String header) {
        return new ImpactConfig(mode, header, recentDays, staleDays, highMinClients, highShare, criticalShare,
                criticalClients, serverErrors, unusedMinRequests);
    }

    public ImpactConfig withCriticalClients(Set<String> clients) {
        return new ImpactConfig(identityMode, identityHeader, recentDays, staleDays, highMinClients, highShare,
                criticalShare, Set.copyOf(clients), serverErrors, unusedMinRequests);
    }

    public ImpactConfig withServerErrors(String mode) {
        return new ImpactConfig(identityMode, identityHeader, recentDays, staleDays, highMinClients, highShare,
                criticalShare, criticalClients, mode, unusedMinRequests);
    }
}
