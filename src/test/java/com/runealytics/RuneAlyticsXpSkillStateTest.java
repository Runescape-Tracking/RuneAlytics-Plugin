package com.runealytics;

import java.util.List;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic coverage for the per-skill session math. The active-clock is
 * passed in explicitly (activeNow), so XP/hr, AFK-pausing and time-to-level are
 * fully controllable without any real wall-clock dependency.
 */
public class RuneAlyticsXpSkillStateTest
{
    private static final long AFK = 60_000L; // 1 min AFK timeout
    private static final boolean IGNORE_AFK = true;

    private RuneAlyticsXpSkillState state(long baselineXp)
    {
        return new RuneAlyticsXpSkillState(Skill.MINING, baselineXp);
    }

    @Test
    public void constructor_setsBaselineWithNoGains()
    {
        RuneAlyticsXpSkillState s = state(1_000);
        assertEquals(1_000, s.getStartXp());
        assertEquals(1_000, s.getCurrentXp());
        assertEquals(0L, s.getTotalGained());
        assertFalse(s.hasGains());
        assertEquals(Experience.getLevelForXp(1_000), s.getStartLevel());
    }

    @Test
    public void record_firstForwardGain_startsSessionAndCountsGain()
    {
        RuneAlyticsXpSkillState s = state(0);
        int gained = s.record(500, 1_000L, 0L, false, AFK);

        assertEquals(500, gained);
        assertEquals(500L, s.getTotalGained());
        assertTrue(s.hasGains());
        assertEquals(500, s.getLastDropXp());
        assertEquals(1, s.recentDropsSnapshot().size());
    }

    @Test
    public void record_nonForwardObservation_isIgnored()
    {
        RuneAlyticsXpSkillState s = state(1_000);
        assertEquals(0, s.record(1_000, 10L, 10L, false, AFK)); // equal
        assertEquals(0, s.record(900, 20L, 20L, false, AFK));   // lower
        assertEquals(0L, s.getTotalGained());
        assertFalse(s.hasGains());
    }

    @Test
    public void record_accumulatesGainsMostRecentFirst()
    {
        RuneAlyticsXpSkillState s = state(0);
        s.record(100, 1L, 0L, false, AFK);
        s.record(300, 2L, 0L, false, AFK);

        assertEquals(300L, s.getTotalGained());
        assertEquals(2, s.getActions());
        List<RuneAlyticsXpSkillState.XpDrop> drops = s.recentDropsSnapshot();
        assertEquals(2, drops.size());
        assertEquals(200, drops.get(0).amount); // newest first
        assertEquals(100, drops.get(1).amount);
    }

    @Test
    public void recentDrops_cappedAtMax()
    {
        RuneAlyticsXpSkillState s = state(0);
        long xp = 0;
        for (int i = 0; i < RuneAlyticsXpSkillState.MAX_RECENT_DROPS + 10; i++)
        {
            xp += 10;
            s.record(xp, i, i, false, AFK);
        }
        assertEquals(RuneAlyticsXpSkillState.MAX_RECENT_DROPS, s.recentDropsSnapshot().size());
    }

    @Test
    public void samples_downSampledUnderCap()
    {
        RuneAlyticsXpSkillState s = state(0);
        long xp = 0;
        for (int i = 0; i < RuneAlyticsXpSkillState.MAX_SAMPLES + 5; i++)
        {
            xp += 5;
            s.record(xp, i, i, false, AFK);
        }
        int size = s.samplesSnapshot().size();
        assertTrue(size > 0 && size <= RuneAlyticsXpSkillState.MAX_SAMPLES);
    }

    @Test
    public void levelProgress_zeroAtBoundaryAndFractionalMidway()
    {
        long l50 = Experience.getXpForLevel(50);
        RuneAlyticsXpSkillState atBoundary = state(l50);
        assertEquals(0.0, atBoundary.levelProgress(), 1e-9);

        long l51 = Experience.getXpForLevel(51);
        RuneAlyticsXpSkillState mid = state((l50 + l51) / 2);
        double p = mid.levelProgress();
        assertTrue(p > 0.0 && p < 1.0);
    }

    @Test
    public void xpToNextLevel_matchesExperienceTable()
    {
        long l50 = Experience.getXpForLevel(50);
        RuneAlyticsXpSkillState s = state(l50);
        assertEquals(Experience.getXpForLevel(51) - l50, s.xpToNextLevel());
    }

    @Test
    public void xpPerHour_zeroBeforeThreeSecondsThenScales()
    {
        RuneAlyticsXpSkillState s = state(0);
        s.record(1_000, 1L, 0L, false, AFK); // first gain at active=0

        assertEquals(0L, s.xpPerHour(2_000L, false, AFK));            // < 3s active
        assertEquals(1_000L, s.xpPerHour(3_600_000L, false, AFK));    // exactly 1h → 1000/h
        assertEquals(1L, s.actionsPerHour(3_600_000L, false, AFK));   // 1 action in 1h
    }

    @Test
    public void activeMillis_excludesAfkGapWhenEnabled()
    {
        RuneAlyticsXpSkillState s = state(0);
        s.record(100, 1L, 0L, IGNORE_AFK, AFK);        // first gain, active=0
        s.record(200, 2L, 200_000L, IGNORE_AFK, AFK);  // gap 200k > 60k → 140k paused

        // ignoreAfk: elapsed 200k - paused 140k = 60k
        assertEquals(60_000L, s.activeMillis(200_000L, IGNORE_AFK, AFK));
        // not ignoring AFK: full elapsed
        assertEquals(200_000L, s.activeMillis(200_000L, false, AFK));
    }

    @Test
    public void timeToNextLevelMs_negativeWhenNoRate()
    {
        RuneAlyticsXpSkillState s = state(0);
        assertEquals(-1L, s.timeToNextLevelMs(3_600_000L, false, AFK)); // no gains yet
    }

    @Test
    public void resetRate_clearsTimingButKeepsGains()
    {
        RuneAlyticsXpSkillState s = state(0);
        s.record(1_000, 1L, 0L, false, AFK);
        s.resetRate();

        assertEquals(1_000L, s.getTotalGained());               // gains preserved
        assertEquals(0L, s.xpPerHour(3_600_000L, false, AFK));  // timing reset → not started
        assertTrue(s.rateHistorySnapshot().isEmpty());
    }
}
