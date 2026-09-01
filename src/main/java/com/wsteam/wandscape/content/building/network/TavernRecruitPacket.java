package com.wsteam.wandscape.content.building.network;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.npc.data.MageResume;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.building.internal.BuildingInteractHandler;
import com.wsteam.wandscape.content.building.internal.BuildingSavedData;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.task.component.ColonyMember;
import com.wsteam.wandscape.content.task.ecs.World;
import com.wsteam.wandscape.content.npc.types.AttributeType;
import com.wsteam.wandscape.impl.WandscapeEngine;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.content.npc.data.MageAttributeRoller;
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

            // 2.5 招募计费门控（仅「招募 NPC」收费）：每小镇首次免费，之后每次需每种元素 10000
            com.wsteam.wandscape.api.TavernApi tavernApi = null;
            try {
                tavernApi = com.wsteam.wandscape.api.WandscapeApis.getTavernApi();
            } catch (IllegalStateException ignored) {}
            if (tavernApi != null && !tavernApi.canAffordRecruit(colonyId)) {
                ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.tavern.insufficient_elements",
                        "[Wandscape] Insufficient elements: recruiting costs %d of every element "
                                + "(first recruit free).",
                        WandscapeConstants.TAVERN_RECRUIT_COST_PER_ELEMENT), true);
                return;
            }

            // 3. Roll recruit attributes: random² 偏斜分布 + 小镇等级加成
            //    （模拟小镇等级游客投出的简历——与法师游客掷简历同一公式）
            var levelMgr = WandscapeEngine.getColonyLevelManager();
            int colonyLevel = levelMgr != null ? levelMgr.getLevel(colonyId) : 1;
            var candidate = MageAttributeRoller.roll(colonyLevel,
                    new Random(level.random.nextLong()));

            // 4. Find spawn position near the tavern
            BlockPos spawnPos = findSpawnPos(level, pkt.buildingPos);

            // 5. Spawn NPC
            var npc = Wandscape.WANDSCAPE_NPC.get().spawn(level, spawnPos, MobSpawnType.COMMAND);
            if (npc == null) {
                ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.tavern.recruit_failed",
                        "[Wandscape] Failed to recruit NPC."), true);
                return;
            }
            npc.setPersistenceRequired();
            npc.colonyId = colonyId;
            // 酒馆招募的法师无起始战斗魔法（仅特殊区 heal/teleport 系统固有）：清空 onAddedToLevel 种的默认载荷
            npc.equippedMagic.clear();

            // 6. Apply rolled attributes + 满蓝入职
            npc.setBaseAttributeValue(AttributeType.MAX_HP, candidate.maxHp());
            npc.setBaseAttributeValue(AttributeType.MOVE_SPEED, candidate.moveSpeed());
            npc.setBaseAttributeValue(AttributeType.SPELL_POWER, candidate.spellPower());
            npc.setBaseAttributeValue(AttributeType.WORK_SPEED, candidate.workSpeed());
            npc.setBaseAttributeValue(AttributeType.SPELL_SPEED, candidate.spellSpeed());
            npc.setBaseAttributeValue(AttributeType.ARMOR_VALUE, candidate.armorValue());
            npc.setBaseAttributeValue(AttributeType.MAX_MANA, candidate.maxMana());
            npc.magic.setMana(candidate.maxMana());
            npc.setLevel(candidate.level());

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

            ScreenFeedbackPacket.send(sp,
                    I18n.name("message.wandscape.tavern.recruited_direct",
                            "[Wandscape] Mage recruited! Lv.%d 强度:%.1f 工速:%.1f 施速:%.1f 护甲:%.1f %s",
                            candidate.level(), candidate.spellPower(), candidate.workSpeed(),
                            candidate.spellSpeed(), candidate.armorValue(), spawnPos.toShortString()),
                    false);

            if (tavernApi != null) {
                PacketDistributor.sendToPlayer(sp,
                        new TavernOpenPacket(pkt.buildingPos, colonyId,
                                tavernApi.getRecruitCount(colonyId),
                                tavernApi.getMageResumes(colonyId),
                                BuildingInteractHandler.resolveCreator(sp.serverLevel(), pkt.buildingPos)));
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

        BlockPos spawnPos = findSpawnPos(level, pkt.buildingPos);
        var npc = Wandscape.WANDSCAPE_NPC.get().spawn(level, spawnPos, MobSpawnType.COMMAND);
        if (npc == null) {
            ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.tavern.recruit_mage_failed",
                    "[Wandscape] Failed to recruit mage."), true);
            return;
        }
        npc.setPersistenceRequired();
        npc.colonyId = colonyId;
        // 酒馆招募的法师无起始战斗魔法：清空默认载荷（特殊区 heal/teleport 系统固有）
        npc.equippedMagic.clear();

        // Apply mage stats from resume
        npc.setCustomName(Component.literal(resume.touristName()));
        npc.setCustomNameVisible(true);
        npc.setBaseAttributeValue(AttributeType.MAX_HP, resume.maxHp());
        npc.setBaseAttributeValue(AttributeType.MOVE_SPEED, resume.moveSpeed());
        npc.setBaseAttributeValue(AttributeType.SPELL_POWER, resume.spellPower());
        npc.setBaseAttributeValue(AttributeType.WORK_SPEED, resume.workSpeed());
        npc.setBaseAttributeValue(AttributeType.SPELL_SPEED, resume.spellSpeed());
        npc.setBaseAttributeValue(AttributeType.ARMOR_VALUE, resume.armorValue());
        npc.setBaseAttributeValue(AttributeType.MAX_MANA, resume.maxMana());
        npc.magic.setMana(resume.maxMana()); // 满蓝入职
        npc.setLevel(resume.level());

        fixEcsAfterSpawn(npc, colonyId);

        Log.info(TAG, "[Tourist] Recruited mage {} (Lv.{}) from resume for colony {} at {}",
                resume.touristName(), resume.level(),
                colonyId.toString().substring(0, 8),
                spawnPos.toShortString());

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
        World ecsWorld = WandscapeEngine.getWorld();
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
