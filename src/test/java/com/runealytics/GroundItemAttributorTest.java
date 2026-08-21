package com.runealytics;

import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ground item attribution with safety validation.
 */
public class GroundItemAttributorTest
{
    @Mock private Client mockClient;
    @Mock private Player mockLocalPlayer;

    private GroundItemAttributor attributor;
    private WorldPoint killLocation;
    private long killTime;

    @Before
    public void setUp()
    {
        MockitoAnnotations.openMocks(this);
        attributor = new GroundItemAttributor(mockClient);

        killLocation = new WorldPoint(3250, 3250, 0);
        killTime = System.currentTimeMillis();

        // Setup common mock behavior
        when(mockClient.getLocalPlayer()).thenReturn(mockLocalPlayer);
        when(mockLocalPlayer.getWorldLocation()).thenReturn(killLocation);
        when(mockClient.getWorld()).thenReturn(1);  // Safe world
    }

    @Test
    public void testValidItemAttribution()
    {
        assertTrue("Item should be attributed when all conditions met",
                attributor.shouldAttributeToKill(ItemID.GOLD_BAR, 1, killLocation, killTime));
    }

    @Test
    public void testItemTooFarAway()
    {
        WorldPoint farLocation = killLocation.dy(20);  // 20 tiles away
        assertFalse("Item too far from kill location should not be attributed",
                attributor.shouldAttributeToKill(ItemID.GOLD_BAR, 1, farLocation, killTime));
    }

    @Test
    public void testItemTooLate()
    {
        long staleKillTime = killTime - 10_000;  // 10 seconds ago (exceeds 5s window)
        assertFalse("Item from stale kill should not be attributed",
                attributor.shouldAttributeToKill(ItemID.GOLD_BAR, 1, killLocation, staleKillTime));
    }

    @Test
    public void testItemWithinTimeWindow()
    {
        long recentKill = killTime - 2_000;  // 2 seconds ago
        assertTrue("Item within time window should be attributed",
                attributor.shouldAttributeToKill(ItemID.GOLD_BAR, 1, killLocation, recentKill));
    }

    @Test
    public void testInvalidItemId()
    {
        assertFalse("Item with ID 0 should not be attributed",
                attributor.shouldAttributeToKill(0, 1, killLocation, killTime));
    }

    @Test
    public void testInvalidQuantity()
    {
        assertFalse("Item with quantity 0 should not be attributed",
                attributor.shouldAttributeToKill(ItemID.GOLD_BAR, 0, killLocation, killTime));
    }

    @Test
    public void testNullLocation()
    {
        assertFalse("Null kill location should not allow attribution",
                attributor.shouldAttributeToKill(ItemID.GOLD_BAR, 1, null, killTime));
    }

    @Test
    public void testNullLocalPlayer()
    {
        when(mockClient.getLocalPlayer()).thenReturn(null);
        assertFalse("No local player should prevent attribution",
                attributor.shouldAttributeToKill(ItemID.GOLD_BAR, 1, killLocation, killTime));
    }

    @Test
    public void testNullPlayerLocation()
    {
        when(mockLocalPlayer.getWorldLocation()).thenReturn(null);
        assertFalse("Player with no location should prevent attribution",
                attributor.shouldAttributeToKill(ItemID.GOLD_BAR, 1, killLocation, killTime));
    }

    @Test
    public void testNonLootItemFiltered()
    {
        assertFalse("Clue scroll should be filtered (handled separately)",
                attributor.shouldAttributeToKill(ItemID.CLUE_SCROLL, 1, killLocation, killTime));
    }

    @Test
    public void testProximityBoundary()
    {
        // Within max tiles
        WorldPoint nearby = killLocation.dy(7);  // 7 tiles away (within 8 limit)
        assertTrue("Item within max proximity should be attributed",
                attributor.shouldAttributeToKill(ItemID.GOLD_BAR, 1, nearby, killTime));

        // Just outside max tiles
        WorldPoint tooFar = killLocation.dy(9);  // 9 tiles away (exceeds 8 limit)
        assertFalse("Item outside max proximity should not be attributed",
                attributor.shouldAttributeToKill(ItemID.GOLD_BAR, 1, tooFar, killTime));
    }

    @Test
    public void testTimeBoundary()
    {
        // Just within window
        long recentKill = killTime - 4_900;  // 4.9 seconds ago
        assertTrue("Item just within time window should be attributed",
                attributor.shouldAttributeToKill(ItemID.GOLD_BAR, 1, killLocation, recentKill));

        // Just outside window
        long staleKill = killTime - 5_100;  // 5.1 seconds ago
        assertFalse("Item just outside time window should not be attributed",
                attributor.shouldAttributeToKill(ItemID.GOLD_BAR, 1, killLocation, staleKill));
    }

    @Test
    public void testMultipleItems()
    {
        assertTrue("Multiple items should each be validated",
                attributor.shouldAttributeToKill(ItemID.GOLD_BAR, 100, killLocation, killTime));

        assertTrue("Large stacks should be attributed",
                attributor.shouldAttributeToKill(ItemID.COINS, 1_000_000, killLocation, killTime));
    }
}
