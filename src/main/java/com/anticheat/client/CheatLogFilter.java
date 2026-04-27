package com.anticheat.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.AbstractFilter;

/**
 * Globaler Log4j2-Filter (Configuration), damit nicht nur der Root-Logger alle Events sieht.
 */
public final class CheatLogFilter extends AbstractFilter {

    private static volatile boolean installed;
    private static final CheatLogFilter INSTANCE = new CheatLogFilter();

    private CheatLogFilter() {
        super(Filter.Result.NEUTRAL, Filter.Result.NEUTRAL);
    }

    public static synchronized void install() {
        if (installed) {
            return;
        }
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration cfg = ctx.getConfiguration();
        cfg.addFilter(INSTANCE);
        ctx.updateLoggers();
        installed = true;
    }

    @Override
    public Filter.Result filter(LogEvent event) {
        CheatDetector.inspect(event);
        return Filter.Result.NEUTRAL;
    }
}
