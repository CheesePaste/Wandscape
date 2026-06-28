package com.wsteam.wandscape.shared.log;

import com.wsteam.wandscape.shared.log.LogFilter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.Filter;
import org.slf4j.LoggerFactory;

/**
 * Installs the Log4j2 filter so SLF4J log events also go through
 * {@link LogFilter} whitelist.
 */
public final class LogFilterBootstrap {

    private static volatile boolean installed = false;

    LogFilterBootstrap() {}

    public static void install() {
        if (installed) return;
        installed = true;

        try {
            LoggerContext ctx = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
            Configuration config = ctx.getConfiguration();
            WandscapeLogFilter filter = new WandscapeLogFilter();
            config.addFilter(filter);
            ctx.updateLoggers();
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger(LogFilterBootstrap.class)
                    .warn("Failed to install Log4j2 whitelist filter", t);
        }

        org.slf4j.LoggerFactory.getLogger(LogFilterBootstrap.class)
                .info("[LogFilter] SLF4J whitelist filter installed — tag={} filterEnabled={}",
                        "LogFilterBootstrap", LogFilter.isEnabled());
    }
}
