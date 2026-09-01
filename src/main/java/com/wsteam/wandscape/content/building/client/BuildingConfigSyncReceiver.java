package com.wsteam.wandscape.content.building.client;

import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.building.network.BuildingConfigCompressor;
import com.wsteam.wandscape.content.building.network.BuildingConfigSyncChunkPacket;
import com.wsteam.wandscape.shared.log.Log;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;

/**
 * Client-side reassembly for the chunked building-config sync. Buffers chunks per
 * {@code configIndex}; once all chunks of a config arrive, inflates + registers it.
 * Runs on the Netty client thread (single-threaded per connection, so the maps are
 * safe without locking). A fresh sync is signalled by config 0 / chunk 0, which resets
 * the previous sync state (covers /reload and rejoin).
 */
public final class BuildingConfigSyncReceiver {
    private static final String TAG = "BuildingConfigSync";

    private static final Map<Integer, byte[][]> chunkBuffers = new HashMap<>();
    private static final Map<Integer, Integer> receivedChunks = new HashMap<>();
    private static int completedConfigs = 0;
    private static int totalConfigs = 0;

    private BuildingConfigSyncReceiver() {}

    public static void onChunk(BuildingConfigSyncChunkPacket packet) {
        if (packet.configIndex() == 0 && packet.chunkIndex() == 0) {
            chunkBuffers.clear();
            receivedChunks.clear();
            completedConfigs = 0;
        }
        totalConfigs = packet.totalConfigs();

        byte[][] buffers = chunkBuffers.computeIfAbsent(packet.configIndex(),
                k -> new byte[packet.totalChunks()][]);
        buffers[packet.chunkIndex()] = packet.payload();
        int received = receivedChunks.merge(packet.configIndex(), 1, Integer::sum);
        if (received != packet.totalChunks()) return;

        // All chunks of this config present → inflate + register.
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] chunk : buffers) {
            bos.write(chunk, 0, chunk.length);
        }
        String json;
        try {
            json = BuildingConfigCompressor.inflateToString(bos.toByteArray());
        } catch (DataFormatException e) {
            Log.warn(TAG, "Failed to inflate building config #{}: {}", packet.configIndex(), e.toString());
            chunkBuffers.remove(packet.configIndex());
            receivedChunks.remove(packet.configIndex());
            return;
        }
        chunkBuffers.remove(packet.configIndex());
        receivedChunks.remove(packet.configIndex());
        completedConfigs++;

        BuildingConfigLoader.getInstance().registerFromJsonString(json);
        if (completedConfigs == totalConfigs) {
            Log.info(TAG, "Received and registered {} building configs from server", completedConfigs);
        }
    }
}
