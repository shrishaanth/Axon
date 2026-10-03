package io.github.shrishaanth.axon.spec;

import java.util.Map;

/** Request or response content: one schema per media type (a media type without a schema maps to null). */
public record Body(boolean required, Map<String, Schema> content) {

    /** The schema of the first JSON-like media type, or null. Traffic analysis only covers JSON bodies. */
    public Schema jsonSchema() {
        for (Map.Entry<String, Schema> e : content.entrySet()) {
            if (isJson(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    public static boolean isJson(String mediaType) {
        if (mediaType == null) {
            return false;
        }
        String m = mediaType.toLowerCase(java.util.Locale.ROOT);
        int semi = m.indexOf(';');
        if (semi >= 0) {
            m = m.substring(0, semi);
        }
        m = m.trim();
        return m.equals("application/json") || m.endsWith("+json") || m.equals("*/*") || m.equals("application/*");
    }
}
