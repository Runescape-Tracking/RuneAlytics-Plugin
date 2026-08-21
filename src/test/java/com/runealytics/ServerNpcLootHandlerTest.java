package com.runealytics;

import net.runelite.api.NPC;
import net.runelite.client.events.ServerNpcLoot;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ServerNpcLoot event handling.
 */
public class ServerNpcLootHandlerTest
{
    @Mock private ServerNpcLoot mockServerNpcLoot;
    @Mock private NPC mockNpc;

    private ServerNpcLootHandler handler;

    @Before
    public void setUp()
    {
        MockitoAnnotations.openMocks(this);
        handler = new ServerNpcLootHandler();
    }

    @Test
    public void testBufferingServerNpcLoot()
    {
        // Setup
        List<net.runelite.client.game.ItemStack> items = new ArrayList<>();
        items.add(new net.runelite.client.game.ItemStack(12345, 5));
        items.add(new net.runelite.client.game.ItemStack(54321, 100));

        when(mockServerNpcLoot.getNpc()).thenReturn(mockNpc);
        when(mockNpc.getIndex()).thenReturn(1234);
        when(mockNpc.getId()).thenReturn(5678);
        when(mockServerNpcLoot.getItems()).thenReturn(items);

        // Process
        handler.onServerNpcLoot(mockServerNpcLoot);

        // Verify
        assertTrue("Should have pending loot for NPC index",
                handler.hasPendingLoot(1234));
    }

    @Test
    public void testPollingServerNpcLoot()
    {
        // Setup
        List<net.runelite.client.game.ItemStack> items = new ArrayList<>();
        items.add(new net.runelite.client.game.ItemStack(12345, 5));

        when(mockServerNpcLoot.getNpc()).thenReturn(mockNpc);
        when(mockNpc.getIndex()).thenReturn(1234);
        when(mockNpc.getId()).thenReturn(5678);
        when(mockServerNpcLoot.getItems()).thenReturn(items);

        // Process
        handler.onServerNpcLoot(mockServerNpcLoot);

        // Poll
        ServerNpcLootHandler.ServerNpcLootEvent event = handler.pollForNpc(1234);

        assertNotNull("Should retrieve buffered event", event);
        assertEquals("Should have correct NPC index", 1234, event.npc.getIndex());
        assertEquals("Should have correct NPC ID", 5678, event.npcId);
        assertEquals("Should have correct items count", 1, event.items.size());
        assertEquals("Should have correct item ID", 12345, event.items.get(0).getId());
        assertEquals("Should have correct item quantity", 5, event.items.get(0).getQuantity());
    }

    @Test
    public void testPollingEmptyReturnsNull()
    {
        ServerNpcLootHandler.ServerNpcLootEvent event = handler.pollForNpc(9999);
        assertNull("Polling non-existent NPC should return null", event);
    }

    @Test
    public void testNullNpcHandling()
    {
        when(mockServerNpcLoot.getNpc()).thenReturn(null);

        handler.onServerNpcLoot(mockServerNpcLoot);

        assertFalse("Should not buffer null NPC", handler.hasPendingLoot(0));
    }

    @Test
    public void testNullEventHandling()
    {
        handler.onServerNpcLoot(null);
        // Should not throw exception

        assertFalse("Should handle null event gracefully",
                handler.hasPendingLoot(0));
    }

    @Test
    public void testEmptyItemsHandling()
    {
        when(mockServerNpcLoot.getNpc()).thenReturn(mockNpc);
        when(mockNpc.getIndex()).thenReturn(1234);
        when(mockServerNpcLoot.getItems()).thenReturn(new ArrayList<>());

        handler.onServerNpcLoot(mockServerNpcLoot);

        assertFalse("Should not buffer empty items", handler.hasPendingLoot(1234));
    }

    @Test
    public void testMultipleNpcBuffering()
    {
        List<net.runelite.client.game.ItemStack> items1 = new ArrayList<>();
        items1.add(new net.runelite.client.game.ItemStack(111, 1));

        List<net.runelite.client.game.ItemStack> items2 = new ArrayList<>();
        items2.add(new net.runelite.client.game.ItemStack(222, 2));

        // First NPC
        ServerNpcLoot event1 = mockServerNpcLoot;
        when(mockServerNpcLoot.getNpc()).thenReturn(mockNpc);
        when(mockNpc.getIndex()).thenReturn(1111);
        when(mockServerNpcLoot.getItems()).thenReturn(items1);
        handler.onServerNpcLoot(event1);

        // Simulate second NPC event
        NPC mockNpc2 = org.mockito.Mockito.mock(NPC.class);
        ServerNpcLoot event2 = org.mockito.Mockito.mock(ServerNpcLoot.class);
        when(event2.getNpc()).thenReturn(mockNpc2);
        when(mockNpc2.getIndex()).thenReturn(2222);
        when(event2.getItems()).thenReturn(items2);
        handler.onServerNpcLoot(event2);

        // Both should be buffered
        assertTrue("First NPC should be buffered", handler.hasPendingLoot(1111));
        assertTrue("Second NPC should be buffered", handler.hasPendingLoot(2222));

        // Poll first
        ServerNpcLootHandler.ServerNpcLootEvent polled1 = handler.pollForNpc(1111);
        assertEquals("First poll should get NPC 1111", 1111, polled1.npc.getIndex());

        // Polling same NPC again should return null
        assertNull("Second poll should return null after first", handler.pollForNpc(1111));

        // Second should still be available
        assertTrue("Second NPC should still be available", handler.hasPendingLoot(2222));
    }

    @Test
    public void testResetClearsAllEvents()
    {
        List<net.runelite.client.game.ItemStack> items = new ArrayList<>();
        items.add(new net.runelite.client.game.ItemStack(12345, 5));

        when(mockServerNpcLoot.getNpc()).thenReturn(mockNpc);
        when(mockNpc.getIndex()).thenReturn(1234);
        when(mockServerNpcLoot.getItems()).thenReturn(items);

        handler.onServerNpcLoot(mockServerNpcLoot);
        assertTrue("Should have pending before reset", handler.hasPendingLoot(1234));

        handler.reset();
        assertFalse("Should have no pending after reset", handler.hasPendingLoot(1234));
    }
}
