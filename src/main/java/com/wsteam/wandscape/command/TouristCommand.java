package com.wsteam.wandscape.command;
import com.wsteam.wandscape.content.task.ecs.World;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.content.tourist.data.BarRatio;
import com.wsteam.wandscape.content.npc.attributes.NpcAttributes;
import com.wsteam.wandscape.content.npc.data.RecruitmentCandidate;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.foundation.ui.I18n;
import com.wsteam.wandscape.content.tourist.entity.TouristEntity;
import com.wsteam.wandscape.content.tourist.internal.TouristCooldownDebug;
import com.wsteam.wandscape.content.tourist.internal.TouristSimSystem;
import com.wsteam.wandscape.content.tourist.internal.TouristSpawnSystem;
import com.wsteam.wandscape.content.tourist.internal.TouristState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
/**
 * Debug commands for tourist NPC testing.
 *
 * <pre>
 * /wandscape tourist list
 * /wandscape tourist spawn
 * /wandscape tourist spawn mage [level]
 * /wandscape tourist spawn_mage [level]
 * /wandscape tourist recruit [level]
 * /wandscape tourist state &lt;name|all&gt; &lt;state&gt;
 * /wandscape tourist cooldown &lt;visited|all&gt; &lt;on|off&gt;
 * </pre>
 */
public final class TouristCommand {

    private TouristCommand() {}

