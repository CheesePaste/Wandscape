package com.wsteam.wandscape.building.network;

import java.util.UUID;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.building.internal.BuildingSavedData;
import com.wsteam.wandscape.building.internal.BuildingState;
import com.wsteam.wandscape.npc.internal.EntityComponentBridge;
import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.engine.WandscapeEngine;
import com.wsteam.wandscape.core.ecs.World;

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

/**
 * Client→server packet: player clicks "Recruit NPC" in the tavern GUI.
 *
 * <p>Server validates the building at {@code buildingPos} is a tavern,
 * then spawns a new {@link WandscapeNpc} assigned to that colony.
 */
public record TavernRecruitPacket(BlockPos buildingPos, String action)
        implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

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
                LOGGER.warn("[Tavern] No building at {}", pkt.buildingPos);
                return;
            }
            BuildingState state = data.getBuilding(buildingId);
            if (state == null || !"tavern".equals(state.getCategory())) {
                LOGGER.warn("[Tavern] Building {} is not a tavern", buildingId);
                return;
            }
            UUID colonyId = state.getColonyId();
            if (colonyId == null) {
                sp.displayClientMessage(
                        Component.literal("[Wandscape] This tavern is not assigned to any colony."),
                        false);
                return;
            }

            // 2. Find spawn position near the tavern
            BlockPos spawnPos = findSpawnPos(level, pkt.buildingPos);

            // 3. Spawn NPC
            var npc = Wandscape.WANDSCAPE_NPC.get().spawn(level, spawnPos, MobSpawnType.COMMAND);
            if (npc == null) {
                sp.displayClientMessage(
                        Component.literal("[Wandscape] Failed to recruit NPC."),
                        false);
                return;
            }
            npc.setPersistenceRequired();
            npc.colonyId = colonyId;

            // 4. Fix ECS state (spawn() already triggered onNpcJoinWorld)
            fixEcsAfterSpawn(npc, colonyId);

            LOGGER.info("[Tavern] Recruited NPC {} for colony {} at {}",
                    npc.getUUID().toString().substring(0, 8),
                    colonyId.toString().substring(0, 8),
                    spawnPos.toShortString());

            sp.displayClientMessage(
                    Component.literal("[Wandscape] NPC recruited! " + spawnPos.toShortString()),
                    false);
        });
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
                com.wsteam.wandscape.core.component.ColonyMember.class);
        if (member != null && !colonyId.equals(member.colonyId())) {
            ecsWorld.addComponent(ecsId,
                    new com.wsteam.wandscape.core.component.ColonyMember(colonyId));
            LOGGER.info("[Tavern] Fixed NPC {} colony {} → {}",
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
