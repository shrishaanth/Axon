package io.github.shrishaanth.axon.spec;

/** The document could not be turned into a model at all. {@link #category()} is stable and used in reports. */
public class SpecParseException extends Exception {

    public enum Category { NOT_PARSEABLE, NOT_OPENAPI, UNSUPPORTED_VERSION, INVALID_STRUCTURE, TOO_LARGE }

    private final Category category;

    public SpecParseException(Category category, String message) {
        super(message);
        this.category = category;
    }

    public SpecParseException(Category category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
