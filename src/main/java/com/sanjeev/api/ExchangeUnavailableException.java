package com.sanjeev.api;

/**
 * The exchange cannot answer right now (HTTP 503): it is shutting down, it has stopped after a
 * failure, or it did not reply in time. A timed-out command may STILL have been applied later:
 * the caller cannot tell, which is why real systems let clients choose order ids they can look up.
 */
public class ExchangeUnavailableException extends RuntimeException {

    public ExchangeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
