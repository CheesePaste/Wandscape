package com.wsteam.wandscape.foundation.log;

import org.slf4j.Logger;

/**
 * Centralized logging facade for Wandscape.
 * Backed by SLF4J / Log4j2 and integrated with functional domain categories.
 */
public final class Log {

    private Log() {}

    // ---- Core Category Methods ----

    public static void debug(LogCategory cat, String msg, Object... args) {
        if (!LogConfig.isEnabled(cat, LogLevel.DEBUG)) return;
        logInternal(cat, null, LogLevel.DEBUG, msg, args, null);
    }

    public static void debug(LogCategory cat, String subTag, String msg, Object... args) {
        if (!LogConfig.isEnabled(cat, LogLevel.DEBUG)) return;
        if (!LogFilter.allows(subTag)) return;
        logInternal(cat, subTag, LogLevel.DEBUG, msg, args, null);
    }

    public static void info(LogCategory cat, String msg, Object... args) {
        if (!LogConfig.isEnabled(cat, LogLevel.INFO)) return;
        logInternal(cat, null, LogLevel.INFO, msg, args, null);
    }

    public static void info(LogCategory cat, String subTag, String msg, Object... args) {
        if (!LogConfig.isEnabled(cat, LogLevel.INFO)) return;
        if (!LogFilter.allows(subTag)) return;
        logInternal(cat, subTag, LogLevel.INFO, msg, args, null);
    }

    public static void warn(LogCategory cat, String msg, Object... args) {
        if (!LogConfig.isEnabled(cat, LogLevel.WARN)) return;
        logInternal(cat, null, LogLevel.WARN, msg, args, null);
    }

    public static void warn(LogCategory cat, String subTag, String msg, Object... args) {
        if (!LogConfig.isEnabled(cat, LogLevel.WARN)) return;
        logInternal(cat, subTag, LogLevel.WARN, msg, args, null);
    }

    public static void warn(LogCategory cat, String msg, Throwable t) {
        if (!LogConfig.isEnabled(cat, LogLevel.WARN)) return;
        logInternal(cat, null, LogLevel.WARN, msg, new Object[0], t);
    }

    public static void error(LogCategory cat, String msg, Object... args) {
        if (!LogConfig.isEnabled(cat, LogLevel.ERROR)) return;
        logInternal(cat, null, LogLevel.ERROR, msg, args, null);
    }

    public static void error(LogCategory cat, String subTag, String msg, Object... args) {
        if (!LogConfig.isEnabled(cat, LogLevel.ERROR)) return;
        logInternal(cat, subTag, LogLevel.ERROR, msg, args, null);
    }

    public static void error(LogCategory cat, String msg, Throwable t) {
        if (!LogConfig.isEnabled(cat, LogLevel.ERROR)) return;
        logInternal(cat, null, LogLevel.ERROR, msg, new Object[0], t);
    }

    // ---- Throttled & Once-Per-Session Logging ----

    public static void warnThrottled(LogCategory cat, String throttleKey, long intervalMs, String msg, Object... args) {
        if (!LogConfig.isEnabled(cat, LogLevel.WARN)) return;
        if (!LogRateLimiter.shouldLog(throttleKey, intervalMs)) return;
        logInternal(cat, null, LogLevel.WARN, msg, args, null);
    }

    public static void infoThrottled(LogCategory cat, String throttleKey, long intervalMs, String msg, Object... args) {
        if (!LogConfig.isEnabled(cat, LogLevel.INFO)) return;
        if (!LogRateLimiter.shouldLog(throttleKey, intervalMs)) return;
        logInternal(cat, null, LogLevel.INFO, msg, args, null);
    }

    public static void warnOnce(LogCategory cat, String key, String msg, Object... args) {
        if (!LogConfig.isEnabled(cat, LogLevel.WARN)) return;
        if (!LogRateLimiter.shouldLogOnce(key)) return;
        logInternal(cat, null, LogLevel.WARN, msg, args, null);
    }

    // ---- Backwards Compatibility APIs ----

    public static boolean isVerbose() {
        return LogConfig.getRootLevel() == LogLevel.DEBUG;
    }

    public static void setVerbose(boolean v) {
        if (v) {
            LogConfig.setAllLevels(LogLevel.DEBUG);
        } else {
            LogConfig.setAllLevels(LogLevel.INFO);
        }
    }

    public static void debug(String tag, String msg, Object... args) {
        LogCategory cat = matchCategory(tag);
        debug(cat, tag, msg, args);
    }

    public static void info(String tag, String msg, Object... args) {
        LogCategory cat = matchCategory(tag);
        info(cat, tag, msg, args);
    }

    public static void warn(String tag, String msg, Object... args) {
        LogCategory cat = matchCategory(tag);
        warn(cat, tag, msg, args);
    }

