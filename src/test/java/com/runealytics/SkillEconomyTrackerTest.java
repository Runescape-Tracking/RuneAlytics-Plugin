package com.runealytics;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Covers the per-skill GP economics: GE vs alch valuation, material-input
 * accounting (outputs net against consumed supplies; pure sinks show as a
 * complete loss), supplies aggregation, day persistence/rollover and account
 * isolation.
 */
public class SkillEconomyTrackerTest
{
    private static final long T0 = 1_000_000L;

    private Map<String, String> backing;
    private SkillEconomyTracker.Store store;
    private String[] day;
    private SkillEconomyTracker tracker;

    @Before
    public void setUp()
    {
        backing = new HashMap<>();
        store = new SkillEconomyTracker.Store()
        {
            @Override public String get(String key) { return backing.get(key); }

            @Override public void put(String key, String value) { backing.put(key, value); }
        };
        day = new String[]{ "2026-07-20" };
        tracker = new SkillEconomyTracker(store, new Gson(), () -> day[0]);
        tracker.setAccount("acct1");
    }

    private static SkillEconomyTracker.ValuedStack stack(int id, String name, int qty, long ge, long alch)
    {
        return new SkillEconomyTracker.ValuedStack(id, name, qty, ge, alch);
    }

    // ── Core accounting ───────────────────────────────────────────────────────

    @Test
    public void fletching_outputsNetAgainstMaterialInputs()
    {
        // 10 yew logs (200 gp GE / 96 alch each) -> 10 yew longbows (u)
        // (250 gp GE / 384 alch each).
        tracker.record("Fletching",
                Collections.singletonList(stack(66, "Yew longbow (u)", 10, 250, 384)),
                Collections.singletonList(stack(1515, "Yew logs", 10, 200, 96)),
                T0);

        SkillEconomyTracker.Snapshot s = tracker.snapshot("Fletching");
        assertEquals(2_500L, s.sessionOutputGe);
        assertEquals(2_000L, s.sessionInputGe);
        assertEquals(500L, s.sessionProfitGe());          // bows minus logs
        assertEquals(3_840L - 960L, s.sessionProfitAlch()); // alch scheme independent
        assertEquals(500L, s.todayProfitGe());
    }

    @Test
    public void valeOffering_isACompleteLoss()
    {
        // Logs offered with no output item: profit must be the full negative cost.
        tracker.record("Fletching",
                Collections.emptyList(),
                Collections.singletonList(stack(1515, "Yew logs", 25, 200, 96)),
                T0);

        SkillEconomyTracker.Snapshot s = tracker.snapshot("Fletching");
        assertEquals(-5_000L, s.sessionProfitGe());
        assertEquals(-2_400L, s.sessionProfitAlch());
        assertEquals(1, s.sessionSupplies.size());
        assertEquals(25L, s.sessionSupplies.get(0).getQuantity());
    }

    @Test
    public void suppliesAggregateAcrossRecordsAndSortByGeValue()
    {
        tracker.record("Herblore",
                Collections.emptyList(),
                Arrays.asList(
                        stack(259, "Ranarr weed", 1, 7_000, 25),
                        stack(227, "Vial of water", 1, 5, 1)),
                T0);
        tracker.record("Herblore",
                Collections.emptyList(),
                Arrays.asList(
                        stack(259, "Ranarr weed", 2, 7_000, 25),
                        stack(231, "Snape grass", 3, 900, 1)),
                T0 + 1_000);

        List<SkillEconomyTracker.ItemFlow> supplies = tracker.snapshot("Herblore").sessionSupplies;
        assertEquals(3, supplies.size());
        assertEquals("Ranarr weed", supplies.get(0).getItemName()); // 21,000 gp first
        assertEquals(3L, supplies.get(0).getQuantity());            // 1 + 2 merged
        assertEquals(21_000L, supplies.get(0).getGeGp());
        assertEquals("Snape grass", supplies.get(1).getItemName()); // 2,700 gp second
    }

    @Test
    public void skillsAreIndependent()
    {
        tracker.record("Fletching", Collections.emptyList(),
                Collections.singletonList(stack(1515, "Yew logs", 1, 200, 96)), T0);

        assertFalse(tracker.snapshot("Cooking").hasSessionData());
        assertEquals(SkillEconomyTracker.Snapshot.EMPTY.sessionOutputGe,
                tracker.snapshot("Cooking").sessionOutputGe);
    }