    public static com.mojang.brigadier.tree.CommandNode<CommandSourceStack> node() {
        return Commands.literal("tourist")
                .then(Commands.literal("list")
                        .executes(TouristCommand::list))
                .then(Commands.literal("spawn")
                        .executes(TouristCommand::forceSpawn)
                        .then(Commands.literal("mage")
                                .executes(ctx -> spawnRecruitableMage(ctx, 1))
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 10))
                                        .executes(ctx -> spawnRecruitableMage(ctx, IntegerArgumentType.getInteger(ctx, "level"))))))
                .then(Commands.literal("spawn_mage")
                        .executes(ctx -> spawnRecruitableMage(ctx, 1))
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 10))
                                .executes(ctx -> spawnRecruitableMage(ctx, IntegerArgumentType.getInteger(ctx, "level")))))
                .then(Commands.literal("recruit")
                        .executes(ctx -> spawnRecruitableMage(ctx, 1))
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, 10))
                                .executes(ctx -> spawnRecruitableMage(ctx, IntegerArgumentType.getInteger(ctx, "level")))))
                .then(Commands.literal("state")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("state", StringArgumentType.word())
                                        .suggests(TouristCommand::suggestStates)
                                        .executes(TouristCommand::forceState))))
                .then(Commands.literal("cooldown")
                        .then(Commands.argument("layer", StringArgumentType.word())
                                .suggests(TouristCommand::suggestLayers)
                                .then(Commands.argument("toggle", StringArgumentType.word())
                                        .suggests(TouristCommand::suggestToggle)
                                        .executes(TouristCommand::cooldownToggle))))
                .build();
    }

    // ── list / spawn / state ──

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();

        List<TouristEntity> tourists = findTourists(level);
        MutableComponent msg = Component.literal("=== Tourists: " + tourists.size() + " active ===");

        for (TouristEntity t : tourists) {
            Component appearance = t.isMage()
                    ? I18n.name("message.wandscape.command.tourist_role_mage", "法师")
                    : I18n.name("message.wandscape.command.tourist_role_citizen", "市民");
            BarRatio br = BarRatio.of(t.getComfortSat(), t.getComfortNeed(),
                    t.getMagicSat(), t.getMagicNeed(), t.getWonderSat(), t.getWonderNeed());
            Component state = I18n.name(t.getCurrentState().getDisplayNameKey(),
                    t.getCurrentState().getDisplayName());
            msg.append(Component.literal("\n")).append(I18n.name(
                    "message.wandscape.command.tourist_list_line",
                    "  %s | %s | %s | Lv.%s | 精力%s | C%s%% M%s%% W%s%%",
                    t.getTouristName(), appearance, state,
                    t.getLevel(), t.getEnergy(), br.comfort(), br.magic(), br.wonder()));
        }

        // Show debug flag state
        msg.append(Component.literal("\n\n--- Debug ---\n  visited : "
                + (TouristCooldownDebug.skipVisitedBuildings ? "DISABLED (skip)" : "ENABLED (normal)")));

        src.sendSuccess(() -> msg, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int forceSpawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();

        int before = countTourists(level);
        TouristSpawnSystem.forceSpawn(level);
        int after = countTourists(level);
        int spawned = after - before;

        src.sendSuccess(() -> Component.literal(
                "[Tourist] Spawn triggered. Before=" + before
                        + ", After=" + after + ", New=" + spawned), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int countTourists(ServerLevel level) {
        int count = 0;
        for (var entity : level.getAllEntities()) {
            if (entity instanceof TouristEntity t && t.isAlive()) {
                count++;
            }
        }
        return count;
    }

    private static int forceState(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        String stateName = StringArgumentType.getString(ctx, "state");

        TouristState targetState;
        try {
            targetState = TouristState.valueOf(stateName.toUpperCase());
        } catch (IllegalArgumentException e) {
            src.sendFailure(Component.literal("Unknown state: " + stateName
                    + ". Valid: visiting, exploring, wandering, idle, sleeping"));
            return 0;
        }

        ServerLevel level = src.getLevel();
        List<TouristEntity> tourists = findTourists(level);

        if ("all".equalsIgnoreCase(name)) {
            for (TouristEntity t : tourists) {
                t.forceMoveMode(targetState);
            }
            src.sendSuccess(() -> Component.literal("[Tourist] All " + tourists.size() + " tourists → ")
                    .append(I18n.name(targetState.getDisplayNameKey(), targetState.getDisplayName())), false);
            return Command.SINGLE_SUCCESS;
        }

        for (TouristEntity t : tourists) {
            if (t.getTouristName().startsWith(name)) {
                t.forceMoveMode(targetState);
                src.sendSuccess(() -> Component.literal("[Tourist] " + t.getTouristName() + " → ")
                        .append(I18n.name(targetState.getDisplayNameKey(), targetState.getDisplayName())), false);
                return Command.SINGLE_SUCCESS;
            }
        }
        src.sendFailure(Component.literal("No tourist matching '" + name + "'"));
        return 0;
    }

    // ── cooldown toggle ──

    private static int cooldownToggle(CommandContext<CommandSourceStack> ctx) {
        String layer = StringArgumentType.getString(ctx, "layer").toLowerCase();
        String toggle = StringArgumentType.getString(ctx, "toggle").toLowerCase();

        boolean enable;
        if ("on".equals(toggle)) {
            enable = true;
        } else if ("off".equals(toggle)) {
            enable = false;
        } else {
            ctx.getSource().sendFailure(Component.literal(
                    "Expected 'on' or 'off', got '" + toggle + "'"));
            return 0;
        }

        switch (layer) {
            case "visited" -> {
                TouristCooldownDebug.skipVisitedBuildings = !enable;
            }
            case "all" -> {
                if (enable) {
                    TouristCooldownDebug.enableAll();
                } else {
                    TouristCooldownDebug.disableAll();
                }
            }
            default -> {
                ctx.getSource().sendFailure(Component.literal(
                        "Unknown layer: '" + layer + "'. Valid: visited, all"));
                return 0;
            }
        }

        String state = enable ? "ENABLED (normal)" : "DISABLED (debug skip)";
        ctx.getSource().sendSuccess(() -> Component.literal(
                "[Tourist] Cooldown '" + layer + "' -> " + state), true);

        // Also log to server console for traceability
        com.wsteam.wandscape.foundation.log.Log.info("TouristCommand",
                "[Debug] Cooldown '{}' set to {}", layer, enable ? "on" : "off");

        return Command.SINGLE_SUCCESS;
    }

    // ── Suggestions ──

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestStates(
            CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        builder.suggest("visiting");
        builder.suggest("exploring");
        builder.suggest("wandering");
        builder.suggest("idle");
        builder.suggest("sleeping");
        return builder.buildFuture();
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestLayers(
            CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        builder.suggest("visited");
        builder.suggest("all");
        return builder.buildFuture();
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestToggle(
            CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        builder.suggest("on");
        builder.suggest("off");
        return builder.buildFuture();
    }

    // ── Helpers ──

    public static int spawnRecruitableMage(CommandContext<CommandSourceStack> ctx, int level) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel serverLevel = src.getLevel();
        Vec3 pos = src.getPosition();

        // 1. Resolve colonyId
        UUID colonyId = null;
        var colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi != null) {
            colonyId = colonyApi.getColonyId(BlockPos.containing(pos));
            if (colonyId == null) {
                var ids = colonyApi.getAllColonyIds();
                if (!ids.isEmpty()) {
                    colonyId = ids.iterator().next();
                }
            }
        }

        // 2. Roll candidate stats
        int safeLevel = Math.clamp(level, 1, 10);
        RecruitmentCandidate candidate = NpcAttributes.roll(safeLevel,
                new java.util.Random(serverLevel.random.nextLong()));

        // 3. Create TouristEntity
        TouristEntity tourist = new TouristEntity(Wandscape.TOURIST.get(), serverLevel);
        tourist.setPos(pos.x, pos.y, pos.z);
        String name = TouristSpawnSystem.generateRandomTouristName(colonyId);
        tourist.setTouristName(name);
        tourist.setLevel(safeLevel);
        tourist.setAppearance(TouristEntity.Appearance.MAGE);
        tourist.setSkinVariant(serverLevel.random.nextInt(TouristEntity.WIZARD_SKIN_COUNT));
        tourist.setColonyId(colonyId);
        tourist.setArrivalTime(serverLevel.getGameTime());
        tourist.setDepartureDeadline(serverLevel.getGameTime() + 72000L);
        tourist.setWallet(500);
        tourist.setInitialWallet(500);
        tourist.setTravelFund(1500);

        // 4. 三值全满 (Comfort, Magic, Wonder satisfaction bars 100% full)
        tourist.setComfortNeed(100);
        tourist.setComfortSat(100);
        tourist.setMagicNeed(100);
        tourist.setMagicSat(100);
        tourist.setWonderNeed(100);
        tourist.setWonderSat(100);
        tourist.setEnergy(100);

        // 5. Set Mage attributes
        tourist.setMageAttributes(candidate.maxHp(), candidate.moveSpeed(), candidate.spellPower(),
                candidate.workSpeed(), candidate.spellSpeed(), candidate.armorValue(), candidate.maxMana());
        tourist.setMageResumeStored(true);

        tourist.applyState(TouristState.VISITING);
        serverLevel.addFreshEntity(tourist);

        // 6. Adopt by SimSystem
        TouristSimSystem sim = TouristSimSystem.getActive();
        if (sim != null) {
            sim.adoptTourist(tourist);
        }

        // 7. Store Resume directly to Tavern for immediate recruitment testing
        if (colonyId != null) {
            try {
                var tavernApi = WandscapeApis.getTavernApi();
                tavernApi.receiveMageResume(colonyId, tourist.getTouristName(), tourist.getLevel(),
                        tourist.getMaxHp(), tourist.getMoveSpeed(), tourist.getSpellPower(),
                        tourist.getWorkSpeed(), tourist.getSpellSpeed(), tourist.getArmor(),
                        tourist.getMaxMana(), tourist.getSkinVariant());
            } catch (Exception e) {
                Log.warn("TouristCommand", "TavernApi not available when storing resume: {}", e.getMessage());
            }
        }

        Component colonyInfo = colonyId != null
                ? I18n.name("message.wandscape.command.tourist_spawn_colony", "小镇 %s", colonyId.toString().substring(0, 8))
                : I18n.name("message.wandscape.command.tourist_spawn_no_colony", "无绑定小镇");
        Component tavernInfo = colonyId != null
                ? I18n.name("message.wandscape.command.tourist_spawn_resume_linked", "已录入酒馆「法师简历」列表，可前往酒馆查看与招聘")
                : I18n.name("message.wandscape.command.tourist_spawn_resume_none", "未录入（未找到小镇）");
        Component resultMsg = I18n.name("message.wandscape.command.tourist_spawn_result",
                "[Tourist] 已生成三值全满法师：%s (Lv.%s, %s)\n"
                + "  满意度三值: 舒适 100%% | 魔法 100%% | 奇观 100%%\n"
                + "  法师属性: 生命 %s, 魔力 %s, 强度 %s, 工速 %s, 施速 %s, 护甲 %s\n"
                + "  酒馆简历: %s",
                tourist.getTouristName(), safeLevel, colonyInfo,
                String.format(Locale.ROOT, "%.0f", candidate.maxHp()),
                String.format(Locale.ROOT, "%.0f", candidate.maxMana()),
                String.format(Locale.ROOT, "%.2f", candidate.spellPower()),
                String.format(Locale.ROOT, "%.2f", candidate.workSpeed()),
                String.format(Locale.ROOT, "%.2f", candidate.spellSpeed()),
                String.format(Locale.ROOT, "%.1f", candidate.armorValue()),
                tavernInfo);
        src.sendSuccess(() -> resultMsg, false);

        return Command.SINGLE_SUCCESS;
    }

    private static List<TouristEntity> findTourists(ServerLevel level) {
        List<TouristEntity> result = new ArrayList<>();
        for (var entity : level.getAllEntities()) {
            if (entity instanceof TouristEntity t && t.isAlive()) {
                result.add(t);
            }
        }
        return result;
    }
}
