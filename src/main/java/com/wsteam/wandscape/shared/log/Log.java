package com.wsteam.wandscape.shared.log;

import java.util.logging.*;

/**
 * Centralized logging utility wrapping java.util.logging.
 * Use static methods for convenient one-liners:
 * <pre>
 *   Log.info("Scheduler", "Assigned task #%d to NPC %d", taskId, npcId);
 * </pre>
 */
public final class Log {

    private Log() {}

    private static final Logger ROOT = Logger.getLogger("");
    private static final ConsoleHandler HANDLER = new ConsoleHandler();
    private static volatile boolean verbose = false;

    static {
        // Configure root logger for clean console output.
        // Level follows setVerbose(): false (default) = WARN/ERROR only, true = DEBUG+.
        for (Handler h : ROOT.getHandlers()) {
            ROOT.removeHandler(h);
        }
        HANDLER.setFormatter(new BriefFormatter());
        ROOT.addHandler(HANDLER);
        applyLevel(verbose);
    }

    public static boolean isVerbose() { return verbose; }

    /** Toggle verbose logging. false (default): only WARN/ERROR shown; true: DEBUG/INFO/WARN/ERROR. */
    public static void setVerbose(boolean v) {
        verbose = v;
        applyLevel(v);
    }

    private static void applyLevel(boolean v) {
        Level level = v ? Level.FINE : Level.WARNING;
        ROOT.setLevel(level);
        HANDLER.setLevel(level);
    }

    // ---- Convenience methods ----

    public static Logger get(String name) {
        return Logger.getLogger(name);
    }

    public static void info(String tag, String msg, Object... args) {
        if (!verbose) return;
        if (!LogFilter.allows(tag)) return;
        Logger logger = get(tag);
        if (logger.isLoggable(Level.INFO)) {
            logger.info(format(msg, args));
        }
    }

    public static void warn(String tag, String msg, Object... args) {
        Logger logger = get(tag);
        logger.warning(format(msg, args));
    }

    public static void warn(String tag, String msg, Throwable t) {
        Logger logger = get(tag);
        logger.log(Level.WARNING, msg, t);
    }

    public static void error(String tag, String msg, Object... args) {
        Logger logger = get(tag);
        logger.severe(format(msg, args));
    }

    public static void error(String tag, String msg, Throwable t) {
        Logger logger = get(tag);
        logger.log(Level.SEVERE, msg, t);
    }

    // ----

    private static String format(String msg, Object... args) {
        if (args.length == 0) return msg;
        if (msg.contains("{}")) return formatBrace(msg, args);
        return String.format(msg, args);
    }

    /** SLF4J-style {} substitution. */
    private static String formatBrace(String msg, Object... args) {
        StringBuilder sb = new StringBuilder();
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
