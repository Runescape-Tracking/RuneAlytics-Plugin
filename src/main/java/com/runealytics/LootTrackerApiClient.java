package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.runealytics.LootStorageData;
import java.util.*;

import static com.runealytics.RuneAlyticsHttp.JSON;


/**
 * API client for syncing loot data to RuneAlytics
 */
@Slf4j
@Singleton
public class LootTrackerApiClient
{
    private static final String LOOT_SYNC_PATH = "/loot/sync";
    private static final String LOOT_STATS_PATH = "/loot/stats/";
    private static final String LOOT_PRESTIGE_PATH = "/loot/prestige";
    private static final String LOOT_BULK_SYNC_PATH = "/loot/bulk-sync";

    private final OkHttpClient httpClient;
    private final RunealyticsConfig config;
    private final RuneAlyticsState state;
    private final Gson gson;

    @Inject
    public LootTrackerApiClient(
            OkHttpClient httpClient,
            RunealyticsConfig config,
            RuneAlyticsState state,
            Gson gson
    )
    {
        this.httpClient = httpClient;
        this.config = config;
        this.state = state;
        this.gson = gson;
    }

    /**
     * Sync kill data to server (real-time sync for individual kills)
     */
    public void syncKillData(JsonObject payload) throws IOException
    {
        String token = config.authToken();

        if (token == null || token.isEmpty())
        {
            log.debug("No auth token, skipping loot sync");
            return;
        }

        if (!config.syncLootToServer())
        {
            log.debug("Loot sync disabled in config");
            return;
        }

        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(JSON, gson.toJson(payload));

        Request request = new Request.Builder()
                .url(config.apiUrl() + LOOT_SYNC_PATH)
                .post(body)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (response.isSuccessful())
            {
                log.debug("Loot data synced successfully");
            }
            else
            {
                log.warn("Loot sync failed: HTTP {} - {}", response.code(), responseBody);
            }
        }
    }

    /**
     * Sync prestige to server
     */
    public void syncPrestige(JsonObject payload) throws IOException
    {
        String token = config.authToken();

        if (token == null || token.isEmpty())
        {
            log.debug("No auth token, skipping prestige sync");
            return;
        }

        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(JSON, gson.toJson(payload));

        Request request = new Request.Builder()
                .url(config.apiUrl() + LOOT_PRESTIGE_PATH)
                .post(body)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (response.isSuccessful())
            {
                log.debug("Prestige synced successfully");
            }
            else
            {
                String responseBody = response.body() != null ? response.body().string() : "";
                log.warn("Prestige sync failed: HTTP {} - {}", response.code(), responseBody);
            }
        }
    }

    /**
     * Fetch boss stats from server
     */
    public Map<String, ServerBossStats> fetchBossStatsFromServer(String username) throws IOException
    {
        Request request = new Request.Builder()
                .url(config.apiUrl() + "/loot/stats/" + username)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful() || response.body() == null)
            {
                log.warn("Failed to fetch boss stats from server: HTTP {}", response.code());
                return new HashMap<>();
            }

            String json = response.body().string();
            log.debug("Server response: {}", json);

            JsonObject responseObj = gson.fromJson(json, JsonObject.class);

            if (!responseObj.has("boss_stats") || !responseObj.get("boss_stats").isJsonArray())
            {
                log.warn("Invalid server response format");
                return new HashMap<>();
            }

            JsonArray bossStatsArray = responseObj.getAsJsonArray("boss_stats");
            Map<String, ServerBossStats> serverStats = new HashMap<>();

            for (int i = 0; i < bossStatsArray.size(); i++)
            {
                JsonObject bossObj = bossStatsArray.get(i).getAsJsonObject();

                ServerBossStats stats = new ServerBossStats();
                stats.bossName = bossObj.get("boss_name").getAsString();
                stats.killCount = bossObj.get("kill_count").getAsInt();
                stats.totalLootValue = bossObj.get("total_loot_value").getAsLong();
                stats.prestige = bossObj.has("prestige") ? bossObj.get("prestige").getAsInt() : 0;

                serverStats.put(stats.bossName, stats);
            }

            log.info("Fetched {} boss stats from server", serverStats.size());
            return serverStats;
        }
        catch (Exception e)
        {
            log.error("Error fetching boss stats from server", e);
            return new HashMap<>();
        }
    }

    /**
     * Bulk sync multiple kills to server
     */
    public boolean bulkSyncKills(String username, Map<String, List<LootStorageData.KillRecord>> killsByBoss) throws IOException
    {
        // Convert to API format
        JsonArray killsArray = new JsonArray();

        for (Map.Entry<String, List<LootStorageData.KillRecord>> entry : killsByBoss.entrySet())
        {
            String npcName = entry.getKey();

            for (LootStorageData.KillRecord kill : entry.getValue())
            {
                JsonObject killObj = new JsonObject();
                killObj.addProperty("npc_name", npcName);
                killObj.addProperty("npc_id", kill.getWorld()); // You'll need to store npc_id properly
                killObj.addProperty("combat_level", kill.getCombatLevel());
                killObj.addProperty("kill_count", kill.getKillNumber());
                killObj.addProperty("world", kill.getWorld());
                killObj.addProperty("timestamp", kill.getTimestamp());
                killObj.addProperty("prestige", 0); // Add prestige support

                long totalValue = 0;
                JsonArray dropsArray = new JsonArray();

                for (LootStorageData.DropRecord drop : kill.getDrops())
                {
                    JsonObject dropObj = new JsonObject();
                    dropObj.addProperty("item_id", drop.getItemId());
                    dropObj.addProperty("item_name", drop.getItemName());
                    dropObj.addProperty("quantity", drop.getQuantity());
                    dropObj.addProperty("ge_price", drop.getGePrice());
                    dropObj.addProperty("high_alch", drop.getHighAlch());
                    dropObj.addProperty("total_value", drop.getTotalValue());

                    dropsArray.add(dropObj);
                    totalValue += drop.getTotalValue();
                }

                killObj.add("drops", dropsArray);
                killObj.addProperty("total_loot_value", totalValue);
                killObj.addProperty("drop_count", kill.getDrops().size());

                killsArray.add(killObj);
            }
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("username", username);
        payload.add("kills", killsArray);

        RequestBody body = RequestBody.create(JSON, payload.toString());

        Request request = new Request.Builder()
                .url(config.apiUrl() + "/loot/bulk-sync")
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful())
            {
                log.error("Bulk sync failed: HTTP {}", response.code());
                return false;
            }

            log.info("Bulk sync successful - {} kills", killsArray.size());
            return true;
        }
    }

    /**
     * Fetch complete kill history from server with all drops
     */
    public Map<String, LootStorageData.BossKillData> fetchKillHistoryFromServer(String username) throws IOException
    {
        Request request = new Request.Builder()
                .url(config.apiUrl() + "/loot/history/" + username)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful() || response.body() == null)
            {
                log.warn("Failed to fetch kill history: HTTP {}", response.code());
                return new HashMap<>();
            }

            String json = response.body().string();
            log.debug("Server response: {}", json);

            JsonObject responseObj = gson.fromJson(json, JsonObject.class);

            if (!responseObj.has("kills") || !responseObj.get("kills").isJsonArray())
            {
                log.warn("Invalid server response format - missing 'kills' array");
                return new HashMap<>();
            }

            JsonArray killsArray = responseObj.getAsJsonArray("kills");
            Map<String, LootStorageData.BossKillData> result = new HashMap<>();

            for (int i = 0; i < killsArray.size(); i++)
            {
                JsonObject killObj = killsArray.get(i).getAsJsonObject();

                String bossName = killObj.get("boss_name").getAsString();
                int bossId = killObj.get("boss_id").getAsInt();
                int combatLevel = killObj.get("combat_level").getAsInt();
                int world = killObj.get("world").getAsInt();
                long killTimeSeconds = killObj.get("kill_time").getAsLong();
                long killTimeMs = killTimeSeconds * 1000; // Convert seconds to milliseconds

                // Get or create boss data
                LootStorageData.BossKillData bossData = result.computeIfAbsent(bossName, k -> {
                    LootStorageData.BossKillData bd = new LootStorageData.BossKillData();
                    bd.setNpcName(bossName);
                    bd.setNpcId(bossId);
                    bd.setKillCount(0);
                    bd.setPrestige(0);
                    bd.setTotalLootValue(0);
                    bd.setKills(new ArrayList<>());
                    bd.setAggregatedDrops(new HashMap<>());
                    return bd;
                });

                // Create kill record
                LootStorageData.KillRecord killRecord = new LootStorageData.KillRecord();
                killRecord.setTimestamp(killTimeMs);
                killRecord.setWorld(world);
                killRecord.setCombatLevel(combatLevel);
                killRecord.setSyncedToServer(true);
                killRecord.setDrops(new ArrayList<>());

                // Parse drops
                if (killObj.has("drops") && killObj.get("drops").isJsonArray())
                {
                    JsonArray dropsArray = killObj.getAsJsonArray("drops");

                    for (int j = 0; j < dropsArray.size(); j++)
                    {
                        JsonObject dropObj = dropsArray.get(j).getAsJsonObject();

                        LootStorageData.DropRecord drop = new LootStorageData.DropRecord();
                        drop.setItemId(dropObj.get("item_id").getAsInt());
                        drop.setItemName(dropObj.get("item_name").getAsString());
                        drop.setQuantity(dropObj.get("quantity").getAsInt());
                        drop.setGePrice(dropObj.get("ge_price").getAsInt());
                        drop.setHighAlch(dropObj.get("high_alch").getAsInt());

                        // Calculate total value
                        int totalValue = drop.getGePrice() * drop.getQuantity();
                        drop.setTotalValue(totalValue);
                        drop.setHidden(false);

                        killRecord.getDrops().add(drop);
                    }
                }

                bossData.getKills().add(killRecord);
                bossData.setKillCount(bossData.getKillCount() + 1);
            }

            log.info("Fetched {} bosses with total {} kills from server",
                    result.size(),
                    result.values().stream().mapToInt(b -> b.getKills().size()).sum());

            return result;
        }
        catch (Exception e)
        {
            log.error("Error fetching kill history from server", e);
            return new HashMap<>();
        }
    }

    /**
     * Sync a single kill to the server
     */
    public void syncSingleKill(
            String username,
            String npcName,
            int npcId,
            int combatLevel,
            int killNumber,
            int world,
            long timestamp,
            int prestige,
            List<LootStorageData.DropRecord> drops) throws IOException
    {
        if (!state.isVerified() || state.getVerificationCode() == null)
        {
            log.warn("Cannot sync - not verified or no auth token");
            return;
        }

        // Calculate total loot value and drop count
        int totalLootValue = 0;
        int dropCount = drops.size();

        for (LootStorageData.DropRecord drop : drops)
        {
            totalLootValue += drop.getTotalValue();
        }

        // Add drops array
        JsonArray dropsArray = new JsonArray();
        for (LootStorageData.DropRecord drop : drops)
        {
            JsonObject dropObj = new JsonObject();
            dropObj.addProperty("item_id", drop.getItemId());
            dropObj.addProperty("item_name", drop.getItemName());
            dropObj.addProperty("quantity", drop.getQuantity());
            dropObj.addProperty("ge_price", drop.getGePrice());
            dropObj.addProperty("high_alch", drop.getHighAlch());
            dropObj.addProperty("total_value", drop.getTotalValue());
            dropObj.addProperty("hidden", drop.isHidden());
            dropsArray.add(dropObj);
        }

        // Build payload to match server expectations
        JsonObject payload = new JsonObject();
        payload.addProperty("username", username);
        payload.addProperty("npc_name", npcName);
        payload.addProperty("npc_id", npcId);
        payload.addProperty("combat_level", combatLevel);
        payload.addProperty("kill_count", killNumber); // ← Changed from kill_number
        payload.addProperty("world", world);
        payload.addProperty("timestamp", timestamp / 1000); // Convert to seconds
        payload.addProperty("prestige", prestige);
        payload.addProperty("total_loot_value", totalLootValue); // ← Added
        payload.addProperty("drop_count", dropCount); // ← Added
        payload.add("drops", dropsArray);

        String jsonPayload = new Gson().toJson(payload);
        RequestBody body = RequestBody.create(JSON, jsonPayload);

        Request request = new Request.Builder()
                .url(config.apiUrl() + "/loot/sync")
                .addHeader("Authorization", "Bearer " + state.getVerificationCode())
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            if (!response.isSuccessful())
            {
                String errorBody = response.body() != null ? response.body().string() : "No error body";
                log.error("Failed to sync kill: HTTP {} - {}", response.code(), errorBody);
                throw new IOException("Sync failed: " + response.code());
            }

            log.debug("Successfully synced kill: {} #{}", npcName, killNumber);
        }
    }

    /**
     * Bulk sync all local loot data to server (for startup sync)
     */
    public void bulkSyncAllLoot(String username, Map<String, BossKillStats> bossStats) throws IOException
    {
        log.info("=== BULK SYNC DEBUG ===");
        log.info("Full URL: {}{}", config.apiUrl(), LOOT_BULK_SYNC_PATH);
        log.info("API Base: {}", config.apiUrl());
        log.info("Username: {}", username);
        log.info("=======================");

        log.info("Starting bulk loot sync for username: {}", username);

        JsonArray killsArray = new JsonArray();
        int totalKills = 0;

        // Build kills array
        for (Map.Entry<String, BossKillStats> entry : bossStats.entrySet())
        {
            BossKillStats stats = entry.getValue();
            log.debug("Processing {} with {} kill history records",
                    stats.getNpcName(), stats.getKillHistory().size());

            for (NpcKillRecord kill : stats.getKillHistory())
            {
                JsonObject killObj = new JsonObject();
                killObj.addProperty("npc_name", kill.getNpcName());
                killObj.addProperty("npc_id", kill.getNpcId());
                killObj.addProperty("combat_level", kill.getCombatLevel());
                killObj.addProperty("kill_count", kill.getKillNumber()); // Use sequential kill number
                killObj.addProperty("world", kill.getWorldNumber());
                killObj.addProperty("timestamp", kill.getTimestamp());
                killObj.addProperty("prestige", 0);

                // Add drops array and calculate totals
                JsonArray dropsArray = new JsonArray();
                long killTotalValue = 0;
                int killDropCount = 0;

                for (LootDrop drop : kill.getDrops())
                {
                    long itemTotalValue = drop.getGePrice() * drop.getQuantity();
                    killTotalValue += itemTotalValue;
                    killDropCount++;

                    JsonObject dropObj = new JsonObject();
                    dropObj.addProperty("item_id", drop.getItemId());
                    dropObj.addProperty("item_name", drop.getItemName());
                    dropObj.addProperty("quantity", drop.getQuantity());
                    dropObj.addProperty("ge_price", drop.getGePrice());
                    dropObj.addProperty("high_alch", drop.getHighAlchValue());
                    dropObj.addProperty("total_value", itemTotalValue);
                    dropObj.addProperty("hidden", false);
                    dropsArray.add(dropObj);
                }

                killObj.add("drops", dropsArray);
                killObj.addProperty("total_loot_value", killTotalValue);
                killObj.addProperty("drop_count", killDropCount);

                killsArray.add(killObj);
                totalKills++;
            }
        }

        // Build root payload with username
        JsonObject payload = new JsonObject();
        payload.addProperty("username", username);
        payload.add("kills", killsArray);

        log.info("Sending bulk sync request: {} kills across {} bosses",
                totalKills, bossStats.size());

        // Send request
        String url = config.apiUrl() + LOOT_BULK_SYNC_PATH;

        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(JSON, payload.toString());

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute())
        {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful())
            {
                log.error("✗ Bulk loot sync failed: HTTP {} - {}",
                        response.code(), responseBody);
                throw new IOException("Bulk sync failed with status: " + response.code());
            }

            log.info("✓ Bulk loot sync successful - {} kills synced", totalKills);
            log.debug("Server response: {}", responseBody);
        }
    }

    /**
     * Build kill payload for real-time sync
     */
    public JsonObject buildKillPayload(NpcKillRecord kill)
    {
        String username = state.getVerifiedUsername();
        if (username == null || username.isEmpty())
        {
            log.error("Cannot build kill payload - no verified username");
            return null;
        }

        JsonObject payload = new JsonObject();

        // Basic kill info
        payload.addProperty("username", username);
        payload.addProperty("npc_name", kill.getNpcName());
        payload.addProperty("npc_id", kill.getNpcId());
        payload.addProperty("combat_level", kill.getCombatLevel());
        payload.addProperty("kill_count", kill.getKillNumber()); // Use sequential kill number
        payload.addProperty("world", kill.getWorldNumber());
        payload.addProperty("timestamp", kill.getTimestamp());
        payload.addProperty("prestige", 0);

        // Build drops array and calculate totals
        JsonArray drops = new JsonArray();
        long totalLootValue = 0;
        int dropCount = 0;

        for (LootDrop drop : kill.getDrops())
        {
            long itemTotalValue = drop.getGePrice() * drop.getQuantity();
            totalLootValue += itemTotalValue;
            dropCount++;

            JsonObject dropObj = new JsonObject();
            dropObj.addProperty("item_id", drop.getItemId());
            dropObj.addProperty("item_name", drop.getItemName());
            dropObj.addProperty("quantity", drop.getQuantity());
            dropObj.addProperty("ge_price", drop.getGePrice());
            dropObj.addProperty("high_alch", drop.getHighAlchValue());
            dropObj.addProperty("total_value", itemTotalValue);
            dropObj.addProperty("hidden", false);
            drops.add(dropObj);
        }

        payload.add("drops", drops);
        payload.addProperty("total_loot_value", totalLootValue);
        payload.addProperty("drop_count", dropCount);

        log.debug("Built kill payload for {}: kill #{}, {} drops, {} gp",
                kill.getNpcName(), kill.getKillNumber(), dropCount, totalLootValue);

        return payload;
    }

    /**
     * Simple data class for server boss stats
     */
    public static class ServerBossStats
    {
        public String bossName;
        public int killCount;
        public long totalLootValue;
        public int prestige;
    }
}