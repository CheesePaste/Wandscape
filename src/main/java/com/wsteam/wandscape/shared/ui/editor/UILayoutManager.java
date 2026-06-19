package com.wsteam.wandscape.shared.ui.editor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.wsteam.wandscape.Wandscape;

/**
 * Saves and loads UI layout presets to/from config/wandscape/ui_layouts/.
 */
public final class UILayoutManager {

    private UILayoutManager() {}

    private static final Path LAYOUT_DIR = Paths.get("config", Wandscape.MODID, "ui_layouts");

    private static Path layoutPath(String name) {
        return LAYOUT_DIR.resolve(name + ".json");
    }

    public static void save(WidgetLayout.ScreenLayout layout) {
        try {
            Files.createDirectories(LAYOUT_DIR);
            Files.writeString(layoutPath(layout.name()), layout.toJson());
            Wandscape.LOGGER.info("Saved UI layout: {}", layout.name());
        } catch (IOException e) {
            Wandscape.LOGGER.error("Failed to save UI layout: {}", layout.name(), e);
        }
    }

    public static WidgetLayout.ScreenLayout load(String name) {
        Path path = layoutPath(name);
        if (!Files.exists(path)) return null;
        try {
            String json = Files.readString(path);
            return WidgetLayout.ScreenLayout.fromJson(json);
        } catch (IOException e) {
            Wandscape.LOGGER.error("Failed to load UI layout: {}", name, e);
            return null;
        }
    }

    public static List<String> listLayouts() {
        List<String> names = new ArrayList<>();
        try {
            Files.createDirectories(LAYOUT_DIR);
            try (Stream<Path> files = Files.list(LAYOUT_DIR)) {
                files.filter(f -> f.toString().endsWith(".json"))
                     .map(f -> f.getFileName().toString().replace(".json", ""))
                     .sorted(Comparator.naturalOrder())
                     .forEach(names::add);
            }
        } catch (IOException e) {
            Wandscape.LOGGER.warn("Failed to list UI layouts", e);
        }
        return names;
    }
}
