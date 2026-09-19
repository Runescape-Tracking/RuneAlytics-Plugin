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
        assertEquals("Doom of Mokhaiotl", BossNames.normalize("Doom of Mokhaiotl"));
        assertEquals("Doom of Mokhaiotl", BossNames.normalize("doom of mokhaiotl"));
    }

    @Test
    public void matchesLegacyAlias()
    {
        // The delegating alias and the extracted rules must agree.
        String[] samples = {
                null, "", "cox", "TOB", "Pickpocket: Guard", "Zulrah",
                "  Random Boss  ", "next", "nex", "hard clue",
                "Clue scroll (hard)", "Clue Scroll (beginner)",
        };
        for (String s : samples)
        {
            assertEquals(LootTrackerManager.normalizeBossName(s), BossNames.normalize(s));
        }
    }

    @Test
    public void clueScrollParentheticalNames_mapToTier()
    {
        // RuneLite loot-tracker / casket sources use "Clue scroll (tier)",
        // where the tier and "clue" are not adjacent as "hard clue".
        assertEquals("Beginner Clue", BossNames.normalize("Clue Scroll (beginner)"));
        assertEquals("Easy Clue", BossNames.normalize("Clue scroll (easy)"));
        assertEquals("Medium Clue", BossNames.normalize("clue (medium)"));
        assertEquals("Hard Clue", BossNames.normalize("Clue scroll (hard)"));
        assertEquals("Elite Clue", BossNames.normalize("Clue scroll (elite)"));
        assertEquals("Master Clue", BossNames.normalize("Clue Scroll (master)"));
        assertEquals("Hard Clue", BossNames.normalize("hard clue"));
        assertEquals("Master Clue", BossNames.normalize("a master clue scroll"));
    }

    @Test
    public void corruptedGauntletBeatsPlainGauntletAfterReorder()
    {
        assertEquals("Corrupted Gauntlet", BossNames.normalize("corrupted gauntlet"));
        assertEquals("The Gauntlet", BossNames.normalize("The Gauntlet"));
    }
}
