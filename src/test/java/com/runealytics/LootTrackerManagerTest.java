package com.runealytics;

import com.google.gson.Gson;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Coverage for the pure name-normalization, source-type and chat-parsing
 * helpers on the loot manager. All RuneLite/network deps are mocked; only pure
 * methods are exercised so the mocks are never actually invoked.
 */
public class LootTrackerManagerTest
{
    private LootTrackerManager manager;

    @Before
    public void setUp()
    {
        manager = new LootTrackerManager(
                mock(Client.class),
                mock(ClientThread.class),
                mock(ItemManager.class),
                mock(RunealyticsConfig.class),
                mock(RuneAlyticsState.class),
                mock(LootStorageManager.class),
                mock(LootTrackerApiClient.class),
                mock(ConfigManager.class),
                mock(ScheduledExecutorService.class),
                new Gson());
    }

    // ── normalizeBossName (static) ──────────────────────────────────────────────

    @Test
    public void normalizeBossName_nullOrEmpty_isUnknown()
    {
        assertEquals("Unknown", LootTrackerManager.normalizeBossName(null));
        assertEquals("Unknown", LootTrackerManager.normalizeBossName(""));
    }

    @Test
    public void normalizeBossName_passesThroughPickpocketPrefix()
    {
        assertEquals("Pickpocket: Master Farmer",
                LootTrackerManager.normalizeBossName("Pickpocket: Master Farmer"));
    }

    @Test
    public void normalizeBossName_canonicalisesAliases()
    {
        assertEquals("Chambers of Xeric", LootTrackerManager.normalizeBossName("cox"));
        assertEquals("Theatre of Blood", LootTrackerManager.normalizeBossName("TOB"));
        assertEquals("Tombs of Amascut", LootTrackerManager.normalizeBossName("toa raid"));
        assertEquals("Zulrah", LootTrackerManager.normalizeBossName("Zulrah"));
        assertEquals("Vet'ion", LootTrackerManager.normalizeBossName("vetion"));
    }

    @Test
    public void normalizeBossName_corruptedGauntletBeatsPlainGauntlet()
    {
        assertEquals("Corrupted Gauntlet", LootTrackerManager.normalizeBossName("corrupted gauntlet"));
        assertEquals("The Gauntlet", LootTrackerManager.normalizeBossName("The Gauntlet"));
    }

    @Test
    public void normalizeBossName_nexIsExactMatchNotSubstring()
    {
        assertEquals("Nex", LootTrackerManager.normalizeBossName("nex"));
        // "next" must NOT be treated as Nex; it falls through to trimmed raw.
        assertEquals("next", LootTrackerManager.normalizeBossName("next"));
    }

    @Test
    public void normalizeBossName_clueTiers()
    {
        assertEquals("Hard Clue", LootTrackerManager.normalizeBossName("hard clue"));
        assertEquals("Master Clue", LootTrackerManager.normalizeBossName("a master clue scroll"));
    }

    @Test
    public void normalizeBossName_unknownReturnsTrimmedRaw()
    {
        assertEquals("Goblin", LootTrackerManager.normalizeBossName("Goblin"));
        assertEquals("Random Boss", LootTrackerManager.normalizeBossName("  Random Boss  "));
    }

    // ── source-type prefixes ─────────────────────────────────────────────────

    @Test
    public void pickpocketPrefix_detectionAndStrip()
    {
        assertTrue(manager.isPickpocketSource("Pickpocket: Guard"));
        assertFalse(manager.isPickpocketSource("Guard"));
        assertFalse(manager.isPickpocketSource(null));

        assertEquals("Guard", manager.stripPickpocketPrefix("Pickpocket: Guard"));
        assertEquals("Guard", manager.stripPickpocketPrefix("Guard"));
        assertEquals("Unknown", manager.stripPickpocketPrefix(null));
    }

    @Test
    public void skillingPrefix_detectionAndStrip()
    {
        assertTrue(manager.isSkillingSource("Skilling: Fishing"));
        assertFalse(manager.isSkillingSource("Fishing"));
        assertFalse(manager.isSkillingSource(null));

        assertEquals("Fishing", manager.stripSkillingPrefix("Skilling: Fishing"));
        assertEquals("Fishing", manager.stripSkillingPrefix("Fishing"));
        assertNull(manager.stripSkillingPrefix(null));
    }

    @Test
    public void implingPrefix_detectionAndStrip()
    {
        assertTrue(manager.isImplingSource("Impling: Nature"));
        assertFalse(manager.isImplingSource("Nature"));
        assertFalse(manager.isImplingSource(null));

        assertEquals("Nature", manager.stripImplingPrefix("Impling: Nature"));
        assertEquals("Nature", manager.stripImplingPrefix("Nature"));
        assertNull(manager.stripImplingPrefix(null));
    }

    // ── normalizePickpocketNpc ───────────────────────────────────────────────

    @Test
    public void normalizePickpocketNpc_mappedExactAndPartial()
    {
        assertEquals("Master Farmer", manager.normalizePickpocketNpc("Master Farmer"));
        assertEquals("Guard", manager.normalizePickpocketNpc("Guard (level-19)"));
        assertEquals("H.A.M. Member", manager.normalizePickpocketNpc("H.A.M. member"));
    }

    @Test
    public void normalizePickpocketNpc_nullEmptyAndUnknown()
    {
        assertEquals("Unknown", manager.normalizePickpocketNpc(null));
        assertEquals("Unknown", manager.normalizePickpocketNpc(""));
        assertEquals("Some Random Dude", manager.normalizePickpocketNpc("some random dude"));
    }

    // ── isBoss ────────────────────────────────────────────────────────────────

    @Test
    public void isBoss_matchesByIdOrName()
    {
        // 7554 is a tracked Great Olm (CoX) NPC id.
        assertTrue(manager.isBoss(7554, "anything"));
        // name-based match even for an untracked id
        assertTrue(manager.isBoss(-1, "Zulrah"));
        assertFalse(manager.isBoss(-1, "Some Random NPC"));
        assertFalse(manager.isBoss(-1, null));
    }

    // ── detectChestSource ─────────────────────────────────────────────────────

    @Test
    public void detectChestSource_recognisesActivities()
    {
        assertEquals("Wintertodt", manager.detectChestSource("you have subdued the wintertodt"));
        assertEquals("Tempoross", manager.detectChestSource("you have helped to subdue the tempoross"));
        assertEquals("Zalcano", manager.detectChestSource("you defeated zalcano and got loot"));
        assertEquals("Chambers of Xeric",
                manager.detectChestSource("congratulations - your raid is complete!"));
        assertEquals("Corrupted Gauntlet",
                manager.detectChestSource("your corrupted gauntlet is complete"));
        assertEquals("The Gauntlet", manager.detectChestSource("your gauntlet is complete"));
    }

    @Test
    public void detectChestSource_treasureTrailTiers()
    {
        assertEquals("Hard Clue",
                manager.detectChestSource("you have completed a hard treasure trail"));
        assertEquals("Clue Scroll",
                manager.detectChestSource("you have completed a treasure trail"));
    }

    @Test
    public void detectChestSource_noMatchReturnsNull()
    {
        assertNull(manager.detectChestSource("you killed a goblin"));
    }

    // ── parseKillCountMessage ─────────────────────────────────────────────────

    @Test
    public void parseKillCountMessage_handlesMatchingAndNonMatchingWithoutThrowing()
    {
        manager.parseKillCountMessage("Your Zulrah kill count is: 1234");
        manager.parseKillCountMessage("Some unrelated chat message");
        // No observable state; the contract is simply that it never throws.
    }
}
