package com.sanjeev.api;

import java.util.List;

/**
 * The body of every error, so callers see one shape whatever went wrong.
 *
 * @param status  the HTTP status code
 * @param error   the standard reason phrase, e.g. "Bad Request"
 * @param message a sentence a person can act on
 * @param details one entry per bad field when there are several (empty otherwise)
 */
public record ErrorResponse(int status, String error, String message, List<FieldProblem> details) {

    /** One field and what is wrong with it. */
    public record FieldProblem(String field, String message) { }
}
