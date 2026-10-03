package com.wsteam.wandscape.content.building.wonder;

import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.building.event.WonderCompletedEvent;
import com.wsteam.wandscape.content.production.ProductionRecipeManager;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.networking.ScreenFeedbackPacket;
import com.wsteam.wandscape.foundation.ui.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Registry and dispatcher for wonder building completion triggers.
 *
 * <p>Wonder buildings carry special historical milestones and game-changing capabilities.
 * When a wonder completes construction:
 * <ol>
 *   <li>{@link WonderCompletedEvent} is posted to {@link NeoForge#EVENT_BUS} for decoupled systems.</li>
 *   <li>Matching triggers registered in this registry are executed with full contextual payload.</li>
 * </ol>
 */
public final class WonderTriggerRegistry {
    private static final String TAG = "WonderTrigger";

    private static final Map<String, List<WonderTrigger>> BY_TYPE = new ConcurrentHashMap<>();
    private static final List<WonderTrigger> PREDICATE_TRIGGERS = new CopyOnWriteArrayList<>();
    private static volatile boolean initialized = false;

    private WonderTriggerRegistry() {}

    /**
     * Initializes built-in wonder triggers. Safe to call multiple times (idempotent).
     */
    public static synchronized void init() {
        if (initialized) return;
        initialized = true;

        registerBuiltInTriggers();
    }

    /**
     * Register core built-in wonder effects.
     */
    private static void registerBuiltInTriggers() {
        // 魔法学院 (magic_academy): 建成时解锁殖民地所有合成配方
        register("magic_academy", true, context -> {
            UUID colonyId = context.colonyId();
            if (colonyId != null) {
                int unlocked = ProductionRecipeManager.unlockAllSynthesize(colonyId, "wonder:magic_academy");
                Log.info(TAG, "[MagicAcademy] Wonder completed for colony {} — unlocked all synthesize recipes ({} newly unlocked)",
                        colonyId.toString().substring(0, 8), unlocked);

                Component message = I18n.name(
                        "message.wandscape.wonder.magic_academy_completed",
                        "[奇观] 魔法学院落成！为小镇解锁了全部合成配方（共 %s 项）",
                        unlocked > 0 ? String.valueOf(unlocked) : "全套");
                broadcastToColony(context, message);
            }
        });
    }

    /**
     * Register a trigger for a specific building type id.
     *
     * @param buildingTypeId target building type ID (e.g. "magic_academy" or "default:magic_academy")
     * @param firstOnly      if true, only fires on first completion, not on subsequent repairs
     * @param action         callback logic to execute
     */
    public static void register(String buildingTypeId, boolean firstOnly, Consumer<WonderTriggerContext> action) {
        String normalized = normalizeId(buildingTypeId);
        BY_TYPE.computeIfAbsent(normalized, k -> new CopyOnWriteArrayList<>()).add(new WonderTrigger() {
            @Override
            public boolean matches(WonderTriggerContext context) {
                return !firstOnly || context.firstCompletion();
            }

            @Override
            public void onComplete(WonderTriggerContext context) {
                action.accept(context);
            }
        });
    }

    /**
     * Register a general predicate trigger matching arbitrary wonder conditions.
     */
    public static void register(Predicate<WonderTriggerContext> predicate, Consumer<WonderTriggerContext> action) {
        PREDICATE_TRIGGERS.add(new WonderTrigger() {
            @Override
            public boolean matches(WonderTriggerContext context) {
                return predicate.test(context);
            }

            @Override
            public void onComplete(WonderTriggerContext context) {
                action.accept(context);
            }
        });
    }

    /**
     * Dispatch completion for a wonder building: fires WonderCompletedEvent and executes all matching triggers.
     *
     * @param context contextual payload of the completed wonder
     */
    public static void dispatch(WonderTriggerContext context) {
        if (!initialized) {
            init();
        }

        // 1. Post NeoForge event so decoupled systems (advancements, quests, etc.) can hook in
        NeoForge.EVENT_BUS.post(new WonderCompletedEvent(
                context.buildingId(),
                context.colonyId(),
                context.buildingTypeId(),
                context.config(),
                context.state(),
                context.level(),
                context.firstCompletion()
        ));

        // 2. Exact / normalized building type matches
        String normalized = normalizeId(context.buildingTypeId());
        List<WonderTrigger> typedTriggers = BY_TYPE.get(normalized);
        if (typedTriggers != null) {
            for (WonderTrigger trigger : typedTriggers) {
                tryTrigger(trigger, context);
            }
        }

        // 3. Predicate triggers
        for (WonderTrigger trigger : PREDICATE_TRIGGERS) {
            tryTrigger(trigger, context);
        }
    }

    private static void tryTrigger(WonderTrigger trigger, WonderTriggerContext context) {
        try {
            if (trigger.matches(context)) {
                trigger.onComplete(context);
            }
        } catch (Exception e) {
            Log.error(TAG, "Error executing wonder trigger for {}: {}", context.buildingTypeId(), e.getMessage());
        }
    }

    /**
     * Broadcast a feedback banner and system message to players belonging to this colony or nearby.
     */
    public static void broadcastToColony(WonderTriggerContext context, Component message) {
        MinecraftServer server = context.level().getServer();
        if (server == null) return;
        UUID colonyId = context.colonyId();
        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        UUID founderId = colonyId != null && colonyApi != null ? colonyApi.getFounder(colonyId) : null;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean shouldSend = false;
            if (colonyId != null) {
                if (founderId != null && founderId.equals(player.getUUID())) {
                    shouldSend = true;
                } else if (colonyApi != null && colonyId.equals(colonyApi.getColonyId(player.blockPosition()))) {
                    shouldSend = true;
                }
            }
            if (!shouldSend && context.state() != null && context.state().getAnchor() != null) {
                double distSq = player.distanceToSqr(
                        context.state().getAnchor().getX(),
                        context.state().getAnchor().getY(),
                        context.state().getAnchor().getZ());
                if (distSq <= 128 * 128) {
                    shouldSend = true;
                }
            }
            if (shouldSend) {
                ScreenFeedbackPacket.send(player, message, false);
                player.sendSystemMessage(message);
            }
        }
    }

    /**
     * Get all registered building type IDs.
     */
    public static Set<String> getRegisteredTypeIds() {
        return Collections.unmodifiableSet(BY_TYPE.keySet());
    }

    /**
     * Get the count of general predicate-based triggers.
     */
    public static int getPredicateTriggerCount() {
        return PREDICATE_TRIGGERS.size();
    }

    public static String normalizeId(String id) {
        if (id == null) return "";
        return id.contains(":") ? id.substring(id.indexOf(':') + 1).toLowerCase() : id.toLowerCase();
    }
}
