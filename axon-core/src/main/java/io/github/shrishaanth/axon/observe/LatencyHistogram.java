package io.github.shrishaanth.axon.observe;

import java.util.Map;
import java.util.TreeMap;

/**
 * Latencies in logarithmic buckets: bucket {@code k} covers {@code (1.25^(k-1), 1.25^k]} milliseconds. Two
 * histograms merge by adding counts, so batch, replay and live ingestion agree. A percentile is reported as the
 * upper edge of its bucket, so it can overstate the true value by up to 25%.
 */
public final class LatencyHistogram {

    private static final double BASE = 1.25;

    private final Map<Integer, Long> buckets = new TreeMap<>();
    private long count;

    public void add(double millis) {
        add(bucket(millis), 1);
    }

    /** The bucket a latency falls in. */
    public static int bucket(double millis) {
        return millis <= 1.0 ? 0 : (int) Math.ceil(Math.log(millis) / Math.log(BASE) - 1e-9);
    }

    /** Adds {@code n} observations to a bucket; this is how two histograms merge. */
    public void add(int bucket, long n) {
        buckets.merge(bucket, n, Long::sum);
        count += n;
    }

    public long count() {
        return count;
    }

    /** Upper edge of the bucket holding the given quantile (0..1], or NaN when empty. */
    public double percentile(double q) {
        if (count == 0) {
            return Double.NaN;
        }
        long rank = (long) Math.ceil(q * count);
        long seen = 0;
        for (Map.Entry<Integer, Long> e : buckets.entrySet()) {
            seen += e.getValue();
            if (seen >= rank) {
                return Math.pow(BASE, e.getKey());
            }
        }
        return Math.pow(BASE, ((TreeMap<Integer, Long>) buckets).lastKey());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LatencyHistogram h && h.buckets.equals(buckets) && h.count == count;
    }

    @Override
    public int hashCode() {
        return buckets.hashCode();
    }
}
