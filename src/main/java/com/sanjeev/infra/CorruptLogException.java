package com.sanjeev.infra;

import java.io.IOException;

/**
 * The log is damaged in a way that cannot be explained by a crash while writing the last line
 * (a bad line in the middle, a gap or a repeat in the sequence numbers). Recovery refuses to
 * guess: silently skipping a command in the middle would rebuild a different exchange.
 */
public final class CorruptLogException extends IOException {

    public CorruptLogException(String message) {
        super(message);
    }
}
