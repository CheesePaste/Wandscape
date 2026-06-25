package com.wsteam.wandscape.citizen;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Dynamic;
import com.wsteam.wandscape.citizen.ai.CitizenMoveGoal;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A decorative citizen NPC that lives in the colony.
 *
 * <p>Extends {@link Villager} to reuse vanilla rendering, model, and poses.
 * Vanilla brain AI is suppressed. A simple goalSelector with
 * {@link CitizenMoveGoal} handles state-driven movement.
 *
 * <p><b>State machine:</b> {@link CitizenManager} sets state, commute target,
 * and wander parameters each tick. The entity is only spawned for visible
 * states ({@link CitizenState#COMMUTING}, {@link CitizenState#LEISURE},
 * {@link CitizenState#IDLE}); during {@link CitizenState#WORKING} and
 * {@link CitizenState#SLEEPING} it is discarded and tracked as pure data
 * in {@code CitizenManager.storedCitizens}.
 *
 * <p><b>No ECS coupling.</b> Managed entirely by {@link CitizenManager}.
 */
public class CitizenEntity extends Villager {

    // ── Identity ──
    private String citizenName = "";
    private Profession profession = Profession.IDLER;
    private int mood = 50;

    // ── State machine (set by CitizenManager) ──
    private CitizenState currentState = CitizenState.IDLE;

    /** Target for COMMUTING state. */
    @Nullable
    private BlockPos commuteTarget;
    private boolean commuteArrived;

    /** Anchor for WORKING / LEISURE / IDLE wandering. */
    @Nullable
    private BlockPos wanderAnchor;
    private int wanderRadius = 8;

    /** Points of interest for LEISURE city wandering. */
    private List<BlockPos> poiList = List.of();

    public CitizenEntity(EntityType<? extends Villager> entityType, Level level) {
        super(entityType, level);
        setInvulnerable(true);
        setCustomNameVisible(true);
    }

    // ──────────────────────── Brain suppression ────────────────────────

    @Override
    protected Brain.Provider<Villager> brainProvider() {
        return Brain.provider(ImmutableList.of(), ImmutableList.of());
    }

    @Override
    protected Brain<?> makeBrain(Dynamic<?> dynamic) {
        return this.brainProvider().makeBrain(dynamic);
    }

    @Override
    protected void customServerAiStep() {
        // empty — suppress brain.tick(), trade timer, raid sweep
    }

    // ──────────────────────── AI goals ────────────────────────

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new CitizenMoveGoal(this, 0.55, 0.35));
        this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }

    // ──────────────────────── Interaction ────────────────────────

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.isAlive()) return super.mobInteract(player, hand);

        if (!level().isClientSide) {
            String msg = citizenName + " - " + profession.getDisplayName()
                    + " - 情绪 " + mood + " (" + currentState.getDisplayName() + ")";
            player.sendSystemMessage(Component.literal(msg));
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    // ──────────────────────── Lifecycle ────────────────────────

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        setInvulnerable(true);
        syncName();
    }

    /**
     * Never persist to disk — managed entirely by CitizenManager.
     * Overriding this is the canonical way to exclude an entity from
     * chunk saves; vanilla checks it before calling save().
     */
    @Override
    public boolean shouldBeSaved() { return false; }

    @Override
    public boolean removeWhenFarAway(double d) { return false; }

    /** Ignore vanilla NBT — everything is managed by CitizenManager. */
    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {}

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {}

    // ──────────────────────── Attributes ────────────────────────

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.5)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.MAX_HEALTH, 20.0);
    }

    // ──────────────────────── State helpers ────────────────────────

    /** Called by CitizenManager each tick. Syncs pose. */
    public void applyState(CitizenState state) {
        if (this.currentState != state) {
            this.currentState = state;
            if (state == CitizenState.SLEEPING) setPose(Pose.SLEEPING);
            else if (getPose() == Pose.SLEEPING) setPose(Pose.STANDING);
        }
    }

    // ──────────────────────── Getters / Setters ────────────────────────

    public String getCitizenName() { return citizenName; }
    public void setCitizenName(String name) { this.citizenName = name; syncName(); }

    public Profession getProfession() { return profession; }
    public void setProfession(Profession p) { this.profession = p; }

    public int getMood() { return mood; }
    public void setMood(int m) { this.mood = Math.clamp(m, 0, 100); }

    public CitizenState getCurrentState() { return currentState; }

    @Nullable public BlockPos getCommuteTarget() { return commuteTarget; }
    public void setCommuteTarget(@Nullable BlockPos t) { this.commuteTarget = t; }

    public boolean isCommuteArrived() { return commuteArrived; }
    public void setCommuteArrived(boolean a) { this.commuteArrived = a; }

    @Nullable public BlockPos getWanderAnchor() { return wanderAnchor; }
    public void setWanderAnchor(@Nullable BlockPos a) { this.wanderAnchor = a; }

    public int getWanderRadius() { return wanderRadius; }
    public void setWanderRadius(int r) { this.wanderRadius = r; }

    /** Points of interest for LEISURE wandering. Set by CitizenManager. */
    public List<BlockPos> getPoiList() { return poiList; }
    public void setPoiList(List<BlockPos> pois) { this.poiList = List.copyOf(pois); }

    private void syncName() { setCustomName(Component.literal(citizenName)); }
}
