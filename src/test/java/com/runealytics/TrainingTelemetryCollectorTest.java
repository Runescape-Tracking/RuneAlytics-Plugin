package com.runealytics;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TrainingTelemetryCollectorTest
{
    private static final long W = 60_000;
    private static final long T0 = 1_800_000_000_000L;

    private TrainingTelemetryCollector collector()
    {
        return new TrainingTelemetryCollector(W, 90_000);
    }

    @Test
    public void firstObservationIsABaselineNotAGain()
    {
        TrainingTelemetryCollector c = collector();
        c.sessionStart("genifer", "regular", T0);
        c.offer("construction", 1_000_000, T0 + 1_000, false);
        List<TelemetryBucket> out = c.process(T0 + W);

        assertEquals(1, out.size());
        assertNull("no gain → XP-free window", out.get(0).skill);
        assertEquals(W, out.get(0).elapsedMs);
    }

    @Test
    public void gainsAggregateIntoOneBucketPerSkillPerWindow()
    {
        TrainingTelemetryCollector c = collector();
        c.sessionStart("genifer", "regular", T0);
        c.offer("construction", 1_000_000, T0 + 1_000, false);
        c.offer("construction", 1_000_500, T0 + 10_000, false);
        c.offer("construction", 1_001_000, T0 + 20_000, false);
        c.offer("hitpoints", 50_000, T0 + 20_000, false);
        c.offer("hitpoints", 50_100, T0 + 21_000, false);
        List<TelemetryBucket> out = c.process(T0 + W + 1);

        assertEquals(2, out.size());
        TelemetryBucket b = out.get(0);
        assertEquals("construction", b.skill);
        assertEquals(Long.valueOf(1_000_000), b.xpStart);
        assertEquals(Long.valueOf(1_001_000), b.xpEnd);
        assertEquals(T0, b.startedAtMs);
        assertEquals(T0 + W, b.endedAtMs);
        assertEquals(b.sessionId, out.get(1).sessionId);
    }

    @Test
    public void nextWindowContinuesFromPreviousXp()
    {
        TrainingTelemetryCollector c = collector();
        c.sessionStart("genifer", "regular", T0);
        c.offer("mining", 100, T0, false);
        c.offer("mining", 200, T0 + 30_000, false);
        c.offer("mining", 300, T0 + 70_000, false);
        List<TelemetryBucket> out = c.process(T0 + 2 * W);

        assertEquals(2, out.size());
        assertEquals(Long.valueOf(200), out.get(1).xpStart);
        assertEquals(Long.valueOf(300), out.get(1).xpEnd);
    }

    @Test
    public void xpDecreaseReBaselinesAndIsFlagged()
    {
        TrainingTelemetryCollector c = collector();
        c.sessionStart("genifer", "regular", T0);
        c.offer("magic", 5_000, T0, false);
        c.offer("magic", 6_000, T0 + 5_000, false);
        c.offer("magic", 10, T0 + 6_000, false);   // bad read / reset
        c.offer("magic", 110, T0 + 7_000, false);
        List<TelemetryBucket> out = c.process(T0 + W);

        assertEquals("pre-reset and post-reset ranges are separate buckets", 2, out.size());
        assertEquals(Long.valueOf(5_000), out.get(0).xpStart);
        assertEquals(Long.valueOf(6_000), out.get(0).xpEnd);
        assertFalse(out.get(0).flags.contains("tracker_reset"));
        assertEquals(Long.valueOf(10), out.get(1).xpStart);
        assertEquals(Long.valueOf(110), out.get(1).xpEnd);
        assertTrue(out.get(1).flags.contains("tracker_reset"));
    }

    @Test
    public void lampFlagAndWorldHopAreCarried()
    {
        TrainingTelemetryCollector c = collector();
        c.sessionStart("genifer", "regular", T0);
        c.offer("herblore", 1_000, T0, false);
        c.worldHop(T0 + 1_000);
        c.offer("herblore", 21_000, T0 + 2_000, true);
        TelemetryBucket b = c.process(T0 + W).get(0);

        assertTrue(b.flags.contains("reward_lamp"));
        assertTrue(b.flags.contains("world_hop"));
    }

    @Test
    public void logoutClosesAPartialWindowAndEndsTheSession()
    {
        TrainingTelemetryCollector c = collector();
        c.sessionStart("genifer", "regular", T0);
        c.offer("agility", 1_000, T0, false);
        c.offer("agility", 1_500, T0 + 10_000, false);
        c.logout(T0 + 30_000);
        List<TelemetryBucket> out = c.process(T0 + 5 * W);

        assertEquals(1, out.size());
        assertEquals(30_000, out.get(0).elapsedMs);
        assertTrue(out.get(0).flags.contains("logout"));
        assertNull(c.currentSessionId());
        assertTrue("nothing after logout", c.process(T0 + 10 * W).isEmpty());
    }

    @Test
    public void accountSwitchOrWorldModeChangeStartsANewSession()
    {
        TrainingTelemetryCollector c = collector();
        c.sessionStart("genifer", "regular", T0);
        String first = c.process(T0).isEmpty() ? c.currentSessionId() : null;
        c.sessionStart("genifer", "leagues", T0 + 1_000);
        c.process(T0 + 1_000);
        assertNotEquals(first, c.currentSessionId());
        assertEquals("leagues", c.currentWorldMode());

        String second = c.currentSessionId();
        c.sessionStart("alt", "leagues", T0 + 2_000);
        c.process(T0 + 2_000);
        assertNotEquals(second, c.currentSessionId());
    }

    @Test
    public void suspendedClientDoesNotInventObservedTime()
    {
        TrainingTelemetryCollector c = collector();
        c.sessionStart("genifer", "regular", T0);
        List<TelemetryBucket> out = c.process(T0 + 3_600_000); // laptop slept for an hour

        assertEquals("only the window that was open", 1, out.size());
        c.offer("woodcutting", 1, T0 + 3_600_000, false);
        c.offer("woodcutting", 2, T0 + 3_610_000, false);
        List<TelemetryBucket> next = c.process(T0 + 3_600_000 + W);
        assertTrue(next.get(0).flags.contains("reconnect"));
        assertEquals(T0 + 3_600_000, next.get(0).startedAtMs);
    }

    @Test
    public void activeTimeUsesAfkTimeoutAfterXpEvents()
    {
        TrainingTelemetryCollector c = new TrainingTelemetryCollector(W, 10_000);
        c.sessionStart("genifer", "regular", T0);
        c.offer("fishing", 0, T0, false);
        c.offer("fishing", 10, T0 + 5_000, false);   // active 5s..15s
        c.offer("fishing", 20, T0 + 40_000, false);  // active 40s..50s
        TelemetryBucket b = c.process(T0 + W).get(0);

        assertEquals(20_000, b.estimatedActiveMs);
        assertTrue(b.estimatedActiveMs <= b.elapsedMs);
    }

    @Test
    public void queueIsBoundedAndNeverBlocks()
    {
        TrainingTelemetryCollector c = collector();
        c.sessionStart("genifer", "regular", T0);
        int accepted = 0;
        for (int i = 0; i < TrainingTelemetryCollector.MAX_QUEUED_EVENTS + 100; i++)
        {
            if (c.offer("attack", i, T0, false))
            {
                accepted++;
            }
        }
        assertEquals(TrainingTelemetryCollector.MAX_QUEUED_EVENTS - 1, accepted);
        assertTrue(c.droppedEvents() > 0);
        c.process(T0);
        assertTrue("queue drained, accepts again", c.offer("attack", 1, T0, false));
        assertFalse(c.droppedEvents() == 0);
    }
}
