package com.sanjeev.infra;

import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/** Test double: remembers every write and every flush, and optionally logs into a shared trace. */
final class RecordingWriter extends Writer {

    final List<String> writes = new ArrayList<>();
    int flushes;
    private final List<String> trace;

    RecordingWriter() {
        this(null);
    }

    /** @param trace if not null, "log" is added to it on every write (to check ordering) */
    RecordingWriter(List<String> trace) {
        this.trace = trace;
    }

    @Override
    public void write(String str) {
        writes.add(str);
        if (trace != null) {
            trace.add("log");
        }
    }

    @Override
    public void write(char[] buffer, int offset, int length) {
        write(new String(buffer, offset, length));
    }

    @Override
    public void flush() {
        flushes++;
    }

    @Override
    public void close() {
        // nothing to release
    }
}
