package com.wsteam.wandscape.projection.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.projection.data.BuildingSlot;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Abilities;

/**
 * Client-side static state holder for soul projection mode.
 * Thread-safe via volatile fields + synchronized collections.
 * Pattern mirrors {@code RoadEditorClientState}.
 */
public final class ProjectionClientState {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Whether the player is currently in projection mode. */
    private static volatile boolean projecting = false;

    /** World position where the player's body is anchored. */
    private static volatile BlockPos bodyAnchor = null;

    /** Snapshot of player abilities before projection (for restore on exit). */
    private static volatile AbilitySnapshot savedAbilities = null;

    /** Index into {@link #buildingSlots} of the currently selected building. */
    private static volatile int selectedSlotIndex = 0;

    /** Current ghost preview position (null = no valid target under crosshair). */
    private static volatile BlockPos ghostPos = null;

    /** Whether the current ghost position overlaps an existing building. */
    private static volatile boolean overlapDetected = false;

    /** Available building slots received from server. */
    private static final List<BuildingSlot> buildingSlots =
            Collections.synchronizedList(new ArrayList<>());

    /** Flying speed in projection mode (from config). */
    private static volatile float flyingSpeed = 0.15f;

    /** Accumulated scroll delta for building selection cycling. */
    private static volatile double accumulatedScroll = 0;

    private ProjectionClientState() {}

    // ── Projection mode ──

    public static boolean isProjecting() {
        return projecting;
    }

    /**
     * Enter projection mode. Called from {@code ProjectionEnterResponsePacket} client handler.
     * Saves current player state, enables flight, stores building slots.
     */
    public static void enterProjection(BlockPos anchor, List<BuildingSlot> slots) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        bodyAnchor = anchor;
        flyingSpeed = Config.PROJECTION_FLYING_SPEED.get().floatValue();

        // Save abilities snapshot for restore
        Abilities abilities = mc.player.getAbilities();
        savedAbilities = new AbilitySnapshot(
                abilities.mayfly,
                abilities.flying,
                abilities.instabuild,
                abilities.mayBuild,
                abilities.getFlyingSpeed(),
                abilities.getWalkingSpeed());

        // Enable creative flight
        abilities.mayfly = true;
        abilities.flying = true;
        abilities.setFlyingSpeed(flyingSpeed);
        mc.player.onUpdateAbilities();

        // Store building slots
        synchronized (buildingSlots) {
            buildingSlots.clear();
            buildingSlots.addAll(slots);
        }
        selectedSlotIndex = 0;
        ghostPos = null;
        overlapDetected = false;
        accumulatedScroll = 0;

        projecting = true;

        LOGGER.info("[Projection] Entered projection mode. Body at {}, {} buildings, flySpeed={}",
                anchor, slots.size(), flyingSpeed);
    }

    /**
     * Exit projection mode. Called from client handler or flight controller.
     * Restores player abilities, teleports to body anchor, clears state.
     */
    public static void exitProjection() {
        Minecraft mc = Minecraft.getInstance();
        projecting = false;

        if (mc.player != null) {
            // Restore abilities
            if (savedAbilities != null) {
                Abilities abilities = mc.player.getAbilities();
                abilities.mayfly = savedAbilities.mayfly;
                abilities.flying = savedAbilities.flying;
                abilities.instabuild = savedAbilities.instabuild;
                abilities.mayBuild = savedAbilities.mayBuild;
                abilities.setFlyingSpeed(savedAbilities.flyingSpeed);
                abilities.setWalkingSpeed(savedAbilities.walkingSpeed);
                mc.player.onUpdateAbilities();
            }

            // Teleport back to body anchor
            if (bodyAnchor != null) {
                mc.player.setPos(bodyAnchor.getX() + 0.5,
                        bodyAnchor.getY(),
                        bodyAnchor.getZ() + 0.5);
            }
        }

        // Clear state
        bodyAnchor = null;
        savedAbilities = null;
        selectedSlotIndex = 0;
        ghostPos = null;
        overlapDetected = false;
        accumulatedScroll = 0;
        synchronized (buildingSlots) {
            buildingSlots.clear();
        }

        LOGGER.info("[Projection] Exited projection mode");
    }

    // ── Body anchor ──

    public static BlockPos getBodyAnchor() {
        return bodyAnchor;
    }

    // ── Building selection ──

    public static int getSelectedSlotIndex() {
        return selectedSlotIndex;
    }

    public static void setSelectedSlotIndex(int index) {
        selectedSlotIndex = index;
    }

    public static List<BuildingSlot> getBuildingSlots() {
        synchronized (buildingSlots) {
            return List.copyOf(buildingSlots);
        }
    }

    // ── Ghost position ──

    public static BlockPos getGhostPos() {
        return ghostPos;
    }

    public static void setGhostPos(BlockPos pos) {
        ghostPos = pos;
    }

    // ── Overlap ──

    public static boolean isOverlapDetected() {
        return overlapDetected;
    }

    public static void setOverlapDetected(boolean overlapped) {
        overlapDetected = overlapped;
    }

    // ── Flying speed ──

    public static float getFlyingSpeed() {
        return flyingSpeed;
    }

    // ── Scroll accumulator ──

    public static double getAccumulatedScroll() {
        return accumulatedScroll;
    }

    public static void addScrollDelta(double delta) {
        accumulatedScroll += delta;
    }

    public static void resetScroll() {
        accumulatedScroll = 0;
    }

    // ── Inner types ──

    /**
     * Snapshot of player abilities for restoration on exit.
     */
    private record AbilitySnapshot(
            boolean mayfly,
            boolean flying,
            boolean instabuild,
            boolean mayBuild,
            float flyingSpeed,
            float walkingSpeed
    ) {}
}
