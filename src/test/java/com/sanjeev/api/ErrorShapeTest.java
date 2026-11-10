package com.sanjeev.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import java.beans.PropertyChangeEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

/**
 * Every failure must reach the caller in ONE shape ({@link ErrorResponse}), and an unexpected one
 * must never leak its real message. These call the handler directly, to reach the branches that a
 * normal HTTP request rarely triggers.
 */
class ErrorShapeTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final WebRequest request = new ServletWebRequest(new MockHttpServletRequest());

    private static ErrorResponse body(ResponseEntity<Object> response) {
        return (ErrorResponse) response.getBody();
    }

    private HttpMessageNotReadableException unreadable(MismatchedInputException cause) {
        return new HttpMessageNotReadableException("bad body", cause, new MockHttpInputMessage(new byte[0]));
    }

    @Test
    void anInvalidRequestWithoutAFieldHasNoFieldDetails() {
        ResponseEntity<Object> response = handler.invalidRequest(new InvalidRequestException(null, "limit must be 1 to 200"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("limit must be 1 to 200", body(response).message());
        assertTrue(body(response).details().isEmpty());
    }

    @Test
    void anInvalidRequestWithAFieldNamesIt() {
        ResponseEntity<Object> response = handler.invalidRequest(new InvalidRequestException("price", "must be positive"));

        assertEquals("price", body(response).details().get(0).field());
    }

    @Test
    void aWrongTypeInsideAListNamesTheElementByItsPosition() {
        MismatchedInputException cause = MismatchedInputException.from((com.fasterxml.jackson.core.JsonParser) null,
                Object.class, "bad");
        cause.prependPath(new Object(), 0);          // the element...
        cause.prependPath(new Object(), "orders");   // ...of the list called "orders"

        ResponseEntity<Object> response = handler.handleHttpMessageNotReadable(
                unreadable(cause), new HttpHeaders(), HttpStatus.BAD_REQUEST, request);

        assertEquals(400, response.getStatusCode().value());
        assertTrue(body(response).message().contains("orders.[0]"), body(response).message());
    }

    @Test
    void aWrongTypeParameterNamesTheParameterEvenWhenSpringDoesNotSayWhichArgumentItWas() {
        TypeMismatchException problem = new TypeMismatchException(
                new PropertyChangeEvent(new Object(), "quantity", null, "abc"), Long.class);

        ResponseEntity<Object> response = handler.handleTypeMismatch(
                problem, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);

        assertEquals("parameter 'quantity' must be a whole number", body(response).message());
    }

    @Test
    void aServerErrorFromSpringNeverRevealsItsRealMessage() {
        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new RuntimeException("jdbc password is hunter2"), null, new HttpHeaders(),
                HttpStatus.INTERNAL_SERVER_ERROR, request);

        assertEquals(500, response.getStatusCode().value());
        assertEquals("an unexpected error occurred", body(response).message());
        assertFalse(body(response).message().contains("hunter2"));
    }

    @Test
    void aClientErrorFromSpringUsesTheDetailSpringGave() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "Method 'PUT' is not supported");

        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new RuntimeException("ignored"), problem, new HttpHeaders(), HttpStatus.METHOD_NOT_ALLOWED, request);

        assertEquals("Method 'PUT' is not supported", body(response).message());
        assertEquals("Method Not Allowed", body(response).error());
    }

    @Test
    void aClientErrorWithNoDetailFallsBackToTheExceptionMessage() {
        ProblemDetail noDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);

        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new RuntimeException("the reason"), noDetail, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);

        assertEquals("the reason", body(response).message());
    }

    @Test
    void aStatusCodeWithoutAStandardNameIsStillAnswered() {
        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new RuntimeException("odd"), null, new HttpHeaders(), HttpStatusCode.valueOf(499), request);

        assertEquals(499, response.getStatusCode().value());
        assertEquals("Error", body(response).error());
    }
}
