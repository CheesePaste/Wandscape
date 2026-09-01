package com.wsteam.wandscape.content.items.ring.client;

/**
 * 客户端缓存的盟誓戒指共享空间占用掩码（由 {@code OathRingDataPacket} 服务端→客户端同步）。
 *
 * <p>tooltip 渲染据此计算「本档位已存/档位容量」：x = 本戒指容量前缀 [0, capacity) 内已占槽数，
 * y = 档位容量。仅客户端写/读（服务端不渲染 tooltip，静态值无意义且无害）。
 */
public final class OathRingClientData {

    /** 共享空间最大槽位数（与 OathRingStorage.MAX_SLOTS 一致）。 */
    public static final int MAX_SLOTS = 4;

    private static int occupancyMask;

    private OathRingClientData() {}

    public static void setOccupancy(byte mask) {
        occupancyMask = mask & 0x0F;
    }

    /** 本档位容量前缀内已占槽数。 */
    public static int reachable(int capacity) {
        int bound = Math.min(capacity, MAX_SLOTS);
        int mask = occupancyMask & ((1 << bound) - 1);
        return Integer.bitCount(mask);
    }
}