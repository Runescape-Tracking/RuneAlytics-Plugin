package com.runealytics;

import net.runelite.api.Experience;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural coverage for the XP session manager: account scoping / baselining,
 * gain accounting, hidden & favorite persistence, list filtering, levels-gained
 * math and the sync-payload build. Timing-dependent rate math is covered
 * deterministically in RuneAlyticsXpSkillStateTest; here we assert the
 * account/aggregation/persistence logic.
 */
public class RuneAlyticsXpSessionManagerTest
{
    private RunealyticsConfig config;
    private CurrentPlayerIdentityService identity;
    private ConfigManager configManager;
    private RuneAlyticsXpSessionManager mgr;

    @Before
    public void setUp()
    {
        config = mock(RunealyticsConfig.class);
        identity = mock(CurrentPlayerIdentityService.class);
        configManager = mock(ConfigManager.class);
        when(config.xpIgnoreAfk()).thenReturn(false);
        when(config.xpAfkTimeout()).thenReturn(5);
        when(identity.getAccountKey()).thenReturn("acct1");
        mgr = new RuneAlyticsXpSessionManager(config, identity, configManager);
    }

    private void gain(Skill skill, long baseline, long current)
    {
        mgr.recordXp(skill, baseline); // first observation = baseline
        mgr.recordXp(skill, current);  // real gain
    }

    @Test
    public void recordXp_ignoresOverallSkill()
    {
        mgr.recordXp(Skill.OVERALL, 1_000);
        assertNull(mgr.getState(Skill.OVERALL));
        assertFalse(mgr.hasAnyGains());
    }

    @Test
    public void recordXp_ignoredWhenNoAccount()
    {
        when(identity.getAccountKey()).thenReturn(null);
        mgr.recordXp(Skill.MINING, 1_000);
        assertNull(mgr.getState(Skill.MINING));
    }

    @Test
    public void recordXp_firstObservationIsBaselineWithNoGain()
    {
        mgr.recordXp(Skill.MINING, 1_000);
        assertNotNull(mgr.getState(Skill.MINING));
        assertEquals(0L, mgr.getState(Skill.MINING).getTotalGained());
        assertFalse(mgr.hasAnyGains());
        assertEquals(0L, mgr.totalXpGained());
    }

    @Test
    public void recordXp_secondObservationCountsGainAndTodayTotal()
    {
        gain(Skill.MINING, 1_000, 1_500);
        assertEquals(500L, mgr.getState(Skill.MINING).getTotalGained());
        assertTrue(mgr.hasAnyGains());
        assertEquals(500L, mgr.totalXpGained());
        assertTrue(mgr.todayXpGained() >= 500L);
    }

    @Test
    public void recordXp_accountSwitchStartsFreshSession()
    {
        gain(Skill.MINING, 1_000, 1_500);
        assertEquals("acct1", mgr.getSessionAccountKey());

        when(identity.getAccountKey()).thenReturn("acct2");
        mgr.recordXp(Skill.FISHING, 2_000);

        assertEquals("acct2", mgr.getSessionAccountKey());
        assertNull("old account's skill state must be cleared", mgr.getState(Skill.MINING));
        assertNotNull(mgr.getState(Skill.FISHING));
    }

    @Test
    public void hiddenSkills_toggledAndPersisted()
    {
        assertEquals(0, mgr.hiddenCount());
        mgr.setHidden(Skill.FISHING, true);

        assertTrue(mgr.isHidden(Skill.FISHING));
        assertEquals(1, mgr.hiddenCount());
        verify(configManager).setConfiguration(eq("runealytics"), eq("xpHiddenSkills"), anyString());

        mgr.setHidden(Skill.FISHING, false);
        assertFalse(mgr.isHidden(Skill.FISHING));
    }

    @Test
    public void favoriteSkills_toggledAndPersisted()
    {
        mgr.setFavorite(Skill.MAGIC, true);
        assertTrue(mgr.isFavorite(Skill.MAGIC));
        verify(configManager).setConfiguration(eq("runealytics"), eq("xpFavoriteSkills"), anyString());

        mgr.setFavorite(Skill.MAGIC, false);
        assertFalse(mgr.isFavorite(Skill.MAGIC));
    }

    @Test
    public void snapshotStates_excludesHiddenAndUntrainedByDefault()
    {
        gain(Skill.MINING, 0, 500);
        gain(Skill.FISHING, 0, 500);
        mgr.setHidden(Skill.FISHING, true);

        assertEquals(1, mgr.snapshotStates().size());               // hidden excluded
        assertEquals(2, mgr.snapshotStates(false, true).size());    // hidden included
    }

    @Test
    public void levelsGained_sumsRealLevelDeltas()
    {
        // Baseline at level 1 (0 xp), then gain up to level 5.
        mgr.recordXp(Skill.MINING, 0);
        mgr.recordXp(Skill.MINING, Experience.getXpForLevel(5));
        assertEquals(4, mgr.levelsGained());
    }

    @Test
    public void buildPayload_scopesToTrainedSkillsWithCurrentTotals()
    {
        gain(Skill.MINING, 1_000, 1_500);

        RuneAlyticsXpSyncPayload p =
                mgr.buildPayload("Zezima", "prof1", "regular", "normal", true);

        assertEquals("Zezima", p.username);
        assertTrue(p.ended);
        assertEquals(500L, p.totalXp);
        assertEquals(1, p.skills.size());
        RuneAlyticsXpSyncPayload.SkillEntry e = p.skills.get(0);
        assertEquals("mining", e.skill);
        assertEquals(500L, e.xpGained);
        assertEquals(1_500L, e.currentXp);
    }

    @Test
    public void buildPayload_omitsUntrainedSkills()
    {
        mgr.recordXp(Skill.MINING, 1_000); // baseline only, no gain
        RuneAlyticsXpSyncPayload p =
                mgr.buildPayload("Zezima", null, "regular", "normal", false);
        assertTrue(p.skills.isEmpty());
    }
}
