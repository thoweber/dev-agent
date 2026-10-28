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
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Set;

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
    private boolean closed = false;

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
        this.sink = new BufferOutputStream(this);
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
        if (closed) {
            throw new IOException("Buffer is closed");
        }
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
        closed = true;
        IOException streamCloseException = null;
        if (fileOutputStream != null) {
            try {
                fileOutputStream.close();
            } catch (IOException e) {
                streamCloseException = e;
            } finally {
                fileOutputStream = null;
            }
        }
        if (diskTempFile != null) {
            try {
                Files.deleteIfExists(diskTempFile);
            } catch (IOException _) {
                // Ignore failure to delete temp file on close
            } finally {
                diskTempFile = null;
            }
        }
        memoryBuffer = null;
        if (streamCloseException != null) {
            throw streamCloseException;
        }
    }

    private Path createSecureTempFile() throws IOException {
        try {
            FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(
                EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            );
            return Files.createTempFile("dev-agent-proc-", ".tmp", attr);
        } catch (UnsupportedOperationException _) {
            Path userTmp = Path.of(System.getProperty("user.home"), ".config", "dev-agent", "tmp");
            Files.createDirectories(userTmp);
            return Files.createTempFile(userTmp, "dev-agent-proc-", ".tmp");
        }
    }

    private void checkOverflow(int incoming) throws IOException {
        if (closed) {
            throw new IOException("Buffer is closed");
        }
        if (diskTempFile == null && totalBytesWritten + incoming > memoryThreshold) {
            diskTempFile = createSecureTempFile();
            fileOutputStream = Files.newOutputStream(diskTempFile);
            if (memoryBuffer != null) {
                memoryBuffer.writeTo(fileOutputStream);
                memoryBuffer = null;
            }
        }
    }

    synchronized void appendByte(int b) throws IOException {
        if (closed) {
            throw new IOException("Buffer is closed");
        }
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

    synchronized void appendBytes(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("Buffer is closed");
        }
        if (b == null) {
            throw new NullPointerException("Buffer must not be null");
        }
        if (off < 0 || len < 0 || off + len > b.length || off + len < 0) {
            throw new IndexOutOfBoundsException("Invalid offset or length: off=" + off + ", len=" + len + ", bufferLength=" + b.length);
        }
        if (len == 0) {
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

    synchronized void flushSink() throws IOException {
        if (fileOutputStream != null) {
            fileOutputStream.flush();
        }
    }
}
