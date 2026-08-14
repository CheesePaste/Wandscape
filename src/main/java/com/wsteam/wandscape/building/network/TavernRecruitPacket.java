package com.wsteam.wandscape.building.network;

import java.util.Random;
import java.util.UUID;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.core.component.ColonyMember;
import com.wsteam.wandscape.core.component.EquipmentComponent;
import com.wsteam.wandscape.core.types.NpcAttributes;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.core.ecs.World;
import com.wsteam.wandscape.shared.data.MageAttributeRoller;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.wsteam.wandscape.Wandscape.MODID;
import com.wsteam.wandscape.shared.log.Log;
import com.wsteam.wandscape.shared.registry.WandscapeConstants;

/**
 * Client→server packet: player clicks "Recruit NPC" in the tavern GUI.
 *
 * <p>Server validates the building at {@code buildingPos} is a tavern,
 * then spawns a new {@link WandscapeNpc} assigned to that colony.
 */
public record TavernRecruitPacket(BlockPos buildingPos, String action)
        implements CustomPacketPayload {

    private static final String TAG = "TavernRecruitPacket";

    public static final Type<TavernRecruitPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "tavern_recruit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TavernRecruitPacket> STREAM_CODEC =
            StreamCodec.of(TavernRecruitPacket::write, TavernRecruitPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(TavernRecruitPacket pkt, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer sp)) return;

        sp.getServer().execute(() -> {
            ServerLevel level = sp.serverLevel();

            // 1. Validate building
            BuildingSavedData data = BuildingSavedData.get(level);
            UUID buildingId = data.getBuildingIdAt(pkt.buildingPos);
            if (buildingId == null) {
                Log.warn(TAG, "[Tourist] No building at {}", pkt.buildingPos);
                return;
            }
            BuildingState state = data.getBuilding(buildingId);
            if (state == null || !"tavern".equals(state.getCategory())) {
                Log.warn(TAG, "[Tourist] Building {} is not a tavern", buildingId);
                return;
            }
            UUID colonyId = state.getColonyId();
            if (colonyId == null) {
                sp.displayClientMessage(
                        Component.literal("[Wandscape] This tavern is not assigned to any colony."),
                        false);
                return;
            }

            // 2. Handle recruit_mage action
            if (pkt.action.startsWith("recruit_mage:")) {
                handleRecruitMage(sp, level, buildingId, colonyId, pkt);
                return;
            }

            // 2.5 招募计费门控（仅「招募 NPC」收费）：每殖民地首次免费，之后每次需每种元素 10000
            com.wsteam.wandscape.shared.api.TavernApi tavernApi = null;
            try {
                tavernApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getTavernApi();
            } catch (IllegalStateException ignored) {}
            if (tavernApi != null && !tavernApi.canAffordRecruit(colonyId)) {
                sp.displayClientMessage(
                        Component.literal("[Wandscape] Insufficient elements: recruiting costs "
                                + WandscapeConstants.TAVERN_RECRUIT_COST_PER_ELEMENT
                                + " of every element (first recruit free)."),
                        false);
                return;
            }

            // 3. Roll recruit attributes: random² 偏斜分布 + 殖民地等级加成
            //    （模拟殖民地等级游客投出的简历——与法师游客掷简历同一公式）
            var levelMgr = WandscapeEngine.getColonyLevelManager();
            int colonyLevel = levelMgr != null ? levelMgr.getLevel(colonyId) : 1;
            var candidate = MageAttributeRoller.roll(colonyLevel,
                    new Random(level.random.nextLong()));

            // 4. Find spawn position near the tavern
            BlockPos spawnPos = findSpawnPos(level, pkt.buildingPos);

            // 5. Spawn NPC
            var npc = Wandscape.WANDSCAPE_NPC.get().spawn(level, spawnPos, MobSpawnType.COMMAND);
            if (npc == null) {
                sp.displayClientMessage(
                        Component.literal("[Wandscape] Failed to recruit NPC."),
                        false);
                return;
            }
            npc.setPersistenceRequired();
            npc.colonyId = colonyId;

            // 6. Apply rolled attributes + 满蓝入职
            npc.maxHp = candidate.maxHp();
            npc.moveSpeed = candidate.moveSpeed();
            npc.spellPower = candidate.spellPower();
            npc.workSpeed = candidate.workSpeed();
            npc.spellSpeed = candidate.spellSpeed();
            npc.armorValue = candidate.armorValue();
            npc.maxMana = candidate.maxMana();
            npc.magic.setMana(candidate.maxMana());

            // 7. Fix ECS state (spawn() already triggered onNpcJoinWorld)
            fixEcsAfterSpawn(npc, colonyId);

            // 7.5 生成成功后再扣费计数（首次免费）
            if (tavernApi != null) {
                tavernApi.chargeRecruit(colonyId);
            }

            Log.info(TAG, "[Tourist] Recruited mage Lv.{} for colony {} at {}",
                    candidate.level(),
                    colonyId.toString().substring(0, 8),
                    spawnPos.toShortString());

            sp.displayClientMessage(
                    Component.literal("[Wandscape] Mage recruited! Lv." + candidate.level()
                            + " 强度:" + fmt(candidate.spellPower())
                            + " 工速:" + fmt(candidate.workSpeed())
                            + " 施速:" + fmt(candidate.spellSpeed())
                            + " 护甲:" + fmt(candidate.armorValue())
                            + " " + spawnPos.toShortString()),
                    false);

            if (tavernApi != null) {
                PacketDistributor.sendToPlayer(sp,
                        new TavernOpenPacket(pkt.buildingPos, colonyId,
                                tavernApi.getRecruitCount(colonyId),
                                tavernApi.getMageResumes(colonyId)));
            }
        });
    }

    private static void handleRecruitMage(ServerPlayer sp, ServerLevel level,
                                           UUID buildingId, UUID colonyId,
                                           TavernRecruitPacket pkt) {
        int index;
        try {
            index = Integer.parseInt(pkt.action.substring("recruit_mage:".length()));
        } catch (NumberFormatException e) {
            Log.warn(TAG, "[Tourist] Invalid recruit_mage action: {}", pkt.action);
            return;
        }

        com.wsteam.wandscape.shared.data.MageResume resume;
        try {
            var tavernApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getTavernApi();
            resume = tavernApi.recruitMage(buildingId, colonyId, index);
        } catch (IllegalStateException e) {
            sp.displayClientMessage(
                    Component.literal("[Wandscape] Tavern system not available."),
                    false);
            return;
        }

        if (resume == null) {
            sp.displayClientMessage(
                    Component.literal("[Wandscape] Invalid mage selection."),
                    false);
            return;
        }

        BlockPos spawnPos = findSpawnPos(level, pkt.buildingPos);
        var npc = Wandscape.WANDSCAPE_NPC.get().spawn(level, spawnPos, MobSpawnType.COMMAND);
        if (npc == null) {
            sp.displayClientMessage(
                    Component.literal("[Wandscape] Failed to recruit mage."),
                    false);
            return;
        }
        npc.setPersistenceRequired();
        npc.colonyId = colonyId;

        // Apply mage stats from resume
        npc.setCustomName(Component.literal(resume.touristName()));
        npc.setCustomNameVisible(true);
        npc.maxHp = resume.maxHp();
        npc.moveSpeed = resume.moveSpeed();
        npc.spellPower = resume.spellPower();
        npc.workSpeed = resume.workSpeed();
        npc.spellSpeed = resume.spellSpeed();
        npc.armorValue = resume.armorValue();
        npc.maxMana = resume.maxMana();
        npc.magic.setMana(resume.maxMana()); // 满蓝入职

        fixEcsAfterSpawn(npc, colonyId);

        Log.info(TAG, "[Tourist] Recruited mage {} (Lv.{}) from resume for colony {} at {}",
                resume.touristName(), resume.level(),
                colonyId.toString().substring(0, 8),
                spawnPos.toShortString());

        sp.displayClientMessage(
                Component.literal("[Wandscape] Mage " + resume.touristName()
                        + " recruited! Lv." + resume.level()
                        + " 强度:" + fmt(resume.spellPower())
                        + " 工速:" + fmt(resume.workSpeed())
                        + " 施速:" + fmt(resume.spellSpeed())
                        + " 护甲:" + fmt(resume.armorValue())),
                false);

        try {
            var tavernApi = com.wsteam.wandscape.shared.registry.WandscapeApis.getTavernApi();
            if (tavernApi != null) {
                PacketDistributor.sendToPlayer(sp,
                        new TavernOpenPacket(pkt.buildingPos, colonyId,
                                tavernApi.getRecruitCount(colonyId),
                                tavernApi.getMageResumes(colonyId)));
            }
        } catch (Exception ignored) {}
    }

    private static String fmt(float v) {
        return String.format("%.1f", v);
    }

    /** Find a valid spawn position near the tavern. */
    private static BlockPos findSpawnPos(ServerLevel level, BlockPos origin) {
        // Try positions around the tavern entrance (front = +z direction)
        BlockPos[] candidates = {
                origin.offset(0, 0, 2),
                origin.offset(1, 0, 2),
                origin.offset(-1, 0, 2),
                origin.offset(2, 0, 0),
                origin.offset(-2, 0, 0),
                origin.offset(0, 0, -2),
                origin.offset(1, 0, 0),
                origin.offset(-1, 0, 0),
        };
        for (BlockPos pos : candidates) {
            // Find ground: first air block above solid
            for (int dy = 0; dy < 6; dy++) {
                BlockPos check = pos.offset(0, dy, 0);
                if (level.isEmptyBlock(check) && !level.isEmptyBlock(check.below())) {
                    return check;
                }
            }
            // Fallback: just above pos
            if (level.isEmptyBlock(pos.above())) {
                return pos.above();
            }
        }
        return origin.above(2);
    }

    /** Mirror of ColonyCommand.fixEcsAfterSpawn — correct PLACEHOLDER_COLONY → real colonyId,
     *  and re-seed ECS base attributes from the resume (the NPC spawned with defaults). */
    private static void fixEcsAfterSpawn(WandscapeNpc npc, UUID colonyId) {
        World ecsWorld = WandscapeEngine.getWorld();
        if (ecsWorld == null) return;

        Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(npc.getUUID());
        if (ecsId == null) return;

        // Re-seed ECS base attributes from the resume's rolled values
        EquipmentComponent eq = ecsWorld.get(ecsId, EquipmentComponent.class);
        if (eq != null) {
            eq.seedBaseValues(new NpcAttributes(npc.maxHp, npc.moveSpeed, npc.spellPower,
                    npc.workSpeed, npc.spellSpeed, npc.armorValue, npc.maxMana));
        }

        var member = ecsWorld.get(ecsId,
                ColonyMember.class);
        if (member != null && !colonyId.equals(member.colonyId())) {
            ecsWorld.addComponent(ecsId,
                    new ColonyMember(colonyId));
            Log.info(TAG, "[Tourist] Fixed NPC {} colony {} → {}",
                    ecsId,
                    member.colonyId().toString().substring(0, 8),
                    colonyId.toString().substring(0, 8));
        }
    }

    // ── StreamCodec helpers ──

    static void write(RegistryFriendlyByteBuf buf, TavernRecruitPacket pkt) {
        buf.writeBlockPos(pkt.buildingPos);
        buf.writeUtf(pkt.action);
    }

    static TavernRecruitPacket read(RegistryFriendlyByteBuf buf) {
        return new TavernRecruitPacket(buf.readBlockPos(), buf.readUtf());
    }
}
