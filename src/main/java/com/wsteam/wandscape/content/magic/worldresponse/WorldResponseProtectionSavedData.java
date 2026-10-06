package com.wsteam.wandscape.content.magic.worldresponse;

import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 「移山填海不许动的方块」名单：**按玩家各存一份**，落在主世界 DataStorage 里，跨重启。
 *
 * <p>放在这里而不是做成方块标签 / 配置项，是因为它是一件**玩家自己的偏好**：谁都能加自己的，
 * 不影响服务器上别人，也不需要权限。做成标签就得让玩家去写数据包再 `/reload`，做成 TOML 就得重启。
 *
 * <p>名单是「方块」粒度（不是方块状态）：加了 `minecraft:oak_stairs` 就是所有朝向的橡木楼梯都不动。
 * 除了这份名单，移山填海另有几条**不可被名单覆盖**的硬保护（不可破坏、带方块实体、建筑地皮、
 * 传送门与门框），见 {@link TerraformEffect}。
 *
 * <p>落盘格式带 {@code version}：读到不认识的版本就整份忽略（记 warn）——按仓库的格式纪律，
 * 不留「缺 key 补默认」的兼容分支，真要改格式就断档。
 */
public final class WorldResponseProtectionSavedData extends SavedData {

    public static final String DATA_NAME = "wandscape_world_response_protection";

    private static final String TAG = "WorldResponse";
    private static final int VERSION = 1;
    private static final String TAG_VERSION = "version";
    private static final String TAG_PLAYERS = "players";
    private static final String TAG_UUID = "uuid";
    private static final String TAG_BLOCKS = "blocks";

    private static final Set<Block> EMPTY = Set.of();

    private final Map<UUID, Set<Block>> blacklists = new HashMap<>();

    public static final Factory<WorldResponseProtectionSavedData> FACTORY = new Factory<>(
            WorldResponseProtectionSavedData::new,
            WorldResponseProtectionSavedData::load,
            null);

    private static WorldResponseProtectionSavedData get(Level level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** 这名玩家的名单（只读视图）。**一次扫描取一次**，别在每个方块上重复查表。 */
    public static Set<Block> blacklist(Level level, UUID playerId) {
        if (level == null || playerId == null) return EMPTY;
        Set<Block> mine = get(level).blacklists.get(playerId);
        return mine == null ? EMPTY : mine;
    }

    /** 名单内容（按 id 排序，给指令回显用）。 */
    public static List<String> ids(Level level, UUID playerId) {
        if (level == null || playerId == null) return List.of();
        Set<Block> mine = get(level).blacklists.get(playerId);
        if (mine == null || mine.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(mine.size());
        for (Block block : mine) {
            out.add(BuiltInRegistries.BLOCK.getKey(block).toString());
        }
        out.sort(null);
        return out;
    }

    /** @return 真的加进去了（本来就在名单里则 false） */
    public static boolean add(Level level, UUID playerId, Block block) {
        if (level == null || playerId == null || block == null) return false;
        WorldResponseProtectionSavedData data = get(level);
        Set<Block> mine = data.blacklists.computeIfAbsent(playerId, k -> new HashSet<>());
        if (!mine.add(block)) return false;
        data.setDirty();
        return true;
    }

    /** @return 真的移出去了（本来就不在名单里则 false） */
    public static boolean remove(Level level, UUID playerId, Block block) {
        if (level == null || playerId == null || block == null) return false;
        WorldResponseProtectionSavedData data = get(level);
        Set<Block> mine = data.blacklists.get(playerId);
        if (mine == null || !mine.remove(block)) return false;
        if (mine.isEmpty()) data.blacklists.remove(playerId);
        data.setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(TAG_VERSION, VERSION);
        ListTag players = new ListTag();
        for (Map.Entry<UUID, Set<Block>> e : blacklists.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putUUID(TAG_UUID, e.getKey());
            List<String> ids = new ArrayList<>(e.getValue().size());
            for (Block block : e.getValue()) {
                ids.add(BuiltInRegistries.BLOCK.getKey(block).toString());
            }
            ids.sort(null);
            ListTag blocks = new ListTag();
            for (String id : ids) {
                blocks.add(StringTag.valueOf(id));
            }
            entry.put(TAG_BLOCKS, blocks);
            players.add(entry);
        }
        tag.put(TAG_PLAYERS, players);
        return tag;
    }

    private static WorldResponseProtectionSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldResponseProtectionSavedData data = new WorldResponseProtectionSavedData();
        int version = tag.getInt(TAG_VERSION);
        if (version != VERSION) {
            Log.warn(TAG, "[WorldResponse] Protection list version {} is not {} — ignoring the file",
                    version, VERSION);
            return data;
        }
        ListTag players = tag.getList(TAG_PLAYERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag entry = players.getCompound(i);
            if (!entry.hasUUID(TAG_UUID)) continue;
            ListTag blocks = entry.getList(TAG_BLOCKS, Tag.TAG_STRING);
            Set<Block> set = new HashSet<>();
            for (int j = 0; j < blocks.size(); j++) {
                ResourceLocation id = ResourceLocation.tryParse(blocks.getString(j));
                Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
                if (block == null) {
                    // 方块被删了（换整合包/换模组）：直接丢这一条，不猜、不补默认
                    Log.warn(TAG, "[WorldResponse] Unknown block '{}' in the protection list — dropped",
                            blocks.getString(j));
                    continue;
                }
                set.add(block);
            }
            if (!set.isEmpty()) data.blacklists.put(entry.getUUID(TAG_UUID), set);
        }
        return data;
    }
}
