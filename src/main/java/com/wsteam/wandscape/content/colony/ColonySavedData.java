package com.wsteam.wandscape.content.colony;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.colony.roster.ColonyRole;
import com.wsteam.wandscape.foundation.util.NameStyle;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Standalone persistence for colonies — does not depend on buildings.
 *
 * <p>Each colony is stored as (colonyId → origin). On server restart,
 * {@code ColonyApiImpl.rebuildFromSavedData()} reads from this store directly
 * instead of scanning {@code BuildingSavedData} for government buildings.
 */
public class ColonySavedData extends SavedData {
    private static final String TAG = "ColonySavedData";
    private static final String DATA_NAME = "wandscape_colonies";

    private static final String KEY_COLONIES = "colonies";
    private static final String KEY_ID = "id";
    private static final String KEY_X = "x";
    private static final String KEY_Y = "y";
    private static final String KEY_Z = "z";
    private static final String KEY_FOUNDER = "founder";
    private static final String KEY_NAMING_STYLE = "namingStyle";
    private static final String KEY_REVIVE_COOLDOWN_UNTIL = "reviveCooldownUntil";
    private static final String KEY_TOURIST_SPAWN_DISABLED = "touristSpawnDisabled";

    // ── 花名册（v2 起）──
    private static final String KEY_VERSION = "version";
    private static final String KEY_ROSTER = "roster";
    private static final String KEY_PLAYER = "player";
    private static final String KEY_ROLE = "role";

    /**
     * 存档版本。v1 = 无 version 字段的旧档（只有 founder，无花名册）；
     * v2 = 带 {@code colonyId → (playerUuid → ColonyRole)} 花名册。
     * 改数据格式必须走 {@link #migrate} 的显式迁移链，不许留「缺 key 补默认」的兼容分支。
     */
    private static final int CURRENT_VERSION = 2;

    private final Map<UUID, BlockPos> colonies = new ConcurrentHashMap<>();
    /** colonyId → founding player UUID. 归属的权威仍是花名册里的 OWNER，这里保留是为了既有的 founder 反查路径。 */
    private final Map<UUID, UUID> founders = new ConcurrentHashMap<>();
    /** colonyId → (playerUuid → 档位)。一人可在多座镇各有档位，故为两层映射。 */
    private final Map<UUID, Map<UUID, ColonyRole>> rosters = new ConcurrentHashMap<>();
    /** colonyId → character naming rule (defaults to FANTASY when absent). */
    private final Map<UUID, NameStyle> namingStyles = new ConcurrentHashMap<>();
    /** Colony IDs whose town hall 「生成游客」 toggle is OFF (absent = enabled). */
    private final Set<UUID> touristSpawnDisabled = ConcurrentHashMap.newKeySet();
    /** colonyId → game time until which the town hall 「复活法师」 bootstrap revive stays on cooldown (absent = ready). */
    private final Map<UUID, Long> reviveCooldownUntil = new ConcurrentHashMap<>();

    private static final Factory<ColonySavedData> FACTORY = new Factory<>(
            ColonySavedData::new,
            ColonySavedData::load,
            null
    );

    public static ColonySavedData getOrCreate(Level level) {
        return level.getServer().overworld()
                .getDataStorage()
                .computeIfAbsent(FACTORY, DATA_NAME);
    }

    // ── Accessors ──

    public void addColony(UUID colonyId, BlockPos origin) {
        addColony(colonyId, origin, null);
    }

    public void addColony(UUID colonyId, BlockPos origin, @Nullable UUID founder) {
        colonies.put(colonyId, origin.immutable());
        if (founder != null) {
            founders.put(colonyId, founder);
            // 建镇者即花名册第一人（唯一 OWNER）。花名册是权限与友军判定的唯一真源。
            rosterOf(colonyId).put(founder, ColonyRole.OWNER);
        }
        setDirty();
        Log.info(TAG, "[Colony] Persisted colony {} at {}", colonyId.toString().substring(0, 8), origin);
    }

    public void removeColony(UUID colonyId) {
        BlockPos removed = colonies.remove(colonyId);
        founders.remove(colonyId);
        namingStyles.remove(colonyId);
        touristSpawnDisabled.remove(colonyId);
        rosters.remove(colonyId);
        if (removed != null) {
            setDirty();
            Log.info(TAG, "[Colony] Removed colony {} from persistence", colonyId.toString().substring(0, 8));
        }
    }

    @Nullable
    public BlockPos getOrigin(UUID colonyId) {
        return colonies.get(colonyId);
    }

    @Nullable
    public UUID getFounder(UUID colonyId) {
        return founders.get(colonyId);
    }

