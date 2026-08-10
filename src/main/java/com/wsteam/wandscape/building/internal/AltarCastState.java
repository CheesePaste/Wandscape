package com.wsteam.wandscape.building.internal;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 每世界一份的祭坛施法状态（SavedData）：每个祭坛（building UUID）独立存放各魔法的
 * 剩余冷却（tick）。不同祭坛之间 CD 不共享——{@code setCooldown} 按 altarId 索引。
 *
 * <p>引导中（active cast）状态不在此持久化：与仪式一样为内存态（见 AltarCastExecutor），
 * 服务器重启即取消引导；CD 落盘保证重启后冷却不被白嫖。
 */
public class AltarCastState extends SavedData {

    private static final String DATA_NAME = "wandscape_altar_casts";
    private static final String TAG_ALTARS = "altars";
    private static final String TAG_ALTAR = "altar";
    private static final String TAG_CDS = "cds";

    /** buildingId → magicId → 剩余冷却 tick。 */
    private final Map<UUID, Map<String, Integer>> cooldowns = new HashMap<>();

    public static final Factory<AltarCastState> FACTORY = new Factory<>(
            AltarCastState::new,
            AltarCastState::load,
            null
    );

    public static AltarCastState get(Level level) {
        return level.getServer().overworld()
                .getDataStorage()
                .computeIfAbsent(FACTORY, DATA_NAME);
    }

    private AltarCastState() {}

    // ── 冷却存取 ──

    /** 指定祭坛指定魔法的剩余冷却 tick；无记录 = 0（可施放）。 */
    public int getCooldown(UUID altarId, String magicId) {
        Map<String, Integer> m = cooldowns.get(altarId);
        return m != null ? m.getOrDefault(magicId, 0) : 0;
    }

    /** 设置冷却（施法成功时起算）；ticks &lt;= 0 视为清除。 */
    public void setCooldown(UUID altarId, String magicId, int ticks) {
        if (ticks <= 0) {
            removeCooldown(altarId, magicId);
            return;
        }
        cooldowns.computeIfAbsent(altarId, k -> new HashMap<>()).put(magicId, ticks);
        setDirty();
    }

    private void removeCooldown(UUID altarId, String magicId) {
        Map<String, Integer> m = cooldowns.get(altarId);
        if (m != null) {
            m.remove(magicId);
            if (m.isEmpty()) cooldowns.remove(altarId);
        }
        setDirty();
    }

    /** 每 server tick：所有祭坛所有魔法冷却减 1，到期即移除。 */
    public void tick() {
        boolean dirty = false;
        Iterator<Map.Entry<UUID, Map<String, Integer>>> altars = cooldowns.entrySet().iterator();
        while (altars.hasNext()) {
            Map<String, Integer> m = altars.next().getValue();
            Iterator<Map.Entry<String, Integer>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Integer> e = it.next();
                int v = e.getValue() - 1;
                if (v <= 0) {
                    it.remove();
                } else {
                    e.setValue(v);
                }
                dirty = true;
            }
            if (m.isEmpty()) altars.remove();
        }
        if (dirty) setDirty();
    }

    // ── NBT ──

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag altars = new ListTag();
        for (Map.Entry<UUID, Map<String, Integer>> e : cooldowns.entrySet()) {
            CompoundTag at = new CompoundTag();
            at.putUUID(TAG_ALTAR, e.getKey());
            CompoundTag cds = new CompoundTag();
            for (Map.Entry<String, Integer> me : e.getValue().entrySet()) {
                cds.putInt(me.getKey(), me.getValue());
            }
            at.put(TAG_CDS, cds);
            altars.add(at);
        }
        tag.put(TAG_ALTARS, altars);
        return tag;
    }

    private static AltarCastState load(CompoundTag tag, HolderLookup.Provider registries) {
        AltarCastState st = new AltarCastState();
        ListTag altars = tag.getList(TAG_ALTARS, Tag.TAG_COMPOUND);
        for (int i = 0; i < altars.size(); i++) {
            CompoundTag at = altars.getCompound(i);
            if (!at.hasUUID(TAG_ALTAR)) continue;
            CompoundTag cds = at.getCompound(TAG_CDS);
            Map<String, Integer> m = new HashMap<>();
            for (String key : cds.getAllKeys()) {
                m.put(key, cds.getInt(key));
            }
            if (!m.isEmpty()) st.cooldowns.put(at.getUUID(TAG_ALTAR), m);
        }
        return st;
    }
}
