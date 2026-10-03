package io.github.shrishaanth.axon.spec;

/** An operation parameter. {@code in} is one of path, query, header, cookie. */
public record Parameter(String name, String in, boolean required, Schema schema) {

    public String key() {
        return in + ":" + name;
    }
}
