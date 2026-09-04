package com.wsteam.wandscape.content.building.network;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.npc.data.MageResume;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.building.internal.BuildingInteractHandler;
import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.task.component.ColonyMember;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;
import com.wsteam.wandscape.foundation.ui.I18n;
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

import java.util.Random;
import java.util.UUID;

import static com.wsteam.wandscape.Wandscape.MODID;

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
                ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.tavern.no_colony",
                        "[Wandscape] This tavern is not assigned to any colony."), true);
                return;
            }
            // 完全平行隔离：只能在自己小镇的酒馆招募/拒绝。
            if (!com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.isOwn(colonyId, sp)) {
                com.wsteam.wandscape.content.colony.ownership.ColonyOwnership.deny(sp, "酒馆");
                return;
            }

            // 2. Handle recruit_mage action
            if (pkt.action.startsWith("recruit_mage:")) {
                handleRecruitMage(sp, level, buildingId, colonyId, pkt);
                return;
            }

            // 2.25 Handle reject_mage action (remove a resume without spawning)
            if (pkt.action.startsWith("reject_mage:")) {
                handleRejectMage(sp, level, buildingId, colonyId, pkt);
                return;
            }

            // 2.5 付费招募（「招募 NPC」）：走 TavernApi.recruitForColony —— 首次免费，之后每种元素
            //     Config 价；整合包可经 recruitForColony(colonyId, pos, spec, cost) 覆盖花费与自定义 NPC。
            com.wsteam.wandscape.api.TavernApi tavernApi = null;
            try {
                tavernApi = com.wsteam.wandscape.api.WandscapeApis.getTavernApi();
            } catch (IllegalStateException ignored) {}
            if (tavernApi == null) {
                ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.tavern.system_unavailable",
                        "[Wandscape] Tavern system not available."), true);
                return;
            }

            BlockPos spawnPos = findSpawnPos(level, pkt.buildingPos);
            UUID npcId = tavernApi.recruitForColony(colonyId, spawnPos);
            if (npcId == null) {
                ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.tavern.insufficient_elements",
                        "[Wandscape] Insufficient elements: recruiting costs %d of every element "
                                + "(first recruit free).",
                        com.wsteam.wandscape.Config.TAVERN_RECRUIT_COST_PER_ELEMENT.get()), true);
                return;
            }

            // 反馈用新生成 NPC 快照（等级 + 属性），避免重复掷点。
            var npcApi = com.wsteam.wandscape.api.WandscapeApis.getNpcApiSilently();
            var npcData = npcApi != null ? npcApi.getNpc(npcId) : null;
            int lvl = npcData != null ? npcData.level() : 1;
            float spPow = npcData != null ? npcData.attributes().getOrDefault(AttributeType.SPELL_POWER, 0f) : 0f;
            float ws = npcData != null ? npcData.attributes().getOrDefault(AttributeType.WORK_SPEED, 0f) : 0f;
            float cs = npcData != null ? npcData.attributes().getOrDefault(AttributeType.SPELL_SPEED, 0f) : 0f;
            float ar = npcData != null ? npcData.attributes().getOrDefault(AttributeType.ARMOR_VALUE, 0f) : 0f;

            Log.info(TAG, "[Tourist] Recruited mage Lv.{} for colony {} at {}",
                    lvl, colonyId.toString().substring(0, 8), spawnPos.toShortString());

            ScreenFeedbackPacket.send(sp,
                    I18n.name("message.wandscape.tavern.recruited_direct",
                            "[Wandscape] Mage recruited! Lv.%d 强度:%.1f 工速:%.1f 施速:%.1f 护甲:%.1f %s",
                            lvl, spPow, ws, cs, ar, spawnPos.toShortString()),
                    false);

            PacketDistributor.sendToPlayer(sp,
                    new TavernOpenPacket(pkt.buildingPos, colonyId,
                            tavernApi.getRecruitCount(colonyId),
                            tavernApi.getMageResumes(colonyId),
                            BuildingInteractHandler.resolveCreator(sp.serverLevel(), pkt.buildingPos)));
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

        com.wsteam.wandscape.content.npc.data.MageResume resume;
        try {
            var tavernApi = com.wsteam.wandscape.api.WandscapeApis.getTavernApi();
            resume = tavernApi.recruitMage(buildingId, colonyId, index);
        } catch (IllegalStateException e) {
            ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.tavern.system_unavailable",
                    "[Wandscape] Tavern system not available."), true);
            return;
        }

        if (resume == null) {
            ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.tavern.invalid_selection",
                    "[Wandscape] Invalid mage selection."), true);
            return;
        }

        // recruitMage 已在酒馆位置生成实体（经 NpcApi.spawnNpc），此处只反馈 + 刷新。
        Log.info(TAG, "[Tourist] Recruited mage {} (Lv.{}) from resume for colony {}",
                resume.touristName(), resume.level(), colonyId.toString().substring(0, 8));

        ScreenFeedbackPacket.send(sp,
                I18n.name("message.wandscape.tavern.recruited_resume",
                        "[Wandscape] Mage %s recruited! Lv.%d 强度:%.1f 工速:%.1f 施速:%.1f 护甲:%.1f",
                        resume.touristName(), resume.level(), resume.spellPower(),
                        resume.workSpeed(), resume.spellSpeed(), resume.armorValue()),
                false);

        try {
            var tavernApi = com.wsteam.wandscape.api.WandscapeApis.getTavernApi();
            if (tavernApi != null) {
                PacketDistributor.sendToPlayer(sp,
                        new TavernOpenPacket(pkt.buildingPos, colonyId,
                                tavernApi.getRecruitCount(colonyId),
                                tavernApi.getMageResumes(colonyId),
                                BuildingInteractHandler.resolveCreator(sp.serverLevel(), pkt.buildingPos)));
            }
        } catch (Exception ignored) {}
    }

    private static void handleRejectMage(ServerPlayer sp, ServerLevel level,
                                         UUID buildingId, UUID colonyId,
                                         TavernRecruitPacket pkt) {
        int index;
        try {
            index = Integer.parseInt(pkt.action.substring("reject_mage:".length()));
        } catch (NumberFormatException e) {
            Log.warn(TAG, "[Tourist] Invalid reject_mage action: {}", pkt.action);
            return;
        }

        try {
            var tavernApi = com.wsteam.wandscape.api.WandscapeApis.getTavernApi();
            com.wsteam.wandscape.content.npc.data.MageResume removed = tavernApi.rejectMage(colonyId, index);
            if (removed == null) {
                ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.tavern.invalid_selection",
                        "[Wandscape] Invalid mage selection."), true);
            } else {
                Log.info(TAG, "[Tourist] Rejected mage resume {} for colony {}",
                        removed.touristName(), colonyId.toString().substring(0, 8));
                ScreenFeedbackPacket.send(sp,
                        I18n.name("message.wandscape.tavern.rejected",
                                "[Wandscape] 已拒绝 %s 的求职简历", removed.touristName()),
                        false);
            }

            // Refresh the tavern screen with the updated resume list
            PacketDistributor.sendToPlayer(sp,
                    new TavernOpenPacket(pkt.buildingPos, colonyId,
                            tavernApi.getRecruitCount(colonyId),
                            tavernApi.getMageResumes(colonyId),
                            BuildingInteractHandler.resolveCreator(sp.serverLevel(), pkt.buildingPos)));
        } catch (IllegalStateException e) {
            ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.tavern.system_unavailable",
                    "[Wandscape] Tavern system not available."), true);
        } catch (Exception ignored) {}
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

    /** Mirror of ColonyCommand.fixEcsAfterSpawn — correct PLACEHOLDER_COLONY → real colonyId. */
    private static void fixEcsAfterSpawn(WandscapeNpc npc, UUID colonyId) {
        World ecsWorld = com.wsteam.wandscape.content.task.ecs.World.getActive();
        if (ecsWorld == null) return;

        Long ecsId = EntityComponentBridge.INSTANCE.getEcsId(npc.getUUID());
        if (ecsId == null) return;

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
