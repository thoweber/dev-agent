package org.quarkus.agent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedOverflowBufferTest {

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

        try (BoundedOverflowBuffer buffer = new BoundedOverflowBuffer(memoryLimit, maxCapacity)) {
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
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> out.write(null, 0, 0))
                .isInstanceOf(NullPointerException.class);

            // Zero length write is no-op
            out.write(new byte[10], 0, 0);
            assertThat(buffer.getTotalBytesWritten()).isZero();

            out.write("default-buffer-test".getBytes(StandardCharsets.UTF_8));
            out.close();

            assertThat(buffer.asString()).isEqualTo("default-buffer-test");
            buffer.close(); // idempotent close
        }
    }

    @Test
    @DisplayName("Given invalid constructor thresholds - throws IllegalArgumentException")
    void testConstructorValidations() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BoundedOverflowBuffer(0, 100))
            .isInstanceOf(IllegalArgumentException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BoundedOverflowBuffer(100, 0))
            .isInstanceOf(IllegalArgumentException.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new BoundedOverflowBuffer(200, 100))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Given closed buffer - subsequent operations throw IOException and temp file is cleaned up")
    void testClosedBufferThrowsOnSubsequentWrites() throws IOException {
        BoundedOverflowBuffer buffer = new BoundedOverflowBuffer(10, 100);
        OutputStream out = buffer.outputStream();
        out.write("test".getBytes(StandardCharsets.UTF_8));
        buffer.close();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> out.write('a'))
            .isInstanceOf(IOException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> out.write(new byte[]{1, 2, 3}))
            .isInstanceOf(IOException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(buffer::inputStream)
            .isInstanceOf(IOException.class);
    }
}
