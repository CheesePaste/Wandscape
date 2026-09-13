package com.wsteam.wandscape.compat.tlm;

import com.wsteam.wandscape.content.npc.attributes.NpcAttributes;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * 车万女仆作为殖民地工作者时的**自有状态**（NeoForge Data Attachment，见 {@link TlmCompatImpl}）。
 *
 * <p>为什么不用女仆的 vanilla 属性表：本模组 7 项属性里 6 项是自定义属性
 * （{@code content/npc/WandscapeAttributes}），而 {@code EntityMaid} 的属性供给器由 TLM 提供、
 * 不含它们；{@code EntityAttributeCreationEvent} 只在注册期生效，事后也换不掉已注册 EntityType
 * 的供给器。所以女仆的殖民地属性存在这里，语义与法师一致（同一套 {@link NpcAttributes#computeEffective}
 * 纯函数），只是换了个容器。
 *
 * <p>**只存不可推导的东西**：殖民地本身由主人推导（{@link TlmCompatImpl#ownerColonyOf}），
 * 是唯一权威，不在这里冗余一份。这里只有等级与 7 项 base——**魔力/冷却（阶段二）也挂这里，
 * 现在还没有这两个字段**。
 *
 * <p>存档格式带 {@code v} 顶层版本号，走显式迁移链（硬规则 7）。
 */
public final class MaidColonyState implements INBTSerializable<CompoundTag> {

    /** 当前存档格式版本。加字段/改语义时递增并在 {@link #deserializeNBT} 里补迁移分支。 */
    public static final int VERSION = 1;

    private static final String TAG_VERSION = "v";
    private static final String TAG_LEVEL = "level";
    private static final String TAG_BASE = "base";

    /** 属性等级（与法师的 level 同语义；阶段二法师小屋训练改它）。 */
    private int level = 1;

    /** 7 项 base 属性，按 {@link NpcAttributes#ORDER} 索引。 */
    private final float[] base = new float[NpcAttributes.ORDER.size()];

    public MaidColonyState() {
        resetToDefaults();
    }

    private void resetToDefaults() {
        level = 1;
        for (int i = 0; i < NpcAttributes.ORDER.size(); i++) {
            base[i] = NpcAttributes.defaultFor(NpcAttributes.ORDER.get(i));
        }
    }

    public int level() {
        return level;
    }

    public void setLevel(int level) {
        this.level = Math.max(1, level);
    }

    public float base(AttributeType type) {
        int i = NpcAttributes.ORDER.indexOf(type);
        return i >= 0 ? base[i] : NpcAttributes.defaultFor(type);
    }

    public void setBase(AttributeType type, float value) {
        int i = NpcAttributes.ORDER.indexOf(type);
        if (i >= 0) base[i] = value;
    }

    /** 有效属性值 = base + perLevel*(level-1)。女仆没有装备加成桥，equipBonus 恒 0。 */
    public float effective(AttributeType type) {
        return NpcAttributes.computeEffective(type, base(type), level, 0f);
    }

    @Override
    public CompoundTag serializeNBT(net.minecraft.core.HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(TAG_VERSION, VERSION);
        tag.putInt(TAG_LEVEL, level);
        ListTag list = new ListTag();
        for (float v : base) {
            list.add(net.minecraft.nbt.FloatTag.valueOf(v));
        }
        tag.put(TAG_BASE, list);
        return tag;
    }

    @Override
    public void deserializeNBT(net.minecraft.core.HolderLookup.Provider provider, CompoundTag tag) {
        resetToDefaults();
        int version = tag.contains(TAG_VERSION) ? tag.getInt(TAG_VERSION) : 0;
        if (version < 1) {
            // 无版本号 = 从未写入过（或早期实验档）：保持默认值，不猜
            return;
        }
        level = Math.max(1, tag.getInt(TAG_LEVEL));
        ListTag list = tag.getList(TAG_BASE, Tag.TAG_FLOAT);
        for (int i = 0; i < Math.min(list.size(), base.length); i++) {
            base[i] = list.getFloat(i);
        }
        // 将来的迁移：if (version < 2) { ...; }
    }
}
