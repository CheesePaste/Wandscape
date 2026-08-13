package com.wsteam.wandscape.building.network;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Round-trip for the chunked building-config sync: compress a real building JSON,
 * split into CHUNK_BYTES slices, concatenate + inflate, and assert byte-identical.
 * Also confirms palette migration keeps even the largest building under the old
 * 262144-char string limit (pre-chunking).
 */
class BuildingConfigChunkingTest {

    @Test
    void roundTripSeaStoreThroughChunks() throws Exception {
        String original;
        try (InputStream is = getClass().getResourceAsStream("/data/wandscape/buildings/sea_store.json")) {
            assertNotNull(is, "sea_store.json must be on test classpath");
            original = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        byte[] compressed = BuildingConfigCompressor.compress(original);
        assertTrue(compressed.length < original.length(),
                "compression must shrink: %d -> %d".formatted(original.length(), compressed.length));

        // Chunk exactly like the server does.
        int totalChunks = Math.max(1, (compressed.length
                + BuildingConfigSyncChunkPacket.CHUNK_BYTES - 1) / BuildingConfigSyncChunkPacket.CHUNK_BYTES);
        byte[][] chunks = new byte[totalChunks][];
        for (int off = 0, i = 0; off < compressed.length; off += BuildingConfigSyncChunkPacket.CHUNK_BYTES, i++) {
            int len = Math.min(BuildingConfigSyncChunkPacket.CHUNK_BYTES, compressed.length - off);
            chunks[i] = java.util.Arrays.copyOfRange(compressed, off, off + len);
        }
        assertEquals(compressed.length, java.util.Arrays.stream(chunks).mapToInt(c -> c.length).sum());

        // Reassemble (client side).
        byte[] joined = new byte[compressed.length];
        int p = 0;
        for (byte[] c : chunks) {
            System.arraycopy(c, 0, joined, p, c.length);
            p += c.length;
        }
        assertEquals(original, BuildingConfigCompressor.inflateToString(joined));
    }

    @Test
    void largestBuildingFitsUnderStringLimit() throws Exception {
        String original;
        try (InputStream is = getClass().getResourceAsStream("/data/wandscape/buildings/sea_store.json")) {
            original = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        // The network sends compact JSON (json.toString()), which is what matters for the
        // 262144 limit. Read the file and strip whitespace as a faithful proxy.
        String compact = original.replaceAll("\\s+", "");
        assertTrue(compact.length() < 262144,
                "compact sea_store must fit under the 262144 string limit, was " + compact.length());
    }
}
