package com.runealytics;

import com.google.gson.JsonObject;
import java.io.IOException;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guard-path coverage for {@link BankDataManager#syncBankData} (no network call
 * on missing token/username/snapshot, exceptions swallowed) plus the wealth
 * math in {@link BankDataManager#buildBankSnapshot}.
 */
public class BankDataManagerTest
{
    private RunealyticsApiClient apiClient;
    private ItemManager itemManager;
    private BankDataManager manager;

    @Before
    public void setUp()
    {
        apiClient = mock(RunealyticsApiClient.class);
        itemManager = mock(ItemManager.class);
        when(itemManager.canonicalize(anyInt())).thenAnswer(inv -> inv.getArgument(0));
        manager = new BankDataManager(apiClient, itemManager);
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
    public void syncBankData_skipsOnMissingToken() throws IOException
    {
        manager.syncBankData(null, "Zezima", new JsonObject());
        manager.syncBankData("", "Zezima", new JsonObject());
        verify(apiClient, never()).syncBankData(any(), any());
    }

    @Test
    public void syncBankData_skipsOnMissingUsername() throws IOException
    {
        manager.syncBankData("tok", null, new JsonObject());
        manager.syncBankData("tok", "", new JsonObject());
        verify(apiClient, never()).syncBankData(any(), any());
    }

    @Test
    public void syncBankData_skipsOnNullSnapshot() throws IOException
    {
        manager.syncBankData("tok", "Zezima", null);
        verify(apiClient, never()).syncBankData(any(), any());
    }

    @Test
    public void syncBankData_forwardsToApiWhenValid() throws IOException
    {
        JsonObject snapshot = new JsonObject();
        when(apiClient.syncBankData("tok", snapshot)).thenReturn(true);

        manager.syncBankData("tok", "Zezima", snapshot);
        verify(apiClient, times(1)).syncBankData("tok", snapshot);
    }

    @Test
    public void syncBankData_swallowsApiException() throws IOException
    {
        when(apiClient.syncBankData(any(), any())).thenThrow(new IOException("boom"));
        // Must not propagate.
        manager.syncBankData("tok", "Zezima", new JsonObject());
        verify(apiClient).syncBankData(any(), any());
    }

    @Test
    public void buildBankSnapshot_populatesTotalsAndAllowsNullContainers()
    {
        when(itemManager.getItemPrice(100)).thenReturn(1000);
        when(itemManager.getItemPrice(200)).thenReturn(500);

        ItemContainer bank = container(item(100, 2)); // 2000
        ItemContainer inventory = container(item(200, 3)); // 1500
        JsonObject data = manager.buildBankSnapshot("Zezima", 330, bank, inventory, null);

        assertEquals("Zezima", data.get("username").getAsString());
        assertEquals(330, data.get("world").getAsInt());
        assertEquals(1, data.get("items").getAsJsonArray().size());
        assertEquals(1, data.get("inventory").getAsJsonArray().size());
        assertEquals(0, data.get("equipment").getAsJsonArray().size());
        assertEquals(2000L, data.get("bank_value").getAsLong());
        assertEquals(1500L, data.get("inventory_value").getAsLong());
        assertEquals(0L, data.get("equipment_value").getAsLong());
        assertEquals(3500L, data.get("total_wealth").getAsLong());
    }
}
