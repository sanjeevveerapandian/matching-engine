package com.sanjeev.infra;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Writer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test double: a disk that makes the engine thread WAIT in its first write until the test says go,
 * then either succeeds or fails. It lets a test hold the engine thread still while it arranges
 * exactly the situation it wants (a full queue, a producer waiting, ...).
 */
final class GatedWriter extends Writer {

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final boolean failWhenReleased;

    GatedWriter(boolean failWhenReleased) {
        this.failWhenReleased = failWhenReleased;
    }

    /** Wait until the engine thread is inside its first write. */
    void awaitEntered() throws InterruptedException {
        if (!entered.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("the engine thread never reached the log");
        }
    }

    /** Let the waiting write carry on (and fail, if this writer was made to fail). */
    void release() {
        released.countDown();
    }

    @Override
    public void write(char[] buffer, int offset, int length) throws IOException {
        entered.countDown();
        try {
            if (!released.await(10, TimeUnit.SECONDS)) {
                throw new IOException("the test never released the gate");
            }
        } catch (InterruptedException e) {
            throw new InterruptedIOException("interrupted while gated");
        }
        if (failWhenReleased) {
            throw new IOException("disk full");
        }
    }

    @Override
    public void flush() { }

    @Override
    public void close() { }
}
