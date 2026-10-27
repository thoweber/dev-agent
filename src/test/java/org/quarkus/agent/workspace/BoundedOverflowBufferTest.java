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
}
