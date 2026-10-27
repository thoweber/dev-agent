package org.quarkus.agent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedOverflowOutputStreamTest {

    @Test
    @DisplayName("Given small output below threshold - buffers purely in memory")
    void testInMemoryBuffering() throws IOException {
        try (BoundedOverflowOutputStream out = new BoundedOverflowOutputStream(1024, 4096)) {
            byte[] data = "Hello, dev-agent!".getBytes(StandardCharsets.UTF_8);
            out.write(data);

            assertThat(out.isOverflowedToDisk()).isFalse();
            assertThat(out.isTruncated()).isFalse();
            assertThat(out.readString(StandardCharsets.UTF_8)).isEqualTo("Hello, dev-agent!");
        }
    }

    @Test
    @DisplayName("Given output exceeding memory threshold - overflows to disk and preserves full content")
    void testOverflowToDisk() throws IOException {
        int memoryLimit = 100;
        int maxCapacity = 1000;

        try (BoundedOverflowOutputStream out = new BoundedOverflowOutputStream(memoryLimit, maxCapacity)) {
            byte[] chunk1 = "A".repeat(80).getBytes(StandardCharsets.UTF_8);
            byte[] chunk2 = "B".repeat(50).getBytes(StandardCharsets.UTF_8);

            out.write(chunk1);
            assertThat(out.isOverflowedToDisk()).isFalse();

            out.write(chunk2);
            assertThat(out.isOverflowedToDisk()).isTrue();
            assertThat(out.isTruncated()).isFalse();

            String content = out.readString(StandardCharsets.UTF_8);
            assertThat(content).hasSize(130);
            assertThat(content).startsWith("A".repeat(80)).endsWith("B".repeat(50));
        }
    }

    @Test
    @DisplayName("Given output exceeding max capacity - truncates content and sets truncated flag")
    void testTruncationAtMaxCapacity() throws IOException {
        int memoryLimit = 50;
        int maxCapacity = 200;

        try (BoundedOverflowOutputStream out = new BoundedOverflowOutputStream(memoryLimit, maxCapacity)) {
            byte[] largeChunk = "X".repeat(300).getBytes(StandardCharsets.UTF_8);
            out.write(largeChunk);

            assertThat(out.isOverflowedToDisk()).isTrue();
            assertThat(out.isTruncated()).isTrue();

            String content = out.readString(StandardCharsets.UTF_8);
            assertThat(content).hasSize(200);
            assertThat(content).isEqualTo("X".repeat(200));
        }
    }
}
