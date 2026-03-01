package com.runealytics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.Instant;

@Singleton
public class BankDataManager
{
    private static final Logger log = LoggerFactory.getLogger(BankDataManager.class);

    private final RunealyticsApiClient apiClient;

    @Inject
    public BankDataManager(RunealyticsApiClient apiClient)
    {
        this.apiClient = apiClient;
    }

    /**
     * Syncs a complete wealth snapshot: bank + inventory + equipped items.
     *
     * Call this when the player opens their bank so that all three containers
     * are available simultaneously, giving an accurate total wealth figure.
     *
     * @param token              auth token
     * @param username           verified RSN
     * @param bankContainer      InventoryID.BANK container
     * @param inventoryContainer InventoryID.INVENTORY container (may be null)
     * @param equipmentContainer InventoryID.EQUIPMENT container (may be null)
     */
    public void syncBankData(
            String token,
            String username,
            ItemContainer bankContainer,
            ItemContainer inventoryContainer,
            ItemContainer equipmentContainer)
    {
        if (token == null || token.isEmpty())
        {
            log.debug("No auth token available, skipping bank sync");
            return;
        }

        if (username == null || username.isEmpty())
        {
            log.warn("Username is null/empty, skipping bank sync");
            return;
        }

        if (bankContainer == null)
        {
            log.warn("Bank container is null, skipping bank sync");
            return;
        }

        try
        {
            JsonObject bankData = buildBankData(username, bankContainer, inventoryContainer, equipmentContainer);

            int bankCount      = bankData.getAsJsonArray("items").size();
            int inventoryCount = bankData.getAsJsonArray("inventory").size();
            int equipmentCount = bankData.getAsJsonArray("equipment").size();

            log.info("Syncing wealth snapshot for {}: bank={} inv={} equip={} items",
                    username, bankCount, inventoryCount, equipmentCount);

            boolean success = apiClient.syncBankData(token, bankData);

            if (success)
            {
                log.info("Wealth snapshot synced successfully for {}", username);
            }
            else
            {
                log.error("Failed to sync wealth snapshot for {}", username);
            }
        }
        catch (Exception e)
        {
            log.error("Error syncing wealth snapshot for {}: {}", username, e.getMessage(), e);
        }
    }

    /**
     * Backward-compatible overload for callers that only have the bank container.
     * Inventory and equipment will be sent as empty arrays.
     */
    public void syncBankData(String token, String username, ItemContainer bankContainer)
    {
        syncBankData(token, username, bankContainer, null, null);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private JsonObject buildBankData(
            String username,
            ItemContainer bankContainer,
            ItemContainer inventoryContainer,
            ItemContainer equipmentContainer)
    {
        JsonObject data = new JsonObject();
        data.addProperty("username",  username);
        data.addProperty("timestamp", Instant.now().getEpochSecond());
        data.addProperty("world", 0); // Future: populate if you track world

        // Bank items
        JsonArray bankItems = buildItemArray(bankContainer);
        data.add("items", bankItems);

        // Inventory items — included so the snapshot captures carried wealth
        JsonArray inventoryItems = buildItemArray(inventoryContainer);
        data.add("inventory", inventoryItems);

        // Equipped items — included so BiS gear is counted even if not banked
        JsonArray equipmentItems = buildItemArray(equipmentContainer);
        data.add("equipment", equipmentItems);

        log.debug("Wealth snapshot: bank={} inv={} equip={} items for {}",
                bankItems.size(), inventoryItems.size(), equipmentItems.size(), username);

        return data;
    }

    /**
     * Converts an {@link ItemContainer} to a JSON array of {@code {id, qty}} objects.
     * Returns an empty array if the container is null or empty.
     */
    private JsonArray buildItemArray(ItemContainer container)
    {
        JsonArray array = new JsonArray();

        if (container == null)
        {
            return array;
        }

        Item[] items = container.getItems();
        if (items == null)
        {
            return array;
        }

        for (Item item : items)
        {
            if (item != null && item.getId() > 0 && item.getQuantity() > 0)
            {
                JsonObject entry = new JsonObject();
                entry.addProperty("id",  item.getId());
                entry.addProperty("qty", item.getQuantity());
                array.add(entry);
            }
        }

        return array;
    }
}