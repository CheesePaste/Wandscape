package com.wsteam.wandscape.content.building.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 建筑方块网格的打包编解码。
 *
 * <p>一个 JSON 对象描述整张 pattern：
 * <pre>
 * "pattern": {
 *   "format":  1,
 *   "origin":  [x, y, z],          // pattern 包围盒最小角（可为负）
 *   "size":    [sx, sy, sz],       // 包围盒尺寸，逐轴 >= 1
 *   "palette": ["minecraft:stone", ...],
 *   "cells":   "&lt;base64(gzip(varint 流))&gt;",
 *   "values":  "&lt;base64(gzip(varint 流))&gt;"
 * }
 * </pre>
 *
 * <p><b>为什么是稀疏的</b>：只存真正有方块的格子。线性下标
 * {@code ((y-oy)*sx + (x-ox))*sz + (z-oz)} 升序排列，存与前一个的差分（首个对 0 差分），
 * 边长整数的变长编码；palette 下标另开一条流、与 cells 逐位对齐。两条流各自 gzip 再 base64。
 *
 * <p>实测（magic_academy，44.3 万格 / 453 种方块）：pretty JSON 29.5 MB → 本格式 189 KB，约 156 倍。
 * 与之对照，按整个包围盒铺满的定长 9 bit 位域方案是 414 KB —— 稀疏流把「没有方块的地方」压缩到
 * 几乎不占体积，代价是解码要顺序走一遍（我们本来就要把整张表实体化成 List，顺序走不亏）。
 *
 * <p><b>为什么自带 origin/size 而不复用 boundary</b>：boundary 是可选的、且语义是「整箱清空的范围」，
 * 与 pattern 的实际包围盒不是一回事（扫描器导出的 boundaryMin 常比 pattern 的 min 更外扩）。
 * 打包数据自带范围，解码器就不必知道 boundary 是否存在、是否与 pattern 对齐。
 *
 * <p><b>编解码对称性由两边共同保证</b>：{@code ScannerExportPacket} 编码、{@code BuildingConfig.Deserializer}
 * 解码，都走这一个类。旧的 {@code pattern} 数组 + {@code block_indices} 形态**不再支持**（版本断档），
 * 见到即抛 {@link JsonParseException}，不做兼容读取。
 *
 * <p>纯逻辑，不引用任何 Minecraft 类型。
 */
public final class PatternCodec {
    private PatternCodec() {}

    /** 顶层键名。 */
    public static final String KEY = "pattern";
    /** 当前格式版本。格式变更时递增，解码器只认自己这一版。 */
    public static final int FORMAT_VERSION = 1;

    private static final String FIELD_FORMAT = "format";
    private static final String FIELD_ORIGIN = "origin";
    private static final String FIELD_SIZE = "size";
    private static final String FIELD_PALETTE = "palette";
    private static final String FIELD_CELLS = "cells";
    private static final String FIELD_VALUES = "values";

    /** 解码结果：三条平行列表，{@code pattern[i]} 的方块是 {@code palette[blockIndices[i]]}。 */
    public record Decoded(List<String> palette, List<BlockOffset> pattern, List<Integer> blockIndices) {}

    // ──────────────── 编码 ────────────────

