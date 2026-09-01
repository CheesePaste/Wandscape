package com.wsteam.wandscape.content.items.scepter.internal;
import com.wsteam.wandscape.content.task.ecs.World;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 玩家权杖庇护/敌对标记的全局持久化（存档于 overworld SavedData）。
 *
 * <p>按殖民地名下存储，跨会话长期保留：庇护目标是持续盟友、强制仇恨目标在解除/目标死亡前一直
 * 指挥该殖民地法师。仿 {@code OathRingSavedData} 的 Factory + overworld getDataStorage 模式；
 * 标记本体与 NBT 序列化在 {@link ScepterMarks}，本类只负责持有并驱动落盘。任何变更必须
 * {@code setDirty()}，否则标记丢失即特性丢失。
 */
public class ScepterMarksSavedData extends SavedData {
    private static final String DATA_NAME = "wandscape_scepter_marks";

    private final ScepterMarks marks = new ScepterMarks();

    public static final Factory<ScepterMarksSavedData> FACTORY = new Factory<>(
            ScepterMarksSavedData::new,
            ScepterMarksSavedData::load,
            null
    );

    public static ScepterMarksSavedData get(MinecraftServer server) {
        return server.overworld()
                .getDataStorage()
                .computeIfAbsent(FACTORY, DATA_NAME);
    }

    public ScepterMarks marks() {
        return marks;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return marks.toNbt();
    }

    static ScepterMarksSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ScepterMarksSavedData data = new ScepterMarksSavedData();
        data.marks.loadFromNbt(tag);
        return data;
    }
}