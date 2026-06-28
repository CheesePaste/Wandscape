package com.wsteam.wandscape.command;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.wsteam.wandscape.blueprint.editor.BlueprintEditorClientState;
import com.wsteam.wandscape.imgui.ImGuiManager;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Toggle the blueprint node editor ImGui panel.
 *
 * <p>Usage:
 * <pre>
 *   /wandscape blueprinteditor
 * </pre>
 *
 * <p>Opens a Unreal-Engine-style visual node graph for editing blueprint DSL.
 * Step nodes (14 types) + expression nodes (22 types) + input parameter nodes
 * share one unified canvas. Exec edges (white) control execution flow;
 * data edges (type-colored) connect expression trees to step inputs.
 */
public final class BlueprintEditorCommand {

    private static final String TAG = "BlueprintEditorCommand";

    private BlueprintEditorCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("blueprinteditor")
                .requires(src -> src.hasPermission(2))
                .executes(BlueprintEditorCommand::toggle)
                .build();
    }

    private static int toggle(CommandContext<CommandSourceStack> ctx) {
        boolean nowActive = ImGuiManager.toggleBlueprintEditor();
        ctx.getSource().sendSuccess(() -> Component.literal(
                nowActive ? "§aBlueprint editor opened (F12 to show ImGui)"
                          : "§eBlueprint editor closed"),
                true);
        Log.info(TAG, "Blueprint editor toggled: {}", nowActive);
        return 1;
    }
}
