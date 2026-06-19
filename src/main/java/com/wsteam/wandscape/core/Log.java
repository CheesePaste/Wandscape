package com.wsteam.wandscape.core;

import java.util.logging.*;

/**
 * Centralized logging utility wrapping java.util.logging.
 * Use static methods for convenient one-liners:
 * <pre>
 *   Log.info("Scheduler", "Assigned task #%d to NPC %d", taskId, npcId);
 *   Log.debug("TaskExec", "Executing %s at %s", op.getClass().getSimpleName(), pos);
 * </pre>
 */
public final class Log {

    private Log() {}

    // Default level: FINE for debug, INFO for normal
    private static final Level DEFAULT_LEVEL = Level.FINE;

    static {
        // Configure root logger for clean console output
        Logger rootLogger = Logger.getLogger("");
        for (Handler h : rootLogger.getHandlers()) {
            rootLogger.removeHandler(h);
        }
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(DEFAULT_LEVEL);
        handler.setFormatter(new BriefFormatter());
        rootLogger.addHandler(handler);
        rootLogger.setLevel(DEFAULT_LEVEL);
    }

    // ---- Convenience methods ----

    public static Logger get(String name) {
        return Logger.getLogger(name);
    }

    public static void debug(String tag, String msg, Object... args) {
        Logger logger = get(tag);
        if (logger.isLoggable(Level.FINE)) {
            logger.fine(format(msg, args));
        }
    }

    public static void info(String tag, String msg, Object... args) {
        Logger logger = get(tag);
        if (logger.isLoggable(Level.INFO)) {
            logger.info(format(msg, args));
        }
    }

    public static void warn(String tag, String msg, Object... args) {
        Logger logger = get(tag);
        logger.warning(format(msg, args));
    }

    public static void error(String tag, String msg, Object... args) {
        Logger logger = get(tag);
        logger.severe(format(msg, args));
    }

    // ----

    private static String format(String msg, Object... args) {
        return args.length == 0 ? msg : String.format(msg, args);
    }

    /** Compact formatter: [LEVEL] tag | message */
    private static class BriefFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            StringBuilder sb = new StringBuilder();
            sb.append('[').append(pad(record.getLevel().getName(), 5)).append("] ");
            sb.append(record.getLoggerName());
            sb.append(" | ").append(record.getMessage());
            sb.append('\n');
            Throwable t = record.getThrown();
            if (t != null) {
                sb.append("  ").append(t).append('\n');
            }
            return sb.toString();
        }

        private static String pad(String s, int w) {
            if (s.length() >= w) return s;
            StringBuilder sb = new StringBuilder(w);
            sb.append(s);
            while (sb.length() < w) sb.append(' ');
            return sb.toString();
        }
    }
}
