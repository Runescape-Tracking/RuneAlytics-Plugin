package com.runealytics;

import net.runelite.api.ItemComposition;
import net.runelite.api.ItemID;
import net.runelite.client.game.ItemManager;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Edge-case coverage for {@link ItemValueResolver}, which values untradeable /
 * charged items by decomposing them into their tradeable components.
 */
public class ItemValueResolverTest
{
    private final ItemManager itemManager = mock(ItemManager.class);

    @Test
    public void nonPositiveItemId_returnsZeroWithoutTouchingManager()
    {
        assertEquals(0, ItemValueResolver.perItemGeValue(itemManager, 0));
        assertEquals(0, ItemValueResolver.perItemGeValue(itemManager, -1));
    }

    @Test
    public void nullManager_returnsZero()
    {
        assertEquals(0, ItemValueResolver.perItemGeValue(null, 4151));
    }

    @Test
    public void decomposition_sumsMultipleComponents()
    {
        when(itemManager.getItemPrice(ItemID.AMULET_OF_FURY)).thenReturn(1_000_000);
        when(itemManager.getItemPrice(ItemID.BLOOD_SHARD)).thenReturn(2_000_000);

        assertEquals(3_000_000,
                ItemValueResolver.perItemGeValue(itemManager, ItemID.AMULET_OF_BLOOD_FURY));
    }

    @Test
    public void decomposition_overflow_isClampedToIntMax()
    {
        when(itemManager.getItemPrice(ItemID.AMULET_OF_FURY)).thenReturn(Integer.MAX_VALUE);
        when(itemManager.getItemPrice(ItemID.BLOOD_SHARD)).thenReturn(Integer.MAX_VALUE);

        assertEquals(Integer.MAX_VALUE,
                ItemValueResolver.perItemGeValue(itemManager, ItemID.AMULET_OF_BLOOD_FURY));
    }

    @Test
    public void decomposition_zeroTotal_fallsThroughToCanonical()
    {
        when(itemManager.getItemPrice(ItemID.SCYTHE_OF_VITUR_UNCHARGED)).thenReturn(0);
        when(itemManager.canonicalize(ItemID.SCYTHE_OF_VITUR)).thenReturn(999);
        when(itemManager.getItemPrice(999)).thenReturn(500);

        assertEquals(500, ItemValueResolver.perItemGeValue(itemManager, ItemID.SCYTHE_OF_VITUR));
    }

    @Test
    public void plainItem_usesCanonicalPrice()
    {
        when(itemManager.canonicalize(4151)).thenReturn(4151);
        when(itemManager.getItemPrice(4151)).thenReturn(2_500_000);

        assertEquals(2_500_000, ItemValueResolver.perItemGeValue(itemManager, 4151));
    }

    @Test
    public void untradeable_fallsBackToHighAlch()
    {
        ItemComposition comp = mock(ItemComposition.class);
        when(comp.getHaPrice()).thenReturn(150);
        when(itemManager.canonicalize(995)).thenReturn(995);
        when(itemManager.getItemPrice(995)).thenReturn(0);
        when(itemManager.getItemComposition(995)).thenReturn(comp);

        assertEquals(150, ItemValueResolver.perItemGeValue(itemManager, 995));
    }

    @Test
    public void trulyUntradeable_returnsZero()
    {
        when(itemManager.canonicalize(995)).thenReturn(995);
        when(itemManager.getItemPrice(995)).thenReturn(0);
        when(itemManager.getItemComposition(995)).thenReturn(null);

        assertEquals(0, ItemValueResolver.perItemGeValue(itemManager, 995));
    }
}
