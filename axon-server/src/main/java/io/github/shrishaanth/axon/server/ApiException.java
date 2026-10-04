package io.github.shrishaanth.axon.server;

import org.springframework.http.HttpStatus;

/** An error the client can act on; rendered as {@code {"error": code, "message": ...}}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