    public static void warn(String tag, String msg, Throwable t) {
        LogCategory cat = matchCategory(tag);
        if (!LogConfig.isEnabled(cat, LogLevel.WARN)) return;
        logInternal(cat, tag, LogLevel.WARN, msg, new Object[0], t);
    }

    public static void error(String tag, String msg, Object... args) {
        LogCategory cat = matchCategory(tag);
        error(cat, tag, msg, args);
    }

    public static void error(String tag, String msg, Throwable t) {
        LogCategory cat = matchCategory(tag);
        if (!LogConfig.isEnabled(cat, LogLevel.ERROR)) return;
        logInternal(cat, tag, LogLevel.ERROR, msg, new Object[0], t);
    }

    // ---- Internal Dispatcher ----

    private static void logInternal(LogCategory cat, String subTag, LogLevel level, String msg, Object[] args, Throwable t) {
        Logger logger = cat.getLogger();
        String formatted = format(msg, args);
        if (subTag != null && !subTag.isEmpty()) {
            formatted = "[" + subTag + "] " + formatted;
        }

        switch (level) {
            case DEBUG -> {
                if (logger.isDebugEnabled()) {
                    if (t != null) logger.debug(formatted, t);
                    else logger.debug(formatted);
                } else {
                    // Promote to INFO with prefix when developer explicitly asked for category DEBUG
                    if (t != null) logger.info("[DEBUG] " + formatted, t);
                    else logger.info("[DEBUG] " + formatted);
                }
            }
            case INFO -> {
                if (t != null) logger.info(formatted, t);
                else logger.info(formatted);
            }
            case WARN -> {
                if (t != null) logger.warn(formatted, t);
                else logger.warn(formatted);
            }
            case ERROR -> {
                if (t != null) logger.error(formatted, t);
                else logger.error(formatted);
            }
            case OFF -> {}
        }
    }

    public static LogCategory matchCategory(String tag) {
        if (tag == null || tag.isBlank()) return LogCategory.GENERAL;
        String lower = tag.toLowerCase();
        if (lower.contains("colony") || lower.contains("raid") || lower.contains("metrics") || lower.contains("stats")) return LogCategory.COLONY;
        if (lower.contains("building") || lower.contains("blueprint") || lower.contains("scanner") || lower.contains("preview") || lower.contains("ghost")) return LogCategory.BUILDING;
        if (lower.contains("npc") || lower.contains("mage") || lower.contains("guard") || lower.contains("nav")) return LogCategory.NPC;
        if (lower.contains("task") || lower.contains("scheduler") || lower.contains("pool") || lower.contains("exec") || lower.contains("op")) return LogCategory.TASK;
        if (lower.contains("warehouse") || lower.contains("transport") || lower.contains("itembank")) return LogCategory.WAREHOUSE;
        if (lower.contains("road") || lower.contains("spline")) return LogCategory.ROAD;
        if (lower.contains("magic") || lower.contains("spell") || lower.contains("altar")) return LogCategory.MAGIC;
        if (lower.contains("tourist") || lower.contains("tavern") || lower.contains("hotel")) return LogCategory.TOURIST;
        if (lower.contains("element")) return LogCategory.ELEMENT;
        if (lower.contains("production") || lower.contains("craft") || lower.contains("synthesize")) return LogCategory.PRODUCTION;
        if (lower.contains("wand") || lower.contains("scepter") || lower.contains("ring") || lower.contains("compass") || lower.contains("guide")) return LogCategory.ITEMS;
        if (lower.contains("screen") || lower.contains("overlay") || lower.contains("panel") || lower.contains("ui")) return LogCategory.UI;
        if (lower.contains("packet") || lower.contains("network") || lower.contains("sync")) return LogCategory.NETWORK;
        if (lower.contains("curios") || lower.contains("iron") || lower.contains("jei")) return LogCategory.COMPAT;
        if (lower.contains("bootstrap") || lower.contains("engine") || lower.contains("setup")) return LogCategory.BOOTSTRAP;
        return LogCategory.GENERAL;
    }

    private static String format(String msg, Object... args) {
        if (msg == null) return "null";
        if (args == null || args.length == 0) return msg;
        if (msg.contains("{}")) return formatBrace(msg, args);
        try {
            return String.format(msg, args);
        } catch (Exception e) {
            return msg;
        }
    }

    private static String formatBrace(String msg, Object... args) {
        StringBuilder sb = new StringBuilder(msg.length() + 32);
        int argIdx = 0;
        int pos = 0;
        int placeholder;
        while ((placeholder = msg.indexOf("{}", pos)) >= 0 && argIdx < args.length) {
            sb.append(msg, pos, placeholder);
            sb.append(args[argIdx++]);
            pos = placeholder + 2;
        }
        sb.append(msg.substring(pos));
        return sb.toString();
    }
}
