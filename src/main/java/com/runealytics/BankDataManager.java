package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.runelite.api.ItemContainer;
import net.runelite.api.Item;
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
    private final Gson gson;

    @Inject
    public BankDataManager(RunealyticsApiClient apiClient)
    {
        this.apiClient = apiClient;
        this.gson = new Gson();
    }

    /**
     * Sync bank data to RuneAlytics API
     */
    public void syncBankData(String token, String username, ItemContainer bankContainer)
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
            JsonObject bankData = buildBankData(username, bankContainer);

            log.info("Syncing bank data for user: {} ({} items)",
                    username, bankData.getAsJsonArray("items").size());

            boolean success = apiClient.syncBankData(token, bankData);

            if (success)
            {
                log.info("Bank data synced successfully for {}", username);
            }
            else
            {
                log.error("Failed to sync bank data for {}", username);
            }
        }
        catch (Exception e)
        {
            log.error("Error syncing bank data for {}: {}", username, e.getMessage(), e);
        }
    }

    /**
     * Build bank data JSON payload
     */
    private JsonObject buildBankData(String username, ItemContainer bankContainer)
    {
        JsonObject data = new JsonObject();
        data.addProperty("username", username);
        data.addProperty("timestamp", Instant.now().getEpochSecond());
        data.addProperty("world", 0); // Can be populated if you track world

        JsonArray items = new JsonArray();
        Item[] bankItems = bankContainer.getItems();

        if (bankItems != null)
        {
            for (Item item : bankItems)
            {
                if (item != null && item.getId() > 0 && item.getQuantity() > 0)
                {
                    JsonObject itemData = new JsonObject();
                    itemData.addProperty("id", item.getId());
                    itemData.addProperty("qty", item.getQuantity());
                    items.add(itemData);
                }
            }
        }

        data.add("items", items);

        log.debug("Built bank data with {} items for {}", items.size(), username);

        return data;
    }

    /**
     * Get total number of items in bank
     */
    private int getTotalItems(ItemContainer bankContainer)
    {
        if (bankContainer == null)
        {
            return 0;
        }

        int count = 0;
        Item[] items = bankContainer.getItems();

        if (items != null)
        {
            for (Item item : items)
            {
                if (item != null && item.getId() > 0 && item.getQuantity() > 0)
                {
                    count++;
                }
            }
        }

        return count;
    }
}