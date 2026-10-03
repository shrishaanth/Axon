package io.github.shrishaanth.axon.spec;

import java.util.Locale;

/** The seven JSON Schema primitive types. */
public enum JsonType {
    STRING, INTEGER, NUMBER, BOOLEAN, NULL, OBJECT, ARRAY;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Returns null for a name that is not a JSON Schema type. */
    public static JsonType fromWire(String s) {
        if (s == null) {
            return null;
        }
        return switch (s) {
            case "string" -> STRING;
            case "integer" -> INTEGER;
            case "number" -> NUMBER;
            case "boolean" -> BOOLEAN;
            case "null" -> NULL;
            case "object" -> OBJECT;
            case "array" -> ARRAY;
            default -> null;
        };
    }
}
