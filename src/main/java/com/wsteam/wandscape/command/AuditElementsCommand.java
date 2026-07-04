package com.wsteam.wandscape.command;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import com.wsteam.wandscape.Wandscape;
import com.wsteam.wandscape.element.internal.ElementAuditor;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Scans ALL registered Minecraft items and reports which ones lack
 * elemental values (no seed, no mapping, no recipe-derivable value).
 * Items like leaves, grass, saplings — anything without a crafting recipe —
 * are invisible to the generator but will show up here.
 *
 * Usage: /wandscape audit_elements
 */
public final class AuditElementsCommand {
    private static final String TAG = "AuditElementsCommand";
    private static final String SEEDS_RESOURCE = "data/wandscape/element_seeds.json";

    private AuditElementsCommand() {}

    public static CommandNode<CommandSourceStack> node() {
        return Commands.literal("audit_elements")
                .requires(src -> src.hasPermission(2))
                .executes(AuditElementsCommand::execute)
                .build();
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();

        // 1. Load seeds
        Set<String> seedIds;
        try {
            ClassLoader cl = AuditElementsCommand.class.getClassLoader();
            InputStream is = cl.getResourceAsStream(SEEDS_RESOURCE);
            if (is == null) {
                src.sendFailure(Component.literal("[Wandscape] Seed file not found: " + SEEDS_RESOURCE));
                return 0;
            }
            String seedJson = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            is.close();
            seedIds = ElementAuditor.parseSeedIds(seedJson);
        } catch (IOException e) {
            src.sendFailure(Component.literal("[Wandscape] Failed to read seed file: " + e.getMessage()));
            return 0;
        }

        // 2. Collect all mapped item IDs from loaded element_mappings
        Set<String> mappedIds = new HashSet<>();
        for (var config : Wandscape.ELEMENT_MAPPING_LOADER.getAllConfigs()) {
            if (config.blockId() != null) mappedIds.add(config.blockId());
            if (config.itemId() != null) mappedIds.add(config.itemId());
        }

        // 3. Run audit
        var report = ElementAuditor.audit(seedIds, mappedIds);
        String msg = report.toFormattedString();

        Log.info(TAG, "[Wandscape]\n{}", msg);
        src.sendSuccess(() -> Component.literal(msg.trim()), true);

        return Command.SINGLE_SUCCESS;
    }

    /** Parse seed item IDs from the seed JSON string. */
    public static Set<String> parseSeedIds(String seedJson) {
        Set<String> ids = new HashSet<>();
        JsonObject root = JsonParser.parseString(seedJson).getAsJsonObject();
        JsonArray seeds = root.getAsJsonArray("seeds");
        for (JsonElement elem : seeds) {
            ids.add(elem.getAsJsonObject().get("item").getAsString());
        }
        return ids;
    }
}