    /** The colony founded by the given player (one player = one colony), or null. */
    @Nullable
    public UUID getColonyByFounder(UUID founder) {
        for (var entry : founders.entrySet()) {
            if (entry.getValue().equals(founder)) return entry.getKey();
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════
    //  花名册（玩家 → 档位）
    // ══════════════════════════════════════════════════════════════

    /** 该镇花名册的可变视图（不存在则建）。调用方改动后必须自行 {@link #setDirty()}。 */
    private Map<UUID, ColonyRole> rosterOf(UUID colonyId) {
        return rosters.computeIfAbsent(colonyId, k -> new ConcurrentHashMap<>());
    }

    /** 玩家在该镇的档位；不在花名册返回 null（= 非成员，无任何权限）。 */
    @Nullable
    public ColonyRole getRole(UUID colonyId, UUID playerId) {
        Map<UUID, ColonyRole> roster = rosters.get(colonyId);
        return roster != null ? roster.get(playerId) : null;
    }

    /** 该镇花名册（只读快照）。 */
    public Map<UUID, ColonyRole> getRoster(UUID colonyId) {
        Map<UUID, ColonyRole> roster = rosters.get(colonyId);
        return roster != null ? Collections.unmodifiableMap(roster) : Collections.<UUID, ColonyRole>emptyMap();
    }

    /**
     * 玩家参与的所有小镇（colonyId → 档位）。一人可在多座镇各有档位，故返回多值。
     *
     * <p>反向查询靠遍历各镇花名册得出（镇数量级很小）。若将来镇数显著变大，再补反向索引。
     */
    public Map<UUID, ColonyRole> getColoniesOf(UUID playerId) {
        Map<UUID, ColonyRole> out = new LinkedHashMap<>();
        for (var entry : rosters.entrySet()) {
            ColonyRole role = entry.getValue().get(playerId);
            if (role != null) out.put(entry.getKey(), role);
        }
        return out;
    }

    /**
     * 设置档位（新增成员或改档）。**不接受 OWNER** —— 所有权变更走 {@link #transferOwner}，
     * 以免绕过「一座镇恒有且仅有一个 OWNER」这个不变量（它同时是友军白名单的归属锚点）。
     *
     * @return 是否实际发生变更
     */
    public boolean setRole(UUID colonyId, UUID playerId, ColonyRole role) {
        if (role == null || role == ColonyRole.OWNER) {
            Log.warn(TAG, "[Colony] setRole rejected for {}: use transferOwner for OWNER", role);
            return false;
        }
        Map<UUID, ColonyRole> roster = rosterOf(colonyId);
        if (roster.get(playerId) == role) return false;
        roster.put(playerId, role);
        setDirty();
        Log.info(TAG, "[Colony] Roster {}: {} → {}", short8(colonyId), short8(playerId), role);
        return true;
    }

    /** 移出花名册。OWNER 不可被移出（要换人请走 {@link #transferOwner}）。 */
    public boolean removeMember(UUID colonyId, UUID playerId) {
        Map<UUID, ColonyRole> roster = rosters.get(colonyId);
        if (roster == null) return false;
        if (roster.get(playerId) == ColonyRole.OWNER) {
            Log.warn(TAG, "[Colony] Refused to remove the OWNER of colony {}", short8(colonyId));
            return false;
        }
        if (roster.remove(playerId) == null) return false;
        setDirty();
        Log.info(TAG, "[Colony] Roster {}: removed {}", short8(colonyId), short8(playerId));
        return true;
    }

    /**
     * 转让所有权：新人置 OWNER，前任降为 MANAGER（交接不断管理权），并同步 founder 反查。
     * 新人不存在、或与前任同一人时视为无操作。
     */
    public boolean transferOwner(UUID colonyId, UUID newOwnerId) {
        if (newOwnerId == null || !colonies.containsKey(colonyId)) return false;
        UUID prev = founders.get(colonyId);
        if (newOwnerId.equals(prev)) return false;

        Map<UUID, ColonyRole> roster = rosterOf(colonyId);
        if (prev != null) {
            roster.put(prev, ColonyRole.MANAGER);
        }
        roster.put(newOwnerId, ColonyRole.OWNER);
        founders.put(colonyId, newOwnerId);
        setDirty();
        Log.info(TAG, "[Colony] Colony {} ownership transferred {} → {}",
                short8(colonyId), prev != null ? short8(prev) : "none", short8(newOwnerId));
        return true;
    }

    private static String short8(UUID id) {
        return id.toString().substring(0, 8);
    }

    /** Naming rule for future tourist/NPC names; defaults to FANTASY. */
    public NameStyle getNamingStyle(UUID colonyId) {
        return namingStyles.getOrDefault(colonyId, NameStyle.FANTASY);
    }

    public void setNamingStyle(UUID colonyId, NameStyle style) {
        if (style == getNamingStyle(colonyId)) return;
        namingStyles.put(colonyId, style);
        setDirty();
        Log.info(TAG, "[Colony] Naming style for colony {} → {}",
                colonyId.toString().substring(0, 8), style);
    }

    /** True when the colony's town hall has 「生成游客」 enabled (default true). */
    public boolean isTouristSpawningEnabled(UUID colonyId) {
        return !touristSpawnDisabled.contains(colonyId);
    }

    public void setTouristSpawningEnabled(UUID colonyId, boolean enabled) {
        boolean changed = enabled ? touristSpawnDisabled.remove(colonyId)
                : touristSpawnDisabled.add(colonyId);
        if (!changed) return;
        setDirty();
        Log.info(TAG, "[Colony] Tourist spawning for colony {} → {}",
                colonyId.toString().substring(0, 8), enabled ? "enabled" : "disabled");
    }

    /** Game time until which the town hall 「复活法师」 bootstrap revive is on cooldown; 0 = ready. */
    public long getReviveCooldownUntil(UUID colonyId) {
        return reviveCooldownUntil.getOrDefault(colonyId, 0L);
    }

    public void setReviveCooldownUntil(UUID colonyId, long gameTime) {
        if (reviveCooldownUntil.getOrDefault(colonyId, 0L) == gameTime) return;
        if (gameTime <= 0) {
            reviveCooldownUntil.remove(colonyId);
        } else {
            reviveCooldownUntil.put(colonyId, gameTime);
        }
        setDirty();
    }

    public Map<UUID, BlockPos> getAllColonies() {
        return Collections.unmodifiableMap(colonies);
    }

    public int size() {
        return colonies.size();
    }

    /**
     * Write colony data to disk synchronously, bypassing NeoForge's async IO
     * worker. Call this immediately after {@link #addColony} to guarantee the
     * colony survives a crash or quick exit.
     *
     * @param level      the overworld (used to locate the data folder)
     * @param registries the server registry access
     */
    public void saveNow(Level level, HolderLookup.Provider registries) {
        if (!isDirty()) return;

        CompoundTag root = new CompoundTag();
        root.put("data", this.save(new CompoundTag(), registries));
        NbtUtils.addCurrentDataVersion(root);
        CompoundTag copied = root.copy();

        Path filePath = level.getServer().getWorldPath(
                net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data")
                .resolve(DATA_NAME + ".dat");

        try {
            net.neoforged.neoforge.common.IOUtilities.writeNbtCompressed(
                    copied, filePath);
        } catch (IOException e) {
            Log.error(TAG, "Failed to force-save colony data to {}", filePath);
            return;
        }

        setDirty(false);
        Log.info(TAG, "[Colony] Force-saved {} colonies to disk", colonies.size());
    }

    // ── NBT persistence ──

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (var entry : colonies.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID(KEY_ID, entry.getKey());
            BlockPos pos = entry.getValue();
            entryTag.putInt(KEY_X, pos.getX());
            entryTag.putInt(KEY_Y, pos.getY());
            entryTag.putInt(KEY_Z, pos.getZ());
            UUID founder = founders.get(entry.getKey());
            if (founder != null) {
                entryTag.putUUID(KEY_FOUNDER, founder);
            }
            NameStyle style = namingStyles.get(entry.getKey());
            if (style != null) {
                entryTag.putString(KEY_NAMING_STYLE, style.name());
            }
            Long cooldownUntil = reviveCooldownUntil.get(entry.getKey());
            if (cooldownUntil != null && cooldownUntil > 0) {
                entryTag.putLong(KEY_REVIVE_COOLDOWN_UNTIL, cooldownUntil);
            }
            Map<UUID, ColonyRole> roster = rosters.get(entry.getKey());
            if (roster != null && !roster.isEmpty()) {
                ListTag rosterList = new ListTag();
                for (var member : roster.entrySet()) {
                    CompoundTag memberTag = new CompoundTag();
                    memberTag.putUUID(KEY_PLAYER, member.getKey());
                    memberTag.putString(KEY_ROLE, member.getValue().name());
                    rosterList.add(memberTag);
                }
                entryTag.put(KEY_ROSTER, rosterList);
            }
            list.add(entryTag);
        }
        tag.putInt(KEY_VERSION, CURRENT_VERSION);
        tag.put(KEY_COLONIES, list);

        if (!touristSpawnDisabled.isEmpty()) {
            ListTag disabled = new ListTag();
            for (UUID id : touristSpawnDisabled) {
                disabled.add(net.minecraft.nbt.StringTag.valueOf(id.toString()));
            }
            tag.put(KEY_TOURIST_SPAWN_DISABLED, disabled);
        }
        return tag;
    }

    private static ColonySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ColonySavedData data = new ColonySavedData();
        ListTag list = tag.getList(KEY_COLONIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID id = entry.getUUID(KEY_ID);
            int x = entry.getInt(KEY_X);
            int y = entry.getInt(KEY_Y);
            int z = entry.getInt(KEY_Z);
            data.colonies.put(id, new BlockPos(x, y, z));
            if (entry.contains(KEY_FOUNDER)) {
                data.founders.put(id, entry.getUUID(KEY_FOUNDER));
            }
            if (entry.contains(KEY_ROSTER, Tag.TAG_LIST)) {
                ListTag rosterList = entry.getList(KEY_ROSTER, Tag.TAG_COMPOUND);
                Map<UUID, ColonyRole> roster = new ConcurrentHashMap<>();
                for (int j = 0; j < rosterList.size(); j++) {
                    CompoundTag memberTag = rosterList.getCompound(j);
                    ColonyRole role = ColonyRole.byName(memberTag.getString(KEY_ROLE));
                    if (role == null) {
                        Log.warn(TAG, "[Colony] Unknown roster role '{}' for colony {}, entry skipped",
                                memberTag.getString(KEY_ROLE), id);
                        continue;
                    }
                    roster.put(memberTag.getUUID(KEY_PLAYER), role);
                }
                if (!roster.isEmpty()) {
                    data.rosters.put(id, roster);
                }
            }
            if (entry.contains(KEY_NAMING_STYLE)) {
                try {
                    data.namingStyles.put(id, NameStyle.valueOf(entry.getString(KEY_NAMING_STYLE)));
                } catch (IllegalArgumentException e) {
                    Log.warn(TAG, "[Colony] Unknown naming style '{}' for colony {}, using FANTASY",
                            entry.getString(KEY_NAMING_STYLE), id);
                }
            }
            if (entry.contains(KEY_REVIVE_COOLDOWN_UNTIL)) {
                data.reviveCooldownUntil.put(id, entry.getLong(KEY_REVIVE_COOLDOWN_UNTIL));
            }
        }
        Log.info(TAG, "Loaded {} colonies from saved data", data.colonies.size());

        int version = tag.contains(KEY_VERSION) ? tag.getInt(KEY_VERSION) : 1;
        migrate(data, version);

        if (tag.contains(KEY_TOURIST_SPAWN_DISABLED)) {
            ListTag disabled = tag.getList(KEY_TOURIST_SPAWN_DISABLED, Tag.TAG_STRING);
            for (int i = 0; i < disabled.size(); i++) {
                try {
                    data.touristSpawnDisabled.add(UUID.fromString(disabled.getString(i)));
                } catch (IllegalArgumentException e) {
                    Log.warn(TAG, "[Colony] Bad tourist-spawn-disabled UUID '{}', skipped",
                            disabled.getString(i));
                }
            }
        }
        return data;
    }

    /**
     * 显式迁移链。改数据格式必须在这里补一段，不留「缺 key 补默认」的兼容分支。
     *
     * <p>v1 → v2：旧档没有花名册，按「founder = 唯一 OWNER」补齐。
     */
    private static void migrate(ColonySavedData data, int fromVersion) {
        if (fromVersion < 2) {
            for (var entry : data.founders.entrySet()) {
                data.rosterOf(entry.getKey()).put(entry.getValue(), ColonyRole.OWNER);
            }
            Log.info(TAG, "[Colony] Migrated colony roster v{} → v{} (founder promoted to OWNER)",
                    fromVersion, CURRENT_VERSION);
        }
        // 不变量：有 founder 的镇必须有且仅有一个 OWNER。半迁移或异常档在此纠正并留痕。
        for (var entry : data.founders.entrySet()) {
            UUID colonyId = entry.getKey();
            Map<UUID, ColonyRole> roster = data.rosterOf(colonyId);
            if (!roster.containsValue(ColonyRole.OWNER)) {
                roster.put(entry.getValue(), ColonyRole.OWNER);
                Log.warn(TAG, "[Colony] Colony {} had no OWNER in its roster; founder reinstated",
                        colonyId.toString().substring(0, 8));
            }
        }
    }
}
