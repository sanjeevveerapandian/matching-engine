package com.sanjeev.infra;

import java.io.IOException;
import java.io.Writer;

/** Test double: a disk that is always full. */
final class FailingWriter extends Writer {

    @Override
    public void write(String str) throws IOException {
        throw new IOException("disk full");
    }

    @Override
    public void write(char[] buffer, int offset, int length) throws IOException {
        throw new IOException("disk full");
    }

    @Override
    public void flush() {
        // nothing buffered
    }

    @Override
    public void close() {
        // nothing to release
    }
}
