package com.wsteam.wandscape.content.items.scepter.internal;
import com.wsteam.wandscape.content.task.types.EntityId;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家权杖的殖民地标记存储（纯 Java，零 MC 依赖，可单测）。
 *
 * <p>每个殖民地一份 {@link ColonyMarks}：庇护名单（{@code Set<UUID>} 生物 UUID）+ 单槽强制
 * 仇恨目标（{@code forcedHostile}）。庇护目标同时是友军（法师不主动攻击/不误伤）；强制仇恨目标
 * 让本殖民地 128 格内法师优先集火。敌对与庇护同目标互斥：把某个生物标记为强制仇恨时先撤庇护，
 * 否则守卫过滤器会把它当友军滤掉。
 *
 * <p>只存实体 UUID 不存实体引用（实体可能卸载/重生）；死亡清理等由服务端 {@link ScepterService}
 * 驱动。持久化由 {@link ScepterMarksSavedData} 负责（NBT 落盘），本类不做 NBT。
 */
public final class ScepterMarks {

    private final Map<UUID, ColonyMarks> byColony = new ConcurrentHashMap<>();

    /** 单殖民地标记：庇护名单 + 单槽强制仇恨目标。 */
    public static final class ColonyMarks {
        private final Set<UUID> sheltered = new HashSet<>();
        @Nullable
        private UUID forcedHostile = null;

        public Set<UUID> sheltered() {
            return sheltered;
        }

        @Nullable
        public UUID forcedHostile() {
            return forcedHostile;
        }

        /** 仅序列化恢复用：加入庇护名单。 */
        void putSheltered(UUID entityUuid) {
            sheltered.add(entityUuid);
        }

        /** 仅序列化恢复用：写入强制仇恨目标。 */
        void putForcedHostile(UUID entityUuid) {
            forcedHostile = entityUuid;
        }
    }

    // ── 庇护 ──

    /**
     * 切换庇护状态：未庇护则庇护、已庇护则解除。
     *
     * @return 切换后是否处于庇护状态
     */
    public boolean toggleShelter(UUID colonyId, UUID entityUuid) {
        ColonyMarks marks = marks(colonyId);
        boolean present = marks.sheltered.contains(entityUuid);
        if (present) {
            marks.sheltered.remove(entityUuid);
        } else {
            marks.sheltered.add(entityUuid);
        }
        prune(colonyId, marks);
        return !present;
    }

    /** 目标是否被指定殖民地的庇护名单收录。 */
    public boolean isSheltered(UUID colonyId, UUID entityUuid) {
        ColonyMarks marks = byColony.get(colonyId);
        return marks != null && marks.sheltered.contains(entityUuid);
    }

    /** 目标是否被任意殖民地庇护（守卫触发扫描用——庇护生物不构成对任何小镇的威胁）。 */
    public boolean isShelteredForAny(UUID entityUuid) {
        for (ColonyMarks marks : byColony.values()) {
            if (marks.sheltered.contains(entityUuid)) return true;
        }
        return false;
    }

    // ── 强制仇恨 ──

    /**
     * 切换强制仇恨目标：目标是当前标记则解除；否则设为新目标（转移：替换旧标记）。
     * 设置时若该目标已被庇护则先撤庇护（敌对/庇护互斥）。
     *
     * @return 切换后是否处于强制仇恨标记状态
     */
    public boolean toggleForcedHostile(UUID colonyId, UUID entityUuid) {
        ColonyMarks marks = marks(colonyId);
        if (entityUuid.equals(marks.forcedHostile)) {
            marks.forcedHostile = null;
            prune(colonyId, marks);
            return false;
        }
        // 敌对与庇护互斥：同目标绝不同时庇护
        marks.sheltered.remove(entityUuid);
        marks.forcedHostile = entityUuid;
        return true;
    }

    /** 当前强制仇恨目标 UUID（无则 null）。 */
    @Nullable
    public UUID forcedHostile(UUID colonyId) {
        ColonyMarks marks = byColony.get(colonyId);
        return marks != null ? marks.forcedHostile : null;
    }

    /** 清除指定殖民地的强制仇恨标记（返回被清除的旧目标，无则 null）。 */
    @Nullable
    public UUID clearForcedHostile(UUID colonyId) {
        ColonyMarks marks = byColony.get(colonyId);
        if (marks == null) return null;
        UUID prev = marks.forcedHostile;
        marks.forcedHostile = null;
        prune(colonyId, marks);
        return prev;
    }

