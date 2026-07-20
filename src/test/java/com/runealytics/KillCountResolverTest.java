package com.runealytics;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Covers every KC chat form the {@link KillCountResolver} recognises, the
 * exactly-once consume contract, the correlation window, monotonic
 * regression rejection and account-switch clearing.
 */
public class KillCountResolverTest
{
    private KillCountResolver resolver;

    @Before
    public void setUp()
    {
        // Production passes BossNames::normalize; these tests use it too so
        // the chat-name → storage-key mapping is exercised.
        resolver = new KillCountResolver(BossNames::normalize);
    }

    // ── Pattern coverage ──────────────────────────────────────────────────────

    @Test
    public void observe_standardKillCount()
    {
        KillCountResolver.KcObservation obs =
                resolver.observe("Your Vorkath kill count is: 100.", 1_000L);
        assertNotNull(obs);
        assertEquals("Vorkath", obs.getBossName());
        assertEquals(100, obs.getKillCount());
    }

    @Test
    public void observe_barrowsChestCount()
    {
        KillCountResolver.KcObservation obs =
                resolver.observe("Your Barrows chest count is: 279.", 1_000L);
        assertNotNull(obs);
        assertEquals("Barrows", obs.getBossName());
        assertEquals(279, obs.getKillCount());
    }

    @Test
    public void observe_gauntletCompletionCount()
    {
        KillCountResolver.KcObservation obs =
                resolver.observe("Your Gauntlet completion count is: 12.", 1_000L);
        assertNotNull(obs);
        assertEquals("The Gauntlet", obs.getBossName());
        assertEquals(12, obs.getKillCount());
    }

    @Test
    public void observe_corruptedGauntletDistinctFromNormal()
    {
        KillCountResolver.KcObservation obs =
                resolver.observe("Your Corrupted Gauntlet completion count is: 5.", 1_000L);
        assertNotNull(obs);
        assertEquals("Corrupted Gauntlet", obs.getBossName());
    }

    @Test
    public void observe_completedRaidCount()
    {
        KillCountResolver.KcObservation obs =
                resolver.observe("Your completed Chambers of Xeric count is: 57.", 1_000L);
        assertNotNull(obs);
        assertEquals("Chambers of Xeric", obs.getBossName());
        assertEquals(57, obs.getKillCount());
    }

    @Test
    public void observe_subduedWintertodtCount()
    {
        KillCountResolver.KcObservation obs =
                resolver.observe("Your subdued Wintertodt count is: 245.", 1_000L);
        assertNotNull(obs);
        assertEquals("Wintertodt", obs.getBossName());
        assertEquals(245, obs.getKillCount());
    }

    @Test
    public void observe_lunarChestFallbackMapsToMoonsOfPeril()
    {
        KillCountResolver.KcObservation obs =
                resolver.observe("Your Lunar Chest count is: 11.", 1_000L);
        assertNotNull(obs);
        assertEquals("Moons of Peril", obs.getBossName());
        assertEquals(11, obs.getKillCount());
    }

    @Test
    public void observe_stripsColorTagsAndParsesCommas()
    {
        KillCountResolver.KcObservation obs = resolver.observe(
                "Your <col=ff0000>Zulrah</col> kill count is: <col=ff0000>1,234</col>.", 1_000L);
        assertNotNull(obs);
        assertEquals("Zulrah", obs.getBossName());
        assertEquals(1234, obs.getKillCount());
    }

    @Test
    public void observe_nonKcMessagesIgnored()
    {
        assertNull(resolver.observe("You feel something weird sneaking into your backpack.", 1_000L));
        assertNull(resolver.observe("Welcome to Old School RuneScape.", 1_000L));
        assertNull(resolver.observe(null, 1_000L));
        assertNull(resolver.observe("", 1_000L));
    }

    @Test
    public void observe_zeroOrMalformedCountIgnored()
    {
        assertNull(resolver.observe("Your Vorkath kill count is: 0.", 1_000L));
        // Overflowing number must not throw, just be ignored.
        assertNull(resolver.observe("Your Vorkath kill count is: 99999999999999999999.", 1_000L));
    }

    // ── Consume contract ──────────────────────────────────────────────────────

    @Test
    public void consume_returnsPendingKcExactlyOnce()
    {
        resolver.observe("Your Vorkath kill count is: 100.", 1_000L);

        assertEquals(Integer.valueOf(100), resolver.consume("Vorkath", 2_000L));
        // Exactly-once: a second kill can never claim the same observation.
        assertNull(resolver.consume("Vorkath", 2_500L));
    }

    @Test
    public void consume_isPerBoss()
    {
        resolver.observe("Your Vorkath kill count is: 100.", 1_000L);
        resolver.observe("Your Zulrah kill count is: 50.", 1_000L);

        assertEquals(Integer.valueOf(50), resolver.consume("Zulrah", 2_000L));
        assertEquals(Integer.valueOf(100), resolver.consume("Vorkath", 2_000L));
    }

    @Test
    public void consume_expiredObservationReturnsNull()
    {
        resolver.observe("Your Vorkath kill count is: 100.", 1_000L);
        assertNull(resolver.consume("Vorkath",
                1_000L + KillCountResolver.CONSUME_WINDOW_MS + 1));
        // And the expired observation is gone for good.
        assertNull(resolver.consume("Vorkath", 2_000L));
    }

    @Test
    public void consume_unknownBossReturnsNull()
    {
        assertNull(resolver.consume("Vorkath", 1_000L));
        assertNull(resolver.consume(null, 1_000L));
    }

    @Test
    public void consume_normalizesTheLookupName()
    {
        resolver.observe("Your completed Theatre of Blood count is: 8.", 1_000L);
        // Caller may pass any alias that normalises to the same key.
        assertEquals(Integer.valueOf(8), resolver.consume("tob", 2_000L));
    }

    // ── Monotonic guard ───────────────────────────────────────────────────────

    @Test
    public void observe_rejectsRegressions()
    {
        resolver.observe("Your Vorkath kill count is: 100.", 1_000L);
        assertNull(resolver.observe("Your Vorkath kill count is: 99.", 2_000L));
        // The higher pending value must be untouched.
        assertEquals(Integer.valueOf(100), resolver.consume("Vorkath", 3_000L));
    }

    @Test
    public void observe_acceptsEqualAndHigher()
    {
        resolver.observe("Your Vorkath kill count is: 100.", 1_000L);
        assertNotNull(resolver.observe("Your Vorkath kill count is: 100.", 2_000L));
        assertNotNull(resolver.observe("Your Vorkath kill count is: 101.", 3_000L));
        assertEquals(Integer.valueOf(101), resolver.getHighestSeen("Vorkath"));
    }

    // ── Account isolation ─────────────────────────────────────────────────────

    @Test
    public void clear_dropsPendingAndMonotonicState()
    {
        resolver.observe("Your Vorkath kill count is: 100.", 1_000L);
        resolver.clear();

        assertNull(resolver.consume("Vorkath", 2_000L));
        assertNull(resolver.getHighestSeen("Vorkath"));
        // After an account switch a lower KC is legitimate again.
        assertNotNull(resolver.observe("Your Vorkath kill count is: 3.", 3_000L));
    }
}
