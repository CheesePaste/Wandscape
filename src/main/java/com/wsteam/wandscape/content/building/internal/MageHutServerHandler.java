package com.wsteam.wandscape.content.building.internal;
import com.wsteam.wandscape.content.npc.data.NpcData;

import com.wsteam.wandscape.content.building.data.BlockOffset;
import com.wsteam.wandscape.content.building.network.MageHutActionPacket;
import com.wsteam.wandscape.content.building.network.MageHutDataPacket;
import com.wsteam.wandscape.content.building.network.MageHutDataPacket.MageCandidate;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes.AttributeType;
import com.wsteam.wandscape.impl.WandscapeEngine;
import com.wsteam.wandscape.content.npc.NpcMenu;
import com.wsteam.wandscape.content.npc.NpcStrategyMenu;
import com.wsteam.wandscape.content.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.content.npc.network.NpcDataPacket;
import com.wsteam.wandscape.content.building.projection.BuildingRotation;
import com.wsteam.wandscape.content.element.data.ElementType;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes;
import com.wsteam.wandscape.content.npc.data.MageHutResident;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.registry.WandscapeConstants;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.content.warehouse.ColonyItemBank;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side logic for the Mage Hut panel actions (assign / upgrade / rest /
 * train / open equipment / open strategy).
 *
 * <p>All actions validate that {@code buildingPos} resolves to a committed
 * {@code mage_hut} building with a colony, then mutate the hut's resident record
 * in {@link BuildingSavedData} (which survives the mage's death) and push the
 * resident's attributes into its live {@link WandscapeNpc}.
 */
public final class MageHutServerHandler {
    private static final String TAG = "MageHut";

    private MageHutServerHandler() {}

    /** Open the Mage Hut panel for a right-clicked hut (server side → data packet). */
    public static void openMageHut(ServerPlayer sp, ServerLevel level, UUID buildingId,
                                   BuildingState state) {
        UUID colonyId = state.getColonyId();
        if (colonyId == null) return;
        var colonyLevel = WandscapeEngine.getColonyLevelManager() != null
                ? WandscapeEngine.getColonyLevelManager().getLevel(colonyId) : 1;
        PacketDistributor.sendToPlayer(sp,
                buildPacket(level, buildingId, state, colonyId, colonyLevel));
    }

    public static void handleAction(ServerPlayer sp, ServerLevel level, MageHutActionPacket pkt) {
        BuildingSavedData data = BuildingSavedData.get(level);
        UUID buildingId = data.getBuildingIdAt(pkt.buildingPos());
        if (buildingId == null) {
            buildingId = data.getBuildingIdInInteractionZone(pkt.buildingPos());
        }
        if (buildingId == null) {
            Log.warn(TAG, "No mage-hut building at {}", pkt.buildingPos());
            return;
        }
        BuildingState state = data.getBuilding(buildingId);
        if (state == null || !"mage_hut".equals(state.getCategory())) {
            Log.warn(TAG, "Building {} is not a mage hut", buildingId);
            return;
        }
        UUID colonyId = state.getColonyId();
        if (colonyId == null) {
            ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.mage_hut.no_colony",
                    "[Wandscape] This mage hut is not assigned to any colony."), true);
            return;
        }

        String action = pkt.action();
        if (action.startsWith("assign:")) {
            onAssign(sp, level, data, buildingId, state, colonyId,
                    UUID.fromString(action.substring("assign:".length())));
        } else if ("upgrade".equals(action)) {
            onUpgrade(sp, level, data, buildingId, state, colonyId);
        } else if ("rest".equals(action)) {
            onRest(sp, level, data, buildingId, state, colonyId);
        } else if (action.startsWith("train:")) {
            onTrain(sp, level, data, buildingId, state, colonyId,
                    AttributeType.valueOf(action.substring("train:".length())));
        } else if ("open_equip".equals(action)) {
            onOpenMenu(sp, level, data, buildingId, state, colonyId, false);
        } else if ("open_strategy".equals(action)) {
            onOpenMenu(sp, level, data, buildingId, state, colonyId, true);
        }
    }

    // ── Assign ──

    private static void onAssign(ServerPlayer sp, ServerLevel level, BuildingSavedData data,
                                 UUID buildingId, BuildingState state, UUID colonyId, UUID npcUuid) {
        if (data.getMageHutResident(buildingId) != null) {
            ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.mage_hut.occupied",
                    "[Wandscape] This mage hut is already occupied."), true);
            return;
        }
        if (!(level.getEntity(npcUuid) instanceof WandscapeNpc npc)) {
            ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.mage_hut.mage_not_found",
                    "[Wandscape] That mage was not found."), true);
            return;
        }
        if (!npc.isColonyNpc() || npc.colonyId == null || !npc.colonyId.equals(colonyId)) {
            ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.mage_hut.not_colony_mage",
                    "[Wandscape] That mage is not part of this colony."), true);
            return;
        }
        if (npc.getHomeHutId() != null && !npc.getHomeHutId().equals(buildingId)) {
            ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.mage_hut.already_housed",
                    "[Wandscape] That mage already lives in another hut."), true);
            return;
        }

        float[] base = new float[NpcAttributes.ORDER.size()];
        for (AttributeType type : NpcAttributes.ORDER) {
            base[type.ordinal()] = NpcAttributes.baseFromFlat(type, flat(npc, type), npc.getLevel());
        }
        MageHutResident resident = new MageHutResident(npc.getUUID(), colonyId,
                npc.getNpcName(), npc.getLevel(), base);
        data.setMageHutResident(buildingId, resident);
        npc.setHomeHutId(buildingId);

        Log.info(TAG, "Assigned mage {} (Lv.{}) to hut {} in colony {}",
                npc.getNpcName(), npc.getLevel(), buildingId.toString().substring(0, 8),
                colonyId.toString().substring(0, 8));
        ScreenFeedbackPacket.send(sp,
                I18n.name("message.wandscape.mage_hut.assigned", "[Wandscape] %s moved in!",
                        npc.getNpcName()), false);
        sendRefresh(sp, level, buildingId, state, colonyId);
    }

    // ── Upgrade ──

    private static void onUpgrade(ServerPlayer sp, ServerLevel level, BuildingSavedData data,
                                  UUID buildingId, BuildingState state, UUID colonyId) {
        MageHutResident resident = data.getMageHutResident(buildingId);
        WandscapeNpc npc = requireAlive(sp, level, resident);
        if (npc == null) return;

        int colonyLevel = WandscapeEngine.getColonyLevelManager() != null
                ? WandscapeEngine.getColonyLevelManager().getLevel(colonyId) : 1;
        if (!NpcAttributes.canLevelUp(resident.level(), colonyLevel)) {
            ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.mage_hut.max_level",
                    "[Wandscape] The mage is already at the colony level (%d).", colonyLevel), true);
            return;
        }
        long cost = NpcAttributes.upgradeCostPerElement(resident.level());
        if (!chargeElements(sp, level, colonyId, NpcAttributes.upgradeElements(), cost)) return;

        MageHutResident next = resident.withLevel(resident.level() + 1);
        data.setMageHutResident(buildingId, next);
        applyResidentAttributes(npc, next);

        ScreenFeedbackPacket.send(sp,
                I18n.name("message.wandscape.mage_hut.leveled", "[Wandscape] %s is now Lv.%d!",
                        resident.mageName(), next.level()), false);
        sendRefresh(sp, level, buildingId, state, colonyId);
    }

    // ── Train ──

    private static void onTrain(ServerPlayer sp, ServerLevel level, BuildingSavedData data,
                                UUID buildingId, BuildingState state, UUID colonyId,
                                AttributeType type) {
        MageHutResident resident = data.getMageHutResident(buildingId);
        WandscapeNpc npc = requireAlive(sp, level, resident);
        if (npc == null) return;

        if (!NpcAttributes.canTrain(type, resident.base(type))) {
            ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.mage_hut.max_base",
                    "[Wandscape] %s is already at its base cap.", type.name()), true);
            return;
        }
        long cost = NpcAttributes.trainCostPerElement(type, resident.base(type));
        if (!chargeElements(sp, level, colonyId, NpcAttributes.trainElements(type), cost)) return;

        MageHutResident next = resident.withBase(type,
                NpcAttributes.trainedValue(type, resident.base(type)));
        data.setMageHutResident(buildingId, next);
        applyResidentAttributes(npc, next);

        ScreenFeedbackPacket.send(sp,
                I18n.name("message.wandscape.mage_hut.trained", "[Wandscape] %s trained +%s.",
                        resident.mageName(), type.name()), false);
        sendRefresh(sp, level, buildingId, state, colonyId);
    }

    // ── Rest ──

    private static void onRest(ServerPlayer sp, ServerLevel level, BuildingSavedData data,
                               UUID buildingId, BuildingState state, UUID colonyId) {
        MageHutResident resident = data.getMageHutResident(buildingId);
        WandscapeNpc npc = requireAlive(sp, level, resident);
        if (npc == null) return;

        BlockPos restPos = hutRestPos(state);
        npc.setRest(restPos, level.getGameTime() + WandscapeConstants.MAGE_HUT_REST_TICKS);
        npc.setAiWanderingEnabled(false);

        Log.info(TAG, "Mage {} resting at {} for {} ticks", npc.getNpcName(), restPos,
                WandscapeConstants.MAGE_HUT_REST_TICKS);
        ScreenFeedbackPacket.send(sp,
                I18n.name("message.wandscape.mage_hut.resting",
                        "[Wandscape] Now resting — the mage returns to the hut."), false);
        sendRefresh(sp, level, buildingId, state, colonyId);
    }

    // ── Open equipment / strategy menu ──

    private static void onOpenMenu(ServerPlayer sp, ServerLevel level, BuildingSavedData data,
                                   UUID buildingId, BuildingState state, UUID colonyId,
                                   boolean strategy) {
        MageHutResident resident = data.getMageHutResident(buildingId);
        WandscapeNpc npc = requireAlive(sp, level, resident);
        if (npc == null) return;

        if (strategy) {
            sp.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new NpcStrategyMenu(id, inv, npc),
                    Component.literal("Cast Strategy")));
        } else {
            sp.openMenu(new SimpleMenuProvider(
                    (id, inv, p) -> new NpcMenu(id, inv, npc),
                    Component.literal("NPC Info")));
        }
        sp.serverLevel().getServer().execute(() -> {
            if (!npc.isRemoved() && (sp.containerMenu instanceof NpcMenu
                    || sp.containerMenu instanceof NpcStrategyMenu)) {
                PacketDistributor.sendToPlayer(sp, NpcDataPacket.from(npc));
            }
        });
    }

    // ── Shared helpers ──

    @Nullable
    private static WandscapeNpc requireAlive(ServerPlayer sp, ServerLevel level,
                                             MageHutResident resident) {
        if (resident == null) {
            ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.mage_hut.empty",
                    "[Wandscape] The hut has no mage yet."), true);
            return null;
        }
        WandscapeNpc npc = aliveNpc(level, resident);
        if (npc == null) {
            ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.mage_hut.dead",
                    "[Wandscape] The mage is dead and cannot act."), true);
            return null;
        }
        return npc;
    }

    @Nullable
    private static WandscapeNpc aliveNpc(ServerLevel level, MageHutResident resident) {
        if (resident == null || resident.npcId() == null) return null;
        if (level.getEntity(resident.npcId()) instanceof WandscapeNpc npc
                && !npc.isRemoved() && npc.isAlive()) {
            return npc;
        }
        return null;
    }

    /** Subtract {@code cost} of each element in {@code required} from the colony warehouse, checking first. */
    private static boolean chargeElements(ServerPlayer sp, ServerLevel level, UUID colonyId,
                                          List<ElementType> required, long cost) {
        ColonyItemBank bank = ColonyItemBank.get(level);
        if (bank == null) {
            ScreenFeedbackPacket.send(sp, I18n.name("message.wandscape.mage_hut.no_bank",
                    "[Wandscape] The warehouse is unavailable."), true);
            return false;
        }
        Map<ElementType, Long> balances = bank.getElementSnapshot(colonyId);
        StringBuilder missing = new StringBuilder();
        for (ElementType t : required) {
            if (balances.getOrDefault(t, 0L) < cost) {
                if (!missing.isEmpty()) missing.append("、");
                missing.append(I18n.name("element.wandscape." + t.getId(), t.getId()).getString())
                        .append(" ×").append(cost);
            }
        }
        if (!missing.isEmpty()) {
            ScreenFeedbackPacket.send(sp,
                    I18n.name("message.wandscape.mage_hut.insufficient_elements",
                            "[Wandscape] Insufficient elements: %s.", missing), true);
            return false;
        }
        for (ElementType t : required) {
            bank.consumeElement(colonyId, t, cost);
        }
        return true;
    }

    /** Recompute the mage's base attributes from the resident base+level. */
    private static void applyResidentAttributes(WandscapeNpc npc, MageHutResident resident) {
        for (AttributeType type : NpcAttributes.ORDER) {
            setFlat(npc, type, NpcAttributes.computeEffective(type,
                    resident.base(type), resident.level(), 0f));
        }
        npc.setLevel(resident.level());
    }

    /** The world-space rest point: hut anchor + rotated interior offset. */
    private static BlockPos hutRestPos(BuildingState state) {
        var offset = BuildingRotation.rotateOffset(
                new BlockOffset(1, 0, 1),
                state.getRotationSteps());
        return state.getAnchor().offset(offset.x(), offset.y(), offset.z());
    }

    // ── Packet refresh ──

    private static void sendRefresh(ServerPlayer sp, ServerLevel level, UUID buildingId,
                                    BuildingState state, UUID colonyId) {
        var colonyLevel = WandscapeEngine.getColonyLevelManager() != null
                ? WandscapeEngine.getColonyLevelManager().getLevel(colonyId) : 1;
        PacketDistributor.sendToPlayer(sp,
                buildPacket(level, buildingId, state, colonyId, colonyLevel));
    }

    private static MageHutDataPacket buildPacket(ServerLevel level, UUID buildingId,
                                                 BuildingState state, UUID colonyId, int colonyLevel) {
        String creator = BuildingInteractHandler.resolveCreator(level, state.getAnchor());
        MageHutResident resident = BuildingSavedData.get(level).getMageHutResident(buildingId);

        if (resident == null) {
            List<MageCandidate> candidates = collectCandidates(level, colonyId);
            return new MageHutDataPacket(state.getAnchor(), colonyId, creator, colonyLevel,
                    false, false, false, null, "", 1, -1, new float[NpcAttributes.ORDER.size()],
                    new float[NpcAttributes.ORDER.size()], candidates);
        }

        WandscapeNpc npc = aliveNpc(level, resident);
        boolean alive = npc != null;
        float[] base = resident.base();
        float[] equip = new float[NpcAttributes.ORDER.size()];
        if (alive) {
            for (AttributeType type : NpcAttributes.ORDER) {
                float effective = npc.getEffectiveAttribute(type);
                equip[type.ordinal()] = effective
                        - NpcAttributes.computeEffective(type, base[type.ordinal()],
                                resident.level(), 0f);
            }
        }
        boolean resting = alive && npc.isResting();
        return new MageHutDataPacket(state.getAnchor(), colonyId, creator, colonyLevel,
                true, alive, resting, resident.npcId(), resident.mageName(), resident.level(),
                alive ? npc.getSkinVariant() : -1, base, equip, List.of());
    }

    /** Colony mages that are present and not already bound to another hut. */
    private static List<MageCandidate> collectCandidates(ServerLevel level, UUID colonyId) {
        List<MageCandidate> out = new ArrayList<>();
        try {
            var npcApi = WandscapeApis.getNpcApi();
            for (var npcData : npcApi.getColonyNpcs(colonyId)) {
                if (level.getEntity(npcData.getNpcId()) instanceof WandscapeNpc npc
                        && npc.isColonyNpc() && npc.getHomeHutId() == null) {
                    out.add(new MageCandidate(npc.getUUID(), npc.getNpcName(), npc.isEngineIdle()));
                }
            }
        } catch (IllegalStateException ignored) {}
        return out;
    }

    // ── Attribute field mapping ──

    private static float flat(WandscapeNpc npc, AttributeType type) {
        return npc.getBaseAttributeValue(type);
    }

    private static void setFlat(WandscapeNpc npc, AttributeType type, float value) {
        npc.setBaseAttributeValue(type, value);
    }
}
