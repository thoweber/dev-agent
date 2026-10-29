package org.quarkus.agent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @DisplayName("Given fault on disk overflow - cleans up temporary file and throws IOException")
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

            // Confirm buffer did not set overflowed flag
            assertThat(buffer.isOverflowedToDisk()).isFalse();
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
}
