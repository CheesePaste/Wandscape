package com.wsteam.wandscape.ring.internal;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 一名玩家在盟誓戒指中的固定槽存储（纯逻辑，零 Level 依赖，可单测）。
 *
 * <p>槽位固定为 0~{@link #MAX_SLOTS}-1，不随存取塌缩：低级戒指只访问槽 0、
 * 中级只访问槽 0~1、高级访问全部。释放后槽位保持原索引，其它槽的法师不受影响——
 * 这是「低级只能取出/存入第一个」的语义基础。
 *
 * <p>法师以整份实体 NBT 作为不透明数据存放，本类不解析其内容。
 */
public final class OathRingStorage {

    /** 所有戒指共享空间的最大槽位数（高级戒指容量）。 */
    public static final int MAX_SLOTS = 4;

    /** 空槽哨兵：跃载后空槽位放空 CompoundTag，法师 NBT 恒非空故可作空闲判据。 */
    private static final String TAG_SLOTS = "oath_ring_slots";

    /** 固定槽索引 → 法师整份 NBT（null = 空槽）。 */
    private final Map<Integer, CompoundTag> slots = new HashMap<>();

    /** 首个空槽（在 [0, capacity) 内），无则 -1。 */
    public int findStoreSlot(int capacity) {
        int bound = Math.min(capacity, MAX_SLOTS);
        for (int i = 0; i < bound; i++) {
            if (!slots.containsKey(i)) {
                return i;
            }
        }
        return -1;
    }

    /** 首个已占槽（在 [0, capacity) 内），无则 -1。 */
    public int findReleaseSlot(int capacity) {
        int bound = Math.min(capacity, MAX_SLOTS);
        for (int i = 0; i < bound; i++) {
            if (slots.containsKey(i)) {
                return i;
            }
        }
        return -1;
    }

    /** 是否有任一槽已占（跨档位查看）。 */
    public boolean hasAnyStored() {
        return !slots.isEmpty();
    }

    @Nullable
    public CompoundTag get(int slot) {
        return slots.get(slot);
    }

    public void put(int slot, CompoundTag mageNbt) {
        if (slot >= 0 && slot < MAX_SLOTS) {
            slots.put(slot, mageNbt.copy());
        }
    }

    /** 清空槽位并返回被取走的法师 NBT（空槽返回 null）。 */
    @Nullable
    public CompoundTag remove(int slot) {
        return slots.remove(slot);
    }

    /** 已占槽位掩码（bit i = 槽 i 已占，低 4 位有效），服务端→客户端同步用。 */
    public byte toMask() {
        byte mask = 0;
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (slots.containsKey(i)) {
                mask |= (byte) (1 << i);
            }
        }
        return mask;
    }

    // ── 序列化（全部槽位落盘，空槽为空的 CompoundTag）──

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (int i = 0; i < MAX_SLOTS; i++) {
            CompoundTag entry = slots.containsKey(i) ? slots.get(i).copy() : new CompoundTag();
            list.add(entry);
        }
        tag.put(TAG_SLOTS, list);
        return tag;
    }

    public static OathRingStorage fromNbt(CompoundTag tag) {
        OathRingStorage storage = new OathRingStorage();
        if (tag == null || !tag.contains(TAG_SLOTS, Tag.TAG_LIST)) {
            return storage;
        }
        ListTag list = tag.getList(TAG_SLOTS, Tag.TAG_COMPOUND);
        int bound = Math.min(list.size(), MAX_SLOTS);
        for (int i = 0; i < bound; i++) {
            CompoundTag entry = list.getCompound(i);
            if (!entry.isEmpty()) {
                storage.put(i, entry);
            }
        }
        return storage;
    }
}