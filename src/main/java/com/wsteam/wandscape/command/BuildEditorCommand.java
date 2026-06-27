package com.wsteam.wandscape.command;

import org.slf4j.Logger;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.logging.LogUtils;
import com.wsteam.wandscape.building.editor.BuildingEditorNetwork;
import com.wsteam.wandscape.building.network.BuildingEditorEnterPacket;
import com.wsteam.wandscape.building.network.BuildingEditorEnterResponsePacket;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Building editor commands.
 *
 * <p>Usage:
 * <pre>
 *   /wandscape build edit           — enter editor (new building)
 *   /wandscape build edit &lt;id&gt;     — enter editor (edit existing)
 *   /wandscape build done           — exit editor
 * </pre>
 */
public final class BuildEditorCommand {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BuildEditorCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("build")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("edit")
                        .executes(BuildEditorCommand::editNew)
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(BuildEditorCommand::editExisting)))
                .then(Commands.literal("done")
                        .executes(BuildEditorCommand::done))
                .build();
    }

    /** Enter editor for a new building. */
    private static int editNew(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§cPlayer-only command"));
            return 0;
        }

        // Simulate the enter packet flow directly on server
        BuildingEditorEnterPacket.handleServer(BuildingEditorEnterPacket.createNew(), player);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aEntered building editor — §fnew building mode\n" +
                        "§7Left-click: set Min §f| §6Right-click: set Max §f| §eLeft-Shift+Left-click: set Anchor\n" +
                        "§7E: toggle GUI §f| §aEnter: export §f| §cESC: exit"),
                true);
        return 1;
    }

    /** Enter editor for an existing building. */
    private static int editExisting(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§cPlayer-only command"));
            return 0;
        }

        String buildingId = StringArgumentType.getString(ctx, "id");
        BuildingEditorEnterPacket.handleServer(BuildingEditorEnterPacket.edit(buildingId), player);

        ctx.getSource().sendSuccess(() -> Component.literal(
                "§aEntered building editor — §eediting: §f" + buildingId),
                true);
        return 1;
    }

    /** Exit editor. */
    private static int done(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§cPlayer-only command"));
            return 0;
        }

        if (!BuildingEditorNetwork.isEditing(player)) {
            ctx.getSource().sendFailure(Component.literal("§cNot in editor mode"));
            return 0;
        }

        BuildingEditorNetwork.removeEditing(player);
        ctx.getSource().sendSuccess(() -> Component.literal("§eExited building editor"), true);
        LOGGER.info("[BuildEditor] Player {} exited via /wandscape build done",
                player.getGameProfile().getName());
        return 1;
    }
}
