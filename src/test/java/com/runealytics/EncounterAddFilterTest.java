package com.runealytics;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Which NPC deaths are dropped as no-loot encounter adds. */
public class EncounterAddFilterTest
{
    private static boolean add(String name, boolean inInstance)
    {
        return LootTrackerManager.isNonLootEncounterAdd(name.toLowerCase(), inInstance);
    }

    @Test
    public void ordinaryMonstersWithCommonWordsAreKept()
    {
        for (String name : new String[]{"Kalphite Soldier", "Goblin soldier", "Minotaur", "Lizardman shaman",
                "Earth guardian", "Scarab mage", "Cursed spirit", "Lost soul", "Kalphite Worker"})
        {
            assertFalse(name + " outside an instance is a normal kill", add(name, false));
        }
    }

    @Test
    public void raidAddsAreStillDropped()
    {
        assertTrue(add("Guardian", true));            // Chambers of Xeric
        assertTrue(add("Lizardman shaman", true));
        assertTrue(add("Minotaur", true));            // Fortis Colosseum
        assertTrue(add("Scarab", true));              // Tombs of Amascut
        assertTrue(add("Nylocas Hagios", false));     // only exists in Theatre of Blood
        assertTrue(add("Muttadile", false));
        assertTrue(add("Jal-Nib", false));            // Inferno
        assertTrue(add("Tz-Kih", false));             // Fight Caves
        assertTrue(add("Corrupted wolf", false));     // Corrupted Gauntlet
    }

    @Test
    public void savedDataIsOnlyPurgedForEncounterOnlyNames()
    {
        // Purging on load passes inInstance=false: a stored Kalphite Soldier entry survives.
        assertFalse(add("Kalphite Soldier", false));
        assertTrue(add("Tekton", false));
    }
}
