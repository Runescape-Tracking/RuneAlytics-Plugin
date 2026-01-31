package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

@Slf4j
@Singleton
public class LootStorageManager
{
    private static final String STORAGE_FILE_PREFIX = "runealytics-loot-";
    private static final String STORAGE_FILE_SUFFIX = ".json";
    private String currentUsername;
    private final Gson gson;
    private final RuneAlyticsState state;

    private LootStorageData currentData;

    @Inject
    public LootStorageManager(RuneAlyticsState state)
    {
        this.state = state;
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
    }

    /**
     * Load loot data for current username
     */
    public LootStorageData loadData()
    {
        String username = state.getVerifiedUsername();
        if (username == null || username.isEmpty())
        {
            log.warn("No verified username, cannot load loot data");
            currentData = new LootStorageData();
            return currentData;
        }

        File file = getStorageFile(username);
        if (!file.exists())
        {
            log.info("No existing loot data file for {}", username);
            currentData = new LootStorageData();
            currentData.setUsername(username);
            return currentData;
        }

        try (Reader reader = Files.newBufferedReader(file.toPath()))
        {
            currentData = gson.fromJson(reader, LootStorageData.class);
            if (currentData == null)
            {
                currentData = new LootStorageData();
                currentData.setUsername(username);
            }
            log.info("Loaded loot data for {} - {} bosses, {} total kills",
                    username,
                    currentData.getBossKills().size(),
                    currentData.getBossKills().values().stream()
                            .mapToInt(LootStorageData.BossKillData::getKillCount)
                            .sum());
            return currentData;
        }
        catch (Exception e)
        {
            log.error("Failed to load loot data for {}", username, e);
            currentData = new LootStorageData();
            currentData.setUsername(username);
            return currentData;
        }
    }


    /**
     * Load loot data for a specific username
     */
    public LootStorageData loadLootData(String username)
    {
        if (username == null || username.isEmpty())
        {
            log.warn("Cannot load data: username is null or empty");
            return null;
        }

        this.currentUsername = username;
        return loadLootData();
    }

    public void setCurrentUsername(String username)
    {
        this.currentUsername = username;
        log.info("Set current username to: {}", username);
    }

    /**
     * Load loot data for the current username
     */
    public LootStorageData loadLootData()
    {
        if (currentUsername == null || currentUsername.isEmpty())
        {
            log.warn("Cannot load data: no current username set");
            return null;
        }

        File file = new File(getStorageDirectory(), currentUsername + ".json");

        if (!file.exists())
        {
            log.info("No stored loot data found for {}", currentUsername);
            return new LootStorageData();
        }

        try
        {
            String json = new String(Files.readAllBytes(file.toPath()));
            LootStorageData data = gson.fromJson(json, LootStorageData.class);

            if (data == null)
            {
                log.warn("Failed to parse loot data for {}, returning empty data", currentUsername);
                return new LootStorageData();
            }

            log.info("Loaded loot data for {} - {} bosses, {} total kills",
                    currentUsername,
                    data.getBossKills().size(),
                    data.getBossKills().values().stream()
                            .mapToInt(bossData -> bossData.getKills().size())
                            .sum());

            return data;
        }
        catch (Exception e)
        {
            log.error("Failed to load loot data for {}", currentUsername, e);
            return new LootStorageData();
        }
    }

    /**
     * Get the directory where loot data is stored
     */
    private File getStorageDirectory()
    {
        File dir = new File(RuneLite.RUNELITE_DIR, "runealytics-loot");

        if (!dir.exists())
        {
            if (dir.mkdirs())
            {
                log.info("Created loot storage directory: {}", dir.getAbsolutePath());
            }
            else
            {
                log.error("Failed to create loot storage directory: {}", dir.getAbsolutePath());
            }
        }

        return dir;
    }

