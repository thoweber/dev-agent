package org.quarkus.agent.workspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Output stream that buffers in-memory up to a configured threshold (e.g. 1 MB),
 * overflows to a disk temporary file up to a maximum capacity (e.g. 10 MB),
 * and gracefully truncates subsequent bytes while continuing to drain the input.
 */
public class BoundedOverflowOutputStream extends OutputStream {

    private final int memoryThreshold;
    private final int maxCapacity;

    private ByteArrayOutputStream memoryBuffer;
    private OutputStream fileOutputStream;
    private Path diskTempFile;
    private int totalBytesWritten = 0;
    private boolean truncated = false;

    public BoundedOverflowOutputStream(int memoryThreshold, int maxCapacity) {
        if (memoryThreshold <= 0) {
            throw new IllegalArgumentException("Memory threshold must be greater than 0");
        }
        if (maxCapacity < memoryThreshold) {
            throw new IllegalArgumentException("Max capacity must be greater than or equal to memory threshold");
        }
        this.memoryThreshold = memoryThreshold;
        this.maxCapacity = maxCapacity;
        this.memoryBuffer = new ByteArrayOutputStream(Math.min(memoryThreshold, 8192));
    }

    @Override
    public synchronized void write(int b) throws IOException {
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

    @Override
    public synchronized void write(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException("Input buffer must not be null");
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

    public synchronized String readString(Charset charset) throws IOException {
        flush();
        if (diskTempFile != null) {
            if (fileOutputStream != null) {
                fileOutputStream.flush();
            }
            return Files.readString(diskTempFile, charset);
        }
        return memoryBuffer != null ? memoryBuffer.toString(charset) : "";
    }

    public String readString() throws IOException {
        return readString(StandardCharsets.UTF_8);
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
    public synchronized void flush() throws IOException {
        if (fileOutputStream != null) {
            fileOutputStream.flush();
        }
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
                // Ignore temp file deletion failure on close
            }
            diskTempFile = null;
        }
    }
}
