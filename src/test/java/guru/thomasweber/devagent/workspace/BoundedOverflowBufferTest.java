package guru.thomasweber.devagent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class BoundedOverflowBufferTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given small output below threshold - buffers purely in memory and exposes segregated streams")
    void testInMemoryBuffering() throws IOException {
        try (BoundedOverflowBuffer buffer = new BoundedOverflowBuffer(1024, 4096)) {
            OutputStream out = buffer.outputStream();
            out.write("Hello, dev-agent!".getBytes(StandardCharsets.UTF_8));
            out.flush();

            assertThat(buffer.isOverflowedToDisk()).isFalse();
            assertThat(buffer.isTruncated()).isFalse();
            assertThat(buffer.asString(StandardCharsets.UTF_8)).isEqualTo("Hello, dev-agent!");

            try (InputStream in = buffer.inputStream()) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("Hello, dev-agent!");
            }
        }
    }

    @Test
    @DisplayName("Given output exceeding memory threshold - overflows to disk and preserves full content")
    void testOverflowToDisk() throws IOException {
        int memoryLimit = 100;
        int maxCapacity = 1000;

        try (BoundedOverflowBuffer buffer = new BoundedOverflowBuffer(memoryLimit, maxCapacity, tempDir)) {
            OutputStream out = buffer.outputStream();
            byte[] chunk1 = "A".repeat(80).getBytes(StandardCharsets.UTF_8);
            byte[] chunk2 = "B".repeat(50).getBytes(StandardCharsets.UTF_8);

            out.write(chunk1);
            assertThat(buffer.isOverflowedToDisk()).isFalse();

            out.write(chunk2);
            out.flush();
            assertThat(buffer.isOverflowedToDisk()).isTrue();
            assertThat(buffer.isTruncated()).isFalse();

            String content = buffer.asString(StandardCharsets.UTF_8);
            assertThat(content).hasSize(130);
            assertThat(content).startsWith("A".repeat(80)).endsWith("B".repeat(50));

            try (InputStream in = buffer.inputStream()) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(content);
            }
        }
    }

    @Test
    @DisplayName("Given output exceeding max capacity - truncates content and sets truncated flag")
    void testTruncationAtMaxCapacity() throws IOException {
        int memoryLimit = 50;
        int maxCapacity = 200;

        try (BoundedOverflowBuffer buffer = new BoundedOverflowBuffer(memoryLimit, maxCapacity)) {
            OutputStream out = buffer.outputStream();
            byte[] largeChunk = "X".repeat(300).getBytes(StandardCharsets.UTF_8);
            out.write(largeChunk);
            out.flush();

            assertThat(buffer.isOverflowedToDisk()).isTrue();
            assertThat(buffer.isTruncated()).isTrue();

            String content = buffer.asString(StandardCharsets.UTF_8);
            assertThat(content).hasSize(200);
            assertThat(content).isEqualTo("X".repeat(200));
        }
    }

    @Test
    @DisplayName("Given single byte writes - buffers properly across memory and disk boundary")
    void testSingleByteWritesAndBoundaries() throws IOException {
        try (BoundedOverflowBuffer buffer = new BoundedOverflowBuffer(5, 10)) {
            OutputStream out = buffer.outputStream();
            for (int i = 0; i < 5; i++) {
                out.write('a' + i);
            }
            assertThat(buffer.isOverflowedToDisk()).isFalse();
            assertThat(buffer.getTotalBytesWritten()).isEqualTo(5);

            // Write 6th byte -> overflows to disk
            out.write('f');
            assertThat(buffer.isOverflowedToDisk()).isTrue();
            assertThat(buffer.getTotalBytesWritten()).isEqualTo(6);

            // Write up to max capacity
            for (int i = 6; i < 10; i++) {
                out.write('a' + i);
            }
            assertThat(buffer.isTruncated()).isFalse();
            assertThat(buffer.getTotalBytesWritten()).isEqualTo(10);

            // Additional byte exceeds capacity and sets truncated flag
            out.write('z');
            assertThat(buffer.isTruncated()).isTrue();
            assertThat(buffer.asString()).isEqualTo("abcdefghij");
        }
    }

    @Test
    @DisplayName("Given boundary arguments - write handles nulls, zeros, and closes cleanly")
    void testBoundaryArgumentsAndLifecycle() throws IOException {
        try (BoundedOverflowBuffer buffer = new BoundedOverflowBuffer(1024, 4096)) {
            OutputStream out = buffer.outputStream();
            assertThat(buffer.getTotalBytesWritten()).isZero();

            // Null array throws NPE
            assertThatThrownBy(() -> out.write(null, 0, 0))
                .isInstanceOf(NullPointerException.class);

            // Zero length write is no-op
            out.write(new byte[10], 0, 0);
            assertThat(buffer.getTotalBytesWritten()).isZero();

            // Negative length throws IndexOutOfBoundsException
            assertThatThrownBy(() -> out.write(new byte[10], 0, -1))
                .isInstanceOf(IndexOutOfBoundsException.class);

            // Negative offset throws IndexOutOfBoundsException
            assertThatThrownBy(() -> out.write(new byte[10], -1, 5))
                .isInstanceOf(IndexOutOfBoundsException.class);

            // Offset + length exceeding buffer throws IndexOutOfBoundsException
            assertThatThrownBy(() -> out.write(new byte[10], 6, 5))
                .isInstanceOf(IndexOutOfBoundsException.class);

            out.write("default-buffer-test".getBytes(StandardCharsets.UTF_8));
            out.close();

            assertThat(buffer.asString()).isEqualTo("default-buffer-test");
            buffer.close(); // idempotent close
        }
    }

    @Test
    @DisplayName("Given invalid constructor thresholds - throws IllegalArgumentException")
    void testConstructorValidations() {
        assertThatThrownBy(() -> new BoundedOverflowBuffer(0, 100))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new BoundedOverflowBuffer(100, 0))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new BoundedOverflowBuffer(200, 100))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Given closed buffer - subsequent operations throw IOException and temp file is cleaned up")
    void testClosedBufferThrowsOnSubsequentWrites() throws IOException {
        BoundedOverflowBuffer buffer = new BoundedOverflowBuffer(10, 100);
        OutputStream out = buffer.outputStream();
        out.write("test".getBytes(StandardCharsets.UTF_8));
        buffer.close();

        assertThatThrownBy(() -> out.write('a'))
            .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> out.write(new byte[]{1, 2, 3}))
            .isInstanceOf(IOException.class);
        assertThatThrownBy(buffer::inputStream)
            .isInstanceOf(IOException.class);
        assertThatThrownBy(buffer::asString)
            .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Given buffer at capacity - single byte writes trigger truncation and flushSink flushes disk stream")
    void testSingleByteTruncationAndFlush() throws IOException {
        try (BoundedOverflowBuffer buffer = new BoundedOverflowBuffer(5, 5)) {
            OutputStream out = buffer.outputStream();
            out.write("12345".getBytes(StandardCharsets.UTF_8));
            assertThat(buffer.isTruncated()).isFalse();

            // Next single byte exceeds capacity
            out.write('6');
            assertThat(buffer.isTruncated()).isTrue();
            assertThat(buffer.getTotalBytesWritten()).isEqualTo(5);

            // Array write when already at capacity
            out.write(new byte[]{'7', '8'});
            assertThat(buffer.isTruncated()).isTrue();

            // Flush on memory buffer is safe
            out.flush();
            buffer.flushSink();
        }

        try (BoundedOverflowBuffer diskBuffer = new BoundedOverflowBuffer(5, 20)) {
            OutputStream out = diskBuffer.outputStream();
            out.write("1234567890".getBytes(StandardCharsets.UTF_8));
            assertThat(diskBuffer.isOverflowedToDisk()).isTrue();

            // Flush sink on disk output stream
            out.flush();
            diskBuffer.flushSink();

            // Read as string with custom charset
            String isoString = diskBuffer.asString(StandardCharsets.ISO_8859_1);
            assertThat(isoString).isEqualTo("1234567890");

            // Write up to max capacity and overflow single byte
            out.write("1234567890".getBytes(StandardCharsets.UTF_8));
            assertThat(diskBuffer.isTruncated()).isFalse();
            out.write('X');
            assertThat(diskBuffer.isTruncated()).isTrue();
        }
    }

    @Test
    @DisplayName("Given fault on disk overflow - when write exceeds memory threshold - then cleans up temporary file and preserves in-memory buffer")
    void testDiskOverflowFaultInjectionAndCleanup() throws IOException {
        Path regularFile = tempDir.resolve("file-not-a-directory");
        Files.createFile(regularFile);

        try (BoundedOverflowBuffer buffer = new BoundedOverflowBuffer(10, 100, regularFile)) {
            OutputStream out = buffer.outputStream();

            // In-memory writes below threshold succeed
            out.write("12345".getBytes(StandardCharsets.UTF_8));
            assertThat(buffer.isOverflowedToDisk()).isFalse();
            assertThat(buffer.getTotalBytesWritten()).isEqualTo(5);

            // Exceeding memory threshold attempts overflow into a regular file (not directory) and fails
            assertThatThrownBy(() -> out.write("678901".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IOException.class);

            // Confirm buffer did not set overflowed flag and preserves in-memory content
            assertThat(buffer.isOverflowedToDisk()).isFalse();
            assertThat(buffer.asString()).isEqualTo("12345");
        }
    }

    @Test
    @DisplayName("Given read-only default ACL directory - when overflow occurs - then deletes temporary file and preserves in-memory buffer")
    void testSpillTransitionFailureCleansUpTempFile() throws Exception {
        Path aclDir = Files.createDirectory(tempDir.resolve("acl-restricted-dir"));
        Process setfacl = new ProcessBuilder("setfacl", "-d", "-m", "u::r,g::r,o::r", aclDir.toString()).start();
        if (setfacl.waitFor() != 0) {
            return;
        }

        try (BoundedOverflowBuffer buffer = new BoundedOverflowBuffer(5, 50, aclDir)) {
            OutputStream out = buffer.outputStream();
            out.write("12345".getBytes(StandardCharsets.UTF_8));
            assertThat(buffer.isOverflowedToDisk()).isFalse();

            assertThatThrownBy(() -> out.write('6'))
                .isInstanceOf(IOException.class);

            assertThat(buffer.isOverflowedToDisk()).isFalse();
            assertThat(buffer.asString()).isEqualTo("12345");

            try (Stream<Path> list = Files.list(aclDir)) {
                assertThat(list).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("Given disk-overflowed buffer - close deletes temporary file from filesystem and is idempotent")
    void testOverflowFileDeletedOnCloseAndIdempotentClose() throws IOException {
        BoundedOverflowBuffer buffer = new BoundedOverflowBuffer(10, 100, tempDir);
        OutputStream out = buffer.outputStream();

        // Write enough data to overflow to disk
        out.write("123456789012345".getBytes(StandardCharsets.UTF_8));
        assertThat(buffer.isOverflowedToDisk()).isTrue();

        // Verify a temporary file exists on disk
        try (Stream<Path> list = Files.list(tempDir)) {
            assertThat(list.anyMatch(p -> p.getFileName().toString().startsWith("dev-agent-proc-"))).isTrue();
        }

        // Close buffer
        buffer.close();

        // Verify all temporary files in tempDir are deleted
        try (Stream<Path> list = Files.list(tempDir)) {
            assertThat(list.filter(p -> p.getFileName().toString().startsWith("dev-agent-proc-"))).isEmpty();
        }

        // Multiple subsequent closes are idempotent no-ops
        buffer.close();
        buffer.close();
    }

    @Test
    @DisplayName("Given disk-overflowed buffer when file deletion fails on close - retains path and deletes on retry")
    void testCloseThrowsWhenFileDeletionFails() throws IOException {
        Path restrictedSubdir = Files.createDirectory(tempDir.resolve("restricted-dir"));
        BoundedOverflowBuffer buffer = new BoundedOverflowBuffer(5, 50, restrictedSubdir);
        Path probe = Files.createFile(restrictedSubdir.resolve("probe.tmp"));
        try {
            buffer.outputStream().write("1234567890".getBytes(StandardCharsets.UTF_8));
            assertThat(buffer.isOverflowedToDisk()).isTrue();

            boolean madeNonWritable = restrictedSubdir.toFile().setWritable(false, false);
            boolean deletionBlocked = false;
            if (madeNonWritable) {
                try {
                    Files.delete(probe);
                } catch (IOException _) {
                    deletionBlocked = true;
                }
            }

            if (deletionBlocked) {
                assertThatThrownBy(buffer::close).isInstanceOf(IOException.class);
                assertThat(buffer.isOverflowedToDisk()).isTrue();

                restrictedSubdir.toFile().setWritable(true, false);
                Files.deleteIfExists(probe);
                buffer.close();
                assertThat(buffer.isOverflowedToDisk()).isFalse();

                try (Stream<Path> list = Files.list(restrictedSubdir)) {
                    assertThat(list.filter(p -> p.getFileName().toString().startsWith("dev-agent-proc-"))).isEmpty();
                }
            } else {
                restrictedSubdir.toFile().setWritable(true, false);
                Files.deleteIfExists(probe);
                buffer.close();
            }
        } finally {
            restrictedSubdir.toFile().setWritable(true, false);
            Files.deleteIfExists(probe);
            try {
                buffer.close();
            } catch (Exception _) {
                // Secondary close in cleanup; any failure here is ignored
            }
        }
    }

    @Test
    @DisplayName("Given non-POSIX filesystem - when overflow occurs - then falls back to default temp file creation")
    void testNonPosixFilesystemFallbackUsingSpy() throws IOException {
        BoundedOverflowBuffer buffer = new BoundedOverflowBuffer(5, 50, tempDir);
        BoundedOverflowBuffer spyBuffer = spy(buffer);

        doThrow(new UnsupportedOperationException("Simulated non-POSIX environment"))
            .when(spyBuffer).createTempFileWithPermissions(any(), any());

        try (spyBuffer) {
            OutputStream out = spyBuffer.outputStream();
            out.write("1234567890".getBytes(StandardCharsets.UTF_8));
            assertThat(spyBuffer.isOverflowedToDisk()).isTrue();
            assertThat(spyBuffer.asString()).isEqualTo("1234567890");
        }
    }

    @Test
    @DisplayName("Given channel write failure during spill - when overflow occurs - then closes channel and deletes temp file")
    void testChannelWriteFailureDuringSpillCleansUp() throws IOException {
        BoundedOverflowBuffer buffer = new BoundedOverflowBuffer(5, 50, tempDir);
        BoundedOverflowBuffer spyBuffer = spy(buffer);
        FileChannel mockChannel = mock(FileChannel.class);

        doReturn(mockChannel).when(spyBuffer).openSpillChannel(any());
        doThrow(new IOException("Simulated disk write failure")).when(mockChannel).write(any(ByteBuffer.class));

        try (spyBuffer) {
            OutputStream out = spyBuffer.outputStream();
            out.write("12345".getBytes(StandardCharsets.UTF_8));
            assertThat(spyBuffer.isOverflowedToDisk()).isFalse();

            assertThatThrownBy(() -> out.write('6'))
                .isInstanceOf(IOException.class)
                .hasMessage("Simulated disk write failure");

            verify(mockChannel).close();
            assertThat(spyBuffer.isOverflowedToDisk()).isFalse();
            assertThat(spyBuffer.asString()).isEqualTo("12345");

            try (Stream<Path> list = Files.list(tempDir)) {
                assertThat(list).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("Given channel close failure - when buffer closed - then propagates IOException and clears channel")
    void testChannelCloseFailureDuringBufferClose() throws IOException {
        BoundedOverflowBuffer buffer = new BoundedOverflowBuffer(5, 50, tempDir);
        BoundedOverflowBuffer spyBuffer = spy(buffer);
        FileChannel mockChannel = mock(FileChannel.class);

        doReturn(mockChannel).when(spyBuffer).openSpillChannel(any());
        doAnswer(inv -> {
            ByteBuffer buf = inv.getArgument(0);
            int rem = buf.remaining();
            buf.position(buf.limit());
            return rem;
        }).when(mockChannel).write(any(ByteBuffer.class));
        doThrow(new IOException("Simulated channel close failure")).when(mockChannel).close();

        OutputStream out = spyBuffer.outputStream();
        out.write("1234567890".getBytes(StandardCharsets.UTF_8));
        assertThat(spyBuffer.isOverflowedToDisk()).isTrue();

        assertThatThrownBy(spyBuffer::close)
            .isInstanceOf(IOException.class)
            .hasMessage("Simulated channel close failure");
    }
}
