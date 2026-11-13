package com.runealytics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.spi.FilterReply;
import com.google.gson.Gson;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RuneAlyticsLogGateTest
{
    private final Gson gson = new Gson();
    private final LoggerContext context = new LoggerContext();
    private final Logger ours = context.getLogger("com.runealytics.progress.ProgressTracker");
    private final Logger theirs = context.getLogger("net.runelite.client.plugins.SomePlugin");
    private final RuneAlyticsLogGate gate = new RuneAlyticsLogGate();

    @After
    public void tearDown()
    {
        RuneAlyticsLogGate.setVerbose(false);
    }

    private FilterReply decide(Logger logger, String format, Object... params)
    {
        return gate.decide(null, logger, Level.DEBUG, format, params, null);
    }

    @Test
    public void offByDefaultOnlyCriticalMessagesPass()
    {
        assertFalse(RuneAlyticsLogGate.isVerbose());
        assertEquals(FilterReply.DENY, decide(ours, "[Heartbeat] OK HTTP {}", 200));
        assertEquals(FilterReply.DENY, decide(ours, "[Progress] ready — quests {}", 91));
        assertEquals(FilterReply.NEUTRAL, decide(ours, "[Heartbeat] FAILED HTTP {} — {}", 500, "x"));
        assertEquals(FilterReply.NEUTRAL, decide(ours, "[Progress] upload failed (network, seq={}): {}", 1, "x"));
        assertEquals(FilterReply.NEUTRAL, decide(ours, "Could not read response body: {}", "x"));
        assertEquals(FilterReply.NEUTRAL, decide(ours, "something happened", new IOException("boom")));
        assertEquals(FilterReply.NEUTRAL,
                gate.decide(null, ours, Level.DEBUG, "plain message", null, new IllegalStateException()));
    }

    @Test
    public void verboseLetsEverythingThrough()
    {
        RuneAlyticsLogGate.setVerbose(true);
        assertEquals(FilterReply.NEUTRAL, decide(ours, "[Heartbeat] OK HTTP {}", 200));
        RuneAlyticsLogGate.setVerbose(false);
        assertEquals(FilterReply.DENY, decide(ours, "[Heartbeat] OK HTTP {}", 200));
    }

    @Test
    public void otherLoggersAreNeverTouched()
    {
        assertEquals(FilterReply.NEUTRAL, decide(theirs, "anything at all"));
        assertEquals(FilterReply.NEUTRAL, gate.decide(null, null, Level.DEBUG, "x", null, null));
    }

    @Test
    public void parsesHeartbeatDebugFlagDefensively()
    {
        assertTrue(RuneAlyticsLogGate.parseDebugLogging(gson, "{\"status\":\"ok\",\"debug_logging\":true}"));
        assertFalse(RuneAlyticsLogGate.parseDebugLogging(gson, "{\"status\":\"ok\",\"debug_logging\":false}"));
        assertFalse("missing field = off", RuneAlyticsLogGate.parseDebugLogging(gson, "{\"status\":\"ok\",\"players\":[]}"));
        assertFalse("strings are not booleans", RuneAlyticsLogGate.parseDebugLogging(gson, "{\"debug_logging\":\"true\"}"));
        assertFalse(RuneAlyticsLogGate.parseDebugLogging(gson, "{\"debug_logging\":1}"));
        assertFalse(RuneAlyticsLogGate.parseDebugLogging(gson, "{\"debug_logging\":null}"));
        assertFalse(RuneAlyticsLogGate.parseDebugLogging(gson, "{\"debug_logging\":{\"on\":true}}"));
        assertFalse("garbage never throws", RuneAlyticsLogGate.parseDebugLogging(gson, "<html>502</html>"));
        assertFalse(RuneAlyticsLogGate.parseDebugLogging(gson, ""));
        assertFalse(RuneAlyticsLogGate.parseDebugLogging(gson, null));
    }
}
