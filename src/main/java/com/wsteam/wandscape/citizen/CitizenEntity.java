package com.wsteam.wandscape.citizen;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Dynamic;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * A decorative citizen NPC that lives in the colony.
 *
 * <p>Extends {@link Villager} to reuse vanilla rendering, model, and poses.
 * All vanilla brain AI is suppressed — we use a simple goalSelector for
 * random walking and handle interactions ourselves.
 *
 * <p><b>No ECS coupling.</b> This entity is managed by {@link CitizenManager},
 * not by the ECS World / GlobalTaskPool system.
 */
public class CitizenEntity extends Villager {

    private String citizenName = "";
    private Profession profession = Profession.IDLER;
    private int mood = 50;
    private String statusText = "空闲";

    public CitizenEntity(EntityType<? extends Villager> entityType, Level level) {
        super(entityType, level);
        setInvulnerable(true);
        setCustomNameVisible(true);
    }

    // ──────────────────────── Brain suppression ────────────────────────

    /**
     * Return an empty brain provider — no memories, no sensors.
     * This prevents the vanilla villager schedule/activity system from running.
     */
    @Override
    protected Brain.Provider<Villager> brainProvider() {
        return Brain.provider(ImmutableList.of(), ImmutableList.of());
    }

    /**
     * Build an empty brain. Does NOT call {@code registerBrainGoals()}
     * because it is {@code private} in {@link Villager} and we don't
     * want vanilla activities (WORK, PLAY, MEET, etc.) anyway.
     */
    @Override
    protected Brain<?> makeBrain(Dynamic<?> dynamic) {
        return this.brainProvider().makeBrain(dynamic);
    }

    /**
     * No-op — suppress vanilla brain tick, trade timer, and raid awareness.
     * The {@link #goalSelector} (FloatGoal + RandomStrollGoal) still runs
     * via {@link Mob#serverAiStep()}.
     */
    @Override
    protected void customServerAiStep() {
        // intentionally empty — skip brain.tick(), trade timer, raid sweep
    }

    // ──────────────────────── AI goals ────────────────────────

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(5, new RandomStrollGoal(this, 0.5));
    }

    // ──────────────────────── Interaction ────────────────────────

    /**
     * Right-click shows name, profession, mood, and status in chat.
     * Never opens the vanilla trading GUI.
     */
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.isAlive()) {
            return super.mobInteract(player, hand);
        }

        if (!level().isClientSide) {
            String msg = citizenName + " - " + profession.getDisplayName()
                    + " - 情绪 " + mood + " (" + statusText + ")";
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

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    // ──────────────────────── Attributes ────────────────────────

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MOVEMENT_SPEED, 0.5)
                .add(Attributes.FOLLOW_RANGE, 48.0)
                .add(Attributes.MAX_HEALTH, 20.0);
    }

    // ──────────────────────── Getters / Setters ────────────────────────

    public String getCitizenName() {
        return citizenName;
    }

    public void setCitizenName(String name) {
        this.citizenName = name;
        syncName();
    }

    public Profession getProfession() {
        return profession;
    }

    public void setProfession(Profession profession) {
        this.profession = profession;
    }

    public int getMood() {
        return mood;
    }

    public void setMood(int mood) {
        this.mood = Math.clamp(mood, 0, 100);
    }

    public String getStatusText() {
        return statusText;
    }

    public void setStatusText(String statusText) {
        this.statusText = statusText;
    }

    private void syncName() {
        setCustomName(Component.literal(citizenName));
    }
}