    /**
     * Save current loot data
     */
    public void saveData()
    {
        if (currentData == null)
        {
            log.warn("No data to save");
            return;
        }

        String username = state.getVerifiedUsername();
        if (username == null || username.isEmpty())
        {
            log.warn("No verified username, cannot save loot data");
            return;
        }

        File file = getStorageFile(username);

        try
        {
            // Ensure parent directory exists
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists())
            {
                parentDir.mkdirs();
            }

            // Write data
            try (Writer writer = Files.newBufferedWriter(file.toPath()))
            {
                gson.toJson(currentData, writer);
            }

            log.info("Saved loot data for {} - {} bosses", username, currentData.getBossKills().size());
        }
        catch (Exception e)
        {
            log.error("Failed to save loot data for {}", username, e);
        }
    }

    /**
     * Add a kill to storage
     */
    public void addKill(String npcName, int npcId, int combatLevel, int killNumber, int world,
                        int prestige, List<LootStorageData.DropRecord> drops)
    {
        if (currentData == null)
        {
            currentData = loadData();
        }

        // Get or create boss data
        LootStorageData.BossKillData bossData = currentData.getBossKills()
                .computeIfAbsent(npcName, k -> {
                    LootStorageData.BossKillData newBoss = new LootStorageData.BossKillData();
                    newBoss.setNpcName(npcName);
                    newBoss.setNpcId(npcId);
                    newBoss.setKillCount(0);
                    newBoss.setPrestige(prestige);
                    newBoss.setTotalLootValue(0);
                    return newBoss;
                });

        // Create kill record
        LootStorageData.KillRecord killRecord = new LootStorageData.KillRecord();
        killRecord.setTimestamp(System.currentTimeMillis());
        killRecord.setKillNumber(killNumber);
        killRecord.setWorld(world);
        killRecord.setCombatLevel(combatLevel);
        killRecord.setDrops(drops);
        killRecord.setSyncedToServer(false);

        // Add kill to list
        bossData.getKills().add(killRecord);

        // Update aggregated stats
        bossData.setKillCount(killNumber);
        bossData.setPrestige(prestige);

        long killValue = 0;
        for (LootStorageData.DropRecord drop : drops)
        {
            killValue += drop.getTotalValue();

            // Update aggregated drops
            LootStorageData.AggregatedDrop aggDrop = bossData.getAggregatedDrops()
                    .computeIfAbsent(drop.getItemId(), k -> {
                        LootStorageData.AggregatedDrop newAgg = new LootStorageData.AggregatedDrop();
                        newAgg.setItemId(drop.getItemId());
                        newAgg.setItemName(drop.getItemName());
                        newAgg.setTotalQuantity(0);
                        newAgg.setDropCount(0);
                        newAgg.setTotalValue(0);
                        newAgg.setGePrice(drop.getGePrice());
                        newAgg.setHighAlch(drop.getHighAlch());
                        return newAgg;
                    });

            aggDrop.setTotalQuantity(aggDrop.getTotalQuantity() + drop.getQuantity());
            aggDrop.setDropCount(aggDrop.getDropCount() + 1);
            aggDrop.setTotalValue(aggDrop.getTotalValue() + drop.getTotalValue());
        }

        bossData.setTotalLootValue(bossData.getTotalLootValue() + killValue);

        // Save immediately
        saveData();

        log.debug("Added kill #{} for {} - {} drops, {} gp",
                killNumber, npcName, drops.size(), killValue);
    }

    /**
     * Mark kills as synced to server
     */
    public void markKillsSynced(String npcName, long fromTimestamp, long toTimestamp)
    {
        if (currentData == null) return;

        LootStorageData.BossKillData bossData = currentData.getBossKills().get(npcName);
        if (bossData == null) return;

        int syncedCount = 0;
        for (LootStorageData.KillRecord kill : bossData.getKills())
        {
            if (kill.getTimestamp() >= fromTimestamp && kill.getTimestamp() <= toTimestamp)
            {
                kill.setSyncedToServer(true);
                syncedCount++;
            }
        }

        if (syncedCount > 0)
        {
            saveData();
            log.info("Marked {} kills as synced for {}", syncedCount, npcName);
        }
    }

    /**
     * Get unsynced kills for upload
     */
    public List<LootStorageData.KillRecord> getUnsyncedKills(String npcName)
    {
        if (currentData == null) return Collections.emptyList();

        LootStorageData.BossKillData bossData = currentData.getBossKills().get(npcName);
        if (bossData == null) return Collections.emptyList();

        List<LootStorageData.KillRecord> unsynced = new ArrayList<>();
        for (LootStorageData.KillRecord kill : bossData.getKills())
        {
            if (!kill.isSyncedToServer())
            {
                unsynced.add(kill);
            }
        }

        return unsynced;
    }

    /**
     * Get all unsynced kills across all bosses
     */
    public Map<String, List<LootStorageData.KillRecord>> getAllUnsyncedKills()
    {
        if (currentData == null) return Collections.emptyMap();

        Map<String, List<LootStorageData.KillRecord>> result = new HashMap<>();

        for (Map.Entry<String, LootStorageData.BossKillData> entry : currentData.getBossKills().entrySet())
        {
            List<LootStorageData.KillRecord> unsynced = getUnsyncedKills(entry.getKey());
            if (!unsynced.isEmpty())
            {
                result.put(entry.getKey(), unsynced);
            }
        }

        return result;
    }

    /**
     * Merge server data with local data
     */
    public void mergeServerData(Map<String, LootStorageData.BossKillData> serverData)
    {
        if (currentData == null)
        {
            currentData = loadData();
        }

        int killsAdded = 0;

        for (Map.Entry<String, LootStorageData.BossKillData> entry : serverData.entrySet())
        {
            String npcName = entry.getKey();
            LootStorageData.BossKillData serverBoss = entry.getValue();

            LootStorageData.BossKillData localBoss = currentData.getBossKills()
                    .computeIfAbsent(npcName, k -> {
                        LootStorageData.BossKillData newBoss = new LootStorageData.BossKillData();
                        newBoss.setNpcName(npcName);
                        newBoss.setNpcId(serverBoss.getNpcId());
                        newBoss.setKillCount(0);
                        newBoss.setPrestige(0);
                        newBoss.setTotalLootValue(0);
                        return newBoss;
                    });

            // Merge kills - avoid duplicates by timestamp
            Set<Long> existingTimestamps = new HashSet<>();
            for (LootStorageData.KillRecord kill : localBoss.getKills())
            {
                existingTimestamps.add(kill.getTimestamp());
            }

            for (LootStorageData.KillRecord serverKill : serverBoss.getKills())
            {
                // Check if kill already exists (within 1 second tolerance)
                boolean exists = false;
                for (long existingTs : existingTimestamps)
                {
                    if (Math.abs(existingTs - serverKill.getTimestamp()) <= 1000)
                    {
                        exists = true;
                        break;
                    }
                }

                if (!exists)
                {
                    // Mark as already synced since it came from server
                    serverKill.setSyncedToServer(true);
                    localBoss.getKills().add(serverKill);
                    killsAdded++;

                    // Update aggregated drops
                    for (LootStorageData.DropRecord drop : serverKill.getDrops())
                    {
                        LootStorageData.AggregatedDrop aggDrop = localBoss.getAggregatedDrops()
                                .computeIfAbsent(drop.getItemId(), k -> {
                                    LootStorageData.AggregatedDrop newAgg = new LootStorageData.AggregatedDrop();
                                    newAgg.setItemId(drop.getItemId());
                                    newAgg.setItemName(drop.getItemName());
                                    newAgg.setTotalQuantity(0);
                                    newAgg.setDropCount(0);
                                    newAgg.setTotalValue(0);
                                    newAgg.setGePrice(drop.getGePrice());
                                    newAgg.setHighAlch(drop.getHighAlch());
                                    return newAgg;
                                });

                        aggDrop.setTotalQuantity(aggDrop.getTotalQuantity() + drop.getQuantity());
                        aggDrop.setDropCount(aggDrop.getDropCount() + 1);
                        aggDrop.setTotalValue(aggDrop.getTotalValue() + drop.getTotalValue());
                    }
                }
            }

            // Update stats from server
            localBoss.setKillCount(Math.max(localBoss.getKillCount(), serverBoss.getKillCount()));
            localBoss.setPrestige(Math.max(localBoss.getPrestige(), serverBoss.getPrestige()));
        }

        if (killsAdded > 0)
        {
            currentData.setLastSyncTimestamp(System.currentTimeMillis());
            saveData();
            log.info("Merged {} kills from server", killsAdded);
        }
    }

    /**
     * Get current data
     */
    public LootStorageData getCurrentData()
    {
        if (currentData == null)
        {
            return loadData();
        }
        return currentData;
    }

    /**
     * Clear all data for current user
     */
    public void clearData()
    {
        String username = state.getVerifiedUsername();
        if (username == null || username.isEmpty()) return;

        currentData = new LootStorageData();
        currentData.setUsername(username);
        saveData();

        log.info("Cleared all loot data for {}", username);
    }

    /**
     * Get storage file for username
     */
    private File getStorageFile(String username)
    {
        String sanitized = username.toLowerCase().replaceAll("[^a-z0-9_-]", "_");
        String filename = STORAGE_FILE_PREFIX + sanitized + STORAGE_FILE_SUFFIX;
        return new File(RuneLite.RUNELITE_DIR, filename);
    }
}