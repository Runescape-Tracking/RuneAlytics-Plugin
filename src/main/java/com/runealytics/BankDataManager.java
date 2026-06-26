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
     * Builds the complete wealth snapshot JSON object on the <strong>client
     * thread</strong>.  This method calls {@link ItemManager#getItemComposition}
     * internally, which requires the client thread — it must NOT be called from
     * a background executor.
     *
     * <p>Call this synchronously, then hand the returned {@link JsonObject} to
     * {@link #syncBankData(String, String, JsonObject)} running on an executor
     * thread for the actual HTTP call.</p>
     *
     * @param username           verified RSN (added to the JSON payload)
     * @param world              the current world (captured on the client thread)
     * @param bankContainer      InventoryID.BANK container (required)
     * @param inventoryContainer InventoryID.INVENTORY container (may be null)
     * @param equipmentContainer InventoryID.EQUIPMENT container (may be null)
     * @return pre-serialised JSON ready to pass to the network call
     */
    public JsonObject buildBankSnapshot(
            String username,
            int world,
            ItemContainer bankContainer,
            ItemContainer inventoryContainer,
            ItemContainer equipmentContainer)
    {
        JsonObject data = new JsonObject();
        data.addProperty("username",  username);
        data.addProperty("timestamp", Instant.now().getEpochSecond());
        data.addProperty("world",     world);

        // fromContainerWithValues / containerTotalValue both call
        // ItemManager.getItemComposition which requires the client thread.
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

        data.addProperty("bank_value",      bankValue);
        data.addProperty("inventory_value", invValue);
        data.addProperty("equipment_value", equipValue);
        data.addProperty("total_wealth",    total);

        log.debug("Wealth snapshot built: bank={} ({}gp) inv={} ({}gp) equip={} ({}gp) total={}gp",
                bankItems.size(),      bankValue,
                inventoryItems.size(), invValue,
                equipmentItems.size(), equipValue,
                total);

        return data;
    }

    /**
     * Sends a pre-built wealth snapshot to the API.  Safe to call from a
     * background thread — all client API reads were already done by
     * {@link #buildBankSnapshot}.
     */
    public void syncBankData(String token, String username, JsonObject snapshot)
    {
        if (token == null || token.isEmpty())
        {
            log.debug("No auth token available, skipping bank sync");
            return;
        }

        if (username == null || username.isEmpty())
        {
            log.debug("Username is null/empty, skipping bank sync");
            return;
        }

        if (snapshot == null)
        {
            log.debug("Bank snapshot is null, skipping bank sync");
            return;
        }

        try
        {
            boolean success = apiClient.syncBankData(token, snapshot);
            if (success)
            {
                log.debug("Wealth snapshot synced successfully for {}", username);
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

}