    /**
     * 把平行三列表打包成 JSON 对象。{@code blockIndices} 必须与 {@code pattern} 等长且取值落在
     * palette 范围内 —— 调用方（扫描器）构造时就保证了这一点，这里只做一次廉价断言。
     */
    public static JsonObject encode(List<String> palette, List<BlockOffset> pattern, List<Integer> blockIndices) {
        int n = pattern.size();
        if (blockIndices.size() != n) {
            throw new IllegalArgumentException("blockIndices.size()=" + blockIndices.size()
                    + " != pattern.size()=" + n);
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockOffset off : pattern) {
            if (off.x() < minX) minX = off.x();
            if (off.y() < minY) minY = off.y();
            if (off.z() < minZ) minZ = off.z();
            if (off.x() > maxX) maxX = off.x();
            if (off.y() > maxY) maxY = off.y();
            if (off.z() > maxZ) maxZ = off.z();
        }
        // 空 pattern：给一个 1³ 的取值范围，下面两条流都是空的，解码回来仍是空表。
        if (n == 0) {
            minX = minY = minZ = 0;
            maxX = maxY = maxZ = 0;
        }
        int sx = maxX - minX + 1;
        int sy = maxY - minY + 1;
        int sz = maxZ - minZ + 1;
        // 线性下标要塞进排序键的高 32 位（下面 << 32），超了会静默翻转符号、把顺序搞乱。
        if ((long) sx * sy * sz > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("pattern volume " + ((long) sx * sy * sz)
                    + " exceeds the packable maximum " + Integer.MAX_VALUE);
        }

        // 排序键：高位线性下标、低位 palette 下标。用 long 排序免掉 boxing。
        long[] combined = new long[n];
        for (int i = 0; i < n; i++) {
            BlockOffset off = pattern.get(i);
            long li = linearIndex(off, minX, minY, minZ, sx, sz);
            int value = blockIndices.get(i);
            if (value < 0 || value >= palette.size()) {
                throw new IllegalArgumentException("palette index " + value + " out of range [0,"
                        + palette.size() + ") at pattern index " + i);
            }
            combined[i] = (li << 32) | (value & 0xFFFFFFFFL);
        }
        Arrays.sort(combined);

        VarintWriter cells = new VarintWriter(n * 2);
        VarintWriter values = new VarintWriter(n * 2);
        long previous = 0;
        for (long entry : combined) {
            long li = entry >>> 32;
            cells.writeVarint(li - previous);
            previous = li;
            values.writeVarint((int) entry);
        }

        JsonObject obj = new JsonObject();
        obj.addProperty(FIELD_FORMAT, FORMAT_VERSION);
        obj.add(FIELD_ORIGIN, intArray(minX, minY, minZ));
        obj.add(FIELD_SIZE, intArray(sx, sy, sz));
        JsonArray paletteArr = new JsonArray(palette.size());
        for (String id : palette) {
            paletteArr.add(id);
        }
        obj.add(FIELD_PALETTE, paletteArr);
        obj.addProperty(FIELD_CELLS, pack(cells.toByteArray()));
        obj.addProperty(FIELD_VALUES, pack(values.toByteArray()));
        return obj;
    }

    // ──────────────── 解码 ────────────────

    /** 解包。结构或版本不符一律抛 {@link JsonParseException}，不猜、不兜底。 */
    public static Decoded decode(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new JsonParseException("'" + KEY + "' must be a packed object (format "
                    + FORMAT_VERSION + "); the legacy array form is no longer supported");
        }
        JsonObject obj = element.getAsJsonObject();

        if (!obj.has(FIELD_FORMAT) || !obj.get(FIELD_FORMAT).isJsonPrimitive()) {
            throw new JsonParseException("'" + KEY + "' is missing '" + FIELD_FORMAT + "'");
        }
        int format = obj.get(FIELD_FORMAT).getAsInt();
        if (format != FORMAT_VERSION) {
            throw new JsonParseException("'" + KEY + "' format " + format + " is not supported (expected "
                    + FORMAT_VERSION + ")");
        }

        int[] origin = readIntTriple(obj, FIELD_ORIGIN);
        int[] size = readIntTriple(obj, FIELD_SIZE);
        int sx = size[0], sy = size[1], sz = size[2];
        if (sx <= 0 || sy <= 0 || sz <= 0) {
            throw new JsonParseException("'" + KEY + "." + FIELD_SIZE + "' must be positive, got ["
                    + sx + ", " + sy + ", " + sz + "]");
        }
        long volume = (long) sx * sy * sz;

        if (!obj.has(FIELD_PALETTE) || !obj.get(FIELD_PALETTE).isJsonArray()) {
            throw new JsonParseException("'" + KEY + "' is missing palette array");
        }
        JsonArray paletteArr = obj.getAsJsonArray(FIELD_PALETTE);
        List<String> palette = new ArrayList<>(paletteArr.size());
        for (JsonElement el : paletteArr) {
            palette.add(el.getAsString());
        }

        byte[] cellBytes = unpack(obj, FIELD_CELLS);
        byte[] valueBytes = unpack(obj, FIELD_VALUES);

        VarintReader cells = new VarintReader(cellBytes);
        VarintReader values = new VarintReader(valueBytes);
        List<BlockOffset> pattern = new ArrayList<>(cells.remainingEstimate());
        List<Integer> blockIndices = new ArrayList<>(cells.remainingEstimate());
        long index = 0;
        while (cells.hasRemaining()) {
            index += cells.readVarint();
            if (index >= volume) {
                throw new JsonParseException("'" + KEY + "' cell linear index " + index
                        + " is outside the declared volume " + volume);
            }
            if (!values.hasRemaining()) {
                throw new JsonParseException("'" + KEY + "' values stream is shorter than cells");
            }
            int value = values.readVarint();
            if (value >= palette.size()) {
                throw new JsonParseException("'" + KEY + "' palette index " + value
                        + " out of range [0," + palette.size() + ")");
            }
            int z = (int) (index % sz);
            long rest = index / sz;
            int x = (int) (rest % sx);
            int y = (int) (rest / sx);
            pattern.add(new BlockOffset(x + origin[0], y + origin[1], z + origin[2]));
            blockIndices.add(value);
        }
        if (values.hasRemaining()) {
            throw new JsonParseException("'" + KEY + "' values stream is longer than cells");
        }

