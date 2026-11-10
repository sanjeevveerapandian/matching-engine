package com.sanjeev.api;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into the same JSON shape ({@link ErrorResponse}) with the right status:
 * 400 for a bad request, 404 for an unknown order, 422 if the engine refuses an order, 503 if the
 * exchange cannot answer, 500 for anything unexpected (logged, but its details are not shown).
 *
 * <p>It extends Spring's own handler so that the standard cases (wrong method 405, wrong content
 * type 415, unknown URL 404, bad parameters) are also covered, and only their body is replaced.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ------------------------------------------------------------------ our own exceptions

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<Object> invalidRequest(InvalidRequestException e) {
        List<ErrorResponse.FieldProblem> details = e.field() == null
                ? List.of()
                : List.of(new ErrorResponse.FieldProblem(e.field(), e.getMessage()));
        return respond(HttpStatus.BAD_REQUEST, e.getMessage(), details, null);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<Object> orderNotFound(OrderNotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, e.getMessage(), List.of(), null);
    }

    @ExceptionHandler(OrderRejectedException.class)
    ResponseEntity<Object> orderRejected(OrderRejectedException e) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), List.of(), null);
    }

    @ExceptionHandler(ExchangeUnavailableException.class)
    ResponseEntity<Object> exchangeUnavailable(ExchangeUnavailableException e) {
        LOG.warn("exchange unavailable: {}", e.getMessage());
        return respond(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), List.of(), null);
    }

    /** The last resort. The caller gets a generic message; the real cause goes to the log. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e) {
        LOG.error("unexpected error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "an unexpected error occurred", List.of(), null);
    }

    // ------------------------------------------------------------------ Spring's own failures, re-worded

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ErrorResponse.FieldProblem> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldProblem(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(ErrorResponse.FieldProblem::field)
                        .thenComparing(ErrorResponse.FieldProblem::message))
                .toList();
        String message = "invalid request: " + details.stream()
                .map(problem -> problem.field() + " - " + problem.message())
                .collect(Collectors.joining("; "));
        return respond(HttpStatus.BAD_REQUEST, message, details, headers);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message;
        if (ex.getCause() instanceof UnrecognizedPropertyException unknown) {
            message = "unknown field '" + unknown.getPropertyName() + "'";
        } else if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            String field = mismatch.getPath().stream()
                    .map(reference -> reference.getFieldName() == null ? "[" + reference.getIndex() + "]"
                            : reference.getFieldName())
                    .collect(Collectors.joining("."));
            message = "field '" + field + "' has the wrong type or an invalid value";
        } else {
            message = "the request body is missing or is not valid JSON";
        }
        return respond(HttpStatus.BAD_REQUEST, message, List.of(), headers);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException argument ? argument.getName() : ex.getPropertyName();
        return respond(HttpStatus.BAD_REQUEST, "parameter '" + name + "' must be a whole number", List.of(), headers);
    }

    /** Everything else Spring raises (405, 415, 404 for an unknown URL, a missing parameter, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        String message = statusCode.is5xxServerError()
                ? "an unexpected error occurred"
                : body instanceof ProblemDetail problem && problem.getDetail() != null
                        ? problem.getDetail()
                        : ex.getMessage();
        return respond(statusCode, message, List.of(), headers);
    }

    // ------------------------------------------------------------------ helper

    private static ResponseEntity<Object> respond(HttpStatusCode status, String message,
            List<ErrorResponse.FieldProblem> details, HttpHeaders headers) {
        HttpStatus known = HttpStatus.resolve(status.value());
        ErrorResponse body = new ErrorResponse(status.value(),
                known == null ? "Error" : known.getReasonPhrase(), message, details);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (headers != null) {
            builder.headers(headers);
        }
        return builder.body(body);
    }
}
