package com.wsteam.wandscape.content.building.scanner.client;

import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Client-side file storage for scanner presets.
 * Each preset is an individual .nbt file in {@code <gameDir>/wandscape/scanner_presets/}.
 * All methods are safe to call from the client thread only.
 */
public class ScannerPresetStore {

    private static final String TAG = "PresetStore";
    private static final String EXT = ".nbt";

    private static Path getDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("wandscape/scanner_presets");
    }

    /** List all available preset names, sorted alphabetically. */
    public static List<String> listPresets() {
        Path dir = getDir();
        if (!Files.isDirectory(dir)) return List.of();
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(EXT))
                    .map(p -> p.getFileName().toString())
                    .map(n -> n.substring(0, n.length() - EXT.length()))
                    .sorted(Comparator.naturalOrder())
                    .forEach(names::add);
        } catch (IOException e) {
            Log.warn(TAG, "Failed to list presets", e);
        }
        return names;
    }

    /** Load a preset by name. Returns null if not found. */
    public static CompoundTag loadPreset(String name) {
        Path file = getDir().resolve(sanitize(name) + EXT);
        if (!Files.isRegularFile(file)) return null;
        try {
            return NbtIo.read(file);
        } catch (IOException e) {
            Log.warn(TAG, "Failed to load preset '{}'", name);
            return null;
        }
    }

    /** Save a preset. Overwrites if it already exists. */
    public static void savePreset(String name, CompoundTag data) {
        try {
            Files.createDirectories(getDir());
            NbtIo.write(data, getDir().resolve(sanitize(name) + EXT));
        } catch (IOException e) {
            Log.warn(TAG, "Failed to save preset '{}'", name);
        }
    }

    /** Delete a preset by name. No-op if not found. */
    public static void deletePreset(String name) {
        try {
            Files.deleteIfExists(getDir().resolve(sanitize(name) + EXT));
        } catch (IOException e) {
            Log.warn(TAG, "Failed to delete preset '{}'", name);
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
