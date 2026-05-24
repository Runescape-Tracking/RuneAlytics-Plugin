package com.runealytics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.runelite.api.ItemContainer;
import net.runelite.client.game.ItemManager;
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
    private final ItemManager itemManager;

    @Inject
    public BankDataManager(RunealyticsApiClient apiClient, ItemManager itemManager)
    {
        this.apiClient   = apiClient;
        this.itemManager = itemManager;
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
        data.addProperty("world",     0); // Future: populate if you track world

        // Each item now carries its resolved per-item GE value + line total so the
        // server doesn't have to look prices up itself. Untradeable / charged
        // variants (Scythe, Sanguinesti, Ferocious Gloves, etc.) are
        // decomposed into their tradeable components by ItemValueResolver
        // (issue #5 — bank totals previously under-counted these by tens of
        // thousands of GP each).
        JsonArray bankItems      = RuneAlyticsItemJson.fromContainerWithValues(bankContainer,      itemManager);
        JsonArray inventoryItems = RuneAlyticsItemJson.fromContainerWithValues(inventoryContainer, itemManager);
        JsonArray equipmentItems = RuneAlyticsItemJson.fromContainerWithValues(equipmentContainer, itemManager);

        data.add("items",     bankItems);
        data.add("inventory", inventoryItems);
        data.add("equipment", equipmentItems);

        long bankValue  = RuneAlyticsItemJson.containerTotalValue(bankContainer,      itemManager);
        long invValue   = RuneAlyticsItemJson.containerTotalValue(inventoryContainer, itemManager);
        long equipValue = RuneAlyticsItemJson.containerTotalValue(equipmentContainer, itemManager);
        long total      = bankValue + invValue + equipValue;

        data.addProperty("bank_value",        bankValue);
        data.addProperty("inventory_value",   invValue);
        data.addProperty("equipment_value",   equipValue);
        data.addProperty("total_wealth",      total);

        log.info("Wealth snapshot: bank={} (gp={}) inv={} (gp={}) equip={} (gp={}) total={} gp",
                bankItems.size(), bankValue,
                inventoryItems.size(), invValue,
                equipmentItems.size(), equipValue,
                total);

        return data;
    }
}