package com.wsteam.wandscape.element.internal;

import java.util.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wsteam.wandscape.shared.data.ElementType;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * Scans all Minecraft items and finds which ones lack elemental values.
 * Works in JUnit test (after Bootstrap.bootStrap()) and in-game contexts.
 */
public class ElementAuditor {

    public record AuditReport(
        int seedsCount,
        int mappedCount,
        int totalItems,
        List<String> missingBlocks,
        List<String> missingItems
    ) {
        public int missingCount() { return missingBlocks.size() + missingItems.size(); }

        public String toFormattedString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Element Coverage Audit ===\n");
            sb.append("  Seeds: ").append(seedsCount).append("\n");
            sb.append("  Mapped: ").append(mappedCount).append("\n");
            sb.append("  Total registered items: ").append(totalItems).append("\n");
            sb.append("  Missing: ").append(missingCount()).append("\n\n");

            if (!missingBlocks.isEmpty()) {
                sb.append("-- Missing (blocks) --\n");
                for (String id : missingBlocks) {
                    sb.append("  ").append(id).append("\n");
                }
                sb.append("\n");
            }

            if (!missingItems.isEmpty()) {
                int limit = Math.min(missingItems.size(), 200);
                sb.append("-- Missing (items, showing ").append(limit)
                  .append("/").append(missingItems.size()).append(") --\n");
                for (int i = 0; i < limit; i++) {
                    sb.append("  ").append(missingItems.get(i)).append("\n");
                }
                if (missingItems.size() > limit) {
                    sb.append("  ... and ").append(missingItems.size() - limit).append(" more\n");
                }
            }

            return sb.toString();
        }
    }

    /** Parse seed item IDs from element_seeds.json content. */
    public static Set<String> parseSeedIds(String seedJson) {
        Set<String> ids = new HashSet<>();
        JsonObject root = JsonParser.parseString(seedJson).getAsJsonObject();
        JsonArray seeds = root.getAsJsonArray("seeds");
        for (JsonElement elem : seeds) {
            ids.add(elem.getAsJsonObject().get("item").getAsString());
        }
        return ids;
    }

    /**
     * Run audit against all items in BuiltInRegistries.ITEM.
     * Caller must ensure {@code net.minecraft.Bootstrap.bootStrap()} has been
     * called at least once before invoking this.
     */
    public static AuditReport audit(Set<String> seedIds, Set<String> mappedIds) {
        List<String> missingBlocks = new ArrayList<>();
        List<String> missingItems = new ArrayList<>();

        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            String itemId = id.toString();
            if (seedIds.contains(itemId) || mappedIds.contains(itemId)) continue;
            if (itemId.equals("minecraft:air")) continue;

            boolean isBlock = BuiltInRegistries.BLOCK.containsKey(id);
            if (isBlock) {
                missingBlocks.add(itemId);
            } else {
                missingItems.add(itemId);
            }
        }

        return new AuditReport(
            seedIds.size(),
            mappedIds.size(),
            BuiltInRegistries.ITEM.keySet().size(),
            missingBlocks,
            missingItems
        );
    }
}
