package org.quarkus.agent.workspace;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Two-tier bounded buffer resource that buffers in memory up to a threshold (e.g. 1 MB),
 * overflows to a disk temporary file up to a maximum capacity (e.g. 10 MB),
 * and provides segregated OutputStream (sink) and InputStream (source) abstractions.
 */
public class BoundedOverflowBuffer implements AutoCloseable {

    private final int memoryThreshold;
    private final int maxCapacity;

    private ByteArrayOutputStream memoryBuffer;
    private OutputStream fileOutputStream;
    private Path diskTempFile;
    private int totalBytesWritten = 0;
    private boolean truncated = false;

    private final OutputStream sink;

    public BoundedOverflowBuffer(int memoryThreshold, int maxCapacity) {
        if (memoryThreshold <= 0) {
            throw new IllegalArgumentException("Memory threshold must be greater than 0");
        }
        if (maxCapacity < memoryThreshold) {
            throw new IllegalArgumentException("Max capacity must be greater than or equal to memory threshold");
        }
        this.memoryThreshold = memoryThreshold;
        this.maxCapacity = maxCapacity;
        this.memoryBuffer = new ByteArrayOutputStream(Math.min(memoryThreshold, 8192));
        this.sink = new BufferOutputStream();
    }

    /**
     * Exposes the pure write sink for process stdout/stderr draining.
     */
    public OutputStream outputStream() {
        return sink;
    }

    /**
     * Exposes the pure read source over the captured data (memory or disk).
     */
    public synchronized InputStream inputStream() throws IOException {
        sink.flush();
        if (diskTempFile != null) {
            if (fileOutputStream != null) {
                fileOutputStream.flush();
            }
            return Files.newInputStream(diskTempFile);
        }
        byte[] bytes = memoryBuffer != null ? memoryBuffer.toByteArray() : new byte[0];
        return new ByteArrayInputStream(bytes);
    }

    /**
     * Consumes the input stream and decodes the buffer into a string with the specified charset.
     */
    public synchronized String asString(Charset charset) throws IOException {
        try (InputStream in = inputStream()) {
            return new String(in.readAllBytes(), charset);
        }
    }

    public String asString() throws IOException {
        return asString(StandardCharsets.UTF_8);
    }

    public synchronized boolean isOverflowedToDisk() {
        return diskTempFile != null;
    }

    public synchronized boolean isTruncated() {
        return truncated;
    }

    public synchronized int getTotalBytesWritten() {
        return totalBytesWritten;
    }

    @Override
    public synchronized void close() throws IOException {
        if (fileOutputStream != null) {
            fileOutputStream.close();
            fileOutputStream = null;
        }
        if (diskTempFile != null) {
            try {
                Files.deleteIfExists(diskTempFile);
            } catch (IOException _) {
                // Ignore failure to delete temp file on close
            }
            diskTempFile = null;
        }
    }

    private synchronized void handleWriteByte(int b) throws IOException {
        if (totalBytesWritten >= maxCapacity) {
            truncated = true;
            return;
        }
        checkOverflow(1);
        if (fileOutputStream != null) {
            fileOutputStream.write(b);
        } else {
            memoryBuffer.write(b);
        }
        totalBytesWritten++;
    }

    private synchronized void handleWriteBytes(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException("Buffer must not be null");
        }
        if (len <= 0) {
            return;
        }
        if (totalBytesWritten >= maxCapacity) {
            truncated = true;
            return;
        }
        int toWrite = Math.min(len, maxCapacity - totalBytesWritten);
        if (toWrite < len) {
            truncated = true;
        }
        checkOverflow(toWrite);
        if (fileOutputStream != null) {
            fileOutputStream.write(b, off, toWrite);
        } else {
            memoryBuffer.write(b, off, toWrite);
        }
        totalBytesWritten += toWrite;
    }

    private void checkOverflow(int incoming) throws IOException {
        if (diskTempFile == null && totalBytesWritten + incoming > memoryThreshold) {
            diskTempFile = Files.createTempFile("dev-agent-proc-", ".tmp");
            fileOutputStream = Files.newOutputStream(diskTempFile);
            if (memoryBuffer != null) {
                memoryBuffer.writeTo(fileOutputStream);
                memoryBuffer = null;
            }
        }
    }

    /**
     * Pure OutputStream implementation delegating strictly to the buffer sink.
     */
    private class BufferOutputStream extends OutputStream {

        @Override
        public void write(int b) throws IOException {
            handleWriteByte(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            handleWriteBytes(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            synchronized (BoundedOverflowBuffer.this) {
                if (fileOutputStream != null) {
                    fileOutputStream.flush();
                }
            }
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
