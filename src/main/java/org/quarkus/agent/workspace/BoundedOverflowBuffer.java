package org.quarkus.agent.workspace;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Two-tier bounded buffer resource that buffers in memory up to a threshold (e.g. 1 MB),
 * overflows to a disk temporary file up to a maximum capacity (e.g. 10 MB) using Java NIO
 * {@link FileChannel}, and provides segregated OutputStream (sink) and InputStream (source) abstractions.
 */
public class BoundedOverflowBuffer implements AutoCloseable {

    private final int memoryThreshold;
    private final int maxCapacity;
    private final Path tempDirRoot;

    private ByteArrayOutputStream memoryBuffer;
    private FileChannel fileChannel;
    private Path diskTempFile;
    private int totalBytesWritten = 0;
    private boolean truncated = false;
    private boolean closed = false;

    private final OutputStream sink;

    public BoundedOverflowBuffer(int memoryThreshold, int maxCapacity) {
        this(memoryThreshold, maxCapacity, null);
    }

    public BoundedOverflowBuffer(int memoryThreshold, int maxCapacity, Path tempDirRoot) {
        if (memoryThreshold <= 0) {
            throw new IllegalArgumentException("Memory threshold must be greater than 0");
        }
        if (maxCapacity < memoryThreshold) {
            throw new IllegalArgumentException("Max capacity must be greater than or equal to memory threshold");
        }
        this.memoryThreshold = memoryThreshold;
        this.maxCapacity = maxCapacity;
        this.tempDirRoot = tempDirRoot;
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
            return Files.newInputStream(diskTempFile);
        }
        return new ByteArrayInputStream(memoryBuffer.toByteArray());
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
        IOException failure = null;
        if (fileChannel != null) {
            try {
                fileChannel.close();
            } finally {
                fileChannel = null;
            }
        }
        if (diskTempFile != null) {
            try {
                Files.deleteIfExists(diskTempFile);
            } catch (IOException e) {
                failure = e;
            } finally {
                diskTempFile = null;
            }
        }
        memoryBuffer = null;
        if (failure != null) {
            throw failure;
        }
    }

    private Path createSecureTempFile() throws IOException {
        Path parent = tempDirRoot != null ? tempDirRoot : Path.of(System.getProperty("java.io.tmpdir"));
        FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        );
        return Files.createTempFile(parent, "dev-agent-proc-", ".tmp", attr);
    }

    private void checkOverflow(int incoming) throws IOException {
        if (diskTempFile == null && totalBytesWritten + incoming > memoryThreshold) {
            Path tempFile = createSecureTempFile();
            this.diskTempFile = tempFile;
            try {
                this.fileChannel = FileChannel.open(
                    tempFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.READ
                );
                byte[] inMemoryBytes = memoryBuffer.toByteArray();
                ByteBuffer nioBuffer = ByteBuffer.wrap(inMemoryBytes);
                while (nioBuffer.hasRemaining()) {
                    fileChannel.write(nioBuffer);
                }
                this.memoryBuffer = null;
            } catch (Exception e) {
                close();
                throw e;
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
        if (fileChannel != null) {
            ByteBuffer single = ByteBuffer.wrap(new byte[]{(byte) b});
            fileChannel.write(single);
        } else {
            memoryBuffer.write(b);
        }
        totalBytesWritten++;
    }

    synchronized void appendBytes(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("Buffer is closed");
        }
        Objects.requireNonNull(b, "Buffer must not be null");
        Objects.checkFromIndexSize(off, len, b.length);
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
        if (fileChannel != null) {
            ByteBuffer buf = ByteBuffer.wrap(b, off, toWrite);
            while (buf.hasRemaining()) {
                fileChannel.write(buf);
            }
        } else {
            memoryBuffer.write(b, off, toWrite);
        }
        totalBytesWritten += toWrite;
    }

    synchronized void flushSink() throws IOException {
        // No-op for FileChannel as writes directly populate the OS page cache
    }
}
