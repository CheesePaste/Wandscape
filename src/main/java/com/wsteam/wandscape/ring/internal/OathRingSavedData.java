package com.wsteam.wandscape.ring.internal;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 盟誓戒指玩家共享空间的全局持久化（存档于 overworld SavedData）。
 *
 * <p>同一玩家的所有盟誓戒指共享同一份固定槽存储（{@link OathRingStorage}），
 * 按存取玩家的 UUID 键控，与戒指物品本身是否在背包中无关。
 */
public class OathRingSavedData extends SavedData {
    private static final String DATA_NAME = "wandscape_oath_rings";

    // NBT keys
    private static final String TAG_PLAYERS = "players";
    private static final String TAG_PLAYER_ID = "player_id";
    private static final String TAG_STORAGE = "storage";

    // player UUID → fixed-slot storage
    private final Map<UUID, OathRingStorage> storageByPlayer = new ConcurrentHashMap<>();

    // ── Factory ──

    public static final Factory<OathRingSavedData> FACTORY = new Factory<>(
            OathRingSavedData::new,
            OathRingSavedData::load,
            null
    );

    public static OathRingSavedData get(MinecraftServer server) {
        return server.overworld()
                .getDataStorage()
                .computeIfAbsent(FACTORY, DATA_NAME);
    }

    // ── Access ──

    /** 取玩家的共享存储（不存在则新建并登记）。 */
    public OathRingStorage storageFor(UUID playerId) {
        return storageByPlayer.computeIfAbsent(playerId, k -> new OathRingStorage());
    }

    /** 玩家当前已占槽掩码（不新建条目），登录/变更后同步客户端用。 */
    public byte maskFor(UUID playerId) {
        OathRingStorage storage = storageByPlayer.get(playerId);
        return storage != null ? storage.toMask() : 0;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag playersTag = new ListTag();
        for (var entry : storageByPlayer.entrySet()) {
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID(TAG_PLAYER_ID, entry.getKey());
            playerTag.put(TAG_STORAGE, entry.getValue().toNbt());
            playersTag.add(playerTag);
        }
        tag.put(TAG_PLAYERS, playersTag);
        return tag;
    }

    private static OathRingSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        OathRingSavedData data = new OathRingSavedData();
        if (tag.contains(TAG_PLAYERS, Tag.TAG_LIST)) {
            ListTag playersTag = tag.getList(TAG_PLAYERS, Tag.TAG_COMPOUND);
            for (int i = 0; i < playersTag.size(); i++) {
                CompoundTag playerTag = playersTag.getCompound(i);
                UUID playerId = playerTag.getUUID(TAG_PLAYER_ID);
                if (playerTag.contains(TAG_STORAGE, Tag.TAG_COMPOUND)) {
                    data.storageByPlayer.put(playerId,
                            OathRingStorage.fromNbt(playerTag.getCompound(TAG_STORAGE)));
                }
            }
        }
        return data;
    }
}