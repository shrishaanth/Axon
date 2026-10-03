package io.github.shrishaanth.axon.spec;

/** One declared response. {@code status} is a code such as "200", a range such as "2XX", or "default". */
public record Response(String status, Body body) {
}
