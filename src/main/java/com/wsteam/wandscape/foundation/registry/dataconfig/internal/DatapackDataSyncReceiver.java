package com.wsteam.wandscape.foundation.registry.dataconfig.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.building.network.BuildingConfigCompressor;
import com.wsteam.wandscape.foundation.log.Log;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;

/**
 * 客户端侧通用 datapack 数据同步重组（照 BuildingConfigSyncReceiver）。
 *
 * <p>按 {@code fileIndex} 缓冲分块；整类到齐后 inflate → 解析为
 * {@code {"category": .., "data": {id: json}}} → {@link WandscapeDataLoader#applyCategoryFrom}
 * 灌回 registry。file 0 / chunk 0 表示新一轮同步开始（覆盖 /reload 与重进服）。
 * 运行在 Netty 客户端线程（每连接单线程，缓冲 map 无需加锁），与建筑同步一致。
 */
public final class DatapackDataSyncReceiver {
    private static final String TAG = "DatapackDataSync";

    private static final Map<Integer, byte[][]> chunkBuffers = new HashMap<>();
    private static final Map<Integer, Integer> receivedChunks = new HashMap<>();
    private static int completedFiles = 0;
    private static int totalFiles = 0;

    private DatapackDataSyncReceiver() {}

    public static void onChunk(DatapackDataSyncChunkPacket packet) {
        if (packet.fileIndex() == 0 && packet.chunkIndex() == 0) {
            chunkBuffers.clear();
            receivedChunks.clear();
            completedFiles = 0;
        }
        totalFiles = packet.totalFiles();

        byte[][] buffers = chunkBuffers.computeIfAbsent(packet.fileIndex(),
                k -> new byte[packet.totalChunks()][]);
        buffers[packet.chunkIndex()] = packet.payload();
        int received = receivedChunks.merge(packet.fileIndex(), 1, Integer::sum);
        if (received != packet.totalChunks()) return;

        // 整文件到齐 → inflate + 解析 + 灌回。
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] chunk : buffers) {
            bos.write(chunk, 0, chunk.length);
        }
        chunkBuffers.remove(packet.fileIndex());
        receivedChunks.remove(packet.fileIndex());

        String json;
        try {
            json = BuildingConfigCompressor.inflateToString(bos.toByteArray());
        } catch (DataFormatException e) {
            Log.warn(TAG, "Failed to inflate datapack data file #{}: {}", packet.fileIndex(), e.toString());
            return;
        }
        applyPayload(json);
        completedFiles++;
        if (completedFiles == totalFiles) {
            Log.info(TAG, "Received and applied {} datapack data files from server", completedFiles);
        }
    }

    /** 解析并灌回单个类目的同步载荷（不抛出，坏数据只告警）。 */
    private static void applyPayload(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String category = root.get("category").getAsString();
            JsonObject dataObj = root.getAsJsonObject("data");
            Map<String, JsonElement> entries = new HashMap<>();
            if (dataObj != null) {
                for (var entry : dataObj.entrySet()) {
                    entries.put(entry.getKey(), entry.getValue());
                }
            }
            Wandscape.DATA_LOADER.applyCategoryFrom(category, entries);
        } catch (RuntimeException e) {
            Log.warn(TAG, "Failed to apply datapack data payload: {}", e.toString());
        }
    }
}
