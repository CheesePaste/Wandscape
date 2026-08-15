package com.wsteam.wandscape.npc.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.wsteam.wandscape.core.types.ResourceId;
import com.wsteam.wandscape.core.types.ResourceStack;
import com.wsteam.wandscape.npc.data.DeathRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 每世界一份的死亡留存注册表（SavedData）：NPC 战死快照在此增删查，永久留存直至复活成功。
 * 复活成功后 {@link #remove}；不做时间过期清理。
 */
public class ColonyDeathRegistry extends SavedData {

    private static final String TAG = "ColonyDeathRegistry";
    private static final String DATA_NAME = "wandscape_npc_deaths";
    private static final String TAG_RECORDS = "records";

    private final List<DeathRecord> records = new ArrayList<>();

    public static final Factory<ColonyDeathRegistry> FACTORY = new Factory<>(
            ColonyDeathRegistry::new,
            ColonyDeathRegistry::load,
            null
    );

    public static ColonyDeathRegistry get(Level level) {
        return level.getServer().overworld()
                .getDataStorage()
                .computeIfAbsent(FACTORY, DATA_NAME);
    }

    private ColonyDeathRegistry() {}

    // ── 查询 ──

    public void add(DeathRecord record) {
        records.add(record);
        setDirty();
    }

    /** 移除一条记录（复活成功后调用）。 */
    public void remove(DeathRecord record) {
        records.remove(record);
        setDirty();
    }

    /** 范围内最近的死亡记录（3D 距离，含 Y）；无则 null。 */
    @Nullable
    public DeathRecord nearest(BlockPos pos, double range) {
        return DeathRecord.nearest(records, pos.getX(), pos.getY(), pos.getZ(), range);
    }

    /** 某小镇最近死去的死亡记录（按 deathTime 最新，不限位置）；colonyId 为 null 时不限小镇；无则 null。祭坛复活用。 */
    @Nullable
    public DeathRecord latestInColony(@Nullable UUID colonyId) {
        return DeathRecord.latestInColony(records, colonyId);
    }

    // ── NBT ──

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (DeathRecord r : records) {
            list.add(toNbt(r));
        }
        tag.put(TAG_RECORDS, list);
        return tag;
    }

    private static ColonyDeathRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        ColonyDeathRegistry reg = new ColonyDeathRegistry();
        ListTag list = tag.getList(TAG_RECORDS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            DeathRecord r = fromNbt(list.getCompound(i));
            if (r != null) reg.records.add(r);
        }
        return reg;
    }

    private static CompoundTag toNbt(DeathRecord r) {
        CompoundTag t = new CompoundTag();
        t.putUUID("npcId", r.npcId());
        t.putString("name", r.name());
        t.putString("dimension", r.dimension());
        t.putInt("x", r.x());
        t.putInt("y", r.y());
        t.putInt("z", r.z());
        t.putLong("deathTime", r.deathTime());
        t.putUUID("colonyId", r.colonyId());
        t.putInt("skinVariant", r.skinVariant());
        t.putInt("hatColor", r.hatColor());
        t.putBoolean("hasDefaultWand", r.hasDefaultWand());
        t.putFloat("maxHp", r.maxHp());
        t.putFloat("moveSpeed", r.moveSpeed());
        t.putFloat("spellPower", r.spellPower());
        t.putFloat("workSpeed", r.workSpeed());
        t.putFloat("spellSpeed", r.spellSpeed());
        t.putFloat("armorValue", r.armorValue());
        t.putFloat("maxMana", r.maxMana());
        if (!r.inventory().isEmpty()) {
            ListTag inv = new ListTag();
            for (ResourceStack s : r.inventory()) {
                CompoundTag item = new CompoundTag();
                item.putString("id", s.resource().id());
                item.putInt("amount", s.amount());
                inv.add(item);
            }
            t.put("inventory", inv);
        }
        return t;
    }

    @Nullable
    private static DeathRecord fromNbt(CompoundTag t) {
        if (!t.hasUUID("npcId")) return null;
        List<ResourceStack> inv = new ArrayList<>();
        if (t.contains("inventory")) {
            ListTag list = t.getList("inventory", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag item = list.getCompound(i);
                String id = item.getString("id");
                int amount = item.getInt("amount");
                if (id.isEmpty() || amount <= 0) continue;
                inv.add(new ResourceStack(new ResourceId(id), amount));
            }
        }
        return new DeathRecord(
                t.getUUID("npcId"),
                t.getString("name"),
                t.getString("dimension"),
                t.getInt("x"), t.getInt("y"), t.getInt("z"),
                t.getLong("deathTime"),
                t.getUUID("colonyId"),
                t.getInt("skinVariant"),
                t.getInt("hatColor"),
                t.getBoolean("hasDefaultWand"),
                t.getFloat("maxHp"), t.getFloat("moveSpeed"), t.getFloat("spellPower"),
                t.getFloat("workSpeed"), t.getFloat("spellSpeed"), t.getFloat("armorValue"),
                t.getFloat("maxMana"),
                inv);
    }
}