    @Test
    public void recordIgnoredWithoutAccountOrData()
    {
        SkillEconomyTracker fresh = new SkillEconomyTracker(store, new Gson(), () -> day[0]);
        fresh.record("Mining", Collections.singletonList(stack(440, "Iron ore", 1, 100, 30)),
                Collections.emptyList(), T0);
        assertFalse("no account bound — nothing may be recorded",
                fresh.snapshot("Mining").hasSessionData());

        tracker.record("Mining", null, null, T0);
        tracker.record(null, Collections.singletonList(stack(440, "Iron ore", 1, 100, 30)),
                Collections.emptyList(), T0);
        assertFalse(tracker.snapshot("Mining").hasSessionData());
    }

    // ── Session vs day scopes ─────────────────────────────────────────────────

    @Test
    public void resetSessionKeepsTodayTotals()
    {
        tracker.record("Mining", Collections.singletonList(stack(440, "Iron ore", 5, 100, 30)),
                Collections.emptyList(), T0);
        tracker.resetSession();

        SkillEconomyTracker.Snapshot s = tracker.snapshot("Mining");
        assertFalse(s.hasSessionData());
        assertEquals(500L, s.todayProfitGe());
    }

    @Test
    public void resetSkillClearsOnlyThatSkillsSession()
    {
        tracker.record("Mining", Collections.singletonList(stack(440, "Iron ore", 5, 100, 30)),
                Collections.emptyList(), T0);
        tracker.record("Fishing", Collections.singletonList(stack(317, "Shrimps", 5, 50, 10)),
                Collections.emptyList(), T0);

        tracker.resetSkill("Mining");
        assertFalse(tracker.snapshot("Mining").hasSessionData());
        assertEquals(250L, tracker.snapshot("Fishing").sessionProfitGe());
        assertEquals(500L, tracker.snapshot("Mining").todayProfitGe());
    }

    @Test
    public void dayRollover_resetsTodayKeepsSession()
    {
        tracker.record("Mining", Collections.singletonList(stack(440, "Iron ore", 5, 100, 30)),
                Collections.emptyList(), T0);

        day[0] = "2026-07-21"; // midnight passes

        SkillEconomyTracker.Snapshot s = tracker.snapshot("Mining");
        assertEquals("session totals span midnight", 500L, s.sessionProfitGe());
        assertEquals("today totals reset at midnight", 0L, s.todayProfitGe());
        assertTrue(s.todaySupplies.isEmpty());
    }

    // ── Persistence & account isolation ───────────────────────────────────────

    @Test
    public void todayTotalsSurviveRestartViaStore()
    {
        tracker.record("Fletching",
                Collections.singletonList(stack(66, "Yew longbow (u)", 10, 250, 384)),
                Collections.singletonList(stack(1515, "Yew logs", 10, 200, 96)),
                T0);
        tracker.flush();

        // "Restart": a new tracker over the same store, same day, same account.
        SkillEconomyTracker restarted = new SkillEconomyTracker(store, new Gson(), () -> day[0]);
        restarted.setAccount("acct1");

        SkillEconomyTracker.Snapshot s = restarted.snapshot("Fletching");
        assertFalse("session never survives a restart", s.hasSessionData());
        assertEquals(500L, s.todayProfitGe());
        assertEquals(1, s.todaySupplies.size());
        assertEquals("Yew logs", s.todaySupplies.get(0).getItemName());
        assertEquals(10L, s.todaySupplies.get(0).getQuantity());
    }

    @Test
    public void accountSwitch_neverLeaksSessionOrDayData()
    {
        tracker.record("Mining", Collections.singletonList(stack(440, "Iron ore", 5, 100, 30)),
                Collections.emptyList(), T0);

        tracker.setAccount("acct2");
        assertFalse(tracker.snapshot("Mining").hasSessionData());
        assertFalse(tracker.snapshot("Mining").hasTodayData());

        // Switching back restores acct1's persisted day totals.
        tracker.setAccount("acct1");
        assertEquals(500L, tracker.snapshot("Mining").todayProfitGe());
        assertFalse(tracker.snapshot("Mining").hasSessionData());
    }

    @Test
    public void corruptPersistedPayloadStartsFresh()
    {
        backing.put("xpEconDay_acct3", "{not valid json!!");
        tracker.setAccount("acct3");
        assertFalse(tracker.snapshot("Mining").hasTodayData());
        // And it still records normally afterwards.
        tracker.record("Mining", Collections.singletonList(stack(440, "Iron ore", 1, 100, 30)),
                Collections.emptyList(), T0);
        assertEquals(100L, tracker.snapshot("Mining").todayProfitGe());
    }
}