        return new Decoded(List.copyOf(palette), List.copyOf(pattern), List.copyOf(blockIndices));
    }

    // ──────────────── 内部 ────────────────

    private static long linearIndex(BlockOffset off, int ox, int oy, int oz, int sx, int sz) {
        return ((long) (off.y() - oy) * sx + (off.x() - ox)) * sz + (off.z() - oz);
    }

    private static JsonArray intArray(int a, int b, int c) {
        JsonArray arr = new JsonArray(3);
        arr.add(a);
        arr.add(b);
        arr.add(c);
        return arr;
    }

    private static int[] readIntTriple(JsonObject obj, String field) {
        if (!obj.has(field) || !obj.get(field).isJsonArray()) {
            throw new JsonParseException("'" + KEY + "' is missing '" + field + "' array");
        }
        JsonArray arr = obj.getAsJsonArray(field);
        if (arr.size() != 3) {
            throw new JsonParseException("'" + KEY + "." + field + "' requires exactly 3 elements, got "
                    + arr.size());
        }
        return new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt()};
    }

    private static String pack(byte[] raw) {
        ByteArrayOutputStream sink = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
        try (GZIPOutputStream gzip = new BestCompressionGzip(sink)) {
            gzip.write(raw);
        } catch (IOException e) {
            // ByteArrayOutputStream 不会真的抛 IO 异常；真到了这里说明 JVM 出事了，别静默。
            throw new IllegalStateException("gzip failed", e);
        }
        return Base64.getEncoder().encodeToString(sink.toByteArray());
    }

    /** 默认压缩级别是 6；这里要的是落盘体积，压到顶格。 */
    private static final class BestCompressionGzip extends GZIPOutputStream {
        BestCompressionGzip(OutputStream sink) throws IOException {
            super(sink, 512, true);
            def.setLevel(Deflater.BEST_COMPRESSION);
        }
    }

    private static byte[] unpack(JsonObject obj, String field) {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive()) {
            throw new JsonParseException("'" + KEY + "' is missing '" + field + "'");
        }
        byte[] gzipped;
        try {
            gzipped = Base64.getDecoder().decode(obj.get(field).getAsString());
        } catch (IllegalArgumentException e) {
            throw new JsonParseException("'" + KEY + "." + field + "' is not valid base64", e);
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            return gzip.readAllBytes();
        } catch (IOException e) {
            throw new JsonParseException("'" + KEY + "." + field + "' is not a valid gzip stream", e);
        }
    }

    /** 无符号 LEB128 写出器。 */
    private static final class VarintWriter {
        private byte[] buf;
        private int size;

        VarintWriter(int initialCapacity) {
            this.buf = new byte[Math.max(16, initialCapacity)];
        }

        void writeVarint(long value) {
            while ((value & ~0x7FL) != 0) {
                ensure(1);
                buf[size++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            ensure(1);
            buf[size++] = (byte) value;
        }

        private void ensure(int extra) {
            if (size + extra > buf.length) {
                buf = Arrays.copyOf(buf, Math.max(buf.length * 2, size + extra));
            }
        }

        byte[] toByteArray() {
            return Arrays.copyOf(buf, size);
        }
    }

    /** 无符号 LEB128 读出器，越界抛 {@link JsonParseException} 而不是 AIOOBE。 */
    private static final class VarintReader {
        private final byte[] buf;
        private int pos;

        VarintReader(byte[] buf) {
            this.buf = buf;
        }

        boolean hasRemaining() {
            return pos < buf.length;
        }

        int remainingEstimate() {
            return Math.max(16, (buf.length - pos) * 3 / 2);
        }

        int readVarint() {
            int result = 0;
            int shift = 0;
            while (true) {
                if (pos >= buf.length) {
                    throw new JsonParseException("'" + KEY + "' varint stream ended mid-value");
                }
                int b = buf[pos++] & 0xFF;
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift > 28) {
                    throw new JsonParseException("'" + KEY + "' varint value exceeds 32 bits");
                }
            }
        }
    }
}
