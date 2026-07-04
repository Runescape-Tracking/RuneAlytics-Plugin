package com.runealytics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Container → JSON conversion edge cases: null/empty containers, invalid slot
 * filtering, equipment slot indexing, and overflow-safe valuation.
 */
public class RuneAlyticsItemJsonTest
{
    private ItemManager itemManager;

    @Before
    public void setUp()
    {
        itemManager = mock(ItemManager.class);
        // Make perItemGeValue deterministic: canonical == id, price == id.
        when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static Item item(int id, int qty)
    {
        Item item = mock(Item.class);
        when(item.getId()).thenReturn(id);
        when(item.getQuantity()).thenReturn(qty);
        return item;
    }

    private static ItemContainer container(Item... items)
    {
        ItemContainer c = mock(ItemContainer.class);
        when(c.getItems()).thenReturn(items);
        return c;
    }

    @Test
    public void fromContainer_nullContainer_isEmpty()
    {
        assertEquals(0, RuneAlyticsItemJson.fromContainer(null).size());
    }

    @Test
    public void fromContainer_skipsNullAndNonPositiveSlots()
    {
        ItemContainer c = container(item(4151, 1), null, item(0, 5), item(995, 0), item(560, 100));
        JsonArray arr = RuneAlyticsItemJson.fromContainer(c);

        assertEquals(2, arr.size());
        assertEquals(4151, arr.get(0).getAsJsonObject().get("id").getAsInt());
        assertEquals(1, arr.get(0).getAsJsonObject().get("qty").getAsInt());
        assertEquals(560, arr.get(1).getAsJsonObject().get("id").getAsInt());
        assertEquals(100, arr.get(1).getAsJsonObject().get("qty").getAsInt());
    }

    @Test
    public void fromEquipment_preservesSlotIndicesAcrossGaps()
    {
        ItemContainer c = container(item(4151, 1), null, item(1163, 1));
        JsonArray arr = RuneAlyticsItemJson.fromEquipment(c);

        assertEquals(2, arr.size());
        assertEquals(0, arr.get(0).getAsJsonObject().get("slot").getAsInt());
        assertEquals(2, arr.get(1).getAsJsonObject().get("slot").getAsInt());
        assertEquals(1163, arr.get(1).getAsJsonObject().get("id").getAsInt());
    }

    @Test
    public void fromContainerWithValues_computesGePerAndTotal()
    {
        when(itemManager.getItemPrice(100)).thenReturn(50);
        ItemContainer c = container(item(100, 3));

        JsonObject entry = RuneAlyticsItemJson.fromContainerWithValues(c, itemManager)
                .get(0).getAsJsonObject();
        assertEquals(50, entry.get("ge_per").getAsInt());
        assertEquals(150L, entry.get("total").getAsLong());
    }

    @Test
    public void fromContainerWithValues_totalDoesNotOverflowInt()
    {
        when(itemManager.getItemPrice(200)).thenReturn(2_000_000_000);
        ItemContainer c = container(item(200, 3));

        JsonObject entry = RuneAlyticsItemJson.fromContainerWithValues(c, itemManager)
                .get(0).getAsJsonObject();
        assertEquals(6_000_000_000L, entry.get("total").getAsLong());
    }

    @Test
    public void containerTotalValue_sumsAllItemsAndHandlesNull()
    {
        when(itemManager.getItemPrice(100)).thenReturn(50);
        when(itemManager.getItemPrice(200)).thenReturn(10);
        ItemContainer c = container(item(100, 2), item(200, 5));

        assertEquals(150L, RuneAlyticsItemJson.containerTotalValue(c, itemManager));
        assertEquals(0L, RuneAlyticsItemJson.containerTotalValue(null, itemManager));
    }
}
