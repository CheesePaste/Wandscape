package com.wsteam.wandscape.content.npc.internal;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.api.NpcSpawnSpec;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.task.component.ColonyMember;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.npc.data.NpcData;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.api.NpcApi;
import com.wsteam.wandscape.foundation.util.BalanceValues;
import com.wsteam.wandscape.content.npc.data.DeathRecord;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Implementation of {@link NpcApi} that queries the ECS World via
 * {@link EntityComponentBridge}.
 *
 * <p>Stage 2 limitations:
 * <ul>
 *   <li>{@link #assignHouse} always returns false (stage 4).</li>
 *   <li>NPC lookups go through the bridge's in-memory map (fast but not
 *       persisted across server restarts).</li>
 * </ul>
 */
public class NpcApiImpl implements NpcApi {

    private static final String TAG = "NpcApiImpl";

    @Override
    public List<NpcData> getColonyNpcs(UUID colonyId) {
        List<NpcData> result = new ArrayList<>();
        World world = com.wsteam.wandscape.content.task.ecs.World.getActive();
        if (world == null) return result;

        for (var entry : EntityComponentBridge.INSTANCE.allNpcs().entrySet()) {
            WandscapeNpc npc = entry.getValue();
            if (npc == null || npc.isRemoved()) continue;

            ColonyMember member = world.get(entry.getKey(), ColonyMember.class);
            if (member != null && colonyId.equals(member.colonyId())) {
                result.add(NpcData.from(npc));
            }
        }
        return result;
    }

    @Override
    public List<NpcData> getIdleNpcs(UUID colonyId) {
        List<NpcData> all = getColonyNpcs(colonyId);
        all.removeIf(npc -> !npc.isIdle());
        return all;
    }

    @Override
    @Nullable
    public NpcData getNpc(UUID npcId) {
        Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(npcId);
        if (ecsId == null) return null;
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(ecsId);
        return npc != null ? NpcData.from(npc) : null;
    }

    /** NPC → 所属殖民地反向查询：读实体权威源 {@link WandscapeNpc#colonyId}
     *  （NBT 持久化），未归属/占位 ID 一律 null。 */
    @Override
    @Nullable
    public UUID getNpcColony(UUID npcId) {
        if (npcId == null) return null;
        Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(npcId);
        if (ecsId == null) return null;
        WandscapeNpc npc = EntityComponentBridge.INSTANCE.getNpc(ecsId);
        if (npc == null || npc.isRemoved()) return null;
        UUID colony = npc.colonyId;
        if (colony == null || EntityComponentBridge.PLACEHOLDER_COLONY.equals(colony)) return null;
        return colony;
    }

    // ── 存活/复活 ──

    @Override
    public boolean isNpcAlive(UUID npcId) {
        if (npcId == null) return false;
        ServerLevel level = getServerLevel();
        if (level == null) return false;
        return level.getEntity(npcId) instanceof WandscapeNpc npc
                && !npc.isRemoved() && npc.isAlive();
    }

    @Override
    public boolean reviveNpc(UUID npcId) {
        return reviveNpc(npcId, null);
    }

    @Override
    public boolean reviveNpc(UUID npcId, @Nullable BlockPos pos) {
        if (npcId == null || isNpcAlive(npcId)) return false;
        ServerLevel level = getServerLevel();
        if (level == null) return false;

        ColonyDeathRegistry reg = ColonyDeathRegistry.get(level);
        DeathRecord rec = reg.getByNpcId(npcId);
        if (rec == null) {
            Log.debug(TAG, "reviveNpc: 无死亡记录 {}", npcId);
            return false;
        }

        BlockPos at = pos != null ? pos
                : ReviveHandler.resolveTownHallDoorOrAnchor(level, rec.colonyId(),
                        new BlockPos(rec.x(), rec.y(), rec.z()));
        return ReviveHandler.spawnFromRecordAt(level, rec, at);
    }

