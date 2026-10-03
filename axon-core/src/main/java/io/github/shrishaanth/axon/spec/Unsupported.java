package io.github.shrishaanth.axon.spec;

/**
 * A construct the parser recognised but does not model exactly. Never silently dropped.
 *
 * @param handling {@link #IGNORED} when the construct has no effect on the model, {@link #APPROXIMATED} when it is
 *                 modelled loosely (for example {@code oneOf} as a union of its variants)
 */
public record Unsupported(String construct, String location, String reason, String handling) {
    public static final String IGNORED = "ignored";
    public static final String APPROXIMATED = "approximated";
}
