package com.wsteam.wandscape.building.network;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * zlib compression for building-config sync. Building JSONs (repetitive blockstate
 * strings) compress ~8-10x, so each config fits in a handful of 16KB chunks.
 */
public final class BuildingConfigCompressor {
    private static final int MAX_DECOMPRESSED = 128 * 1024 * 1024;

    private BuildingConfigCompressor() {}

    /** zlib-compress a building JSON string to bytes. */
    public static byte[] compress(String json) {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        var deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(data);
        deflater.finish();
        var out = new ByteArrayOutputStream(data.length / 2 + 64);
        byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            out.write(buf, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }

    /** zlib-inflate compressed bytes back to the original JSON string. */
    public static String inflateToString(byte[] compressed) throws DataFormatException {
        return new String(inflate(compressed), StandardCharsets.UTF_8);
    }

    private static byte[] inflate(byte[] data) throws DataFormatException {
        var inflater = new Inflater();
        inflater.setInput(data);
        var out = new ByteArrayOutputStream(data.length * 4 + 64);
        byte[] buf = new byte[8192];
        while (!inflater.finished() && out.size() < MAX_DECOMPRESSED) {
            int n = inflater.inflate(buf);
            if (n == 0) break; // no progress on truncated/corrupt stream — avoid infinite loop
            out.write(buf, 0, n);
        }
        inflater.end();
        return out.toByteArray();
    }
}