    @Nullable
    private static ServerLevel getServerLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null ? server.overworld() : null;
    }

    // ── 生成（spawnNpc）：默认掷点 / 自定义 spec 覆盖 —— 招募与指令共用 ──

    @Override
    public UUID spawnNpc(UUID colonyId, BlockPos spawnPos) {
        return spawnNpc(colonyId, spawnPos, null);
    }

    @Override
    public UUID spawnNpc(UUID colonyId, BlockPos spawnPos, @Nullable NpcSpawnSpec spec) {
        if (colonyId == null) return null;
        ServerLevel level = getServerLevel();
        if (level == null) return null;

        // 按小镇等级掷点默认属性；spec 未覆盖的键用此兜底。
        var colonyApi = com.wsteam.wandscape.api.WandscapeApis.getColonyApiSilently();
        int colonyLevel = colonyApi != null ? colonyApi.getColonyLevel(colonyId) : 1;
        var candidate = NpcAttributes.roll(colonyLevel, new Random(level.random.nextLong()));

        var npc = Wandscape.WANDSCAPE_NPC.get().spawn(level, spawnPos, MobSpawnType.COMMAND);
        if (npc == null) {
            Log.warn(TAG, "spawnNpc: failed to spawn at {}", spawnPos);
            return null;
        }
        npc.setPersistenceRequired();
        npc.colonyId = colonyId;

        // 等级
        npc.setLevel(spec != null && spec.level() != null ? spec.level() : candidate.level());

        // 名字（缺省由 onAddedToLevel 生成幻想名）
        if (spec != null && spec.name() != null) {
            npc.setCustomName(Component.literal(spec.name()));
            npc.setCustomNameVisible(true);
        }

        // 基础属性：spec 覆盖优先，缺席走掷点默认
        npc.setBaseAttributeValue(AttributeType.MAX_HP, specAttr(spec, AttributeType.MAX_HP, candidate.maxHp()));
        npc.setBaseAttributeValue(AttributeType.MOVE_SPEED, specAttr(spec, AttributeType.MOVE_SPEED, candidate.moveSpeed()));
        npc.setBaseAttributeValue(AttributeType.SPELL_POWER, specAttr(spec, AttributeType.SPELL_POWER, candidate.spellPower()));
        npc.setBaseAttributeValue(AttributeType.WORK_SPEED, specAttr(spec, AttributeType.WORK_SPEED, candidate.workSpeed()));
        npc.setBaseAttributeValue(AttributeType.SPELL_SPEED, specAttr(spec, AttributeType.SPELL_SPEED, candidate.spellSpeed()));
        npc.setBaseAttributeValue(AttributeType.ARMOR_VALUE, specAttr(spec, AttributeType.ARMOR_VALUE, candidate.armorValue()));
        npc.setBaseAttributeValue(AttributeType.MAX_MANA, specAttr(spec, AttributeType.MAX_MANA, candidate.maxMana()));

        // 皮肤 / 帽色
        if (spec != null && spec.skinVariant() != null) npc.setSkinVariant(spec.skinVariant());
        if (spec != null && spec.hatColor() != null) npc.setHatColor(spec.hatColor());

        // 魔法载荷 + 策略（spec.spells 非空才覆盖；招募默认空载荷由调用方传空列表，通用生成走 onAddedToLevel 默认）
        if (spec != null && spec.spells() != null) {
            String preset = spec.strategyPreset() != null
                    ? spec.strategyPreset() : npc.castStrategy.preset().name();
            try {
                WandscapeApis.getMagicApi().setEquippedAndStrategy(npc.getUUID(), preset, spec.spells());
            } catch (IllegalStateException e) {
                Log.warn(TAG, "spawnNpc: MagicApi unavailable, spell equip skipped");
            }
        }

        // 满蓝入职
        npc.magic.setMana(npc.getMaxMana());

        // spawn() 已触发 onAddedToLevel（可能落 PLACEHOLDER_COLONY），此处修正 ECS ColonyMember。
        fixEcsAfterSpawn(npc, colonyId);

        Log.info(TAG, "[NpcApi] spawned mage {} Lv.{} for colony {} at {}",
                npc.getNpcName(), npc.getLevel(), shortId(colonyId), spawnPos.toShortString());
        return npc.getUUID();
    }

    /** spec.attributes 覆盖 → 掷点默认 兜底。 */
    private static float specAttr(NpcSpawnSpec spec, AttributeType type, float fallback) {
        Map<AttributeType, Float> m = spec != null ? spec.attributes() : null;
        return m != null && m.containsKey(type) ? m.get(type) : fallback;
    }

    /** 修正 ECS ColonyMember（spawn 时的 PLACEHOLDER_COLONY → 真实 colonyId）。 */
    private static void fixEcsAfterSpawn(WandscapeNpc npc, UUID colonyId) {
        World ecsWorld = World.getActive();
        if (ecsWorld == null) return;
        Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(npc.getUUID());
        if (ecsId == null) return;
        ColonyMember member = ecsWorld.get(ecsId, ColonyMember.class);
        if (member != null && !colonyId.equals(member.colonyId())) {
            ecsWorld.addComponent(ecsId, new ColonyMember(colonyId));
            Log.info(TAG, "spawnNpc: fixed NPC {} colony → {}", ecsId, shortId(colonyId));
        }
    }

    private static String shortId(UUID id) {
        return id == null ? "?" : id.toString().substring(0, 8);
    }

    // ── NPC 背包（实体级 27 格 SimpleContainer）+ 拾取开关 ──

    /** 取活体 WandscapeNpc 实体（不存在/已移除返回 null）。 */
    @Nullable
    private static WandscapeNpc npcEntity(UUID npcId) {
        if (npcId == null) return null;
        ServerLevel level = getServerLevel();
        if (level == null) return null;
        return level.getEntity(npcId) instanceof WandscapeNpc npc && !npc.isRemoved() ? npc : null;
    }

    @Override
    public List<ItemStack> getNpcInventory(UUID npcId) {
        WandscapeNpc npc = npcEntity(npcId);
        if (npc == null) return List.of();
        List<ItemStack> result = new ArrayList<>(npc.inventory.getContainerSize());
        for (int i = 0; i < npc.inventory.getContainerSize(); i++) {
            result.add(npc.inventory.getItem(i).copy());
        }
        return result;
    }

    @Override
    public int getNpcInventorySize(UUID npcId) {
        WandscapeNpc npc = npcEntity(npcId);
        return npc != null ? npc.inventory.getContainerSize() : 0;
    }

    @Override
    public boolean setNpcInventorySlot(UUID npcId, int index, ItemStack stack) {
        WandscapeNpc npc = npcEntity(npcId);
        if (npc == null || index < 0 || index >= npc.inventory.getContainerSize()) return false;
        npc.inventory.setItem(index, stack == null ? ItemStack.EMPTY : stack.copy());
        return true;
    }

    @Override
    public ItemStack addToNpcInventory(UUID npcId, ItemStack stack) {
        WandscapeNpc npc = npcEntity(npcId);
        if (npc == null || stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        return npc.inventory.addItem(stack.copy());
    }

    @Override
    public void clearNpcInventory(UUID npcId) {
        WandscapeNpc npc = npcEntity(npcId);
        if (npc != null) npc.inventory.clearContent();
    }

    @Override
    public boolean isPickupEnabled(UUID npcId) {
        WandscapeNpc npc = npcEntity(npcId);
        return npc != null && npc.isPickupItems();
    }

    @Override
    public void setPickupEnabled(UUID npcId, boolean enabled) {
        WandscapeNpc npc = npcEntity(npcId);
        if (npc == null) return;
        npc.setPickupItems(enabled);
        if (!enabled) npc.setAutoPickupItems(false); // 关拾取 → 自动拾取一并关（与 NpcTogglePacket 一致）
    }

    @Override
    public boolean isAutoPickupEnabled(UUID npcId) {
        WandscapeNpc npc = npcEntity(npcId);
        return npc != null && npc.isAutoPickupItems();
    }

    @Override
    public void setAutoPickupEnabled(UUID npcId, boolean enabled) {
        WandscapeNpc npc = npcEntity(npcId);
        if (npc == null) return;
        npc.setAutoPickupItems(enabled);
        if (enabled) npc.setPickupItems(true); // 开自动拾取 → 拾取一并开（与 NpcTogglePacket 一致）
    }

    // ── 可调平衡值（委托 BalanceValues；运行时生效，不追溯已生成实体）──
    @Override public int getGuardRange() { return BalanceValues.guardRange(); }
    @Override public void setGuardRange(int v) { BalanceValues.setGuardRange(v); }
    @Override public int getGuardReleaseRange() { return BalanceValues.guardReleaseRange(); }
    @Override public void setGuardReleaseRange(int v) { BalanceValues.setGuardReleaseRange(v); }
    @Override public int getGuardSelfDefenseRange() { return BalanceValues.guardSelfDefenseRange(); }
    @Override public void setGuardSelfDefenseRange(int v) { BalanceValues.setGuardSelfDefenseRange(v); }
    @Override public int getGuardHateRange() { return BalanceValues.guardHateRange(); }
    @Override public void setGuardHateRange(int v) { BalanceValues.setGuardHateRange(v); }
    @Override public int getGuardHateDurationTicks() { return BalanceValues.guardHateDurationTicks(); }
    @Override public void setGuardHateDurationTicks(int v) { BalanceValues.setGuardHateDurationTicks(v); }
    @Override public int getGuardFollowAttackDurationTicks() { return BalanceValues.guardFollowAttackDurationTicks(); }
    @Override public void setGuardFollowAttackDurationTicks(int v) { BalanceValues.setGuardFollowAttackDurationTicks(v); }
    @Override public double getGuardKiteStartDist() { return BalanceValues.guardKiteStartDist(); }
    @Override public void setGuardKiteStartDist(double v) { BalanceValues.setGuardKiteStartDist(v); }
    @Override public double getGuardKiteStandoff() { return BalanceValues.guardKiteStandoff(); }
    @Override public void setGuardKiteStandoff(double v) { BalanceValues.setGuardKiteStandoff(v); }
    @Override public double getGuardEngageStandoff() { return BalanceValues.guardEngageStandoff(); }
    @Override public void setGuardEngageStandoff(double v) { BalanceValues.setGuardEngageStandoff(v); }
    @Override public double getGuardFleeHpThreshold() { return BalanceValues.guardFleeHpThreshold(); }
    @Override public void setGuardFleeHpThreshold(double v) { BalanceValues.setGuardFleeHpThreshold(v); }
    @Override public double getGuardFleeStartDist() { return BalanceValues.guardFleeStartDist(); }
    @Override public void setGuardFleeStartDist(double v) { BalanceValues.setGuardFleeStartDist(v); }
    @Override public double getGuardFleeStandoff() { return BalanceValues.guardFleeStandoff(); }
    @Override public void setGuardFleeStandoff(double v) { BalanceValues.setGuardFleeStandoff(v); }
    @Override public int getNpcRegenGraceTicks() { return BalanceValues.npcRegenGraceTicks(); }
    @Override public void setNpcRegenGraceTicks(int v) { BalanceValues.setNpcRegenGraceTicks(v); }
    @Override public int getNpcRegenIntervalTicks() { return BalanceValues.npcRegenIntervalTicks(); }
    @Override public void setNpcRegenIntervalTicks(int v) { BalanceValues.setNpcRegenIntervalTicks(v); }
    @Override public int getNpcManaRegenTicks() { return BalanceValues.npcManaRegenTicks(); }
    @Override public void setNpcManaRegenTicks(int v) { BalanceValues.setNpcManaRegenTicks(v); }
    @Override public double getNpcManaRegenFraction() { return BalanceValues.npcManaRegenFraction(); }
    @Override public void setNpcManaRegenFraction(double v) { BalanceValues.setNpcManaRegenFraction(v); }
    @Override public int getReviveNearBuildingRange() { return BalanceValues.reviveNearBuildingRange(); }
    @Override public void setReviveNearBuildingRange(int v) { BalanceValues.setReviveNearBuildingRange(v); }
}
