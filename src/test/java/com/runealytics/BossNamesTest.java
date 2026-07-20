package com.runealytics;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Covers the mappings added alongside the {@link BossNames} extraction; the
 * legacy rule behaviour is already pinned by {@code LootTrackerManagerTest}
 * through the delegating alias.
 */
public class BossNamesTest
{
    @Test
    public void newBossMappings()
    {
        assertEquals("Phantom Muspah", BossNames.normalize("Phantom Muspah"));
        assertEquals("Phantom Muspah", BossNames.normalize("muspah"));
        assertEquals("Moons of Peril", BossNames.normalize("Lunar Chest"));
        assertEquals("Moons of Peril", BossNames.normalize("lunar"));
    }

    @Test
    public void matchesLegacyAlias()
    {
        // The delegating alias and the extracted rules must agree.
        String[] samples = {
                null, "", "cox", "TOB", "Pickpocket: Guard", "Zulrah",
                "  Random Boss  ", "next", "nex", "hard clue",
        };
        for (String s : samples)
        {
            assertEquals(LootTrackerManager.normalizeBossName(s), BossNames.normalize(s));
        }
    }
}
