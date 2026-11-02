package com.runealytics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import java.util.Locale;

/**
 * Server-controlled verbosity for RuneAlytics' own log output.
 *
 * <p>Plugin Hub only allows {@code log.debug}, so every RuneAlytics message is a
 * DEBUG event. This gate decides which of them reach RuneLite's log:</p>
 * <ul>
 *   <li><b>Debug logging ON</b> (website heartbeat reply {@code "debug_logging": true}):
 *       every RuneAlytics message is logged.</li>
 *   <li><b>Debug logging OFF</b> (the default, and whenever the server does not send
 *       the field): only critical messages — failures, errors, exceptions, rejected
 *       requests — are logged.</li>
 * </ul>
 *
 * <p>It works like RuneLite's own {@code DeduplicationFilter}: a logback
 * {@link TurboFilter} that only ever looks at loggers under {@code com.runealytics}
 * and never affects other plugins or the client. While installed, the
 * {@code com.runealytics} logger is set to DEBUG so critical messages are written
 * even when RuneLite is not started with {@code --debug}; the previous level is
 * restored on {@link #uninstall()}.</p>
 */
public final class RuneAlyticsLogGate extends TurboFilter
{
    static final String PACKAGE = "com.runealytics";

    /**
     * Lower-case fragments that make a message critical. Matched against the
     * message format string, so existing log lines need no changes.
     */
    static final String[] CRITICAL_KEYWORDS = {
        "fail", "error", "exception", "rejected", "unable", "could not", "couldn't",
        "cannot", "timed out", "timeout", "denied", "invalid", "mismatch", "unauthorized",
    };

    private static volatile boolean verbose;
    private static RuneAlyticsLogGate installed;
    private static Level previousLevel;

    RuneAlyticsLogGate()
    {
    }

    /** True when the website asked for full debug logging. */
    public static boolean isVerbose()
    {
        return verbose;
    }

    /** Applies the website's {@code debug_logging} value. Logs only when it changes. */
    public static void setVerbose(boolean enabled)
    {
        if (verbose == enabled) return;
        // Logged before switching off / after switching on, so the line is always visible.
        if (enabled)
        {
            verbose = true;
            LoggerFactory.getLogger(RuneAlyticsLogGate.class).debug("[Logging] server enabled debug logging");
        }
        else
        {
            LoggerFactory.getLogger(RuneAlyticsLogGate.class).debug("[Logging] server disabled debug logging");
            verbose = false;
        }
    }

    /**
     * Reads the top-level {@code debug_logging} field of a heartbeat reply.
     * Returns {@code true} only for a JSON boolean {@code true}; a missing field,
     * any other type, or an unparseable body all mean {@code false} and never throw.
     */
    public static boolean parseDebugLogging(Gson gson, String responseBody)
    {
        try
        {
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            if (json == null || !json.has("debug_logging")) return false;
            JsonElement value = json.get("debug_logging");
            return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
        }
        catch (RuntimeException e)
        {
            return false;
        }
    }

    /** Installs the filter (idempotent). Safe if RuneLite is not using logback. */
    public static synchronized void install()
    {
        if (installed != null) return;
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext)) return;
        LoggerContext context = (LoggerContext) factory;

        RuneAlyticsLogGate gate = new RuneAlyticsLogGate();
        gate.setName("RuneAlyticsLogGate");
        gate.setContext(context);
        gate.start();
        context.addTurboFilter(gate);

        Logger pluginLogger = context.getLogger(PACKAGE);
        previousLevel = pluginLogger.getLevel();
        pluginLogger.setLevel(Level.DEBUG);
        installed = gate;
    }

    /** Removes the filter and restores the logger level. Called on plugin shutdown. */
    public static synchronized void uninstall()
    {
        if (installed == null) return;
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext)
        {
            LoggerContext context = (LoggerContext) factory;
            context.getTurboFilterList().remove(installed);
            context.getLogger(PACKAGE).setLevel(previousLevel);
        }
        installed.stop();
        installed = null;
        previousLevel = null;
        verbose = false;
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t)
    {
        if (logger == null || !logger.getName().startsWith(PACKAGE)) return FilterReply.NEUTRAL;
        if (verbose) return FilterReply.NEUTRAL;
        if (level != null && level.isGreaterOrEqual(Level.WARN)) return FilterReply.NEUTRAL;
        return isCritical(format, params, t) ? FilterReply.NEUTRAL : FilterReply.DENY;
    }

    /** A message is critical when it carries an exception or its text describes a failure. */
    static boolean isCritical(String format, Object[] params, Throwable t)
    {
        if (t != null) return true;
        if (params != null)
        {
            for (Object p : params)
            {
                if (p instanceof Throwable) return true;
            }
        }
        if (format == null) return false;
        String text = format.toLowerCase(Locale.ROOT);
        for (String keyword : CRITICAL_KEYWORDS)
        {
            if (text.contains(keyword)) return true;
        }
        return false;
    }
}
