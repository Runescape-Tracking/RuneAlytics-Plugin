package com.runealytics;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages local storage of loot tracking data
 * Data is stored per-username to support multiple accounts
 */
@Slf4j
@Singleton
public class LootStorageManager
{
    private static final String LOOT_DATA_FILE_PATTERN = "runealytics-loot-%s.json";
    private static final String DEFAULT_FILE = "runealytics-loot-default.json";

    private final Gson gson;
    private final RuneAlyticsState state;

    @Inject
    public LootStorageManager(RuneAlyticsState state)
    {
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
        this.state = state;
    }

    /**
     * Get the loot data file for the current user
     */
    private File getLootDataFile()
    {
        String username = state.getVerifiedUsername();

        String filename;
        if (username == null || username.isEmpty())
        {
            filename = DEFAULT_FILE;
            log.debug("Using default loot file (no verified username)");
        }
        else
        {
            filename = String.format(LOOT_DATA_FILE_PATTERN, username.toLowerCase());
            log.debug("Using loot file for user: {}", username);
        }

        return new File(RuneLite.RUNELITE_DIR, filename);
    }

    /**
     * Save loot data to local storage
     */
    public void saveLootData(Map<String, BossKillStats> bossStats, int currentPrestige)
    {
        File file = getLootDataFile();

        try
        {
            LootStorageData data = new LootStorageData();
            data.bossStats = bossStats;
            data.currentPrestige = currentPrestige;
            data.lastSaved = System.currentTimeMillis();
            data.username = state.getVerifiedUsername();

            try (FileWriter writer = new FileWriter(file))
            {
                gson.toJson(data, writer);
            }

            log.info("Saved loot data to {} (Prestige: {}, Bosses: {})",
                    file.getName(), currentPrestige, bossStats.size());
        }
        catch (Exception e)
        {
            log.error("Failed to save loot data to {}", file.getName(), e);
        }
    }

    /**
     * Load loot data from local storage for the current user
     */
    public LootStorageData loadLootData()
    {
        File file = getLootDataFile();

        if (!file.exists())
        {
            log.info("No loot data file found: {}", file.getName());
            return createEmptyData();
        }

        try (FileReader reader = new FileReader(file))
        {
            LootStorageData data = gson.fromJson(reader, LootStorageData.class);

            // Validate and initialize data
            if (data == null)
            {
                log.warn("Loaded null data from {}, creating empty", file.getName());
                return createEmptyData();
            }

            if (data.bossStats == null)
            {
                data.bossStats = new HashMap<>();
            }

            // Ensure all drops lists are initialized
            for (BossKillStats stats : data.bossStats.values())
            {
                if (stats.getKillHistory() != null)
                {
                    for (NpcKillRecord kill : stats.getKillHistory())
                    {
                        if (kill.getDrops() == null)
                        {
                            kill.setDrops(new ArrayList<>());
                        }
                    }
                }
            }

            log.info("Loaded loot data from {} (Prestige: {}, Bosses: {})",
                    file.getName(), data.currentPrestige, data.bossStats.size());

            // Log details of what was loaded
            for (Map.Entry<String, BossKillStats> entry : data.bossStats.entrySet())
            {
                BossKillStats stats = entry.getValue();
                log.info("  - {}: {} kills, {} history records, {} gp",
                        stats.getNpcName(),
                        stats.getKillCount(),
                        stats.getKillHistory().size(),
                        stats.getTotalLootValue());
            }

            return data;
        }
        catch (Exception e)
        {
            log.error("Failed to load loot data from {}", file.getName(), e);
            return createEmptyData();
        }
    }

    /**
     * Create empty data structure
     */
    private LootStorageData createEmptyData()
    {
        LootStorageData data = new LootStorageData();
        data.bossStats = new HashMap<>();
        data.currentPrestige = 0;
        data.lastSaved = 0;
        data.username = state.getVerifiedUsername();
        return data;
    }

    /**
     * Delete local storage file for current user
     */
    public void clearStorage()
    {
        File file = getLootDataFile();

        if (file.exists())
        {
            try
            {
                Files.delete(file.toPath());
                log.info("Cleared local loot storage: {}", file.getName());
            }
            catch (Exception e)
            {
                log.error("Failed to delete {}", file.getName(), e);
            }
        }
    }

    /**
     * Delete storage file for a specific username
     */
    public void clearStorageForUser(String username)
    {
        if (username == null || username.isEmpty())
        {
            return;
        }

        String filename = String.format(LOOT_DATA_FILE_PATTERN, username.toLowerCase());
        File file = new File(RuneLite.RUNELITE_DIR, filename);

        if (file.exists())
        {
            try
            {
                Files.delete(file.toPath());
                log.info("Cleared loot storage for user: {}", username);
            }
            catch (Exception e)
            {
                log.error("Failed to delete storage for {}", username, e);
            }
        }
    }

    /**
     * Check if local storage exists for current user
     */
    public boolean hasLocalStorage()
    {
        return getLootDataFile().exists();
    }

    /**
     * Get checksum of local data for comparison
     */
    public String getLocalDataChecksum()
    {
        LootStorageData data = loadLootData();
        if (data == null || data.bossStats == null)
        {
            return "";
        }

        // Create a simple checksum based on total kills and total value
        long totalKills = 0;
        long totalValue = 0;

        for (BossKillStats stats : data.bossStats.values())
        {
            totalKills += stats.getKillCount();
            totalValue += stats.getTotalLootValue();
        }

        return String.format("%d_%d_%d", totalKills, totalValue, data.bossStats.size());
    }

    /**
     * List all loot data files
     */
    public Map<String, File> getAllLootFiles()
    {
        Map<String, File> files = new HashMap<>();
        File runeliteDir = RuneLite.RUNELITE_DIR;

        if (runeliteDir.exists() && runeliteDir.isDirectory())
        {
            File[] allFiles = runeliteDir.listFiles((dir, name) ->
                    name.startsWith("runealytics-loot-") && name.endsWith(".json")
            );

            if (allFiles != null)
            {
                for (File file : allFiles)
                {
                    String username = file.getName()
                            .replace("runealytics-loot-", "")
                            .replace(".json", "");
                    files.put(username, file);
                }
            }
        }

        return files;
    }

    /**
     * Container for stored loot data
     */
    public static class LootStorageData
    {
        public Map<String, BossKillStats> bossStats;
        public int currentPrestige;
        public long lastSaved;
        public String username; // Track which user this data belongs to
    }
}