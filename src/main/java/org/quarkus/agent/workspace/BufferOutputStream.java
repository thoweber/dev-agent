package org.quarkus.agent.workspace;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Segregated OutputStream sink delegating strictly to BoundedOverflowBuffer.
 */
class BufferOutputStream extends OutputStream {

    private final BoundedOverflowBuffer buffer;

    BufferOutputStream(BoundedOverflowBuffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public void write(int b) throws IOException {
        buffer.appendByte(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        buffer.appendBytes(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        buffer.flushSink();
    }

    @Override
    public void close() throws IOException {
        flush();
    }
}
