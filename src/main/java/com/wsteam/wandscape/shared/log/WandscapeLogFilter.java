package com.wsteam.wandscape.shared.log;

import com.wsteam.wandscape.shared.log.LogFilter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

/**
 * Log4j2 filter that applies {@link LogFilter} whitelist to every log event.
 * Registered programmatically on startup.
 *
 * <p>Warn and error always pass. Debug/info only pass when the logger name
 * (simple class name) matches a whitelisted tag.
 */
public final class WandscapeLogFilter extends AbstractFilter {

    WandscapeLogFilter() {}

    public static Result filter(Logger logger, Level level, Marker marker, String msg,
                                Throwable t) {
        if (!LogFilter.isEnabled()) return Result.NEUTRAL;
        if (level.isMoreSpecificThan(Level.WARN)) return Result.NEUTRAL;
        if (LogFilter.allows(logger.getName())) return Result.NEUTRAL;
        return Result.DENY;
    }

    @Override
    public Result filter(LogEvent event) {
        if (!LogFilter.isEnabled()) return Result.NEUTRAL;
        if (event.getLevel().isMoreSpecificThan(Level.WARN)) return Result.NEUTRAL;
        if (LogFilter.allows(event.getLoggerName())) return Result.NEUTRAL;
        return Result.DENY;
    }
}
