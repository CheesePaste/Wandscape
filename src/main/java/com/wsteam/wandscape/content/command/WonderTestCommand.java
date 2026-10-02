package com.wsteam.wandscape.content.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.api.BuildingApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.building.data.BuildingConfig;
import com.wsteam.wandscape.content.building.internal.BuildingConfigLoader;
import com.wsteam.wandscape.content.building.internal.BuildingState;
import com.wsteam.wandscape.content.building.wonder.WonderTriggerContext;
import com.wsteam.wandscape.content.building.wonder.WonderTriggerRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 开发者调试指令：奇观触发器测试与查询。
 *
 * <p>用法：
 * <pre>
 *   /wandscape test wonder list
 *   /wandscape test wonder trigger <buildingType> [firstCompletion]
 * </pre>
 */
public final class WonderTestCommand {

    private WonderTestCommand() {}

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_WONDERS = (ctx, builder) -> {
        Set<String> registered = WonderTriggerRegistry.getRegisteredTypeIds();
        List<String> suggestions = new ArrayList<>(registered);
        for (BuildingConfig config : BuildingConfigLoader.getInstance().getAll().values()) {
            if ("wonder".equalsIgnoreCase(config.category())) {
                String norm = WonderTriggerRegistry.normalizeId(config.id());
                if (!suggestions.contains(norm)) {
                    suggestions.add(norm);
                }
            }
        }
        return SharedSuggestionProvider.suggest(suggestions, builder);
    };

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("wonder")
                .then(Commands.literal("list")
                        .executes(WonderTestCommand::list))
                .then(Commands.literal("trigger")
                        .then(Commands.argument("buildingType", StringArgumentType.word())
                                .suggests(SUGGEST_WONDERS)
                                .executes(ctx -> trigger(ctx, true))
                                .then(Commands.argument("firstCompletion", BoolArgumentType.bool())
                                        .executes(ctx -> trigger(ctx, BoolArgumentType.getBool(ctx, "firstCompletion"))))))
                .build();
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Set<String> types = WonderTriggerRegistry.getRegisteredTypeIds();
        int predicateCount = WonderTriggerRegistry.getPredicateTriggerCount();

        src.sendSuccess(() -> Component.literal(
                String.format("§6[WonderTriggers]§r Registered: %s (total: %d specific, %d predicate)",
                        types.isEmpty() ? "none" : String.join(", ", types),
                        types.size(),
                        predicateCount)), false);
        return 1;
    }

    private static int trigger(CommandContext<CommandSourceStack> ctx, boolean firstCompletion) {
        CommandSourceStack src = ctx.getSource();
        String buildingType = StringArgumentType.getString(ctx, "buildingType");
        String normType = WonderTriggerRegistry.normalizeId(buildingType);

        UUID colonyId = CommandUtil.resolveColony(src);
        if (colonyId == null) {
            src.sendFailure(Component.literal("§c[Wonder] Cannot find colony for current context. Please stand in a colony or create one first."));
            return 0;
        }

        ServerLevel level = src.getLevel();
        BuildingConfig config = BuildingConfigLoader.getInstance().get(buildingType);
        if (config == null) {
            config = BuildingConfigLoader.getInstance().get("default:" + normType);
        }

        // Try to find if this building exists in the colony
        BuildingApi buildingApi = WandscapeApis.getBuildingApiSilently();
        BuildingState foundState = null;
        UUID buildingId = null;
        if (buildingApi != null) {
            for (BuildingState b : buildingApi.getColonyBuildings(colonyId)) {
                if (normType.equalsIgnoreCase(WonderTriggerRegistry.normalizeId(b.getBuildingTypeId()))) {
                    foundState = b;
                    buildingId = b.getBuildingId();
                    break;
                }
            }
        }
        if (buildingId == null) {
            buildingId = UUID.randomUUID();
        }

        WonderTriggerContext context = new WonderTriggerContext(
                buildingId,
                colonyId,
                normType,
                config,
                foundState,
                level,
                firstCompletion
        );

        WonderTriggerRegistry.dispatch(context);

        final boolean hasState = foundState != null;
        src.sendSuccess(() -> Component.literal(
                String.format("§a[Wonder] Dispatched wonder trigger for '%s' (colony=%s, firstCompletion=%s, hasState=%s)",
                        normType,
                        CommandUtil.shortId(colonyId),
                        firstCompletion,
                        hasState)), true);
        return 1;
    }
}
