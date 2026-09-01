package com.wsteam.wandscape.foundation.log;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuration and level filtering for Wandscape logging.
 * Supports per-category level assignment, file persistence, and dynamic runtime updating.
 */
public final class LogConfig {

    private static final String CONFIG_FILENAME = "wandscape-logging.properties";
    private static volatile LogLevel rootLevel = LogLevel.INFO;
    private static final Map<LogCategory, LogLevel> CATEGORY_LEVELS = new ConcurrentHashMap<>();

    static {
        resetToDefaults();
    }

    private LogConfig() {}

    public static void resetToDefaults() {
        rootLevel = LogLevel.INFO;
        for (LogCategory cat : LogCategory.values()) {
            CATEGORY_LEVELS.put(cat, LogLevel.INFO);
        }
    }

    public static LogLevel getRootLevel() {
        return rootLevel;
    }

    public static void setRootLevel(LogLevel level) {
        if (level == null) return;
        rootLevel = level;
    }

    public static LogLevel getLevel(LogCategory cat) {
        if (cat == null) return rootLevel;
        return CATEGORY_LEVELS.getOrDefault(cat, rootLevel);
    }

    public static void setLevel(LogCategory cat, LogLevel level) {
        if (cat == null || level == null) return;
        CATEGORY_LEVELS.put(cat, level);
    }

    public static void setAllLevels(LogLevel level) {
        if (level == null) return;
        rootLevel = level;
        for (LogCategory cat : LogCategory.values()) {
            CATEGORY_LEVELS.put(cat, level);
        }
    }

    public static Map<LogCategory, LogLevel> getAllLevels() {
        return new EnumMap<>(CATEGORY_LEVELS);
    }

    /**
     * Checks if a log with the given category and level is currently permitted.
     */
    public static boolean isEnabled(LogCategory cat, LogLevel level) {
        if (cat == null || level == null) return false;
        LogLevel configured = getLevel(cat);
        return configured.includes(level);
    }

    // ---- Persistence ----

    public static Path getConfigFilePath() {
        try {
            return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILENAME);
        } catch (Throwable ignored) {
            return Path.of("config", CONFIG_FILENAME);
        }
    }

    public static synchronized void load() {
        Path path = getConfigFilePath();
        if (!Files.exists(path)) {
            save(); // Create initial config file with defaults
            return;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
            String rootVal = props.getProperty("root_level");
            if (rootVal != null) {
                rootLevel = LogLevel.fromString(rootVal, LogLevel.INFO);
            }
            for (LogCategory cat : LogCategory.values()) {
                String val = props.getProperty(cat.getId());
                if (val != null) {
                    CATEGORY_LEVELS.put(cat, LogLevel.fromString(val, rootLevel));
                } else {
                    CATEGORY_LEVELS.put(cat, rootLevel);
                }
            }
        } catch (Exception e) {
            // Keep current in-memory levels if file cannot be read
        }
    }

    public static synchronized void save() {
        Path path = getConfigFilePath();
        try {
            if (path.getParent() != null && !Files.exists(path.getParent())) {
                Files.createDirectories(path.getParent());
            }
            Properties props = new Properties();
            props.setProperty("root_level", rootLevel.name());
            for (LogCategory cat : LogCategory.values()) {
                props.setProperty(cat.getId(), getLevel(cat).name());
            }

            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "Wandscape Logging Configuration\n"
                        + "Valid levels: DEBUG, INFO, WARN, ERROR, OFF\n"
                        + "Default: INFO (set to DEBUG to inspect internal workflows)");
            }
        } catch (Exception ignored) {
        }
    }
}
