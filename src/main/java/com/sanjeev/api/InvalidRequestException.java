package com.sanjeev.api;

/** The caller sent something unacceptable (HTTP 400). {@code field} names it when it is one field. */
public class InvalidRequestException extends RuntimeException {

    private final String field;

    public InvalidRequestException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** The request field that is wrong, or null if the problem is not about one field. */
    public String field() {
        return field;
    }
}
