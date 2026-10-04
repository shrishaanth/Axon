package io.github.shrishaanth.axon.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.List;

/** Small statistics helpers for the experiments. */
public final class Stats {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Stats() {
    }

    /** Kendall's tau-b (handles ties); NaN when one of the two is constant. */
    public static double kendallTauB(double[] x, double[] y) {
        int n = x.length;
        long concordant = 0;
        long discordant = 0;
        long tiesX = 0;
        long tiesY = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = Math.signum(x[i] - x[j]);
                double dy = Math.signum(y[i] - y[j]);
                if (dx == 0 && dy == 0) {
                    continue;
                }
                if (dx == 0) {
                    tiesX++;
                } else if (dy == 0) {
                    tiesY++;
                } else if (dx == dy) {
                    concordant++;
                } else {
                    discordant++;
                }
            }
        }
        double denominator = Math.sqrt((double) (concordant + discordant + tiesX) * (concordant + discordant + tiesY));
        return denominator == 0 ? Double.NaN : (concordant - discordant) / denominator;
    }

    public static double mean(List<Double> values) {
        return values.stream().filter(v -> !v.isNaN()).mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    /** Linear-interpolated quantile of the non-NaN values. */
    public static double quantile(List<Double> values, double q) {
        double[] sorted = values.stream().filter(v -> !v.isNaN()).mapToDouble(Double::doubleValue).sorted().toArray();
        if (sorted.length == 0) {
            return Double.NaN;
        }
        double position = q * (sorted.length - 1);
        int low = (int) Math.floor(position);
        int high = (int) Math.ceil(position);
        return sorted[low] + (sorted[high] - sorted[low]) * (position - low);
    }

    /** {mean, p10, median, p90, n} of a list, as JSON. */
    public static ObjectNode summary(List<Double> values) {
        ObjectNode o = JSON.createObjectNode();
        long n = values.stream().filter(v -> !v.isNaN()).count();
        put(o, "mean", mean(values));
        put(o, "p10", quantile(values, 0.10));
        put(o, "median", quantile(values, 0.50));
        put(o, "p90", quantile(values, 0.90));
        o.put("n", n);
        return o;
    }

    /** Writes a rounded number, or JSON null for NaN (which is not valid JSON). */
    public static void put(ObjectNode o, String name, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            o.putNull(name);
        } else {
            o.put(name, round(value));
        }
    }

    public static double round(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? v : Math.round(v * 10_000.0) / 10_000.0;
    }

    public static double[] toArray(List<Double> values) {
        double[] out = new double[values.size()];
        Arrays.setAll(out, values::get);
        return out;
    }
}
