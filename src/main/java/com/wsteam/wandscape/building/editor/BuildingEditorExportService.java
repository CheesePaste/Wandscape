package com.wsteam.wandscape.building.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig;
import com.wsteam.wandscape.shared.data.WonderEffect;
import com.wsteam.wandscape.shared.log.Log;

/**
 * Server-side service for validating and exporting building editor JSON.
 * Writes to {@code data/wandscape/buildings/{id}.json} relative to the game directory.
 */
public final class BuildingEditorExportService {

    private static final String TAG = "BuildingEditorExportService";

    private static final Gson PRETTY_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .registerTypeAdapter(BlockOffset.class, new BlockOffset.Deserializer())
            .registerTypeAdapter(BuildingConfig.class, new BuildingConfig.Deserializer())
            .registerTypeAdapter(WonderEffect.class, new WonderEffect.Deserializer())
            .create();

    private BuildingEditorExportService() {}

    /**
     * Validate and export a building JSON string to file.
     *
     * @param buildingJson the JSON string from the client
     * @param overwrite    whether to overwrite an existing file
     * @return result with success/failure and messages
     */
    public static ExportResult export(String buildingJson, boolean overwrite) {
        List<String> warnings = new ArrayList<>();

        // 1. Parse JSON
        BuildingConfig config;
        try {
            config = PRETTY_GSON.fromJson(buildingJson, BuildingConfig.class);
        } catch (Exception e) {
            Log.error(TAG, "[BuildEditor] Failed to parse building JSON", e);
            return ExportResult.failure("JSON parse error: " + e.getMessage());
        }

        // 2. Validate required fields
        List<String> errors = validate(config);
        if (!errors.isEmpty()) {
            String msg = "Validation failed:\n" + String.join("\n", errors);
            return ExportResult.failureWithWarnings(msg, warnings);
        }

        // 3. Validate block_mapping covers all pattern offsets
        List<String> blockWarnings = validateBlockMapping(config);
        warnings.addAll(blockWarnings);

        // 4. Check category-specific requirements
        List<String> categoryWarnings = validateCategoryConfig(config);
        warnings.addAll(categoryWarnings);

        // 5. Build output path
        Path outputDir = Paths.get("data", "wandscape", "buildings");
        Path outputFile = outputDir.resolve(config.id() + ".json");

        if (Files.exists(outputFile) && !overwrite) {
            return ExportResult.failureWithWarnings(
                    "File already exists: " + outputFile + "\nUse overwrite to replace.",
                    warnings);
        }

        // 6. Write (pretty-printed)
        try {
            Files.createDirectories(outputDir);
            // Re-serialize with pretty print
            JsonElement element = JsonParser.parseString(buildingJson);
            String prettyJson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(element);
            Files.writeString(outputFile, prettyJson);
            Log.info(TAG, "[BuildEditor] Exported building '{}' to {}", config.id(), outputFile.toAbsolutePath());
        } catch (IOException e) {
            Log.error(TAG, "[BuildEditor] Failed to write building JSON", e);
            return ExportResult.failure("Failed to write file: " + e.getMessage());
        }

        String msg = "Exported to data/wandscape/buildings/" + config.id() + ".json";
        return new ExportResult(true, msg, warnings);
    }

    // ── Validation ──

    private static List<String> validate(BuildingConfig config) {
        List<String> errors = new ArrayList<>();

        if (config.id() == null || config.id().isBlank()) {
            errors.add("- Missing 'id' (building identifier)");
        } else if (!config.id().matches("^[a-z][a-z0-9_]*$")) {
            errors.add("- Invalid 'id': must be snake_case (lowercase letters, digits, underscores, starting with a letter)");
        }

        if (config.displayName() == null || config.displayName().isBlank()) {
            errors.add("- Missing 'display_name'");
        }

        String category = config.category();
        if (category == null || category.isBlank()) {
            errors.add("- Missing 'category'");
        } else {
            List<String> validCategories = List.of("basic", "node", "storage", "workstation",
                    "crafting_station", "potion_station", "tavern", "shop", "service", "decoration", "wonder");
            if (!validCategories.contains(category)) {
                errors.add("- Invalid 'category': '" + category +
                        "'. Must be one of: " + String.join(", ", validCategories));
            }
        }

        if (config.pattern() == null || config.pattern().isEmpty()) {
            errors.add("- 'pattern' must not be empty");
        }

        if (config.blockMapping() == null || config.blockMapping().isEmpty()) {
            errors.add("- 'block_mapping' must not be empty");
        }

        return errors;
    }

    private static List<String> validateBlockMapping(BuildingConfig config) {
        List<String> warnings = new ArrayList<>();
        if (config.pattern() == null || config.blockMapping() == null) return warnings;

        for (BlockOffset off : config.pattern()) {
            String key = off.toKey();
            if (!config.blockMapping().containsKey(key)) {
                warnings.add("Pattern offset " + key + " has no entry in block_mapping");
            }
        }

        // Also warn about block_mapping entries not in pattern
        for (String key : config.blockMapping().keySet()) {
            boolean found = config.pattern().stream().anyMatch(o -> o.toKey().equals(key));
            if (!found) {
                warnings.add("block_mapping key '" + key + "' is not in pattern (extraneous)");
            }
        }

        return warnings;
    }

    private static List<String> validateCategoryConfig(BuildingConfig config) {
        List<String> warnings = new ArrayList<>();
        String cat = config.category();

        switch (cat) {
            case "shop" -> {
                if (config.shop() == null || config.shop().equals(
                        com.wsteam.wandscape.shared.data.ShopConfig.NONE)) {
                    warnings.add("Category is 'shop' but no shop config defined");
                } else if (config.shop().goods().isEmpty()) {
                    warnings.add("Shop has no goods defined");
                }
            }
            case "service" -> {
                if (config.service() == null || config.service().equals(
                        com.wsteam.wandscape.shared.data.ServiceConfig.NONE)) {
                    warnings.add("Category is 'service' but no service config defined");
                }
            }
            case "decoration" -> {
                if (config.decoration() == null) {
                    warnings.add("Category is 'decoration' but no decoration config defined");
                }
            }
            case "wonder" -> {
                if (config.wonderConfig() == null || config.wonderConfig().equals(
                        com.wsteam.wandscape.shared.data.WonderConfig.NONE)) {
                    warnings.add("Category is 'wonder' but no wonder_config defined");
                }
            }
            case "node" -> {
                if (config.nodeConfig() == null) {
                    warnings.add("Category is 'node' but no node_config defined");
                }
            }
        }

        return warnings;
    }

    // ── Result record ──

    public record ExportResult(boolean success, String message, List<String> warnings) {
        public static ExportResult failure(String msg) {
            return new ExportResult(false, msg, List.of());
        }

        public static ExportResult failureWithWarnings(String msg, List<String> warnings) {
            return new ExportResult(false, msg, List.copyOf(warnings));
        }
    }
}
