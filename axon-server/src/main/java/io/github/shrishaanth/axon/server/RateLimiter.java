package io.github.shrishaanth.axon.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** A token bucket per key, held in memory: enough for one instance, which is all this deployment has. */
@Component
public class RateLimiter {

    private static final class Bucket {
        double tokens;
        long lastNanos;
    }

    private final double perSecond;
    private final double burst;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(AxonProperties properties) {
        this.perSecond = properties.getIngestRequestsPerSecond();
        this.burst = Math.max(1, perSecond * 2);
    }

    /** True if the request may proceed. */
    public boolean allow(String key) {
        if (perSecond <= 0) {
            return true;
        }
        Bucket b = buckets.computeIfAbsent(key, k -> {
            Bucket fresh = new Bucket();
            fresh.tokens = burst;
            fresh.lastNanos = System.nanoTime();
            return fresh;
        });
        synchronized (b) {
            long now = System.nanoTime();
            b.tokens = Math.min(burst, b.tokens + (now - b.lastNanos) / 1e9 * perSecond);
            b.lastNanos = now;
            if (b.tokens < 1) {
                return false;
            }
            b.tokens -= 1;
            return true;
        }
    }
}