    /**
     * 实体死亡清理：任意殖民地若把该生物标记为强制仇恨目标则清除。
     *
     * @return 是否有殖民地被清除（供服务端判断是否需要 setDirty）
     */
    public boolean clearForcedHostileByEntity(UUID entityUuid) {
        boolean changed = false;
        Iterator<Map.Entry<UUID, ColonyMarks>> it = byColony.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ColonyMarks> e = it.next();
            ColonyMarks marks = e.getValue();
            if (entityUuid.equals(marks.forcedHostile)) {
                marks.forcedHostile = null;
                changed = true;
            }
            if (marks.sheltered.isEmpty() && marks.forcedHostile == null) {
                it.remove();
            }
        }
        return changed;
    }

    /** 当前全部殖民地标记（快照供 SavedData 序列化；副本字段仍可变，序列化是只读遍历）。 */
    public Map<UUID, ColonyMarks> all() {
        return byColony;
    }

    /** 覆盖加载（SavedData 读档用）：仅接收非空条目。 */
    public void loadAll(Map<UUID, ColonyMarks> loaded) {
        byColony.clear();
        byColony.putAll(loaded);
    }

    private ColonyMarks marks(UUID colonyId) {
        return byColony.computeIfAbsent(colonyId, k -> new ColonyMarks());
    }

    /** 空殖民地条目移除，避免无限增长。 */
    private void prune(UUID colonyId, ColonyMarks marks) {
        if (marks.sheltered.isEmpty() && marks.forcedHostile == null) {
            byColony.remove(colonyId);
        }
    }

    // ── 序列化（SavedData 落盘用，纯 NBT，无需 HolderLookup）──

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        ListTag coloniesTag = new ListTag();
        for (Map.Entry<UUID, ColonyMarks> entry : byColony.entrySet()) {
            ColonyMarks cm = entry.getValue();
            if (cm.sheltered.isEmpty() && cm.forcedHostile == null) continue;
            CompoundTag colonyTag = new CompoundTag();
            colonyTag.putUUID(TAG_COLONY_ID, entry.getKey());
            ListTag shelteredTag = new ListTag();
            for (UUID entityId : cm.sheltered) {
                CompoundTag e = new CompoundTag();
                e.putUUID(TAG_ENTITY_ID, entityId);
                shelteredTag.add(e);
            }
            colonyTag.put(TAG_SHELTERED, shelteredTag);
            if (cm.forcedHostile != null) {
                colonyTag.putUUID(TAG_FORCED_HOSTILE, cm.forcedHostile);
            }
            coloniesTag.add(colonyTag);
        }
        tag.put(TAG_COLONIES, coloniesTag);
        return tag;
    }

    /** 覆盖加载（SavedData 读档）：清空后按 NBT 重填。 */
    public void loadFromNbt(@Nullable CompoundTag tag) {
        byColony.clear();
        if (tag == null || !tag.contains(TAG_COLONIES, Tag.TAG_LIST)) return;
        ListTag coloniesTag = tag.getList(TAG_COLONIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < coloniesTag.size(); i++) {
            CompoundTag colonyTag = coloniesTag.getCompound(i);
            if (!colonyTag.hasUUID(TAG_COLONY_ID)) continue;
            UUID colonyId = colonyTag.getUUID(TAG_COLONY_ID);
            ColonyMarks cm = new ColonyMarks();
            if (colonyTag.contains(TAG_SHELTERED, Tag.TAG_LIST)) {
                ListTag shelteredTag = colonyTag.getList(TAG_SHELTERED, Tag.TAG_COMPOUND);
                for (int j = 0; j < shelteredTag.size(); j++) {
                    CompoundTag e = shelteredTag.getCompound(j);
                    if (e.hasUUID(TAG_ENTITY_ID)) {
                        cm.sheltered.add(e.getUUID(TAG_ENTITY_ID));
                    }
                }
            }
            if (colonyTag.hasUUID(TAG_FORCED_HOSTILE)) {
                cm.forcedHostile = colonyTag.getUUID(TAG_FORCED_HOSTILE);
            }
            if (!cm.sheltered.isEmpty() || cm.forcedHostile != null) {
                byColony.put(colonyId, cm);
            }
        }
    }

    private static final String TAG_COLONIES = "colonies";
    private static final String TAG_COLONY_ID = "colony_id";
    private static final String TAG_SHELTERED = "sheltered";
    private static final String TAG_ENTITY_ID = "entity_id";
    private static final String TAG_FORCED_HOSTILE = "forced_hostile";
}